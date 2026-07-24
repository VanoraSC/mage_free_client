package magefree.designsystem.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/*
 * Reusable multipreview annotations for the design system.
 *
 * [ThemePreviews] renders a composable in both light and dark schemes; [FontScalePreviews] renders it
 * across the font scales that matter for accessibility (1.0x default, 1.3x large, 2.0x the platform
 * maximum) so components can be checked for clipping / truncation at large dynamic-type sizes without
 * writing three annotations by hand. Apply them to a `@Composable` preview function the same way as a
 * single `@Preview`.
 */

/** Light + dark previews in one annotation. */
@Preview(name = "Light", showBackground = true)
@Preview(
    name = "Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class ThemePreviews

/**
 * Font-scale previews (1.0x / 1.3x / 2.0x) for verifying dynamic-type support: text and its containers
 * must grow with the scale without clipping or truncating meaningful content.
 */
@Preview(name = "Font 1.0x", showBackground = true, fontScale = 1.0f)
@Preview(name = "Font 1.3x", showBackground = true, fontScale = 1.3f)
@Preview(name = "Font 2.0x", showBackground = true, fontScale = 2.0f)
annotation class FontScalePreviews
