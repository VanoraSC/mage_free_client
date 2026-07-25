package magefree.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/*
 * Section chrome: the top app bar wrapper and the in-content section header.
 *
 * [MageTopAppBar] is a thin wrapper over the Material 3 [TopAppBar] that applies the brand title
 * typography and hoists the navigation-icon / actions slots. [MageSectionHeader] is the smaller,
 * in-list divider label used to group content within a screen. Both are stateless and token-styled.
 */

/**
 * A branded top app bar.
 *
 * @param title the screen / section title text.
 * @param modifier the [Modifier] for the bar.
 * @param navigationIcon an optional leading slot (typically a back or menu [IconButton]); empty by
 *   default.
 * @param actions optional trailing action items laid out in a [RowScope].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MageTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

/**
 * A section header used to label a group of content within a screen.
 *
 * @param text the header label.
 * @param modifier the [Modifier] for the header.
 */
@Composable
fun MageSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Spacing.medium,
                    vertical = Spacing.small,
                ),
    )
}

@Preview(name = "SectionChrome - light", showBackground = true)
@Preview(
    name = "SectionChrome - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SectionChromePreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                MageTopAppBar(
                    title = "Decks",
                    navigationIcon = {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate up",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                            )
                        }
                    },
                )
                MageSectionHeader(text = "Recently played")
            }
        }
    }
}
