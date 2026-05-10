package org.kkkzbh.cph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

internal data class CphPluginSettingsState(
    var codeforcesRemoteSubmitEnabled: Boolean = false,
    var selectedThemeId: String = CphThemeId.CLASSIC,
    var uiLanguage: String = CphUiLanguage.ZH_CN.id,
)

internal interface CphPluginSettingsChangedListener {
    fun uiLanguageChanged()

    companion object {
        val TOPIC: Topic<CphPluginSettingsChangedListener> =
            Topic.create("CPH plugin settings changed", CphPluginSettingsChangedListener::class.java)
    }
}

@State(name = "CphPluginSettings", storages = [Storage("cph-plugin-settings.xml")])
internal class CphPluginSettings : PersistentStateComponent<CphPluginSettingsState> {
    private var state = CphPluginSettingsState()

    override fun getState(): CphPluginSettingsState = state

    override fun loadState(state: CphPluginSettingsState) {
        state.selectedThemeId = CphThemeId.normalize(state.selectedThemeId)
        state.uiLanguage = CphUiLanguage.normalize(state.uiLanguage).id
        this.state = state
    }

    fun setUiLanguage(language: CphUiLanguage) {
        val normalized = CphUiLanguage.normalize(language.id).id
        if (state.uiLanguage == normalized) return
        state.uiLanguage = normalized
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CphPluginSettingsChangedListener.TOPIC)
            .uiLanguageChanged()
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
