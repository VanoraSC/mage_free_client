package magefree.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.app.theme.MageTheme

/**
 * Title rendered at the top of [HomeScreen]; shared with tests so the two agree.
 */
const val HOME_TITLE: String = "Mage Free Client"

/**
 * Content description on the primary **Play** call-to-action. Exposed so the UI test can locate the
 * CTA by its accessibility label rather than its visible glyph/text.
 */
const val HOME_PLAY_CONTENT_DESCRIPTION: String = "Play — find a match and start a game"

/** Minimum height of the Play CTA. Comfortably above the 48dp accessibility floor. */
private val PlayButtonMinHeight = 72.dp

/**
 * The Arena-style home hub. Its dominant, thumb-reachable element is the primary **Play** CTA,
 * anchored in the lower third of the screen where it is easiest to reach one-handed (see
 * [docs/ux-principles.md] — "primary actions live in the lower third"). Lighter secondary entries
 * to the other destinations sit above it.
 *
 * Stateless and preview-able: it holds no state and fetches nothing. Every action is emitted through
 * a hoisted callback — the shell wires these to navigation, and [onPlayClick] is a stub until the
 * real matchmaking/lobby lands in EPIC-06. This story is about home layout and the primacy of
 * "Play," not the play flow.
 */
@Composable
fun HomeScreen(
    onPlayClick: () -> Unit,
    onDecksClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = HOME_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        // Push the interactive weight toward the bottom: the Play CTA lands in the lower third.
        Spacer(modifier = Modifier.weight(1f))

        // Secondary entries — smaller, lower emphasis, sitting above the primary action.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            SecondaryEntry(
                label = "Decks",
                onClick = onDecksClick,
                modifier = Modifier.weight(1f),
            )
            SecondaryEntry(
                label = "Profile",
                onClick = onProfileClick,
                modifier = Modifier.weight(1f),
            )
            SecondaryEntry(
                label = "Settings",
                onClick = onSettingsClick,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Primary action: largest, highest-emphasis, filled, full-width, in the lower third.
        Button(
            onClick = onPlayClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = PlayButtonMinHeight)
                    .semantics { contentDescription = HOME_PLAY_CONTENT_DESCRIPTION },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null, // Decorative: the button carries the description.
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = "Play",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** A single low-emphasis secondary entry. Outlined so the filled Play CTA stays the visual priority. */
@Composable
private fun SecondaryEntry(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(text = label)
    }
}

@Preview(name = "Home — light", showBackground = true)
@Preview(
    name = "Home — dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenPreview() {
    MageTheme {
        HomeScreen(
            onPlayClick = {},
            onDecksClick = {},
            onProfileClick = {},
            onSettingsClick = {},
        )
    }
}
