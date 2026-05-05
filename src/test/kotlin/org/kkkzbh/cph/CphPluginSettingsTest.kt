package org.kkkzbh.cph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CphPluginSettingsTest {
    @Test
    fun codeforcesRemoteSubmitPluginDefaultsDisabled() {
        val service = CphPluginSettings()

        assertFalse(service.state.codeforcesRemoteSubmitEnabled)
    }

    @Test
    fun codeforcesRemoteSubmitPluginEnabledStateLoads() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(codeforcesRemoteSubmitEnabled = true))

        assertTrue(service.state.codeforcesRemoteSubmitEnabled)
    }

    @Test
    fun submitActionRequiresPluginAndSingleFileMode() {
        assertFalse(
            CphCodeforcesSubmitFeature.actionEnabled(
                pluginEnabled = false,
                singleFileModeEnabled = true,
            ),
        )
        assertFalse(
            CphCodeforcesSubmitFeature.actionEnabled(
                pluginEnabled = true,
                singleFileModeEnabled = false,
            ),
        )
        assertTrue(
            CphCodeforcesSubmitFeature.actionEnabled(
                pluginEnabled = true,
                singleFileModeEnabled = true,
            ),
        )
    }
}
