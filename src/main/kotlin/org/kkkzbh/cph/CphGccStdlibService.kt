package org.kkkzbh.cph

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.lang.toolchains.CidrToolEnvironment
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class CphGccStdlibService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") private val project: Project,
    private val processRunner: CphPchProcessRunner = CphDefaultPchProcessRunner,
    private val probeCache: CphBitsHeaderProbeCache = CphBitsHeaderProbeCache.default(),
) {
    private val log = Logger.getInstance(CphGccStdlibService::class.java)

    fun compilerArgs(
        compiler: CphCppFileCompilerResolution.Ready,
        compileSettings: CphCompileSettings,
    ): CphGccStdlibResult {
        val startedAt = System.nanoTime()
        fun finish(status: CphStdlibStatus, args: List<String> = emptyList(), summary: String = ""): CphGccStdlibResult =
            CphGccStdlibResult(args = args, status = status, elapsedMillis = elapsedMillis(startedAt), summary = summary)

        return runCatching {
            val resolvedCompiler = resolveCompilerExecutable(compiler.compilerPath, compiler.environment)
                ?: return finish(CphStdlibStatus.SKIPPED, summary = "skipped(compiler not executable: ${compiler.compilerPath})")
            val probeInput = probeInput(resolvedCompiler, compiler, compileSettings)
                ?: return finish(CphStdlibStatus.SKIPPED, summary = "skipped(compiler not executable: ${resolvedCompiler.path})")
            val probe = probeCache.resolve(probeInput) {
                coldProbeBitsHeader(resolvedCompiler, compiler.environment, compileSettings)
            }
            if (probe is CphBitsHeaderProbeResolution.Missing) {
                return finish(CphStdlibStatus.SKIPPED, summary = "skipped(${probe.summary})")
            }
            val foundProbe = probe as CphBitsHeaderProbeResolution.Found
            val compilerName = File(resolvedCompiler.path).name
            val majorVersion = gccMajorVersion(foundProbe.compilerVersion)
                ?: return finish(CphStdlibStatus.SKIPPED, summary = "skipped(no gcc major version: $compilerName)")
            if (supportsStdModules(majorVersion, foundProbe.cppVersion)) {
                val stdModule = runCatching {
                    ensureStdModule(
                        compiler = resolvedCompiler,
                        environment = compiler.environment,
                        compilerVersion = foundProbe.compilerVersion,
                        realHeader = foundProbe.header,
                        compileSettings = compileSettings,
                        probeInput = probeInput,
                        probeSummary = foundProbe.summary,
                    )
                }.getOrElse {
                    if (it is ProcessCanceledException || it is CancellationException) throw it
                    error("std-module preparation failed: ${it.message ?: it.javaClass.simpleName}")
                }
                val status = if (stdModule.built) CphStdlibStatus.BUILT else CphStdlibStatus.HIT
                finish(status, args = stdModule.args, summary = stdModule.summary).copy(moduleSources = stdModule.sources)
            } else {
                if (!compileSettings.gccBitsPchEnabled || !sourceFileIncludesBitsHeader(compiler.sourceFile)) {
                    return finish(CphStdlibStatus.OFF, summary = "std modules require GCC 16+ and C++20+")
                }
                val pch = ensurePch(
                    compiler = resolvedCompiler,
                    environment = compiler.environment,
                    compilerVersion = foundProbe.compilerVersion,
                    realHeader = foundProbe.header,
                    compileSettings = compileSettings,
                    probeSummary = foundProbe.summary,
                )
                val status = if (pch.built) CphStdlibStatus.BUILT else CphStdlibStatus.HIT
                finish(status, args = listOf("-I", pch.dir.absolutePath), summary = pch.summary)
            }
        }.onFailure {
            if (it is ProcessCanceledException || it is CancellationException) throw it
            log.warn("CPH GCC standard library preparation failed for '${compiler.sourceFile.absolutePath}': ${it.message}", it)
        }.getOrElse {
            finish(CphStdlibStatus.FAILED, summary = it.message ?: it.javaClass.simpleName)
        }
    }

    private fun coldProbeBitsHeader(
        compiler: CompilerExecutable,
        environment: CPPEnvironment,
        compileSettings: CphCompileSettings,
        compilerVersion: String? = null,
    ): CphBitsHeaderProbeResolution {
        val compilerName = File(compiler.path).name
        val version = compilerVersion ?: compilerVersion(compiler.path, environment)
            ?: return CphBitsHeaderProbeResolution.Missing("no compiler version: $compilerName")
        if (!isGccVersion(version)) {
            return CphBitsHeaderProbeResolution.Missing("not gcc: $compilerName")
        }
        val probeStartedAt = System.nanoTime()
        val header = resolveBitsHeaderByDependencyProbe(compiler.path, environment, compileSettings)
            ?: return CphBitsHeaderProbeResolution.Missing("no bits header: $compilerName")
        return CphBitsHeaderProbeResolution.Found(
            header = header,
            compilerVersion = version,
            summary = "header probe ${elapsedMillis(probeStartedAt)}ms",
            cppVersion = effectiveCppVersion(compiler, environment, compileSettings),
        )
    }

    private fun effectiveCppVersion(
        compiler: CompilerExecutable,
        environment: CPPEnvironment,
        settings: CphCompileSettings,
    ): Long {
        val command = listOf(compiler.path) + CphCompileOptions.additionalArgs(settings) +
            listOfNotNull(settings.cppStandard.flag) + listOf("-x", "c++", "-E", "-dM", "-")
        val result = processRunner.run(command, HEADER_PROBE_TIMEOUT_SECONDS, "__cplusplus\n", environment)
        check(result.exitCode == 0) { "Cannot determine the effective C++ standard: ${result.output}" }
        return parseCppVersion(result.output)
    }

    private fun ensurePch(
        compiler: CompilerExecutable,
        environment: CPPEnvironment,
        compilerVersion: String,
        realHeader: File,
        compileSettings: CphCompileSettings,
        probeSummary: String,
    ): PchCache {
        val key = pchKey(compiler.path, compilerVersion, realHeader, compileSettings)
        val pchDir = File(PathManager.getSystemPath(), "cph-target-runner/pch/$key")
        return withCacheLock(pchDir) {
            val bitsDir = File(pchDir, "bits")
            val header = File(bitsDir, "stdc++.h")
            val pch = File(bitsDir, "stdc++.h.gch")
            if (!bitsDir.isDirectory && !bitsDir.mkdirs()) {
                error("Cannot create PCH cache directory: ${bitsDir.absolutePath}")
            }
            val wrapper = "#pragma once\n#include \"${escapeIncludePath(realHeader.absolutePath)}\"\n"
            if (!header.isFile || header.readText() != wrapper) {
                header.writeText(wrapper)
            }
            if (pch.isFile && pch.lastModified() >= header.lastModified()) {
                return@withCacheLock PchCache(pchDir, built = false, summary = pchSummary(compiler, pchDir, probeSummary))
            }
            if (pch.isFile && !pch.delete()) {
                error("Cannot replace stale PCH file: ${pch.absolutePath}")
            }

            val args = buildList {
                add(compiler.path)
                addAll(CphCompileOptions.additionalArgs(compileSettings))
                compileSettings.cppStandard.flag?.let(::add)
                add("-I")
                add(pchDir.absolutePath)
                add("-x")
                add("c++-header")
                add(header.absolutePath)
                add("-o")
                add(pch.absolutePath)
            }
            val result = processRunner.run(args, PCH_BUILD_TIMEOUT_SECONDS, environment = environment)
            if (result.exitCode != 0 || !pch.isFile) {
                error(
                    "PCH build failed with exit code ${result.exitCode}: " +
                        (result.output.lineSequence().firstOrNull { it.isNotBlank() } ?: "no compiler output"),
                )
            }
            return@withCacheLock PchCache(pchDir, built = true, summary = pchSummary(compiler, pchDir, probeSummary))
        }
    }

    private fun pchSummary(compiler: CompilerExecutable, pchDir: File, probeSummary: String): String =
        "pch: ${File(compiler.path).name}:${pchDir.name} | $probeSummary | compiler: ${compiler.summary}"

    private fun ensureStdModule(
        compiler: CompilerExecutable,
        environment: CPPEnvironment,
        compilerVersion: String,
        realHeader: File,
        compileSettings: CphCompileSettings,
        probeInput: CphBitsHeaderProbeInput,
        probeSummary: String,
    ): StdModuleCache {
        val cacheRoot = File(
            PathManager.getSystemPath(),
            "cph-target-runner/std-modules/${stdModuleKey(probeInput, compilerVersion, realHeader)}" +
                if (compileSettings.gccBitsPchEnabled) "-bits" else "-named",
        )
        return withCacheLock(cacheRoot) {
            val sources = stdModuleSources(compiler, environment, compileSettings)
            val mapper = writeStdModuleMapper(cacheRoot, realHeader, compileSettings.gccBitsPchEnabled)
            val args = stdModuleArgs(mapper)
            if (stdModuleCmisReady(cacheRoot, realHeader, compileSettings.gccBitsPchEnabled)) {
                return@withCacheLock StdModuleCache(
                    args = args,
                    sources = sources,
                    built = false,
                    summary = stdModuleSummary(compiler, cacheRoot, "cache hit | $probeSummary"),
                )
            }

            val common = listOf(compiler.path) + CphCompileOptions.additionalArgs(compileSettings) +
                listOfNotNull(compileSettings.cppStandard.flag) + args
            val commands = if (compileSettings.gccBitsPchEnabled) {
                listOf(common + listOf("--compile-std-module", writeStdModuleProbeSource(cacheRoot).absolutePath,
                    "-o", File(cacheRoot, "cph_std_module_probe").absolutePath))
            } else {
                sources.map { source ->
                    common + listOf("-fmodule-only", "-c", source.absolutePath)
                }
            }
            for (command in commands) {
                val result = processRunner.run(command, STD_MODULE_BUILD_TIMEOUT_SECONDS, environment = environment)
                check(result.exitCode == 0) { "std-module build failed (${result.exitCode}): ${result.output}" }
            }
            check(stdModuleCmisReady(cacheRoot, realHeader, compileSettings.gccBitsPchEnabled)) {
                "The compiler did not produce the required standard library modules in $cacheRoot"
            }
            return@withCacheLock StdModuleCache(
                args = args,
                sources = sources,
                built = true,
                summary = stdModuleSummary(compiler, cacheRoot, "built | $probeSummary"),
            )
        }
    }

    private fun stdModuleSources(compiler: CompilerExecutable, environment: CPPEnvironment, compileSettings: CphCompileSettings): List<File> {
        val result = processRunner.run(
            listOf(compiler.path) + CphCompileOptions.additionalArgs(compileSettings) + listOf("-print-file-name=libstdc++.modules.json"),
            HEADER_PROBE_TIMEOUT_SECONDS,
            environment = environment,
        )
        check(result.exitCode == 0) { "Cannot locate libstdc++ module metadata: ${result.output}" }
        val metadata = File(result.output.trim())
        check(metadata.isFile) { "GCC standard library module metadata is missing: $metadata" }
        return parseStdModuleSources(metadata)
    }

    private fun writeStdModuleProbeSource(cacheRoot: File): File {
        val source = File(cacheRoot, "cph_std_module_probe.cpp")
        val text = "auto main() -> int { return 0; }\n"
        if (!source.isFile || source.readText() != text) {
            source.writeText(text)
        }
        return source
    }

    private fun writeStdModuleMapper(cacheRoot: File, realHeader: File, accelerateBits: Boolean): File {
        val mapper = File(cacheRoot, "mapper.txt")
        val text = stdModuleMapperText(cacheRoot, realHeader, accelerateBits)
        if (!mapper.isFile || mapper.readText() != text) {
            mapper.writeText(text)
        }
        return mapper
    }

    private fun stdModuleCmisReady(cacheRoot: File, realHeader: File, accelerateBits: Boolean): Boolean {
        if (!File(cacheRoot, "std.gcm").isFile) return false
        if (!File(cacheRoot, "std.compat.gcm").isFile) return false
        return !accelerateBits || File(cacheRoot, stdModuleHeaderCmiPath(realHeader)).isFile
    }

    private fun stdModuleArgs(mapper: File): List<String> =
        listOf("-fmodules", "-fmodule-mapper=${mapper.absolutePath}")

    private fun stdModuleSummary(compiler: CompilerExecutable, cacheRoot: File, status: String): String =
        "std-module: ${cacheRoot.name} | $status | compiler: ${compiler.summary}"

    private fun resolveCompilerExecutable(
        compilerPath: String,
        environment: CPPEnvironment,
    ): CompilerExecutable? {
        val resolved = environment.resolveEnvPathToLocalExecutable(java.nio.file.Path.of(compilerPath), compilerPath)
        if (!resolved.isResolvedToExecutable) return null
        val resolvedPath = resolved.path?.takeIf { it.isNotBlank() } ?: return null
        val summary = if (resolvedPath == compilerPath) {
            "CLion $compilerPath"
        } else {
            "CLion $compilerPath -> $resolvedPath"
        }
        return CompilerExecutable(path = resolvedPath, summary = summary)
    }

    private fun probeInput(
        resolvedCompiler: CompilerExecutable,
        compiler: CphCppFileCompilerResolution.Ready,
        compileSettings: CphCompileSettings,
    ): CphBitsHeaderProbeInput? {
        val compilerFile = File(resolvedCompiler.path).takeIf { it.isFile } ?: return null
        return CphBitsHeaderProbeInput(
            compilerPath = compilerFile.absolutePath,
            compilerLastModified = compilerFile.lastModified(),
            compilerLength = compilerFile.length(),
            toolchainName = compiler.toolchainName,
            toolchainEnvironment = compiler.toolchainEnvironment,
            standardFlag = compileSettings.cppStandard.flag.orEmpty(),
            compileOptions = CphCompileOptions.renderCompilerOptions(CphCompileOptions.additionalArgs(compileSettings)),
        )
    }

    private fun compilerVersion(compiler: String, environment: CPPEnvironment): String? {
        val result = processRunner.run(listOf(compiler, "--version"), COMPILER_VERSION_TIMEOUT_SECONDS, environment = environment)
        if (result.exitCode != 0) return null
        return result.output
    }

    private fun resolveBitsHeaderByDependencyProbe(
        compiler: String,
        environment: CPPEnvironment,
        compileSettings: CphCompileSettings,
    ): File? {
        val command = buildList {
            add(compiler)
            addAll(CphCompileOptions.additionalArgs(compileSettings))
            compileSettings.cppStandard.flag?.let(::add)
            add("-x")
            add("c++")
            add("-M")
            add("-MT")
            add("cph_pch_probe")
            add("-")
        }
        val result = processRunner.run(
            command = command,
            timeoutSeconds = HEADER_PROBE_TIMEOUT_SECONDS,
            input = "#include <bits/stdc++.h>\n",
            environment = environment,
        )
        if (result.exitCode != 0) return null
        val header = parseBitsHeaderDependency(result.output)?.let(::File) ?: return null
        if (!header.isFile) return null
        return runCatching { header.canonicalFile }.getOrDefault(header.absoluteFile)
    }

    private data class PchCache(
        val dir: File,
        val built: Boolean,
        val summary: String,
    )

    private data class StdModuleCache(
        val args: List<String>,
        val sources: List<File>,
        val built: Boolean,
        val summary: String,
    )

    private data class CompilerExecutable(
        val path: String,
        val summary: String,
    )

    companion object {
        private const val COMPILER_VERSION_TIMEOUT_SECONDS = 2L
        private const val HEADER_PROBE_TIMEOUT_SECONDS = 5L
        private const val PCH_BUILD_TIMEOUT_SECONDS = 20L
        private const val STD_MODULE_BUILD_TIMEOUT_SECONDS = 30L
        private val cacheLocks = ConcurrentHashMap<String, ReentrantLock>()

        private fun <T> withCacheLock(directory: File, action: () -> T): T {
            java.nio.file.Files.createDirectories(directory.toPath())
            return cacheLocks.computeIfAbsent(directory.absolutePath) { ReentrantLock() }.withLock {
                FileChannel.open(File(directory, "build.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use { action() }
                }
            }
        }

        fun parseStdModuleSources(metadata: File): List<File> {
            val modules = JsonParser.parseString(metadata.readText()).asJsonObject.getAsJsonArray("modules")
            val sources = modules.associate { entry ->
                val module = entry.asJsonObject
                module.get("logical-name").asString to module.get("source-path").asString
            }
            return listOf("std", "std.compat").map { name ->
                val path = sources[name] ?: error("Missing $name in $metadata")
                val source = File(path).let { if (it.isAbsolute) it else File(metadata.parentFile, path) }.canonicalFile
                check(source.isFile) { "Standard library module source is missing: $source" }
                source
            }
        }

        fun stdModuleMapperText(cacheRoot: File, realHeader: File, accelerateBits: Boolean): String = buildString {
            appendLine("${'$'}root ${cacheRoot.absolutePath}")
            appendLine("std std.gcm")
            appendLine("std.compat std.compat.gcm")
            if (accelerateBits) appendLine("${realHeader.absolutePath} ${stdModuleHeaderCmiPath(realHeader)}")
        }

        private fun stdModuleHeaderCmiPath(realHeader: File): String =
            realHeader.absolutePath.replace('\\', '/').removePrefix("/") + ".gcm"

        fun parseCppVersion(output: String): Long =
            Regex("""(?m)^#define __cplusplus (\d+)L?$""").find(output)?.groupValues?.get(1)?.toLong()
                ?: error("The compiler did not report __cplusplus")

        fun supportsStdModules(gccMajor: Int, cppVersion: Long): Boolean =
            gccMajor >= 16 && cppVersion >= 202002L
        private val BITS_HEADER_INCLUDE = Regex("""(?m)^\s*#\s*include\s*[<"]bits/stdc\+\+\.h[>"]""")

        fun getInstance(project: Project): CphGccStdlibService = project.service()

        fun sourceFileIncludesBitsHeader(sourceFile: File): Boolean = sourceIncludesBitsHeader(sourceFile.readText())

        fun sourceIncludesBitsHeader(source: String): Boolean {
            var inBlockComment = false
            source.lineSequence().forEach { line ->
                val visible = StringBuilder()
                var index = 0
                while (index < line.length) {
                    if (inBlockComment) {
                        val end = line.indexOf("*/", index)
                        if (end < 0) {
                            index = line.length
                        } else {
                            inBlockComment = false
                            index = end + 2
                        }
                        continue
                    }
                    when {
                        line.startsWith("//", index) -> index = line.length
                        line.startsWith("/*", index) -> {
                            inBlockComment = true
                            index += 2
                        }
                        else -> {
                            visible.append(line[index])
                            index += 1
                        }
                    }
                }
                if (BITS_HEADER_INCLUDE.containsMatchIn(visible)) return true
            }
            return false
        }

        fun isGccVersion(version: String): Boolean {
            val lower = version.lowercase()
            return "gcc" in lower || "g++" in lower || "free software foundation" in lower
        }

        fun gccMajorVersion(version: String): Int? {
            if (!isGccVersion(version)) return null
            val firstLine = version.lineSequence().firstOrNull().orEmpty()
            val match = Regex("""\b(\d+)\.\d+(?:\.\d+)?\b""").find(firstLine)
                ?: Regex("""(?:GCC|g\+\+)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find(firstLine)
            return match?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun pchKey(
            compiler: String,
            compilerVersion: String,
            realHeader: File,
            compileSettings: CphCompileSettings,
        ): String {
            val raw = listOf(
                "gcc-bits-pch-v1",
                compiler,
                compilerVersion.lineSequence().firstOrNull().orEmpty(),
                realHeader.absolutePath,
                realHeader.lastModified().toString(),
                realHeader.length().toString(),
                compileSettings.cppStandard.flag.orEmpty(),
                CphCompileOptions.renderCompilerOptions(CphCompileOptions.additionalArgs(compileSettings)),
            ).joinToString("\u0000")
            return sha256(raw)
        }

        fun probeKey(input: CphBitsHeaderProbeInput): String {
            val raw = listOf(
                "gcc-bits-header-probe-v2",
                input.compilerPath,
                input.compilerLastModified.toString(),
                input.compilerLength.toString(),
                input.toolchainName,
                sha256(input.toolchainEnvironment),
                input.standardFlag,
                input.compileOptions,
            ).joinToString("\u0000")
            return sha256(raw)
        }

        fun stdModuleKey(input: CphBitsHeaderProbeInput, compilerVersion: String, realHeader: File? = null): String {
            val raw = listOf(
                "gcc-std-module-v2",
                input.compilerPath,
                input.compilerLastModified.toString(),
                input.compilerLength.toString(),
                input.toolchainName,
                sha256(input.toolchainEnvironment),
                compilerVersion.lineSequence().firstOrNull().orEmpty(),
                input.standardFlag,
                input.compileOptions,
                realHeader?.absolutePath.orEmpty(),
                realHeader?.lastModified()?.toString().orEmpty(),
                realHeader?.length()?.toString().orEmpty(),
            ).joinToString("\u0000")
            return sha256(raw)
        }

        fun parseBitsHeaderDependency(output: String): String? {
            return dependencyTokens(output).firstOrNull { token ->
                token.replace('\\', '/').endsWith("/bits/stdc++.h") ||
                    token.replace('\\', '/') == "bits/stdc++.h"
            }
        }

        private fun dependencyTokens(output: String): List<String> {
            val logical = StringBuilder()
            output.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
                val trimmed = line.trimEnd()
                if (trimmed.endsWith("\\")) {
                    logical.append(trimmed.dropLast(1)).append(' ')
                } else {
                    logical.append(trimmed).append(' ')
                }
            }

            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var index = 0
            while (index < logical.length) {
                val ch = logical[index]
                if (ch == '\\' && index + 1 < logical.length && logical[index + 1].isWhitespace()) {
                    current.append(logical[index + 1])
                    index += 2
                    continue
                }
                if (ch.isWhitespace()) {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString().replace("\\:", ":"))
                        current.clear()
                    }
                } else {
                    current.append(ch)
                }
                index += 1
            }
            if (current.isNotEmpty()) {
                tokens.add(current.toString().replace("\\:", ":"))
            }
            return tokens
        }

        fun escapeIncludePath(path: String): String =
            path.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }

        private fun elapsedMillis(startedAt: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}

internal data class CphBitsHeaderProbeInput(
    val compilerPath: String,
    val compilerLastModified: Long,
    val compilerLength: Long,
    val toolchainName: String,
    val toolchainEnvironment: String,
    val standardFlag: String,
    val compileOptions: String,
)

internal sealed class CphBitsHeaderProbeResolution {
    data class Found(
        val header: File,
        val compilerVersion: String,
        val summary: String,
        val cppVersion: Long,
    ) : CphBitsHeaderProbeResolution()

    data class Missing(
        val summary: String,
    ) : CphBitsHeaderProbeResolution()
}

internal class CphBitsHeaderProbeCache(private val cacheDir: File) {
    private val negativeSummaries = ConcurrentHashMap<String, String>()

    fun resolve(
        input: CphBitsHeaderProbeInput,
        probe: () -> CphBitsHeaderProbeResolution,
    ): CphBitsHeaderProbeResolution {
        val key = CphGccStdlibService.probeKey(input)
        negativeSummaries[key]?.let {
            return CphBitsHeaderProbeResolution.Missing("$it | probe cached")
        }
        positive(key)?.let { return it }

        return when (val result = probe()) {
            is CphBitsHeaderProbeResolution.Found -> {
                rememberPositive(key, result.header, result.compilerVersion, result.cppVersion)
                result
            }
            is CphBitsHeaderProbeResolution.Missing -> {
                negativeSummaries[key] = result.summary
                result
            }
        }
    }

    private fun positive(key: String): CphBitsHeaderProbeResolution.Found? {
        val cacheFile = cacheFile(key)
        if (!cacheFile.isFile) return null
        val properties = Properties()
        runCatching {
            cacheFile.inputStream().use { properties.load(it) }
        }.getOrElse {
            cacheFile.delete()
            return null
        }
        val headerPath = properties.getProperty("headerPath")?.takeIf { it.isNotBlank() }
        val compilerVersion = properties.getProperty("compilerVersion")?.takeIf { it.isNotBlank() }
        val cppVersion = properties.getProperty("cppVersion")?.toLongOrNull()
        if (headerPath == null || compilerVersion == null || cppVersion == null) {
            cacheFile.delete()
            return null
        }
        val header = File(headerPath)
        if (!header.isFile) {
            cacheFile.delete()
            return null
        }
        return CphBitsHeaderProbeResolution.Found(
            header = runCatching { header.canonicalFile }.getOrDefault(header.absoluteFile),
            compilerVersion = compilerVersion,
            summary = "probe cache hit",
            cppVersion = cppVersion,
        )
    }

    private fun rememberPositive(key: String, header: File, compilerVersion: String, cppVersion: Long) {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return
        val properties = Properties()
        properties.setProperty("headerPath", header.absolutePath)
        properties.setProperty("compilerVersion", compilerVersion)
        properties.setProperty("cppVersion", cppVersion.toString())
        runCatching {
            cacheFile(key).outputStream().use {
                properties.store(it, "CPH bits/stdc++.h probe cache")
            }
        }
    }

    private fun cacheFile(key: String): File = File(cacheDir, "$key.properties")

    companion object {
        fun default(): CphBitsHeaderProbeCache =
            CphBitsHeaderProbeCache(File(PathManager.getSystemPath(), "cph-target-runner/pch-probe"))
    }
}

internal interface CphPchProcessRunner {
    fun run(
        command: List<String>,
        timeoutSeconds: Long,
        input: String? = null,
        environment: CPPEnvironment,
    ): CphPchProcessResult
}

internal object CphDefaultPchProcessRunner : CphPchProcessRunner {
    override fun run(
        command: List<String>,
        timeoutSeconds: Long,
        input: String?,
        environment: CPPEnvironment,
    ): CphPchProcessResult {
        val commandLine = GeneralCommandLine(command)
            .withRedirectErrorStream(true)
        environment.prepare(commandLine, CidrToolEnvironment.PrepareFor.BUILD)
        val process = commandLine.createProcess()
        val output = StringBuilder()
        val outputReader = Thread {
            output.append(process.inputStream.bufferedReader().readText())
        }.also {
            it.isDaemon = true
            it.start()
        }
        if (input != null) {
            process.outputStream.bufferedWriter().use { it.write(input) }
        } else {
            process.outputStream.close()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Command timed out: ${command.firstOrNull().orEmpty()}")
        }
        outputReader.join(1000)
        return CphPchProcessResult(
            exitCode = process.exitValue(),
            output = output.toString(),
        )
    }
}

internal data class CphPchProcessResult(
    val exitCode: Int,
    val output: String,
)

internal enum class CphStdlibStatus {
    OFF,
    SKIPPED,
    HIT,
    BUILT,
    FAILED,
}

internal data class CphGccStdlibResult(
    val moduleSources: List<File> = emptyList(),
    val args: List<String> = emptyList(),
    val status: CphStdlibStatus = CphStdlibStatus.OFF,
    val elapsedMillis: Long = 0L,
    val summary: String = "",
)
