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
    }

    @Test
    fun codeforcesRemoteSubmitPluginEnabledStateLoadsWithDefaultTheme() {
        val service = CphPluginSettings()

        service.loadState(CphPluginSettingsState(codeforcesRemoteSubmitEnabled = true))

        assertTrue(service.state.codeforcesRemoteSubmitEnabled)
        assertEquals(CphThemeId.CLASSIC, service.state.selectedThemeId)
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
        assertEquals(Color(0x140A11), palette.panel)
        assertEquals(Color(0x20101A), palette.surface)
        assertEquals(Color(0x5A2443), palette.border)
        assertEquals(Color(0x779977), palette.good)
        assertEquals(Color(0xBB9955), palette.run)
        assertEquals(Color(0x3A1027), palette.actionHover)
        assertEquals(Color(0x561538), palette.actionPressed)
        assertEquals(Color(0x3A321C), palette.diff)
        assertEquals(Color(0xD7B160), palette.warn)
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
