package org.kkkzbh.cph

import com.intellij.ui.components.JBTextField
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent

internal object CphShortcutCaptureState {
    @Volatile
    private var activeField: CphShortcutTextField? = null
    @Volatile
    private var committedInCurrentGesture: Boolean = false

    fun start(field: CphShortcutTextField) {
        activeField?.takeIf { it !== field }?.restoreStoredShortcut()
        activeField = field
        committedInCurrentGesture = false
    }

    fun stop(field: CphShortcutTextField) {
        if (activeField === field) {
            field.restoreStoredShortcut()
            activeField = null
            committedInCurrentGesture = false
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val field = activeField ?: return false
        when (event.id) {
            KeyEvent.KEY_PRESSED -> handleKeyPressed(field, event)
            KeyEvent.KEY_RELEASED -> handleKeyReleased(field, event)
            KeyEvent.KEY_TYPED -> Unit
            else -> return false
        }
        event.consume()
        return true
    }

    private fun handleKeyPressed(field: CphShortcutTextField, event: KeyEvent) {
        when (event.keyCode) {
            KeyEvent.VK_ESCAPE,
            KeyEvent.VK_BACK_SPACE,
            KeyEvent.VK_DELETE -> {
                field.commitShortcut("")
                committedInCurrentGesture = true
                return
            }
        }

        if (CphShortcutMatcher.isModifierKeyCode(event.keyCode)) {
            if (!committedInCurrentGesture) {
                val modifiers = event.modifiersEx or CphShortcutMatcher.modifiersForKeyCode(event.keyCode)
                field.previewShortcutText(CphShortcutMatcher.modifierPreviewText(modifiers))
            }
            return
        }

        val keyStroke = CphShortcutMatcher.keyStrokeFromEvent(event) ?: return
        field.commitShortcut(CphShortcutMatcher.toStorageString(keyStroke))
        committedInCurrentGesture = true
    }

    private fun handleKeyReleased(field: CphShortcutTextField, event: KeyEvent) {
        val modifiers = currentModifiersAfterRelease(event)
        if (committedInCurrentGesture) {
            field.restoreStoredShortcut()
            if (modifiers == 0) {
                committedInCurrentGesture = false
            }
            return
        }
        if (modifiers == 0) {
            field.restoreStoredShortcut()
        } else {
            field.previewShortcutText(CphShortcutMatcher.modifierPreviewText(modifiers))
        }
    }

    private fun currentModifiersAfterRelease(event: KeyEvent): Int {
        val releasedModifier = CphShortcutMatcher.modifiersForKeyCode(event.keyCode)
        return event.modifiersEx and releasedModifier.inv()
    }
}

internal class CphShortcutTextField : JBTextField() {
    var shortcutText: String = ""
        private set
    var onShortcutChanged: (() -> Unit)? = null

    init {
        isEditable = false
        emptyText.text = "未设置"
        toolTipText = "按下快捷键组合；Esc、Backspace 或 Delete 清空。"
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                CphShortcutCaptureState.start(this@CphShortcutTextField)
            }

            override fun focusLost(e: FocusEvent) {
                CphShortcutCaptureState.stop(this@CphShortcutTextField)
            }
        })
    }

    fun setStoredShortcut(value: String, notify: Boolean = true) {
        val normalized = CphShortcutMatcher.normalizeShortcutText(value)
        val changed = normalized != shortcutText
        shortcutText = normalized
        text = if (shortcutText.isBlank()) "" else CphShortcutMatcher.displayShortcut(shortcutText)
        if (notify && changed) {
            onShortcutChanged?.invoke()
        }
    }

    fun previewShortcutText(value: String) {
        text = value
    }

    fun commitShortcut(value: String, notify: Boolean = true) {
        setStoredShortcut(value, notify)
    }

    fun restoreStoredShortcut() {
        text = if (shortcutText.isBlank()) "" else CphShortcutMatcher.displayShortcut(shortcutText)
    }

    override fun processKeyEvent(event: KeyEvent) {
        CphShortcutCaptureState.handleKeyEvent(event)
        event.consume()
    }
}
