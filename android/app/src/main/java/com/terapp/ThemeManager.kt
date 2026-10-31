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

    val MONOKAI = TerminalTheme(
        name = "Monokai",
        background = Color.parseColor("#272822"),
        foreground = Color.parseColor("#F8F8F2"),
        cursor = Color.parseColor("#F8F8F0"),
        selection = Color.parseColor("#49483E"),
        ansiColors = intArrayOf(
            Color.parseColor("#272822"), // 0: Black
            Color.parseColor("#F92672"), // 1: Red
            Color.parseColor("#A6E22E"), // 2: Green
            Color.parseColor("#FD971F"), // 3: Yellow
            Color.parseColor("#66D9EF"), // 4: Blue
            Color.parseColor("#AE81FF"), // 5: Magenta
            Color.parseColor("#38CCD1"), // 6: Cyan
            Color.parseColor("#F8F8F2"), // 7: White
            Color.parseColor("#75715E"), // 8: Bright Black
            Color.parseColor("#F92672"), // 9: Bright Red
            Color.parseColor("#A6E22E"), // 10: Bright Green
            Color.parseColor("#E6DB74"), // 11: Bright Yellow
            Color.parseColor("#66D9EF"), // 12: Bright Blue
            Color.parseColor("#AE81FF"), // 13: Bright Magenta
            Color.parseColor("#38CCD1"), // 14: Bright Cyan
            Color.parseColor("#F9F8F5")  // 15: Bright White
        )
    )

    val SOLARIZED_DARK = TerminalTheme(
        name = "Solarized Dark",
        background = Color.parseColor("#002B36"),
        foreground = Color.parseColor("#839496"),
        cursor = Color.parseColor("#93A1A1"),
        selection = Color.parseColor("#073642"),
        ansiColors = intArrayOf(
            Color.parseColor("#073642"), // 0: Base02
            Color.parseColor("#DC322F"), // 1: Red
            Color.parseColor("#859900"), // 2: Green
            Color.parseColor("#B58900"), // 3: Yellow
            Color.parseColor("#268BD2"), // 4: Blue
            Color.parseColor("#D33682"), // 5: Magenta
            Color.parseColor("#2AA198"), // 6: Cyan
            Color.parseColor("#EEE8D5"), // 7: Base2
            Color.parseColor("#002B36"), // 8: Base03
            Color.parseColor("#CB4B16"), // 9: Orange
            Color.parseColor("#586E75"), // 10: Base01
            Color.parseColor("#657B83"), // 11: Base00
            Color.parseColor("#839496"), // 12: Base0
            Color.parseColor("#6C71C4"), // 13: Violet
            Color.parseColor("#93A1A1"), // 14: Base1
            Color.parseColor("#FDF6E3")  // 15: Base3
        )
    )

    val SOLARIZED_LIGHT = TerminalTheme(
        name = "Solarized Light",
        background = Color.parseColor("#FDF6E3"),
        foreground = Color.parseColor("#657B83"),
        cursor = Color.parseColor("#586E75"),
        selection = Color.parseColor("#EEE8D5"),
        ansiColors = intArrayOf(
            Color.parseColor("#EEE8D5"), // 0: Base2
            Color.parseColor("#DC322F"), // 1: Red
            Color.parseColor("#859900"), // 2: Green
            Color.parseColor("#B58900"), // 3: Yellow
            Color.parseColor("#268BD2"), // 4: Blue
            Color.parseColor("#D33682"), // 5: Magenta
            Color.parseColor("#2AA198"), // 6: Cyan
            Color.parseColor("#073642"), // 7: Base02
            Color.parseColor("#FDF6E3"), // 8: Base3
            Color.parseColor("#CB4B16"), // 9: Orange
            Color.parseColor("#93A1A1"), // 10: Base1
            Color.parseColor("#839496"), // 11: Base0
            Color.parseColor("#657B83"), // 12: Base00
            Color.parseColor("#6C71C4"), // 13: Violet
            Color.parseColor("#586E75"), // 14: Base01
            Color.parseColor("#002B36")  // 15: Base03
        )
    )

    val GRUVBOX_DARK = TerminalTheme(
        name = "Gruvbox Dark",
        background = Color.parseColor("#282828"),
        foreground = Color.parseColor("#EBDBB2"),
        cursor = Color.parseColor("#EBDBB2"),
        selection = Color.parseColor("#504945"),
        ansiColors = intArrayOf(
            Color.parseColor("#282828"),
            Color.parseColor("#CC241D"),
            Color.parseColor("#98971A"),
            Color.parseColor("#D79921"),
            Color.parseColor("#458588"),
            Color.parseColor("#B16286"),
            Color.parseColor("#689D6A"),
            Color.parseColor("#A89984"),
            Color.parseColor("#928374"),
            Color.parseColor("#FB4934"),
            Color.parseColor("#B8BB26"),
            Color.parseColor("#FABD2F"),
            Color.parseColor("#83A598"),
            Color.parseColor("#D3869B"),
            Color.parseColor("#8EC07C"),
            Color.parseColor("#EBDBB2")
        )
    )

    val THEMES = listOf(
        DRACULA,
        CATPPUCCIN_MOCHA,
        ONE_DARK,
        NORD,
        MONOKAI,
        SOLARIZED_DARK,
        SOLARIZED_LIGHT,
        GRUVBOX_DARK
    )

    var currentTheme: TerminalTheme = DRACULA
}
