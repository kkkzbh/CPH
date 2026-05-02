package org.kkkzbh.cph

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Method

enum class CphTargetKind {
    CMAKE_APP,
    CPP_FILE,
    UNSUPPORTED,
}

data class CphTargetIdentity(
    val id: String,
    val displayName: String,
    val settings: RunnerAndConfigurationSettings?,
    val runnable: Boolean,
    val message: String,
    val kind: CphTargetKind = CphTargetKind.UNSUPPORTED,
)

object CphTargetResolver {
    fun current(project: Project): CphTargetIdentity {
        val settings = RunManager.getInstance(project).selectedConfiguration
            ?: return CphTargetIdentity(
                id = "no-selected-configuration",
                displayName = "No run configuration selected",
                settings = null,
                runnable = false,
                message = "Select a CLion CMake Application run configuration.",
            )
        return fromSettings(settings)
    }

    fun fromSettings(settings: RunnerAndConfigurationSettings): CphTargetIdentity {
        val configuration = settings.configuration
        val typeId = settings.type.id
        val typeName = settings.type.displayName
        val className = configuration.javaClass.name

        return when (detectTargetKind(typeId, typeName, className)) {
            CphTargetKind.CMAKE_APP -> cMakeTarget(settings, configuration)
            CphTargetKind.CPP_FILE -> cppFileTarget(settings, configuration)
            CphTargetKind.UNSUPPORTED -> unsupportedTarget(settings, configuration, typeName)
        }
    }

    private fun cMakeTarget(
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfiguration,
    ): CphTargetIdentity {
        val targetName = readNamedValue(
            configuration,
            "getTargetName",
            "getCMakeTargetName",
            "getTarget",
            "getBuildTarget",
        )
        val profileName = readNamedValue(
            configuration,
            "getProfileName",
            "getCMakeProfileName",
            "getProfile",
        )
        val targetLabel = listOfNotNull(profileName, targetName).joinToString(" / ")
            .ifBlank { settings.name }

        return CphTargetIdentity(
            id = stableCMakeId(settings, configuration, profileName, targetName),
            displayName = targetLabel,
            settings = settings,
            runnable = true,
            message = "Ready",
            kind = CphTargetKind.CMAKE_APP,
        )
    }

    private fun cppFileTarget(
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfiguration,
    ): CphTargetIdentity {
        val sourceFile = readSourceFile(configuration)
        val displayName = cppFileDisplayName(settings.name, sourceFile)
        if (sourceFile.isNullOrBlank()) {
            return CphTargetIdentity(
                id = stableCppFileId(settings, configuration, null),
                displayName = displayName,
                settings = settings,
                runnable = false,
                message = "Select a source file in the CLion C/C++ File run configuration.",
                kind = CphTargetKind.CPP_FILE,
            )
        }

        return CphTargetIdentity(
            id = stableCppFileId(settings, configuration, sourceFile),
            displayName = displayName,
            settings = settings,
            runnable = true,
            message = "Ready",
            kind = CphTargetKind.CPP_FILE,
        )
    }

    private fun unsupportedTarget(
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfiguration,
        typeName: String,
    ): CphTargetIdentity {
        return CphTargetIdentity(
            id = stableCMakeId(settings, configuration, null, null),
            displayName = "${settings.name} (${typeName})",
            settings = settings,
            runnable = false,
            message = "CPH runs CLion CMake Application and C/C++ File configurations.",
            kind = CphTargetKind.UNSUPPORTED,
        )
    }

    internal fun detectTargetKind(typeId: String, typeName: String, className: String): CphTargetKind {
        val values = listOf(typeId, typeName, className)
        val cppFileLike = values.any {
            it.contains("cppfile", ignoreCase = true) ||
                it.contains("runfile", ignoreCase = true) ||
                it.contains("c/c++", ignoreCase = true) ||
                it.contains("c++ file", ignoreCase = true)
        }
        if (cppFileLike) return CphTargetKind.CPP_FILE

        val cMakeLike = values.any { it.contains("cmake", ignoreCase = true) }
        val applicationLike = values.any {
            it.contains("application", ignoreCase = true) || it.contains("app", ignoreCase = true)
        }
        return if (cMakeLike && applicationLike) CphTargetKind.CMAKE_APP else CphTargetKind.UNSUPPORTED
    }

    internal fun cppFileDisplayName(settingsName: String, sourceFile: String?): String {
        val sourceName = sourceFile
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).name }
            ?.takeIf { it.isNotBlank() }
        return sourceName ?: settingsName
    }

    private fun stableCMakeId(
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfiguration,
        profileName: String?,
        targetName: String?,
    ): String {
        return listOf(
            settings.type.id,
            configuration.javaClass.name,
            profileName.orEmpty(),
            targetName.orEmpty(),
            settings.name,
        ).joinToString("::")
    }

    private fun stableCppFileId(
        settings: RunnerAndConfigurationSettings,
        configuration: RunConfiguration,
        sourceFile: String?,
    ): String {
        return listOf(
            settings.type.id,
            configuration.javaClass.name,
            CphTargetKind.CPP_FILE.name,
            sourceFile.orEmpty(),
            settings.name,
        ).joinToString("::")
    }

    private fun readSourceFile(configuration: RunConfiguration): String? {
        return readNamedValue(configuration, "getSourceFile")
            ?: readOptionsValue(configuration, "getSourceFile")
    }

    private fun readNamedValue(configuration: RunConfiguration, vararg methodNames: String): String? {
        for (methodName in methodNames) {
            val method = configuration.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: continue
            val value = runCatching { method.invoke(configuration) }.getOrNull() ?: continue
            val text = renderValue(value)
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun readOptionsValue(configuration: RunConfiguration, methodName: String): String? {
        val options = configuration.javaClass.methods.firstOrNull {
            it.name == "getOptions" && it.parameterCount == 0
        }?.let { method ->
            runCatching { method.invoke(configuration) }.getOrNull()
        } ?: return null
        return invokeNoArg(options, methodName)?.takeIf { it.isNotBlank() }
    }

    private fun renderValue(value: Any): String? {
        if (value is String) return value

        val name = invokeNoArg(value, "getName")
            ?: invokeNoArg(value, "getTargetName")
            ?: invokeNoArg(value, "getDisplayName")
        if (!name.isNullOrBlank()) return name

        return value.toString().takeIf { it.isNotBlank() && !it.contains("@") }
    }

    private fun invokeNoArg(value: Any, name: String): String? {
        val method: Method = value.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 0
        } ?: return null
        return runCatching { method.invoke(value)?.toString() }.getOrNull()
    }
}
