package magefree.designsystem.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * Internal, preview-only showcase demonstrating the brand palette, typography, and shape so the theme
 * can be visually verified in Android Studio (light + dark) without any real screen. This is not a
 * reusable component — it exists solely for the `@Preview`s below.
 */
@Composable
private fun ThemeShowcase() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(text = "Mage Free", style = MaterialTheme.typography.headlineMedium)
            Text(text = "Design system foundation", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Body text scales with dynamic type and uses the brand color roles.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Swatch(MaterialTheme.colorScheme.primary)
                Swatch(MaterialTheme.colorScheme.secondary)
                Swatch(MaterialTheme.colorScheme.tertiary)
                Swatch(MaterialTheme.colorScheme.error)
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MageShapes.large,
            ) {
                Text(
                    text = "Rounded surface — large shape token",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier =
            Modifier
                .size(Sizing.iconLarge)
                .background(color = color, shape = RoundedCornerShape(Corner.small)),
    )
}

@Preview(name = "Theme — light", showBackground = true)
@Preview(
    name = "Theme — dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThemeShowcasePreview() {
    MageTheme {
        ThemeShowcase()
    }
}
