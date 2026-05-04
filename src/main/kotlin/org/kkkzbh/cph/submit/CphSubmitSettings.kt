package org.kkkzbh.cph.submit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

internal enum class CphCfLanguage(val displayName: String, val defaultProgramTypeId: Int) {
    CPP_17("GNU G++17 7.3.0", 54),
    CPP_20("GNU G++20 13.2 (64 bit, winlibs)", 89),
    CPP_23("GNU G++23 14.2 (64 bit, msys2)", 91);

    companion object {
        fun fromKey(key: String?): CphCfLanguage =
            entries.firstOrNull { it.name == key } ?: CPP_17
    }
}

internal data class CphSubmitSettingsState(
    var defaultLang: String = CphCfLanguage.CPP_17.name,
)

@State(name = "CphSubmitSettings", storages = [Storage("cph-submit-settings.xml")])
@Service(Service.Level.APP)
internal class CphSubmitSettings : PersistentStateComponent<CphSubmitSettingsState> {
    private var state = CphSubmitSettingsState()

    override fun getState(): CphSubmitSettingsState = state

    override fun loadState(state: CphSubmitSettingsState) {
        this.state = state.copy(
            defaultLang = CphCfLanguage.fromKey(state.defaultLang).name,
        )
    }

    fun language(): CphCfLanguage = CphCfLanguage.fromKey(state.defaultLang)

    companion object {
        fun getInstance(): CphSubmitSettings =
            ApplicationManager.getApplication().getService(CphSubmitSettings::class.java)
    }
}
