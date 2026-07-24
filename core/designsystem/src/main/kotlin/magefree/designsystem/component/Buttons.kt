package magefree.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.FontScalePreviews
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Sizing
import magefree.designsystem.theme.Spacing

/*
 * The action / button hierarchy.
 *
 * Three thin wrappers over the Material 3 buttons express one consistent emphasis scale:
 *   - [MagePrimaryButton]   — highest emphasis (filled) for the single dominant action on a surface.
 *   - [MageSecondaryButton] — medium emphasis (tonal) for supporting actions.
 *   - [MageTextButton]      — lowest emphasis (text) for incidental / dismissive actions.
 *
 * Each wrapper applies the design tokens (a >= 48dp touch target, token content padding) so callers
 * never re-specify sizing, and each accepts an optional leading [icon] for the icon+label variant.
 * The label always carries the meaning, so the icon is decorative (contentDescription = null) and
 * state is never conveyed by color alone.
 */

/** Content padding shared by every button wrapper so the emphasis scale stays visually consistent. */
private val ButtonContentPadding =
    PaddingValues(
        horizontal = Spacing.large,
        vertical = Spacing.small,
    )

/** Highest-emphasis action: a filled button for the one dominant action on a surface. */
@Composable
fun MagePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizing.minTouchTarget),
        enabled = enabled,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/** Medium-emphasis action: a tonal button for supporting actions alongside a primary one. */
@Composable
fun MageSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizing.minTouchTarget),
        enabled = enabled,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/** Lowest-emphasis action: a text button for incidental or dismissive actions. */
@Composable
fun MageTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = Sizing.minTouchTarget),
        enabled = enabled,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

/**
 * Shared row content for the button wrappers: an optional leading [icon] (decorative — the [text]
 * label is the accessible name) followed by the label.
 */
@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Sizing.iconSmall),
        )
        Spacer(Modifier.size(Spacing.small))
    }
    Text(text = text)
}

@Composable
private fun ButtonsShowcase() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            MagePrimaryButton(text = "Primary", onClick = {})
            MagePrimaryButton(text = "Primary + icon", onClick = {}, icon = Icons.Filled.Star)
            MageSecondaryButton(text = "Secondary", onClick = {})
            MageSecondaryButton(text = "Secondary + icon", onClick = {}, icon = Icons.Filled.Add)
            MageTextButton(text = "Text", onClick = {})
            MagePrimaryButton(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}

@Preview(name = "Buttons - light", showBackground = true)
@Preview(
    name = "Buttons - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ButtonsPreview() {
    MageTheme {
        ButtonsShowcase()
    }
}

// Dynamic-type check: labels must grow with the font scale without clipping.
@FontScalePreviews
@Composable
private fun ButtonsFontScalePreview() {
    MageTheme {
        ButtonsShowcase()
    }
}
