package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ObsidianPurpleColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4C1D95),
    onPrimaryContainer = Color(0xFFDDD6FE),
    secondary = SecondaryCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = AccentEmerald,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    outline = DarkOutline
)

private val CleanLightColorScheme = lightColorScheme(
    primary = PrimaryPurpleLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDD6FE),
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = AccentEmerald,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    outline = LightOutline
)

private val EmeraldBlackColorScheme = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF34D399),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF065F46),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFF6EE7B7),
    background = Color(0xFF050806),
    onBackground = Color(0xFFECFDF5),
    surface = Color(0xFF0C140E),
    onSurface = Color(0xFFECFDF5),
    surfaceVariant = Color(0xFF152219),
    onSurfaceVariant = Color(0xFFA7F3D0),
    surfaceContainerHigh = Color(0xFF1C2E22),
    outline = Color(0xFF059669)
)

private val OrangeBlackColorScheme = darkColorScheme(
    primary = Color(0xFFF97316),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF7C2D12),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF78350F),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF080504),
    onBackground = Color(0xFFFFF7ED),
    surface = Color(0xFF140C07),
    onSurface = Color(0xFFFFF7ED),
    surfaceVariant = Color(0xFF24150B),
    onSurfaceVariant = Color(0xFFFED7AA),
    surfaceContainerHigh = Color(0xFF331E0F),
    outline = Color(0xFFEA580C)
)

private val PinkBlueColorScheme = lightColorScheme(
    primary = Color(0xFFEC4899),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE7F3),
    onPrimaryContainer = Color(0xFF831843),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFFF43F5E),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    outline = Color(0xFFF472B6)
)

private val RedWhiteColorScheme = lightColorScheme(
    primary = Color(0xFFDC2626),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE2E2),
    onPrimaryContainer = Color(0xFF7F1D1D),
    secondary = Color(0xFFE11D48),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4E6),
    onSecondaryContainer = Color(0xFF881337),
    tertiary = Color(0xFFB91C1C),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color.White,
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceContainerHigh = Color(0xFFE4E4E7),
    outline = Color(0xFFEF4444)
)

private val MidnightGoldColorScheme = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF451A03),
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = Color(0xFFFCD34D),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF78350F),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = Color(0xFFD97706),
    background = Color(0xFF06070B),
    onBackground = Color(0xFFFEF3C7),
    surface = Color(0xFF0F111A),
    onSurface = Color(0xFFFEF3C7),
    surfaceVariant = Color(0xFF1A1D2B),
    onSurfaceVariant = Color(0xFFFDE68A),
    surfaceContainerHigh = Color(0xFF24283B),
    outline = Color(0xFFD97706)
)

private val CyberCyanColorScheme = darkColorScheme(
    primary = Color(0xFF06B6D4),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF164E63),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF0369A1),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF22D3EE),
    background = Color(0xFF030712),
    onBackground = Color(0xFFECFEFF),
    surface = Color(0xFF0B132B),
    onSurface = Color(0xFFECFEFF),
    surfaceVariant = Color(0xFF1C2541),
    onSurfaceVariant = Color(0xFFA5F3FC),
    surfaceContainerHigh = Color(0xFF273256),
    outline = Color(0xFF0891B2)
)

fun getColorSchemeForTheme(theme: AppTheme): ColorScheme {
    return when (theme) {
        AppTheme.OBSIDIAN_PURPLE -> ObsidianPurpleColorScheme
        AppTheme.CLEAN_LIGHT -> CleanLightColorScheme
        AppTheme.EMERALD_BLACK -> EmeraldBlackColorScheme
        AppTheme.ORANGE_BLACK -> OrangeBlackColorScheme
        AppTheme.PINK_BLUE -> PinkBlueColorScheme
        AppTheme.RED_WHITE -> RedWhiteColorScheme
        AppTheme.MIDNIGHT_GOLD -> MidnightGoldColorScheme
        AppTheme.CYBER_CYAN -> CyberCyanColorScheme
    }
}

@Composable
fun AuraMusicTheme(
    theme: AppTheme = AppTheme.OBSIDIAN_PURPLE,
    content: @Composable () -> Unit
) {
    val colorScheme = getColorSchemeForTheme(theme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun AuraMusicTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val theme = if (darkTheme) AppTheme.OBSIDIAN_PURPLE else AppTheme.CLEAN_LIGHT
    AuraMusicTheme(theme = theme, content = content)
}
