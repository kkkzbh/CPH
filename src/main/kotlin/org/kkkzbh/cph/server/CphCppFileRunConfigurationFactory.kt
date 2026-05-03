package org.kkkzbh.cph.server

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.kkkzbh.cph.CphTargetKind
import org.kkkzbh.cph.CphTargetResolver

internal data class CphRunConfigurationCreation(
    val settings: RunnerAndConfigurationSettings,
    val createdNew: Boolean,
)

internal object CphCppFileRunConfigurationFactory {
    private val logger = Logger.getInstance(CphCppFileRunConfigurationFactory::class.java)

    private const val CPP_FILE_RUN_CONFIGURATION_CLASS =
        "com.jetbrains.cidr.cpp.runfile.CppFileRunConfiguration"

    fun findOrCreate(
        project: Project,
        sourceFile: VirtualFile,
        displayName: String,
    ): CphRunConfigurationCreation {
        val runManager = RunManager.getInstance(project)
        val sourcePath = sourceFile.path

        runManager.allSettings.firstOrNull { settings ->
            isCppFileRunConfiguration(settings) &&
                runCatching { sourcePathsEqual(readSourceFile(settings.configuration), sourcePath) }.getOrDefault(false)
        }?.let { return CphRunConfigurationCreation(it, createdNew = false) }

        val type = findCppFileConfigurationType()
            ?: error("CLion C/C++ File configuration type not found.")
        val factory = type.configurationFactories.firstOrNull()
            ?: error("CLion C/C++ File configuration factory not found.")

        val name = displayName.ifBlank { sourceFile.nameWithoutExtension }
        val uniqueName = uniqueConfigurationName(runManager, name)
        val newSettings = runManager.createConfiguration(uniqueName, factory)

        applySourceFile(newSettings.configuration, sourcePath)

        runManager.addConfiguration(newSettings)
        return CphRunConfigurationCreation(newSettings, createdNew = true)
    }

    private fun findCppFileConfigurationType(): ConfigurationType? {
        runCatching {
            val typeClass = Class.forName("com.jetbrains.cidr.cpp.runfile.CppFileRunConfigurationType")
                .asSubclass(ConfigurationType::class.java)
            ConfigurationTypeUtil.findConfigurationType(typeClass)
        }.getOrNull()?.let { return it }

        return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull(::isCppFileConfigurationType)
    }

    private fun isCppFileConfigurationType(type: ConfigurationType): Boolean {
        val typeId = type.id
        val displayName = runCatching { type.displayName }.getOrDefault("")
        val className = type.javaClass.name
        val candidates = listOf(typeId, displayName, className)
        return candidates.any {
            it.contains("cppfile", ignoreCase = true) ||
                it.contains("runfile", ignoreCase = true) ||
                it.contains("c/c++ file", ignoreCase = true) ||
                it.contains("c++ file", ignoreCase = true)
        }
    }

    private fun applySourceFile(configuration: Any, sourcePath: String) {
        val applied = trySetOnObject(configuration, sourcePath) || trySetOnOptions(configuration, sourcePath)
        if (!applied) {
            logger.warn("Could not set source file on CppFileRunConfiguration; class=${configuration.javaClass.name}")
            error("Cannot set source file on CLion C/C++ File run configuration.")
        }
    }

    private fun trySetOnObject(target: Any, sourcePath: String): Boolean {
        return SETTERS.any { setter ->
            invokeStringSetter(target, setter, sourcePath)
        }
    }

    private fun trySetOnOptions(configuration: Any, sourcePath: String): Boolean {
        val options = runCatching {
            configuration.javaClass.methods.firstOrNull {
                it.name == "getOptions" && it.parameterCount == 0
            }?.invoke(configuration)
        }.getOrNull() ?: return false
        return SETTERS.any { setter -> invokeStringSetter(options, setter, sourcePath) }
    }

    private fun invokeStringSetter(target: Any, methodName: String, value: String): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return false
        return runCatching {
            method.invoke(target, value)
            true
        }.getOrDefault(false)
    }

    internal fun readSourceFile(configuration: Any): String? {
        for (getter in GETTERS) {
            invokeNoArgString(configuration, getter)?.let { return it }
            val options = runCatching {
                configuration.javaClass.methods.firstOrNull {
                    it.name == "getOptions" && it.parameterCount == 0
                }?.invoke(configuration)
            }.getOrNull() ?: continue
            invokeNoArgString(options, getter)?.let { return it }
        }
        return null
    }

    internal fun sourcePathsEqual(storedPath: String?, expectedPath: String): Boolean {
        val stored = normalizeSourcePath(storedPath) ?: return false
        val expected = normalizeSourcePath(expectedPath) ?: return false
        return stored == expected
    }

    private fun normalizeSourcePath(path: String?): String? {
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

    private fun invokeNoArgString(target: Any, methodName: String): String? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return null
        return runCatching { method.invoke(target)?.toString()?.takeIf { it.isNotBlank() } }
            .getOrNull()
    }

    private fun uniqueConfigurationName(runManager: RunManager, base: String): String {
        val existing = runManager.allSettings.map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private fun isCppFileRunConfiguration(settings: RunnerAndConfigurationSettings): Boolean {
        val configuration = settings.configuration
        if (runCatching {
                Class.forName(CPP_FILE_RUN_CONFIGURATION_CLASS).isAssignableFrom(configuration.javaClass)
            }.getOrDefault(false)
        ) {
            return true
        }
        return CphTargetResolver.detectTargetKind(
            typeId = settings.type.id,
            typeName = settings.type.displayName,
            className = configuration.javaClass.name,
        ) == CphTargetKind.CPP_FILE
    }

    private val SETTERS = listOf("setSourceFile", "setFilePath", "setSourcePath")
    private val GETTERS = listOf("getSourceFile", "getFilePath", "getSourcePath")
}
