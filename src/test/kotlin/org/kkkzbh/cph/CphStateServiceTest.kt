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
    fun loadStatePreservesGlobalCompileSettings() {
        val service = CphStateService()
        service.loadState(
            CphState(
                compileSettings = CphGlobalCompileSettings(
                    cppStandard = CphCppStandard.CPP23,
                    compileOptions = "-O3",
                ),
            ),
        )

        val compileSettings = service.getState().compileSettings
        assertEquals(CphCppStandard.CPP23, compileSettings.cppStandard)
        assertEquals("-O3", compileSettings.compileOptions)
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
        assertEquals(CPH_DEFAULT_EDITOR_FONT_SIZE, ui.editorFontSize)
        assertFalse(ui.noExpectedModeEnabled)
        assertFalse(ui.confidentSubmitEnabled)
    }

    @Test
    fun loadStateDefaultsSingleFileModeEnabled() {
        val service = CphStateService()
        service.loadState(CphState())

        assertTrue(service.getState().singleFileModeEnabled)
    }

    @Test
    fun loadStateDefaultsSingleFileWorkingDirectory() {
        val service = CphStateService()
        service.loadState(CphState())

        assertEquals(".cph/", service.getState().singleFileWorkingDirectory)
    }

    @Test
    fun loadStateNormalizesBlankSingleFileWorkingDirectory() {
        val service = CphStateService()
        service.loadState(CphState(singleFileWorkingDirectory = " "))

        assertEquals(".cph/", service.getState().singleFileWorkingDirectory)
    }

    @Test
    fun loadStatePreservesSingleFileWorkingDirectory() {
        val service = CphStateService()
        service.loadState(CphState(singleFileWorkingDirectory = "build/run/"))

        assertEquals("build/run/", service.getState().singleFileWorkingDirectory)
    }

    @Test
    fun loadStatePreservesDisabledSingleFileMode() {
        val service = CphStateService()
        service.loadState(CphState(singleFileModeEnabled = false))

        assertFalse(service.getState().singleFileModeEnabled)
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
    fun loadStatePreservesNoExpectedModeAndEditorFontSize() {
        val service = CphStateService()
        service.loadState(
            CphState(
                ui = CphUiState(
                    editorFontSize = 18,
                    noExpectedModeEnabled = true,
                    confidentSubmitEnabled = true,
                ),
            ),
        )

        val ui = service.getState().ui
        assertEquals(18, ui.editorFontSize)
        assertTrue(ui.noExpectedModeEnabled)
        assertTrue(ui.confidentSubmitEnabled)
    }

    @Test
    fun loadStateClampsInvalidEditorFontSizes() {
        val low = CphStateService().also {
            it.loadState(CphState(ui = CphUiState(editorFontSize = 1)))
        }
        val high = CphStateService().also {
            it.loadState(CphState(ui = CphUiState(editorFontSize = 100)))
        }

        assertEquals(CPH_MIN_EDITOR_FONT_SIZE, low.getState().ui.editorFontSize)
        assertEquals(CPH_MAX_EDITOR_FONT_SIZE, high.getState().ui.editorFontSize)
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
