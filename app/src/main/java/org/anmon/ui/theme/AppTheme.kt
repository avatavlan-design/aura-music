package org.anmon.ui.theme

import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val primaryColor: Color,
    val secondaryColor: Color
) {
    OBSIDIAN_PURPLE(
        id = "obsidian_purple",
        displayName = "Obsidian Purple",
        isDark = true,
        primaryColor = Color(0xFF8B5CF6),
        secondaryColor = Color(0xFF06B6D4)
    ),
    CLEAN_LIGHT(
        id = "clean_light",
        displayName = "Clean Light",
        isDark = false,
        primaryColor = Color(0xFF6D28D9),
        secondaryColor = Color(0xFF0891B2)
    ),
    EMERALD_BLACK(
        id = "emerald_black",
        displayName = "Green & Black",
        isDark = true,
        primaryColor = Color(0xFF10B981),
        secondaryColor = Color(0xFF34D399)
    ),
    ORANGE_BLACK(
        id = "orange_black",
        displayName = "Orange & Black",
        isDark = true,
        primaryColor = Color(0xFFF97316),
        secondaryColor = Color(0xFFFBBF24)
    ),
    PINK_BLUE(
        id = "pink_blue",
        displayName = "Pink & Light Blue",
        isDark = false,
        primaryColor = Color(0xFFEC4899),
        secondaryColor = Color(0xFF0284C7)
    ),
    RED_WHITE(
        id = "red_white",
        displayName = "Red & White",
        isDark = false,
        primaryColor = Color(0xFFDC2626),
        secondaryColor = Color(0xFFE11D48)
    ),
    MIDNIGHT_GOLD(
        id = "midnight_gold",
        displayName = "Midnight Gold",
        isDark = true,
        primaryColor = Color(0xFFF59E0B),
        secondaryColor = Color(0xFFFCD34D)
    ),
    CYBER_CYAN(
        id = "cyber_cyan",
        displayName = "Cyber Cyan",
        isDark = true,
        primaryColor = Color(0xFF06B6D4),
        secondaryColor = Color(0xFF38BDF8)
    );

    companion object {
        fun fromId(id: String?): AppTheme {
            return values().firstOrNull { it.id == id || it.name == id } ?: OBSIDIAN_PURPLE
        }
    }
}
