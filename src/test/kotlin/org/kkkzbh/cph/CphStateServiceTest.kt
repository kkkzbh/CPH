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
    fun loadStatePreservesTargetSettingsAndMigratesLegacyCompileSettings() {
        val service = CphStateService()
        service.loadState(
            CphState(
                targets = linkedMapOf(
                    "target" to CphTargetCases(
                        timeoutMillis = 2500,
                        ignoreTrailingWhitespace = false,
                        cppStandard = CphCppStandard.CPP20,
                        compileOptions = "-O2 -Wall",
                        syncedCompilerOptionsBase = "-O0",
                        syncedCompilerOptionsApplied = "-O0 -O2 -Wall -std=c++20",
                    ),
                ),
            ),
        )

        val targetCases = service.getState().targets.getValue("target")
        val compileSettings = service.getState().compileSettings
        assertEquals(2500, targetCases.timeoutMillis)
        assertFalse(targetCases.ignoreTrailingWhitespace)
        assertEquals(CphCppStandard.CPP20, compileSettings.cppStandard)
        assertEquals("-O2 -Wall", compileSettings.compileOptions)
        assertEquals(null, targetCases.cppStandard)
        assertEquals(null, targetCases.compileOptions)
        assertEquals("-O0", targetCases.syncedCompilerOptionsBase)
        assertEquals("-O0 -O2 -Wall -std=c++20", targetCases.syncedCompilerOptionsApplied)
    }

    @Test
    fun loadStatePreservesGlobalCompileSettingsOverLegacyTargetSettings() {
        val service = CphStateService()
        service.loadState(
            CphState(
                compileSettings = CphGlobalCompileSettings(
                    cppStandard = CphCppStandard.CPP23,
                    compileOptions = "-O3",
                ),
                targets = linkedMapOf(
                    "target" to CphTargetCases(
                        cppStandard = CphCppStandard.CPP20,
                        compileOptions = "-O2 -Wall",
                    ),
                ),
            ),
        )

        val compileSettings = service.getState().compileSettings
        val targetCases = service.getState().targets.getValue("target")
        assertEquals(CphCppStandard.CPP23, compileSettings.cppStandard)
        assertEquals("-O3", compileSettings.compileOptions)
        assertEquals(null, targetCases.cppStandard)
        assertEquals(null, targetCases.compileOptions)
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
    fun loadStateDefaultsOutputSplitUiState() {
        val service = CphStateService()
        service.loadState(CphState())

        val ui = service.getState().ui
        assertTrue(ui.outputSplitEnabled)
        assertEquals(0.5, ui.outputSplitRatio, 0.0)
    }

    @Test
    fun loadStatePreservesOutputSplitRatio() {
        val service = CphStateService()
        service.loadState(
            CphState(
                ui = CphUiState(
                    outputSplitEnabled = false,
                    outputSplitRatio = 0.25,
                ),
            ),
        )

        val ui = service.getState().ui
        assertFalse(ui.outputSplitEnabled)
        assertEquals(0.25, ui.outputSplitRatio, 0.0)
    }

    @Test
    fun loadStateClampsInvalidOutputSplitRatios() {
        val low = CphStateService().also {
            it.loadState(CphState(ui = CphUiState(outputSplitRatio = -0.5)))
        }
        val high = CphStateService().also {
            it.loadState(CphState(ui = CphUiState(outputSplitRatio = 1.5)))
        }

        assertEquals(0.0, low.getState().ui.outputSplitRatio, 0.0)
        assertEquals(1.0, high.getState().ui.outputSplitRatio, 0.0)
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
