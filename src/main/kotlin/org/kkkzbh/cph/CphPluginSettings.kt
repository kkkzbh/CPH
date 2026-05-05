package org.kkkzbh.cph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

internal data class CphPluginSettingsState(
    var codeforcesRemoteSubmitEnabled: Boolean = false,
    var selectedThemeId: String = CphThemeId.CLASSIC,
)

@State(name = "CphPluginSettings", storages = [Storage("cph-plugin-settings.xml")])
internal class CphPluginSettings : PersistentStateComponent<CphPluginSettingsState> {
    private var state = CphPluginSettingsState()

    override fun getState(): CphPluginSettingsState = state

    override fun loadState(state: CphPluginSettingsState) {
        state.selectedThemeId = CphThemeId.normalize(state.selectedThemeId)
        this.state = state
    }

    companion object {
        fun getInstance(): CphPluginSettings =
            ApplicationManager.getApplication().getService(CphPluginSettings::class.java)
    }
}

internal object CphCodeforcesSubmitFeature {
    fun isEnabled(): Boolean =
        CphPluginSettings.getInstance().state.codeforcesRemoteSubmitEnabled

    fun actionEnabled(pluginEnabled: Boolean, singleFileModeEnabled: Boolean): Boolean =
        pluginEnabled && singleFileModeEnabled
}
