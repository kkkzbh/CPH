package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CphStateServiceTest {
    @Test
    fun getOrCreateTargetCasesSeedsNewTargetWithCase1() {
        val service = CphStateService()

        val targetCases = service.getOrCreateTargetCases(
            CphTargetIdentity(
                id = "target",
                displayName = "Target",
                settings = null,
                runnable = true,
                message = "",
                kind = CphTargetKind.CMAKE_APP,
            ),
        )

        assertEquals(1, targetCases.cases.size)
        assertEquals("Case 1", targetCases.cases.single().name)
        assertTrue(targetCases.cases.single().enabled)
    }

    @Test
    fun getOrCreateTargetCasesPreservesExistingEmptyTarget() {
        val service = CphStateService()
        service.loadState(
            CphState(
                targets = linkedMapOf(
                    "target" to CphTargetCases(
                        targetId = "target",
                        displayName = "Old Target",
                    ),
                ),
            ),
        )

        val targetCases = service.getOrCreateTargetCases(
            CphTargetIdentity(
                id = "target",
                displayName = "Target",
                settings = null,
                runnable = true,
                message = "",
                kind = CphTargetKind.CMAKE_APP,
            ),
        )

        assertTrue(targetCases.cases.isEmpty())
        assertEquals("Target", targetCases.displayName)
    }

    @Test
    fun loadStatePreservesTargetSettings() {
        val service = CphStateService()
        service.loadState(
            CphState(
                targets = linkedMapOf(
                    "target" to CphTargetCases(
                        timeoutMillis = 2500,
                        ignoreTrailingWhitespace = false,
                    ),
                ),
            ),
        )

        val targetCases = service.getState().targets.getValue("target")
        assertEquals(2500, targetCases.timeoutMillis)
        assertFalse(targetCases.ignoreTrailingWhitespace)
    }

    @Test
    fun loadStateClampsInvalidTargetTimeout() {
        val service = CphStateService()
        service.loadState(
            CphState(
                targets = linkedMapOf(
                    "low" to CphTargetCases(timeoutMillis = 1),
                    "high" to CphTargetCases(timeoutMillis = 100000),
                ),
            ),
        )

        assertEquals(CPH_MIN_TIMEOUT_MILLIS, service.getState().targets.getValue("low").timeoutMillis)
        assertEquals(CPH_MAX_TIMEOUT_MILLIS, service.getState().targets.getValue("high").timeoutMillis)
    }

    @Test
    fun loadStatePreservesUiEditorHeights() {
        val service = CphStateService()
        service.loadState(
            CphState(
                ui = CphUiState(
                    inputHeight = 220,
                    expectedHeight = 240,
                    actualHeight = 260,
                ),
            ),
        )

        val ui = service.getState().ui
        assertEquals(220, ui.inputHeight)
        assertEquals(240, ui.expectedHeight)
        assertEquals(260, ui.actualHeight)
    }

    @Test
    fun loadStateClampsInvalidUiEditorHeights() {
        val service = CphStateService()
        service.loadState(
            CphState(
                ui = CphUiState(
                    inputHeight = 1,
                    expectedHeight = 2000,
                    actualHeight = CPH_DEFAULT_ACTUAL_HEIGHT,
                ),
            ),
        )

        val ui = service.getState().ui
        assertEquals(CPH_MIN_EDITOR_HEIGHT, ui.inputHeight)
        assertEquals(CPH_MAX_EDITOR_HEIGHT, ui.expectedHeight)
        assertEquals(CPH_DEFAULT_ACTUAL_HEIGHT, ui.actualHeight)
    }
}
