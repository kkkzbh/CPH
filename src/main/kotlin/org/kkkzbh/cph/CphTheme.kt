package org.kkkzbh.cph

import java.awt.Color

internal data class CphThemePalette(
    val id: String,
    val displayName: String,
    val panel: Color,
    val surface: Color,
    val selected: Color,
    val editor: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val good: Color,
    val bad: Color,
    val actionHover: Color,
    val actionPressed: Color,
    val diff: Color,
    val warn: Color,
    val run: Color,
    val okTabBackground: Color,
    val errorTabBackground: Color,
    val tleTabBackground: Color,
    val runningTabBackground: Color,
) {
    fun tabStatusBackground(status: CphThemeTabStatus): Color {
        return when (status) {
            CphThemeTabStatus.GOOD -> okTabBackground
            CphThemeTabStatus.BAD -> errorTabBackground
            CphThemeTabStatus.WARN -> tleTabBackground
            CphThemeTabStatus.RUN -> runningTabBackground
            CphThemeTabStatus.DEFAULT -> surface
        }
    }
}

internal enum class CphThemeTabStatus {
    GOOD,
    BAD,
    WARN,
    RUN,
    DEFAULT,
}

internal object CphThemeId {
    const val CLASSIC = "classic"
    const val AVE_MUJICA = "avemujica"

    fun normalize(themeId: String?): String {
        return when (themeId) {
            CLASSIC -> CLASSIC
            AVE_MUJICA -> AVE_MUJICA
            else -> CLASSIC
        }
    }
}

internal object CphThemes {
    val classic = CphThemePalette(
        id = CphThemeId.CLASSIC,
        displayName = "默认主题",
        panel = Color(0x151923),
        surface = Color(0x1B202B),
        selected = Color(0x202A3A),
        editor = Color(0x111620),
        border = Color(0x343A46),
        text = Color(0xD8DEE9),
        muted = Color(0x9BA3AF),
        good = Color(0x65C466),
        bad = Color(0xFF5A57),
        actionHover = Color(0x242B38),
        actionPressed = Color(0x2A3548),
        diff = Color(0x3A1F25),
        warn = Color(0xF2A93B),
        run = Color(0x6EA2FF),
        okTabBackground = Color(0x18321F),
        errorTabBackground = Color(0x321B1F),
        tleTabBackground = Color(0x332816),
        runningTabBackground = Color(0x18263B),
    )

    val aveMujica = CphThemePalette(
        id = CphThemeId.AVE_MUJICA,
        displayName = "Ave Mujica",
        panel = Color(0x0E0D18),
        surface = Color(0x171528),
        selected = Color(0x2D2548),
        editor = Color(0x0A0913),
        border = Color(0x4A5C8C),
        text = Color(0xE8EBF7),
        muted = Color(0x8E97B8),
        good = Color(0x7AAA94),
        bad = Color(0xD55F73),
        actionHover = Color(0x1F1D38),
        actionPressed = Color(0x36315E),
        diff = Color(0x2A2A1E),
        warn = Color(0xD9B566),
        run = Color(0x99A7C9),
        okTabBackground = Color(0x162B26),
        errorTabBackground = Color(0x2C1A28),
        tleTabBackground = Color(0x2A2419),
        runningTabBackground = Color(0x1B2240),
    )

    val all = listOf(classic, aveMujica)

    fun current(): CphThemePalette {
        return palette(CphPluginSettings.getInstance().state.selectedThemeId)
    }

    fun palette(themeId: String?): CphThemePalette {
        return when (CphThemeId.normalize(themeId)) {
            CphThemeId.CLASSIC -> classic
            CphThemeId.AVE_MUJICA -> aveMujica
            else -> classic
        }
    }
}
