package org.kkkzbh.cph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

internal data class CphShortcutSettingsState(
    var runAllShortcut: String = "",
    var runSelectedCaseShortcut: String = "",
    var debugSelectedCaseShortcut: String = "",
    var submitShortcut: String = "",
)

internal enum class CphShortcutAction(val displayName: String) {
    RUN_ALL("全局运行快捷键"),
    RUN_SELECTED_CASE("单CASE运行快捷键"),
    DEBUG_SELECTED_CASE("单CASE调试快捷键"),
    SUBMIT("提交CF快捷键"),
}

internal object CphShortcutMatcher {
    private const val SHORTCUT_MODIFIERS =
        InputEvent.SHIFT_DOWN_MASK or
            InputEvent.CTRL_DOWN_MASK or
            InputEvent.META_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or
            InputEvent.ALT_GRAPH_DOWN_MASK

    fun actionFor(
        keyStroke: KeyStroke?,
        state: CphShortcutSettingsState,
        fromShortcutInput: Boolean,
        codeforcesSubmitEnabled: Boolean = true,
    ): CphShortcutAction? {
        if (fromShortcutInput || keyStroke == null) return null
        return configuredShortcuts(state, codeforcesSubmitEnabled).firstOrNull { it.second == keyStroke }?.first
    }

    fun keyStrokeFromEvent(event: KeyEvent): KeyStroke? {
        if (event.id != KeyEvent.KEY_PRESSED || event.keyCode == KeyEvent.VK_UNDEFINED) return null
        if (isModifierKeyCode(event.keyCode)) return null
        return KeyStroke.getKeyStroke(event.keyCode, event.modifiersEx and SHORTCUT_MODIFIERS)
    }

    fun modifierPreviewText(modifiersEx: Int): String {
        return listOfNotNull(
            "Ctrl".takeIf { modifiersEx and InputEvent.CTRL_DOWN_MASK != 0 },
            "Shift".takeIf { modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0 },
            "Alt".takeIf { modifiersEx and InputEvent.ALT_DOWN_MASK != 0 },
            "Meta".takeIf { modifiersEx and InputEvent.META_DOWN_MASK != 0 },
            "Alt Graph".takeIf { modifiersEx and InputEvent.ALT_GRAPH_DOWN_MASK != 0 },
        ).joinToString("+")
    }

    fun modifiersForKeyCode(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
            KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
            KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
            KeyEvent.VK_META -> InputEvent.META_DOWN_MASK
            KeyEvent.VK_ALT_GRAPH -> InputEvent.ALT_GRAPH_DOWN_MASK
            else -> 0
        }
    }

    fun parseShortcut(text: String): KeyStroke? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        return runCatching { KeyStroke.getKeyStroke(trimmed) }.getOrNull()
    }

    fun normalizeShortcutText(text: String): String {
        return parseShortcut(text)?.let(::toStorageString).orEmpty()
    }

    fun toStorageString(keyStroke: KeyStroke): String = keyStroke.toString()

    fun displayShortcut(text: String): String {
        val keyStroke = parseShortcut(text) ?: return "未设置"
        return KeymapUtil.getKeystrokeText(keyStroke)
    }

    fun duplicateShortcutMessage(
        state: CphShortcutSettingsState,
        codeforcesSubmitEnabled: Boolean = true,
    ): String? {
        val duplicates = rawShortcutEntries(state, codeforcesSubmitEnabled)
            .filter { it.second.isNotBlank() }
            .groupBy { normalizeShortcutText(it.second) }
            .filterKeys { it.isNotBlank() }
            .values
            .firstOrNull { it.size > 1 }
            ?: return null

        val names = duplicates.joinToString("、") { it.first.displayName }
        return "$names 使用了相同快捷键：${displayShortcut(duplicates.first().second)}"
    }

    private fun configuredShortcuts(
        state: CphShortcutSettingsState,
        codeforcesSubmitEnabled: Boolean,
    ): List<Pair<CphShortcutAction, KeyStroke>> {
        return rawShortcutEntries(state, codeforcesSubmitEnabled).mapNotNull { (action, text) ->
            parseShortcut(text)?.let { action to it }
        }
    }

    private fun rawShortcutEntries(
        state: CphShortcutSettingsState,
        codeforcesSubmitEnabled: Boolean = true,
    ): List<Pair<CphShortcutAction, String>> {
        return listOf(
            CphShortcutAction.RUN_ALL to state.runAllShortcut,
            CphShortcutAction.RUN_SELECTED_CASE to state.runSelectedCaseShortcut,
            CphShortcutAction.DEBUG_SELECTED_CASE to state.debugSelectedCaseShortcut,
            CphShortcutAction.SUBMIT to state.submitShortcut,
        ).filter { (action, _) -> action != CphShortcutAction.SUBMIT || codeforcesSubmitEnabled }
    }

    fun isModifierKeyCode(keyCode: Int): Boolean {
        return keyCode == KeyEvent.VK_SHIFT ||
            keyCode == KeyEvent.VK_CONTROL ||
            keyCode == KeyEvent.VK_ALT ||
            keyCode == KeyEvent.VK_ALT_GRAPH ||
            keyCode == KeyEvent.VK_META
    }
}

@State(name = "CphShortcutSettings", storages = [Storage("cph-shortcut-settings.xml")])
internal class CphShortcutSettings : PersistentStateComponent<CphShortcutSettingsState> {
    private var state = CphShortcutSettingsState()

    override fun getState(): CphShortcutSettingsState = state

    override fun loadState(state: CphShortcutSettingsState) {
        this.state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.normalizeShortcutText(state.runAllShortcut),
            runSelectedCaseShortcut = CphShortcutMatcher.normalizeShortcutText(state.runSelectedCaseShortcut),
            debugSelectedCaseShortcut = CphShortcutMatcher.normalizeShortcutText(state.debugSelectedCaseShortcut),
            submitShortcut = CphShortcutMatcher.normalizeShortcutText(state.submitShortcut),
        )
    }

    fun update(nextState: CphShortcutSettingsState) {
        state.runAllShortcut = CphShortcutMatcher.normalizeShortcutText(nextState.runAllShortcut)
        state.runSelectedCaseShortcut = CphShortcutMatcher.normalizeShortcutText(nextState.runSelectedCaseShortcut)
        state.debugSelectedCaseShortcut = CphShortcutMatcher.normalizeShortcutText(nextState.debugSelectedCaseShortcut)
        state.submitShortcut = CphShortcutMatcher.normalizeShortcutText(nextState.submitShortcut)
    }

    companion object {
        fun getInstance(): CphShortcutSettings =
            ApplicationManager.getApplication().getService(CphShortcutSettings::class.java)
    }
}
