package org.kkkzbh.cph

import com.intellij.openapi.project.Project
import com.jetbrains.cidr.cpp.runfile.CppFileBuildTargetsService
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.lang.CLanguageKind
import com.jetbrains.cidr.lang.OCFileTypeHelpers
import com.jetbrains.cidr.toolchains.EnvironmentProblems
import java.io.File
import java.util.concurrent.TimeUnit

internal sealed class CphCppFileCompilerResolution {
    abstract val elapsedMillis: Long

    data class Ready(
        val compilerPath: String,
        val sourceFile: File,
        val environment: CPPEnvironment,
        val toolchainName: String,
        val toolchainEnvironment: String,
        val summary: String,
        override val elapsedMillis: Long,
    ) : CphCppFileCompilerResolution()

    data class Skipped(
        val summary: String,
        override val elapsedMillis: Long,
    ) : CphCppFileCompilerResolution()
}

internal class CphCppFileCompilerResolver(private val project: Project) {
    fun resolve(configuration: CppFileRunConfiguration): CphCppFileCompilerResolution {
        val startedAt = System.nanoTime()
        fun skipped(summary: String): CphCppFileCompilerResolution.Skipped =
            CphCppFileCompilerResolution.Skipped(summary, elapsedMillis(startedAt))

        val target = project.getService(CppFileBuildTargetsService::class.java)
            ?.getTargetOrNullFor(configuration)
            ?: return skipped("no CLion build target")
        val buildConfiguration = target.buildConfiguration
        if (!buildConfiguration.isSupported) {
            return skipped("unsupported CLion toolchain")
        }
        val sourceFile = buildConfiguration.sourceFile.toFile().takeIf { it.isFile }
            ?: return skipped("no source")
        val projectDir = project.basePath?.let(::File)
        val environment = CPPToolchains.createCPPEnvironment(
            project,
            projectDir,
            configuration.options.toolchainName,
            EnvironmentProblems(),
            false,
            null,
        ) ?: return skipped("no CLion environment")
        val toolchain = runCatching {
            CPPToolchains.getInstance().getToolchainByNameOrDefault(configuration.options.toolchainName)
        }.getOrNull()
        val languageKind = OCFileTypeHelpers.getLanguageKind(sourceFile.name) ?: CLanguageKind.CPP
        val compilerPath = runCatching {
            compilerCommandPath(buildConfiguration.resolveCompiler(project, environment, languageKind).first)
        }.getOrElse {
            return skipped(it.message ?: it.javaClass.simpleName)
        }
        return CphCppFileCompilerResolution.Ready(
            compilerPath = compilerPath,
            sourceFile = sourceFile,
            environment = environment,
            toolchainName = toolchain?.name ?: configuration.options.toolchainName.orEmpty(),
            toolchainEnvironment = toolchain?.environment.orEmpty(),
            summary = "CLion $compilerPath",
            elapsedMillis = elapsedMillis(startedAt),
        )
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        fun compilerCommandPath(path: java.nio.file.Path): String =
            if (path.isAbsolute) path.toFile().absolutePath else path.toString()
    }
}
