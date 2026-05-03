package org.kkkzbh.cph.server

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration
import com.jetbrains.cidr.cpp.runfile.CppFileRunConfigurationType
import org.kkkzbh.cph.CphStateService
import java.io.File

internal data class CphRunConfigurationCreation(
    val settings: RunnerAndConfigurationSettings,
    val createdNew: Boolean,
)

internal object CphCppFileRunConfigurationFactory {
    fun findOrCreate(
        project: Project,
        sourceFile: VirtualFile,
        displayName: String,
        workingDirectory: String? = null,
    ): CphRunConfigurationCreation {
        val runManager = RunManager.getInstance(project)
        val sourcePath = sourceFile.path
        val resolvedWorkingDirectory = workingDirectory?.let { resolveWorkingDirectory(project, it) }
        resolvedWorkingDirectory?.let(::ensureWorkingDirectoryExists)

        runManager.allSettings.firstOrNull { settings ->
            val configuration = settings.configuration as? CppFileRunConfiguration ?: return@firstOrNull false
            sourcePathsEqual(readSourceFile(configuration), sourcePath)
        }?.let {
            val configuration = it.configuration as CppFileRunConfiguration
            if (resolvedWorkingDirectory != null) {
                applyWorkingDirectory(configuration, resolvedWorkingDirectory)
            }
            return CphRunConfigurationCreation(it, createdNew = false)
        }

        val type = ConfigurationTypeUtil.findConfigurationType(CppFileRunConfigurationType::class.java)
        val factory = type.configurationFactories.firstOrNull()
            ?: error("CLion C/C++ File configuration factory not found.")

        val name = displayName.ifBlank { sourceFile.nameWithoutExtension }
        val uniqueName = uniqueConfigurationName(runManager, name)
        val newSettings = runManager.createConfiguration(uniqueName, factory)
        val configuration = newSettings.configuration as? CppFileRunConfiguration
            ?: error("CLion C/C++ File factory created ${newSettings.configuration.javaClass.name}.")

        applySourceFile(configuration, sourcePath)
        if (resolvedWorkingDirectory != null) {
            applyWorkingDirectory(configuration, resolvedWorkingDirectory)
        }

        runManager.addConfiguration(newSettings)
        return CphRunConfigurationCreation(newSettings, createdNew = true)
    }

    private fun applySourceFile(configuration: CppFileRunConfiguration, sourcePath: String) {
        configuration.options.sourceFile = sourcePath
    }

    internal fun applyWorkingDirectory(configuration: CppFileRunConfiguration, workingDirectory: String): Boolean {
        val normalized = CphStateService.normalizeSingleFileWorkingDirectory(workingDirectory)
        if (normalizePath(configuration.workingDirectory) == normalizePath(normalized)) return false
        configuration.workingDirectory = normalized
        return true
    }

    internal fun readSourceFile(configuration: CppFileRunConfiguration): String? = configuration.options.sourceFile

    internal fun sourcePathsEqual(storedPath: String?, expectedPath: String): Boolean {
        val stored = normalizePath(storedPath) ?: return false
        val expected = normalizePath(expectedPath) ?: return false
        return stored == expected
    }

    private fun normalizePath(path: String?): String? {
        val text = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutFileScheme = when {
            text.startsWith("file://") -> text.removePrefix("file://")
            else -> text
        }
        return withoutFileScheme
            .substringBefore('?')
            .substringBefore('#')
            .replace('\\', '/')
            .removeSuffix("/")
    }

    private fun uniqueConfigurationName(runManager: RunManager, base: String): String {
        val existing = runManager.allSettings.map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private fun resolveWorkingDirectory(project: Project, workingDirectory: String): String {
        val normalized = CphStateService.normalizeSingleFileWorkingDirectory(workingDirectory)
        val file = File(normalized)
        if (file.isAbsolute) return file.path
        val basePath = project.basePath?.takeIf { it.isNotBlank() } ?: return normalized
        return File(basePath, normalized).path
    }

    internal fun ensureWorkingDirectoryExists(workingDirectory: String) {
        val directory = File(workingDirectory)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                error("CPH working directory path exists but is not a directory: $workingDirectory")
            }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            error("Cannot create CPH working directory: $workingDirectory")
        }
    }
}
