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
        panel = Color(0x140A11),
        surface = Color(0x20101A),
        selected = Color(0x3A1730),
        editor = Color(0x0E0A10),
        border = Color(0x5A2443),
        text = Color(0xF1E8D9),
        muted = Color(0xBFA8B7),
        good = Color(0x779977),
        bad = Color(0xE66370),
        actionHover = Color(0x3A1027),
        actionPressed = Color(0x561538),
        diff = Color(0x3A321C),
        warn = Color(0xD7B160),
        run = Color(0xBB9955),
        okTabBackground = Color(0x173326),
        errorTabBackground = Color(0x3B1428),
        tleTabBackground = Color(0x342A19),
        runningTabBackground = Color(0x32142B),
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
