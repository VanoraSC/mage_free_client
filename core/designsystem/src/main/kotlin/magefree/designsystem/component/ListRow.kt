package magefree.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Sizing

/*
 * The standard list row.
 *
 * [MageListRow] is a thin, token-styled wrapper over the Material 3 [ListItem]: a title, optional
 * supporting text, and optional leading / trailing slots for icons, avatars, or controls. It is sized
 * for touch (the M3 list item is already >= 56dp; a clickable row additionally enforces the
 * accessibility minimum) and is fully stateless — an optional [onClick] event is hoisted to the caller.
 */

/**
 * A standard, touch-sized list row.
 *
 * @param headline the primary title line.
 * @param modifier the [Modifier] for the row.
 * @param supportingText an optional secondary line beneath the [headline].
 * @param leadingContent an optional leading slot (e.g. an icon or avatar).
 * @param trailingContent an optional trailing slot (e.g. a chevron or control).
 * @param onClick when non-null the whole row is clickable; the row then enforces the >= 48dp target.
 */
@Composable
fun MageListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier =
        if (onClick != null) {
            modifier
                .defaultMinSize(minHeight = Sizing.minTouchTarget)
                .clickable(onClick = onClick)
        } else {
            modifier
        }
    ListItem(
        headlineContent = { Text(text = headline) },
        modifier = rowModifier,
        supportingContent = supportingText?.let { { Text(text = it) } },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}

@Preview(name = "ListRow - light", showBackground = true)
@Preview(
    name = "ListRow - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MageListRowPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                MageListRow(
                    headline = "Title only",
                    onClick = {},
                )
                MageListRow(
                    headline = "Title with supporting text",
                    supportingText = "Secondary line describing the item",
                    onClick = {},
                )
                MageListRow(
                    headline = "With leading and trailing",
                    supportingText = "Both slots populated",
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(Sizing.iconLarge),
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
            }
        }
    }
}
