package magefree.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The Mage Free client's theme: the branded Material 3 [MaterialTheme] every screen and component is
 * wrapped in. Supplies the brand [ColorScheme]s ([MageLightColorScheme] / [MageDarkColorScheme]),
 * [MageTypography], and [MageShapes]. Design tokens (spacing/elevation/sizing, see `Tokens.kt`) are
 * plain constants consumed alongside this theme.
 *
 * ## Dynamic color decision: OFF (deliberate, brand-controlled)
 *
 * Android 12+ "Material You" dynamic color (deriving the scheme from the user's wallpaper) is
 * **intentionally not used**. The UX direction calls for *one coherent, chosen visual system* — a
 * deliberate mage identity — rather than a per-device wallpaper-defaulted look. A fixed brand palette
 * also keeps card art, mana symbols, and game state reading consistently across every device, and
 * makes light/dark previews authoritative. There is therefore no `dynamicColor` parameter to toggle;
 * should product direction ever change, dynamic color would be introduced here as an explicit opt-in.
 *
 * @param darkTheme whether to use the dark scheme; defaults to the system setting so the app follows
 *   the device's light/dark preference. Overridable for previews and tests.
 */
@Composable
fun MageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) MageDarkColorScheme else MageLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MageTypography,
        shapes = MageShapes,
        content = content,
    )
}
