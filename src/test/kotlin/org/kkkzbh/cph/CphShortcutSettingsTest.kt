package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.KeyStroke

class CphShortcutSettingsTest {
    @Test
    fun defaultsAreUnset() {
        val service = CphShortcutSettings()

        val state = service.state

        assertTrue(state.runAllShortcut.isBlank())
        assertTrue(state.runSelectedCaseShortcut.isBlank())
        assertTrue(state.debugSelectedCaseShortcut.isBlank())
        assertTrue(state.submitShortcut.isBlank())
    }

    @Test
    fun parsesAndNormalizesValidShortcut() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val stored = CphShortcutMatcher.toStorageString(keyStroke)

        assertEquals(keyStroke, CphShortcutMatcher.parseShortcut(stored))
        assertEquals(stored, CphShortcutMatcher.normalizeShortcutText(stored))
    }

    @Test
    fun loadStateDropsInvalidShortcutValues() {
        val service = CphShortcutSettings()

        service.loadState(
            CphShortcutSettingsState(
                runAllShortcut = "not a shortcut",
                runSelectedCaseShortcut = CphShortcutMatcher.toStorageString(
                    KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
                ),
                submitShortcut = "still invalid",
            ),
        )

        assertEquals("", service.state.runAllShortcut)
        assertEquals("", service.state.submitShortcut)
        assertEquals(
            KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
            CphShortcutMatcher.parseShortcut(service.state.runSelectedCaseShortcut),
        )
    }

    @Test
    fun duplicateShortcutValidationIgnoresUnsetValues() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val stored = CphShortcutMatcher.toStorageString(keyStroke)

        val message = CphShortcutMatcher.duplicateShortcutMessage(
            CphShortcutSettingsState(
                runAllShortcut = stored,
                runSelectedCaseShortcut = "",
                debugSelectedCaseShortcut = stored,
                submitShortcut = "",
            ),
        )

        assertTrue(message?.contains("全局运行快捷键") == true)
        assertTrue(message?.contains(CphText.forLanguage(CphUiLanguage.ZH_CN).debugSelectedShortcut) == true)
    }

    @Test
    fun duplicateShortcutValidationRendersEnglishNames() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val stored = CphShortcutMatcher.toStorageString(keyStroke)

        val message = CphShortcutMatcher.duplicateShortcutMessage(
            CphShortcutSettingsState(
                runAllShortcut = stored,
                debugSelectedCaseShortcut = stored,
            ),
            language = CphUiLanguage.ENGLISH,
        )

        assertTrue(message?.contains("Run all shortcut") == true)
        assertTrue(message?.contains("Debug case shortcut") == true)
        assertTrue(message?.contains("use the same shortcut") == true)
    }

    @Test
    fun emptyConfigurationDoesNotMatch() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)

        assertNull(CphShortcutMatcher.actionFor(keyStroke, CphShortcutSettingsState(), fromShortcutInput = false))
    }

    @Test
    fun configuredShortcutMatchesAction() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )

        assertEquals(
            CphShortcutAction.RUN_ALL,
            CphShortcutMatcher.actionFor(keyStroke, state, fromShortcutInput = false),
        )
    }

    @Test
    fun disabledCphDoesNotMatchConfiguredShortcut() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )

        assertNull(
            CphShortcutMatcher.actionFor(
                keyStroke,
                state,
                fromShortcutInput = false,
                cphEnabled = false,
            ),
        )
    }

    @Test
    fun configuredSubmitShortcutMatchesAction() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            submitShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )

        assertEquals(
            CphShortcutAction.SUBMIT,
            CphShortcutMatcher.actionFor(keyStroke, state, fromShortcutInput = false),
        )
    }

    @Test
    fun disabledSubmitPluginDoesNotMatchSubmitShortcut() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            submitShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )

        assertNull(
            CphShortcutMatcher.actionFor(
                keyStroke,
                state,
                fromShortcutInput = false,
                codeforcesSubmitEnabled = false,
            ),
        )
    }

    @Test
    fun disabledSubmitPluginIgnoresSubmitShortcutDuplicates() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val stored = CphShortcutMatcher.toStorageString(keyStroke)

        val message = CphShortcutMatcher.duplicateShortcutMessage(
            CphShortcutSettingsState(
                runAllShortcut = stored,
                submitShortcut = stored,
            ),
            codeforcesSubmitEnabled = false,
        )

        assertNull(message)
    }

    @Test
    fun unmatchedShortcutDoesNotIntercept() {
        val state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.toStorageString(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
            ),
        )
        val pressedF10 = KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK)

        assertNull(CphShortcutMatcher.actionFor(pressedF10, state, fromShortcutInput = false))
    }

    @Test
    fun shortcutInputContextDoesNotIntercept() {
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )

        assertNull(CphShortcutMatcher.actionFor(keyStroke, state, fromShortcutInput = true))
    }

    @Test
    fun keyEventNormalizesToComparableShortcut() {
        val event = KeyEvent(
            JButton(),
            KeyEvent.KEY_PRESSED,
            0,
            InputEvent.CTRL_DOWN_MASK,
            KeyEvent.VK_R,
            KeyEvent.CHAR_UNDEFINED,
        )

        assertEquals(
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
            CphShortcutMatcher.keyStrokeFromEvent(event),
        )
    }

    @Test
    fun captureModeCommitsModifiedShortcutAndConsumesEvent() {
        val field = CphShortcutTextField()
        var changed = false
        field.onShortcutChanged = { changed = true }
        CphShortcutCaptureState.start(field)
        try {
            val event = KeyEvent(
                field,
                KeyEvent.KEY_PRESSED,
                0,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_F,
                KeyEvent.CHAR_UNDEFINED,
            )

            assertTrue(CphShortcutCaptureState.handleKeyEvent(event))

            assertTrue(event.isConsumed)
            assertTrue(changed)
            assertEquals(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
                CphShortcutMatcher.parseShortcut(field.shortcutText),
            )
        } finally {
            CphShortcutCaptureState.stop(field)
        }
    }

    @Test
    fun captureModePreviewsPureModifierWithoutPersisting() {
        val field = CphShortcutTextField()
        var changed = false
        field.onShortcutChanged = { changed = true }
        CphShortcutCaptureState.start(field)
        try {
            val event = KeyEvent(
                field,
                KeyEvent.KEY_PRESSED,
                0,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED,
            )

            assertTrue(CphShortcutCaptureState.handleKeyEvent(event))

            assertTrue(event.isConsumed)
            assertEquals("Ctrl", field.text)
            assertEquals("", field.shortcutText)
            assertFalse(changed)
        } finally {
            CphShortcutCaptureState.stop(field)
        }
    }

    @Test
    fun captureModeKeepsCommittedShortcutVisibleUntilGestureEnds() {
        val field = CphShortcutTextField()
        CphShortcutCaptureState.start(field)
        try {
            CphShortcutCaptureState.handleKeyEvent(
                KeyEvent(
                    field,
                    KeyEvent.KEY_PRESSED,
                    0,
                    InputEvent.CTRL_DOWN_MASK,
                    KeyEvent.VK_F,
                    KeyEvent.CHAR_UNDEFINED,
                ),
            )
            val committedText = CphShortcutMatcher.displayShortcut(field.shortcutText)

            val releaseF = KeyEvent(
                field,
                KeyEvent.KEY_RELEASED,
                0,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_F,
                KeyEvent.CHAR_UNDEFINED,
            )

            assertTrue(CphShortcutCaptureState.handleKeyEvent(releaseF))

            assertTrue(releaseF.isConsumed)
            assertEquals(committedText, field.text)
        } finally {
            CphShortcutCaptureState.stop(field)
        }
    }

    @Test
    fun captureModeClearKeysClearShortcutAndConsumeEvent() {
        val field = CphShortcutTextField()
        field.setStoredShortcut(
            CphShortcutMatcher.toStorageString(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
            ),
            notify = false,
        )
        var changed = false
        field.onShortcutChanged = { changed = true }
        CphShortcutCaptureState.start(field)
        try {
            val event = KeyEvent(
                field,
                KeyEvent.KEY_PRESSED,
                0,
                0,
                KeyEvent.VK_DELETE,
                KeyEvent.CHAR_UNDEFINED,
            )

            assertTrue(CphShortcutCaptureState.handleKeyEvent(event))

            assertTrue(event.isConsumed)
            assertTrue(changed)
            assertEquals("", field.shortcutText)
        } finally {
            CphShortcutCaptureState.stop(field)
        }
    }

    @Test
    fun actionShortcutMatchesAgainAfterCaptureStops() {
        val field = CphShortcutTextField()
        val keyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
        val state = CphShortcutSettingsState(
            runAllShortcut = CphShortcutMatcher.toStorageString(keyStroke),
        )
        CphShortcutCaptureState.start(field)
        CphShortcutCaptureState.stop(field)

        val event = KeyEvent(
            JButton(),
            KeyEvent.KEY_PRESSED,
            0,
            InputEvent.CTRL_DOWN_MASK,
            KeyEvent.VK_R,
            KeyEvent.CHAR_UNDEFINED,
        )

        assertFalse(CphShortcutCaptureState.handleKeyEvent(event))
        assertEquals(
            CphShortcutAction.RUN_ALL,
            CphShortcutMatcher.actionFor(
                CphShortcutMatcher.keyStrokeFromEvent(event),
                state,
                fromShortcutInput = false,
            ),
        )
    }
}
