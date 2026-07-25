package magefree.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand palette for the Mage Free client.
 *
 * A deliberate, chosen palette (see [MageTheme]'s dynamic-color decision) rather than the Material 3
 * defaults. The hues are an arcane "mage" identity: a violet/indigo primary, a teal secondary, and a
 * warm amber accent, tuned for a dark-first experience while still reading well in light. Raw palette
 * tokens live here; screens and components consume the semantic roles from the `ColorScheme`s below,
 * never these constants directly.
 */
internal object MagePalette {
    // Primary — arcane violet
    val Violet80 = Color(0xFFD3BBFF)
    val Violet60 = Color(0xFFA47DF5)
    val Violet40 = Color(0xFF6C43C4)
    val Violet20 = Color(0xFF3A1E7A)
    val Violet10 = Color(0xFF23124D)

    // Secondary — mana teal
    val Teal80 = Color(0xFF80E5D4)
    val Teal40 = Color(0xFF1E7A6C)
    val Teal20 = Color(0xFF0C3F38)
    val Teal10 = Color(0xFF06231F)

    // Tertiary — spell amber
    val Amber80 = Color(0xFFF7D488)
    val Amber40 = Color(0xFF8A6416)
    val Amber20 = Color(0xFF4A340A)
    val Amber10 = Color(0xFF291C05)

    // Error — Material-aligned red ramp
    val Red80 = Color(0xFFFFB4AB)
    val Red40 = Color(0xFFBA1A1A)
    val Red20 = Color(0xFF690005)
    val Red10 = Color(0xFF410002)

    // Neutrals — surfaces & text
    val White = Color(0xFFFFFFFF)
    val Neutral98 = Color(0xFFFDF8FF)
    val Neutral95 = Color(0xFFF3EDF7)
    val Neutral90 = Color(0xFFE6E0E9)
    val Neutral80 = Color(0xFFCAC4CF)
    val Neutral30 = Color(0xFF48454C)
    val Neutral20 = Color(0xFF322F36)
    val Neutral12 = Color(0xFF1E1B22)
    val Neutral10 = Color(0xFF16131A)
    val Neutral6 = Color(0xFF0F0D13)
}

/**
 * The brand light color scheme. Semantic Material 3 roles mapped onto [MagePalette].
 */
internal val MageLightColorScheme =
    lightColorScheme(
        primary = MagePalette.Violet40,
        onPrimary = MagePalette.White,
        primaryContainer = MagePalette.Violet80,
        onPrimaryContainer = MagePalette.Violet10,
        secondary = MagePalette.Teal40,
        onSecondary = MagePalette.White,
        secondaryContainer = MagePalette.Teal80,
        onSecondaryContainer = MagePalette.Teal10,
        tertiary = MagePalette.Amber40,
        onTertiary = MagePalette.White,
        tertiaryContainer = MagePalette.Amber80,
        onTertiaryContainer = MagePalette.Amber10,
        error = MagePalette.Red40,
        onError = MagePalette.White,
        errorContainer = MagePalette.Red80,
        onErrorContainer = MagePalette.Red10,
        background = MagePalette.Neutral98,
        onBackground = MagePalette.Neutral10,
        surface = MagePalette.Neutral98,
        onSurface = MagePalette.Neutral10,
        surfaceVariant = MagePalette.Neutral90,
        onSurfaceVariant = MagePalette.Neutral30,
        outline = MagePalette.Neutral80,
    )

/**
 * The brand dark color scheme — the primary target (dark-first UX). Semantic roles mapped onto the
 * lighter tonal steps of [MagePalette] for legibility on dark surfaces.
 */
internal val MageDarkColorScheme =
    darkColorScheme(
        primary = MagePalette.Violet80,
        onPrimary = MagePalette.Violet20,
        primaryContainer = MagePalette.Violet40,
        onPrimaryContainer = MagePalette.Violet80,
        secondary = MagePalette.Teal80,
        onSecondary = MagePalette.Teal20,
        secondaryContainer = MagePalette.Teal40,
        onSecondaryContainer = MagePalette.Teal80,
        tertiary = MagePalette.Amber80,
        onTertiary = MagePalette.Amber20,
        tertiaryContainer = MagePalette.Amber40,
        onTertiaryContainer = MagePalette.Amber80,
        error = MagePalette.Red80,
        onError = MagePalette.Red20,
        errorContainer = MagePalette.Red40,
        onErrorContainer = MagePalette.Red80,
        background = MagePalette.Neutral6,
        onBackground = MagePalette.Neutral90,
        surface = MagePalette.Neutral10,
        onSurface = MagePalette.Neutral90,
        surfaceVariant = MagePalette.Neutral20,
        onSurfaceVariant = MagePalette.Neutral80,
        outline = MagePalette.Neutral30,
    )
