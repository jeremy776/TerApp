package com.terapp

import android.graphics.Color

data class TerminalTheme(
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selection: Int,
    val ansiColors: IntArray
)

object ThemeManager {
    val DRACULA = TerminalTheme(
        name = "Dracula",
        background = Color.parseColor("#282A36"),
        foreground = Color.parseColor("#F8F8F2"),
        cursor = Color.parseColor("#F8F8F0"),
        selection = Color.parseColor("#44475A"),
        ansiColors = intArrayOf(
            Color.parseColor("#000000"), // 0: Black
            Color.parseColor("#FF5555"), // 1: Red
            Color.parseColor("#50FA7B"), // 2: Green
            Color.parseColor("#F1FA8C"), // 3: Yellow
            Color.parseColor("#BD93F9"), // 4: Blue/Purple
            Color.parseColor("#FF79C6"), // 5: Magenta
            Color.parseColor("#8BE9FD"), // 6: Cyan
            Color.parseColor("#BFBFBF"), // 7: White
            Color.parseColor("#4D4D4D"), // 8: Bright Black
            Color.parseColor("#FF6E6E"), // 9: Bright Red
            Color.parseColor("#69FF94"), // 10: Bright Green
            Color.parseColor("#FFFFA5"), // 11: Bright Yellow
            Color.parseColor("#D6ACFF"), // 12: Bright Blue
            Color.parseColor("#FF92DF"), // 13: Bright Magenta
            Color.parseColor("#A4FFFF"), // 14: Bright Cyan
            Color.parseColor("#FFFFFF")  // 15: Bright White
        )
    )

    val CATPPUCCIN_MOCHA = TerminalTheme(
        name = "Catppuccin Mocha",
        background = Color.parseColor("#1E1E2E"),
        foreground = Color.parseColor("#CDD6F4"),
        cursor = Color.parseColor("#F5E0DC"),
        selection = Color.parseColor("#45475A"),
        ansiColors = intArrayOf(
            Color.parseColor("#45475A"),
            Color.parseColor("#F38BA8"),
            Color.parseColor("#A6E3A1"),
            Color.parseColor("#F9E2AF"),
            Color.parseColor("#89B4FA"),
            Color.parseColor("#F5C2E7"),
            Color.parseColor("#94E2D5"),
            Color.parseColor("#BAC2DE"),
            Color.parseColor("#585B70"),
            Color.parseColor("#F38BA8"),
            Color.parseColor("#A6E3A1"),
            Color.parseColor("#F9E2AF"),
            Color.parseColor("#89B4FA"),
            Color.parseColor("#F5C2E7"),
            Color.parseColor("#94E2D5"),
            Color.parseColor("#A6ADC8")
        )
    )

    val ONE_DARK = TerminalTheme(
        name = "One Dark",
        background = Color.parseColor("#282C34"),
        foreground = Color.parseColor("#ABB2BF"),
        cursor = Color.parseColor("#528BFF"),
        selection = Color.parseColor("#3E4451"),
        ansiColors = intArrayOf(
            Color.parseColor("#282C34"),
            Color.parseColor("#E06C75"),
            Color.parseColor("#98C379"),
            Color.parseColor("#E5C07B"),
            Color.parseColor("#61AFEF"),
            Color.parseColor("#C678DD"),
            Color.parseColor("#56B6C2"),
            Color.parseColor("#ABB2BF"),
            Color.parseColor("#5C6370"),
            Color.parseColor("#E06C75"),
            Color.parseColor("#98C379"),
            Color.parseColor("#E5C07B"),
            Color.parseColor("#61AFEF"),
            Color.parseColor("#C678DD"),
            Color.parseColor("#56B6C2"),
            Color.parseColor("#FFFFFF")
        )
    )

    val NORD = TerminalTheme(
        name = "Nord",
        background = Color.parseColor("#2E3440"),
        foreground = Color.parseColor("#D8DEE9"),
        cursor = Color.parseColor("#88C0D0"),
        selection = Color.parseColor("#434C5E"),
        ansiColors = intArrayOf(
            Color.parseColor("#3B4252"),
            Color.parseColor("#BF616A"),
            Color.parseColor("#A3BE8C"),
            Color.parseColor("#EBCB8B"),
            Color.parseColor("#81A1C1"),
            Color.parseColor("#B48EAD"),
            Color.parseColor("#88C0D0"),
            Color.parseColor("#E5E9F0"),
            Color.parseColor("#4C566A"),
            Color.parseColor("#BF616A"),
            Color.parseColor("#A3BE8C"),
            Color.parseColor("#EBCB8B"),
            Color.parseColor("#81A1C1"),
            Color.parseColor("#B48EAD"),
            Color.parseColor("#8FBCBB"),
            Color.parseColor("#ECEFF4")
        )
    )

    val THEMES = listOf(DRACULA, CATPPUCCIN_MOCHA, ONE_DARK, NORD)
    var currentTheme: TerminalTheme = DRACULA
}

