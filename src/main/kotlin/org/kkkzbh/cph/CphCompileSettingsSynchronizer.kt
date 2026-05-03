package org.kkkzbh.cph

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.cidr.cpp.runfile.CppFileBuildTargetsService
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class CphCompileSyncResult(
    val changed: Boolean = false,
    val error: String? = null,
)

internal data class CphCppFileCompilerOptionsUpdate(
    val compilerOptions: String,
    val changed: Boolean,
)

internal object CphCppFileCompilerOptionsSync {
    fun compute(
        current: String,
        settings: CphCompileSettings,
    ): CphCppFileCompilerOptionsUpdate {
        val next = if (settings.hasOverrides()) {
            CphCompileOptions.mergeCompilerOptions("", settings)
        } else {
            ""
        }
        return CphCppFileCompilerOptionsUpdate(
            compilerOptions = next,
            changed = next != current,
        )
    }
}

internal class CphCompileSettingsSynchronizer(private val project: Project) {
    fun sync(
        identity: CphTargetIdentity,
        targetCases: CphTargetCases,
        compileSettings: CphCompileSettings,
        waitForCppFileTarget: Boolean = false,
    ): CphCompileSyncResult {
        val settings = identity.settings ?: return CphCompileSyncResult()
        return try {
            when (identity.kind) {
                CphTargetKind.CPP_FILE -> syncCppFile(settings, targetCases, compileSettings, waitForCppFileTarget)
                CphTargetKind.CMAKE_APP -> syncCMake(settings, compileSettings)
                CphTargetKind.UNSUPPORTED -> CphCompileSyncResult()
            }
        } catch (e: Throwable) {
            CphCompileSyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun diagnoseCppFileWorkspace(settings: RunnerAndConfigurationSettings): String {
        return runCatching {
            val configuration = settings.configuration as? CppFileRunConfiguration
                ?: return@runCatching "Not a CLion C/C++ File configuration."
            val targetsService = project.getService(CppFileBuildTargetsService::class.java)
                ?: return@runCatching "Cannot access CLion C/C++ File build targets service."
            formatCppFileWorkspaceDiagnostics(configuration, targetsService)
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    fun refreshCppFileWorkspace(
        settings: RunnerAndConfigurationSettings,
        waitForTarget: Boolean,
    ): CphCompileSyncResult {
        return try {
            runOnEdt {
                refreshCppFileTarget(settings)
            }
            if (waitForTarget) {
                waitForCppFileWorkspace(settings)
            }
            CphCompileSyncResult()
        } catch (e: Throwable) {
            CphCompileSyncResult(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun syncCppFile(
        settings: RunnerAndConfigurationSettings,
        @Suppress("UNUSED_PARAMETER") targetCases: CphTargetCases,
        compileSettings: CphCompileSettings,
        waitForTarget: Boolean,
    ): CphCompileSyncResult {
        val access = cppFileCompilerOptionsAccess(settings)
        val current = access.get()
        val update = CphCppFileCompilerOptionsSync.compute(
            current = current,
            settings = compileSettings,
        )
        if (!update.changed) {
            if (waitForTarget) {
                refreshCppFileTarget(settings)
            }
            return CphCompileSyncResult()
        }

        runOnEdt {
            access.set(update.compilerOptions)
            refreshCppFileTarget(settings)
        }
        if (waitForTarget) {
            waitForCppFileWorkspace(settings)
        }
        return CphCompileSyncResult(changed = true)
    }

    private fun waitForCppFileWorkspace(settings: RunnerAndConfigurationSettings) {
        val configuration = settings.configuration as? CppFileRunConfiguration ?: return
        waitForCppFileBuildTarget(configuration)
    }

    private fun refreshCppFileTarget(settings: RunnerAndConfigurationSettings) {
        val configuration = settings.configuration as? CppFileRunConfiguration ?: return
        val data = configuration.generateBuildTargetAndConfigurationData()
        configuration.setTargetAndConfigurationData(data)
        runCatching {
            val processor = projectService(CPP_FILE_WORKSPACE_PROCESSOR_CLASS, configuration.javaClass.classLoader)
                ?: return@runCatching
            val currentData = configuration.createConfigurationData()
            processor.javaClass.methods.firstOrNull {
                it.name == "addConfigurations" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0].isAssignableFrom(List::class.java) &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }?.invoke(processor, listOf(currentData), true)
        }
    }

    private fun waitForCppFileBuildTarget(configuration: CppFileRunConfiguration) {
        val targetsService = project.getService(CppFileBuildTargetsService::class.java)
            ?: throw ExecutionException("Cannot access CLion C/C++ File build targets service.")

        repeat(CPP_FILE_TARGET_WAIT_ATTEMPTS) {
            val target = targetsService.getTargetOrNullFor(configuration)
            if (target != null) return
            Thread.sleep(CPP_FILE_TARGET_WAIT_INTERVAL_MILLIS)
        }
        throw ExecutionException(
            "CLion did not refresh the C/C++ File build target after updating compiler options. " +
                formatCppFileWorkspaceDiagnostics(configuration, targetsService),
        )
    }

    private fun syncCMake(
        settings: RunnerAndConfigurationSettings,
        compileSettings: CphCompileSettings,
    ): CphCompileSyncResult {
        val targetName = resolveCMakeTargetName(settings)
            ?: throw ExecutionException("Cannot resolve the selected CMake target name.")
        val sourceDir = resolveCMakeSourceDir(settings)
            ?: throw ExecutionException("Cannot resolve the CMake source directory.")
        val cmakeLists = File(sourceDir, "CMakeLists.txt")
        if (!cmakeLists.isFile) {
            throw ExecutionException("Cannot find CMakeLists.txt in ${sourceDir.absolutePath}.")
        }

        val original = cmakeLists.readText()
        val updated = CphCMakeManagedBlock.apply(original, targetName, compileSettings)
        if (updated == original) return CphCompileSyncResult()

        runOnEdt {
            writeTextWithIdeDocument(cmakeLists, updated)
        }
        scheduleCMakeReload()
        return CphCompileSyncResult(changed = true)
    }

    private fun writeTextWithIdeDocument(file: File, text: String) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: throw ExecutionException("Cannot open ${file.absolutePath}.")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: throw ExecutionException("Cannot open document for ${file.absolutePath}.")
        WriteCommandAction.runWriteCommandAction(project, "Sync CPH CMake Settings", null, Runnable {
            document.setText(text)
            FileDocumentManager.getInstance().saveDocument(document)
        })
    }

    private fun scheduleCMakeReload() {
        runCatching {
            val workspace = cmakeWorkspaceInstance() ?: return
            workspace.javaClass.methods.firstOrNull {
                it.name == "scheduleReload" && it.parameterCount == 0
            }?.invoke(workspace)
        }
    }

    private fun cppFileCompilerOptionsAccess(settings: RunnerAndConfigurationSettings): CompilerOptionsAccess {
        val configuration = settings.configuration as? CppFileRunConfiguration
            ?: throw ExecutionException("'${settings.name}' is not a CLion C/C++ File configuration.")
        val options = configuration.options
        return CompilerOptionsAccess(
            get = { options.compilerOptions.orEmpty() },
            set = { options.compilerOptions = it },
        )
    }

    private fun resolveCMakeTargetName(settings: RunnerAndConfigurationSettings): String? {
        val configuration = settings.configuration
        val buildAndRun = resolveCMakeBuildAndRun(settings)
        val explicit = buildAndRun?.javaClass?.fields?.firstOrNull { it.name == "explicitBuildTargetName" }
            ?.get(buildAndRun)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        return runCatching {
            val target = configuration.javaClass.methods.firstOrNull {
                it.name == "getCMakeTarget" && it.parameterCount == 0
            }?.invoke(configuration)
            target?.javaClass?.methods?.firstOrNull {
                it.name == "getName" && it.parameterCount == 0
            }?.invoke(target)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun resolveCMakeSourceDir(settings: RunnerAndConfigurationSettings): File? {
        val buildAndRun = resolveCMakeBuildAndRun(settings)
        val buildDir = buildAndRun?.let { invokeFileField(it, "buildConfiguration", "getBuildWorkingDir") }
        val cacheSource = buildDir?.let { readCMakeCacheValue(it, "CMAKE_HOME_DIRECTORY") }
        return cacheSource?.let(::File) ?: resolveCMakeWorkspaceSourceDir() ?: project.basePath?.let(::File)
    }

    private fun resolveCMakeBuildAndRun(settings: RunnerAndConfigurationSettings): Any? {
        val configuration = settings.configuration
        val target = runCatching { resolveExecutionTarget(settings) }.getOrNull() ?: return null
        return runCatching {
            configuration.javaClass.methods.firstOrNull {
                it.name == "getBuildAndRunConfigurations" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(target.javaClass)
            }?.invoke(configuration, target)
        }.getOrNull()
    }

    private fun resolveExecutionTarget(settings: RunnerAndConfigurationSettings): ExecutionTarget {
        val configuration = settings.configuration
        val activeTarget = ExecutionTargetManager.getActiveTarget(project)
        if (ExecutionTargetManager.canRun(configuration, activeTarget)) return activeTarget
        val manager = ExecutionTargetManager.getInstance(project)
        val foundTarget = manager.findTarget(configuration)
        if (foundTarget != null && ExecutionTargetManager.canRun(configuration, foundTarget)) return foundTarget
        return ExecutionTargetManager.getTargetsToChooseFor(project, configuration)
            .firstOrNull { ExecutionTargetManager.canRun(configuration, it) }
            ?: activeTarget
    }

    private fun invokeFileField(value: Any, fieldName: String, methodName: String): File? {
        val nested = value.javaClass.fields.firstOrNull { it.name == fieldName }?.get(value) ?: return null
        return runCatching {
            nested.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }?.invoke(nested) as? File
        }.getOrNull()
    }

    private fun readCMakeCacheValue(buildDir: File, key: String): String? {
        val cache = File(buildDir, "CMakeCache.txt")
        if (!cache.isFile) return null
        return runCatching {
            cache.useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@firstNotNullOfOrNull null
                    val rawKey = line.substring(0, separator).substringBefore(':')
                    if (rawKey == key) line.substring(separator + 1).takeIf { it.isNotBlank() } else null
                }
            }
        }.getOrNull()
    }

    private fun resolveCMakeWorkspaceSourceDir(): File? {
        return runCatching {
            val workspace = cmakeWorkspaceInstance() ?: return@runCatching null
            workspace.javaClass.methods.firstOrNull {
                it.name == "getModelProjectDir" && it.parameterCount == 0
            }?.invoke(workspace) as? File
        }.getOrNull()
    }

    private fun cmakeWorkspaceInstance(): Any? {
        val workspaceClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace")
        return workspaceClass.methods.firstOrNull {
            it.name == "getInstance" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(project.javaClass)
        }?.invoke(null, project)
    }

    private fun formatCppFileWorkspaceDiagnostics(
        configuration: CppFileRunConfiguration,
        targetsService: CppFileBuildTargetsService,
    ): String {
        val currentData = runCatching { configuration.createConfigurationData().toString() }
            .getOrElse { it.message ?: it.javaClass.simpleName }
        val targets = targetsService.getTargets().joinToString(limit = 4) { target ->
            target.data.toString()
        }.orEmpty()
        return "Current data: $currentData. Known targets: [$targets]."
    }

    private fun projectService(className: String, classLoader: ClassLoader): Any? {
        return runCatching {
            val serviceClass = Class.forName(className, true, classLoader)
            (project as ComponentManager).getService(serviceClass)
        }.getOrNull()
    }

    private fun <T> runOnEdt(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        application.invokeAndWait {
            runCatching { result.set(action()) }
                .onFailure { error.set(it) }
        }
        error.get()?.let { throw it }
        return result.get()
    }

    private data class CompilerOptionsAccess(
        val get: () -> String,
        val set: (String) -> Unit,
    )

    private companion object {
        private const val CPP_FILE_WORKSPACE_PROCESSOR_CLASS = "com.jetbrains.cidr.cpp.runfile.CppFileWorkspaceProcessor"
        private const val CPP_FILE_TARGET_WAIT_ATTEMPTS = 100
        private const val CPP_FILE_TARGET_WAIT_INTERVAL_MILLIS = 100L
    }
}

internal object CphCMakeManagedBlock {
    fun apply(text: String, targetName: String, settings: CphCompileSettings): String {
        val block = render(targetName, settings)
        val withoutOld = remove(text, targetName).trimEnd()
        if (block == null) {
            return if (withoutOld == text.trimEnd()) text else withoutOld + "\n"
        }
        return withoutOld + "\n\n" + block + "\n"
    }

    fun render(targetName: String, settings: CphCompileSettings): String? {
        if (!settings.hasOverrides()) return null
        val lines = mutableListOf<String>()
        lines.add(beginMarker(targetName))
        settings.cppStandard.cmakeValue?.let { standard ->
            lines.add("set_target_properties(${quote(targetName)} PROPERTIES")
            lines.add("    CXX_STANDARD $standard")
            lines.add("    CXX_STANDARD_REQUIRED ON")
            lines.add("    CXX_EXTENSIONS OFF")
            lines.add(")")
        }
        val options = CphCompileOptions.additionalArgs(settings)
        if (options.isNotEmpty()) {
            lines.add("target_compile_options(${quote(targetName)} PRIVATE ${options.joinToString(" ") { quote(it) }})")
        }
        lines.add(endMarker(targetName))
        return lines.joinToString("\n")
    }

    fun remove(text: String, targetName: String): String {
        val begin = Regex("^\\s*${Regex.escape(beginMarker(targetName))}\\s*$", RegexOption.MULTILINE)
        val match = begin.find(text) ?: return text
        val end = Regex("^\\s*${Regex.escape(endMarker(targetName))}\\s*$", RegexOption.MULTILINE)
            .find(text, match.range.last + 1) ?: return text
        val removeStart = previousLineBreakStart(text, match.range.first)
        val removeEnd = nextLineBreakEnd(text, end.range.last + 1)
        return text.removeRange(removeStart, removeEnd)
    }

    fun quote(value: String): String = CphCompileOptions.quoteCMakeArgument(value)

    private fun beginMarker(targetName: String): String = "# CPH Target Runner begin: $targetName"

    private fun endMarker(targetName: String): String = "# CPH Target Runner end: $targetName"

    private fun previousLineBreakStart(text: String, index: Int): Int {
        var start = index
        while (start > 0 && text[start - 1] != '\n') start--
        return start
    }

    private fun nextLineBreakEnd(text: String, index: Int): Int {
        var end = index
        while (end < text.length && text[end] != '\n') end++
        return if (end < text.length) end + 1 else end
    }
}
