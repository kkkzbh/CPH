package org.kkkzbh.cph

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.cidr.lang.daemon.clang.clangd.ClangLanguageServiceProvider
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.ClangIdeFacade
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.ClangUrlConverter
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.Cpp20Module
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.Cpp20ModulesContext
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.params.ClionCompileCommandParams
import com.jetbrains.cidr.lang.daemon.clang.clangd.lsp.server.ClangServerListener
import com.jetbrains.cidr.lang.daemon.clang.clangd.settings.CppModulesState
import com.jetbrains.cidr.lang.daemon.clang.clangd.settings.CppModulesStateUtil
import com.jetbrains.cidr.lang.workspace.OCWorkspaceInterner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

/** Owns the standard-library source modules for the current single-file compilation. */
internal class CphStdlibCodeInsightService(private val project: Project) : Disposable {
    private data class Request(val source: File, val modules: List<File>)

    private var request: Request? = null
    private var analysisCache: CphStdlibAnalysisCache? = null
    private val index = CphStdlibModuleIndex(project.service<CppModulesState>())

    init {
        project.messageBus.connect(this).subscribe(ClangServerListener.TOPIC, object : ClangServerListener {
            override fun onServerRunning() {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val current = synchronized(this@CphStdlibCodeInsightService) { request } ?: return@executeOnPooledThread
                    if (!project.isDisposed) {
                        try {
                            prepare(current)
                        } catch (e: ProcessCanceledException) {
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.getInstance(CphStdlibCodeInsightService::class.java)
                                .warn("Cannot prepare standard library code insight", e)
                        }
                    }
                }
            }
        })
    }

    fun update(source: File, moduleSources: List<File>) {
        val next = Request(source, moduleSources)
        synchronized(this) { request = next }
        prepare(next)
    }

    private fun prepare(next: Request) {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        var cachePath: Path? = null
        val desired = if (next.modules.isEmpty()) emptyList() else {
            // The language server may still be starting when a run configuration is prepared.
            val server = ClangLanguageServiceProvider.getIfStarted(project) ?: return
            val source = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(next.source)
                ?: error("Cannot find code insight source: ${next.source}")
            val context = server.context
            cachePath = Path.of(context.cpp20ModulesPath)
            val command = moduleCompilationCommand(
                server.clangIdeFacade, server.urlConverter, project, source,
                Cpp20ModulesContext(context.cpp20ModuleMapPath, context.cpp20ModulesPath, context.moduleMapPath),
            ).get(30, TimeUnit.SECONDS) ?: error("CLion has no analysis configuration for ${next.source}")
            check(next.modules.size == 2) { "Expected std and std.compat module sources" }
            listOf("std", "std.compat").zip(next.modules).map { (name, file) ->
                val path = file.invariantSeparatorsPath
                standardLibraryModule(name, command.ccParams, server.urlConverter.toUri(file, false), path)
            }
        }
        synchronized(this) {
            if (request !== next || project.isDisposed) return
            val cache = cachePath?.let(::CphStdlibAnalysisCache)
            var cacheChanged = false
            if (cache != null) {
                cacheChanged = cache.prepare(desired, ApplicationInfo.getInstance().build.asString())
                analysisCache = cache
            } else {
                analysisCache?.clear()
                analysisCache = null
            }
            val indexChanged = index.replace(desired)
            if (indexChanged || cacheChanged) publish()
        }
    }

    private fun publish() {
        CppModulesStateUtil.updateModuleMap(project.service<CppModulesState>(), project)
    }

    @Synchronized
    override fun dispose() {
        request = null
        index.replace(emptyList())
    }

    companion object {
        internal fun moduleCompilationCommand(
            facade: ClangIdeFacade,
            converter: ClangUrlConverter,
            project: Project,
            source: VirtualFile,
            modules: Cpp20ModulesContext,
        ) = facade.getCompilationCommandAsync(
            converter, project, source,
            // Module commands have no consumer macros file. Clang supplies their built-in macros.
            "",
            modules,
            OCWorkspaceInterner(),
        )

        internal fun standardLibraryModule(
            name: String, command: ClionCompileCommandParams, uri: String, path: String,
        ) = Cpp20Module(name, moduleCommand(command, uri, path), "", path, false)

        internal fun moduleCommand(original: ClionCompileCommandParams, uri: String, path: String): ClionCompileCommandParams {
            val args = original.commandLine
            check(args.size >= 2 && args[args.lastIndex - 1] == "--") {
                "CLion analysis command must end with '-- <source>'"
            }
            // PCMs are built by Clang. Its built-in macros describe the parser's language features;
            // the consumer command supplies the target, standard, include paths and user -D/-U flags.
            val moduleArgs = args.dropLast(1).filterNot {
                it.startsWith("-fgnuc-version=") || it == "-fno-define-target-os-macros"
            }
            return original.copy(uri = uri, entryUri = uri, commandLine = moduleArgs + path,
                output = "", usePredefines = true)
        }
    }
}

/** Removes only entries contributed by this owner, leaving project modules intact. */
internal class CphStdlibModuleIndex(private val state: CppModulesState) {
    private var owned: List<Cpp20Module> = emptyList()

    fun replace(next: List<Cpp20Module>): Boolean {
        var changed = false
        owned.filter { old -> next.none { it.sourcePath == old.sourcePath } }.forEach { old ->
            if (state.byPath { it[old.sourcePath] } === old) {
                state.removeCppModuleByPath(old.sourcePath)
                changed = true
            }
        }
        owned = next.map { module ->
            owned.firstOrNull { old ->
                old.name == module.name && old.sourcePath == module.sourcePath &&
                    old.compileCommand == module.compileCommand && old.ppDefines == module.ppDefines &&
                    old.isImpl == module.isImpl
            } ?: module
        }
        return restore() || changed
    }

    fun restore(): Boolean {
        val missing = state.byPath { current -> owned.filter { current[it.sourcePath] !== it } }
        missing.forEach(state::addCppModule)
        return missing.isNotEmpty()
    }
}

/** CLion stores named PCMs by module name; their validity belongs to the analysis configuration. */
internal class CphStdlibAnalysisCache(private val directory: Path) {
    private val fingerprintFile = directory.resolve("cph-stdlib.sha256")

    fun prepare(modules: List<Cpp20Module>, ideBuild: String): Boolean {
        val inputs = buildList {
            add(ideBuild)
            modules.forEach { module ->
                add(module.name)
                add(module.sourcePath)
                val source = File(module.sourcePath)
                add(source.lastModified().toString())
                add(source.length().toString())
                val command = checkNotNull(module.compileCommand)
                add(command.directory)
                addAll(command.commandLine)
                add(command.usePredefines.toString())
                add(module.ppDefines.orEmpty())
                val compiler = File(command.commandLine.first())
                add(compiler.lastModified().toString())
                add(compiler.length().toString())
            }
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(inputs.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (Files.exists(fingerprintFile) && Files.readString(fingerprintFile) == fingerprint) return false
        Files.createDirectories(directory)
        clear()
        Files.writeString(fingerprintFile, fingerprint)
        return true
    }

    fun clear() {
        listOf("std.pcm", "std.compat.pcm", "cph-stdlib.sha256").forEach { Files.deleteIfExists(directory.resolve(it)) }
    }
}
