package org.kkkzbh.cph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class CphPluginSettingsTest {
    @Test
    fun codeforcesRemoteSubmitPluginDefaultsDisabled() {
        val service = CphPluginSettings()

        assertFalse(service.state.codeforcesRemoteSubmitEnabled)
        assertEquals(CphThemeId.CLASSIC, service.state.selectedThemeId)
        assertEquals(CphUiLanguage.ZH_CN.id, service.state.uiLanguage)
        assertTrue(service.state.installedThemeVersions.isEmpty())
        assertEquals(0L, service.state.themeUpdateCheckedAtMillis)
    }

    @Test
    fun codeforcesRemoteSubmitPluginEnabledStateLoadsWithDefaultTheme() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(codeforcesRemoteSubmitEnabled = true))

        assertTrue(service.state.codeforcesRemoteSubmitEnabled)
        assertEquals(CphThemeId.CLASSIC, service.state.selectedThemeId)
        assertEquals(CphUiLanguage.ZH_CN.id, service.state.uiLanguage)
    }

    @Test
    fun uiLanguageDefaultsToSimplifiedChinese() {
        val service = CphPluginSettings()

        assertEquals(CphUiLanguage.ZH_CN.id, service.state.uiLanguage)
    }

    @Test
    fun persistedEnglishUiLanguageLoads() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(uiLanguage = CphUiLanguage.ENGLISH.id))

        assertEquals(CphUiLanguage.ENGLISH.id, service.state.uiLanguage)
    }

    @Test
    fun uiLanguageNormalizeAcceptsSupportedValues() {
        assertEquals(CphUiLanguage.ENGLISH, CphUiLanguage.normalize("en"))
        assertEquals(CphUiLanguage.ZH_CN, CphUiLanguage.normalize("zh_cn"))
    }

    @Test
    fun uiLanguageNormalizeFallsBackToSimplifiedChinese() {
        assertEquals(CphUiLanguage.ZH_CN, CphUiLanguage.normalize(null))
        assertEquals(CphUiLanguage.ZH_CN, CphUiLanguage.normalize("unknown"))
    }

    @Test
    fun selectedThemeStateLoads() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(selectedThemeId = CphThemeId.AVE_MUJICA))

        assertEquals(CphThemeId.AVE_MUJICA, service.state.selectedThemeId)
    }

    @Test
    fun classicSelectedThemeStateLoads() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(selectedThemeId = CphThemeId.CLASSIC))

        assertEquals(CphThemeId.CLASSIC, service.state.selectedThemeId)
    }

    @Test
    fun invalidSelectedThemeFallsBackToClassic() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(selectedThemeId = "unknown"))

        assertEquals(CphThemeId.CLASSIC, service.state.selectedThemeId)
    }

    @Test
    fun nullSelectedThemeFallsBackToClassic() {
        assertEquals(CphThemeId.CLASSIC, CphThemeId.normalize(null))
    }

    @Test
    fun unknownThemePaletteFallsBackToClassic() {
        assertEquals(CphThemes.classic, CphThemes.palette("unknown"))
    }

    @Test
    fun buildFeatureChannelControlsExperimentalSurface() {
        val experimentalEnabled = CphBuildFeatures.releaseChannel == "eap"

        assertEquals(experimentalEnabled, CphBuildFeatures.isEap)
        assertEquals(experimentalEnabled, CphBuildFeatures.utilitySettingsEnabled)
        assertEquals(experimentalEnabled, CphBuildFeatures.themeSettingsEnabled)
        assertEquals(experimentalEnabled, CphBuildFeatures.codeforcesSubmitEnabled)
        assertEquals(experimentalEnabled, CphBuildFeatures.aveMujicaThemeEnabled)
        assertEquals(false, CphBuildFeatures.localDiagnosticsEnabled)
    }

    @Test
    fun stableBuildIgnoresPersistedAveMujicaThemeSelection() {
        val effectiveThemeId = CphThemes.effectiveThemeId(
            selectedThemeId = CphThemeId.AVE_MUJICA,
            aveMujicaInstalled = true,
        )

        val expected = if (CphBuildFeatures.aveMujicaThemeEnabled) {
            CphThemeId.AVE_MUJICA
        } else {
            CphThemeId.CLASSIC
        }
        assertEquals(expected, effectiveThemeId)
    }

    @Test
    fun themeRegistryContainsClassicAndAveMujica() {
        assertEquals(listOf(CphThemes.classic, CphThemes.aveMujica), CphThemes.all)
    }

    @Test
    fun classicThemePalettePreservesExistingColors() {
        val palette = CphThemes.palette(CphThemeId.CLASSIC)

        assertEquals(Color(0x151923), palette.panel)
        assertEquals(Color(0x1B202B), palette.surface)
        assertEquals(Color(0x202A3A), palette.selected)
        assertEquals(Color(0x111620), palette.editor)
        assertEquals(Color(0x343A46), palette.border)
        assertEquals(Color(0xD8DEE9), palette.text)
        assertEquals(Color(0x9BA3AF), palette.muted)
        assertEquals(Color(0x65C466), palette.good)
        assertEquals(Color(0xFF5A57), palette.bad)
        assertEquals(Color(0x242B38), palette.actionHover)
        assertEquals(Color(0x2A3548), palette.actionPressed)
        assertEquals(Color(0x3A1F25), palette.diff)
        assertEquals(Color(0xF2A93B), palette.warn)
        assertEquals(Color(0x6EA2FF), palette.run)
    }

    @Test
    fun classicThemePalettePreservesTabStatusBackgrounds() {
        val palette = CphThemes.palette(CphThemeId.CLASSIC)

        assertEquals(Color(0x18321F), palette.tabStatusBackground(CphThemeTabStatus.GOOD))
        assertEquals(Color(0x321B1F), palette.tabStatusBackground(CphThemeTabStatus.BAD))
        assertEquals(Color(0x332816), palette.tabStatusBackground(CphThemeTabStatus.WARN))
        assertEquals(Color(0x18263B), palette.tabStatusBackground(CphThemeTabStatus.RUN))
        assertEquals(Color(0x1B202B), palette.tabStatusBackground(CphThemeTabStatus.DEFAULT))
    }

    @Test
    fun aveMujicaThemePaletteLoads() {
        val palette = CphThemes.palette(CphThemeId.AVE_MUJICA)

        assertEquals("Ave Mujica", palette.displayName)
        assertEquals(Color(0x0E0D18), palette.panel)
        assertEquals(Color(0x171528), palette.surface)
        assertEquals(Color(0x4A5C8C), palette.border)
        assertEquals(Color(0x7AAA94), palette.good)
        assertEquals(Color(0x99A7C9), palette.run)
        assertEquals(Color(0x1F1D38), palette.actionHover)
        assertEquals(Color(0x36315E), palette.actionPressed)
        assertEquals(Color(0x2A2A1E), palette.diff)
        assertEquals(Color(0xD9B566), palette.warn)
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
        assertEquals(
            CphBuildFeatures.codeforcesSubmitEnabled,
            CphCodeforcesSubmitFeature.actionEnabled(
                pluginEnabled = true,
                singleFileModeEnabled = true,
            ),
        )
    }
}
