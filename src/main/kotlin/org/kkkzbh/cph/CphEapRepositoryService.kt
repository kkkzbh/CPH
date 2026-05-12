package org.kkkzbh.cph

import com.intellij.ide.plugins.CustomPluginRepositoryService
import com.intellij.openapi.updateSettings.impl.UpdateSettings

internal object CphEapRepositoryService {
    const val CPH_EAP_REPOSITORY_URL: String = "https://plugins.jetbrains.com/plugins/eap/list"

    fun isEapRepositoryEnabled(): Boolean =
        normalizeHosts(settings().storedPluginHosts).contains(CPH_EAP_REPOSITORY_URL)

    fun enableEapRepository() {
        val updateSettings = settings()
        val hosts = updateSettings.storedPluginHosts
        val updatedHosts = enableInHosts(hosts)
        if (hosts != updatedHosts) {
            hosts.clear()
            hosts.addAll(updatedHosts)
        }
        markPluginsCheckNeeded(updateSettings)
    }

    fun disableEapRepository() {
        val updateSettings = settings()
        val hosts = updateSettings.storedPluginHosts
        val updatedHosts = disableFromHosts(hosts)
        if (hosts != updatedHosts) {
            hosts.clear()
            hosts.addAll(updatedHosts)
            markPluginsCheckNeeded(updateSettings)
        }
    }

    internal fun enableInHosts(hosts: List<String>): List<String> {
        val normalized = normalizeHosts(hosts)
        return if (normalized.contains(CPH_EAP_REPOSITORY_URL)) normalized else normalized + CPH_EAP_REPOSITORY_URL
    }

    internal fun disableFromHosts(hosts: List<String>): List<String> =
        normalizeHosts(hosts).filterNot { it == CPH_EAP_REPOSITORY_URL }

    internal fun normalizeHosts(hosts: List<String>): List<String> {
        val result = linkedSetOf<String>()
        hosts.forEach { host ->
            val normalized = normalizeHost(host)
            if (normalized.isNotEmpty()) result.add(normalized)
        }
        return result.toList()
    }

    private fun normalizeHost(host: String): String =
        host.trim().removeSuffix("/")

    private fun settings(): UpdateSettings =
        UpdateSettings.getInstance()

    private fun markPluginsCheckNeeded(updateSettings: UpdateSettings) {
        updateSettings.isPluginsCheckNeeded = true
        CustomPluginRepositoryService.getInstance().clearCache()
    }
}
