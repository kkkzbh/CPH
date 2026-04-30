package org.kkkzbh.cph

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import java.lang.reflect.Method

data class CphTargetIdentity(
    val id: String,
    val displayName: String,
    val settings: RunnerAndConfigurationSettings?,
    val runnable: Boolean,
    val message: String,
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

        val configuration = settings.configuration
        val typeId = settings.type.id
        val typeName = settings.type.displayName
        val className = configuration.javaClass.name
        val cMakeLike = listOf(typeId, typeName, className)
            .any { it.contains("cmake", ignoreCase = true) }
        val applicationLike = listOf(typeId, typeName, className)
            .any { it.contains("application", ignoreCase = true) || it.contains("app", ignoreCase = true) }

        if (!cMakeLike || !applicationLike) {
            return CphTargetIdentity(
                id = stableId(settings, configuration, null, null),
                displayName = "${settings.name} (${typeName})",
                settings = settings,
                runnable = false,
                message = "CPH only runs CLion CMake Application configurations.",
            )
        }

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
            id = stableId(settings, configuration, profileName, targetName),
            displayName = targetLabel,
            settings = settings,
            runnable = true,
            message = "Ready",
        )
    }

    private fun stableId(
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
