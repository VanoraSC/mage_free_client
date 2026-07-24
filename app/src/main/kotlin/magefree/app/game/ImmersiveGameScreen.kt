package magefree.app.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme

/** Placeholder text on the immersive game surface; shared with tests so the two agree. */
const val IMMERSIVE_GAME_LABEL: String = "In-game (immersive placeholder)"

/**
 * Accessibility label on the exit control. Exposed so UI tests can locate the affordance by its
 * screen-reader description rather than its glyph.
 */
const val EXIT_GAME_CONTENT_DESCRIPTION: String = "Exit game and return to the app"

/**
 * The immersive in-game surface (story 0011): a full-bleed placeholder that stands in for the real
 * board (EPIC-11+). It calls [ImmersiveSystemUi] so the system bars hide while it is shown and
 * restore when it leaves composition, and hosts an always-visible, accessible exit control.
 *
 * Stateless and preview-able: it holds no state and fetches nothing. Both the on-screen exit control
 * and the system back button emit [onExit] — the root
 * [AppNavHost][magefree.app.navigation.AppNavHost] wires that to pop back to the shell.
 */
@Composable
fun ImmersiveGameScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hide the system bars while on this route; restored automatically on dispose.
    ImmersiveSystemUi()

    // System back leaves the immersive mode, exactly like the on-screen control (single exit path).
    BackHandler(onBack = onExit)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = IMMERSIVE_GAME_LABEL,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )

            // Exit affordance: top-start, kept inside the safe-drawing insets so it stays reachable
            // even with the system bars hidden / around display cutouts. FilledIconButton is a 48dp
            // touch target by default, meeting the accessibility floor.
            FilledIconButton(
                onClick = onExit,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = EXIT_GAME_CONTENT_DESCRIPTION,
                )
            }
        }
    }
}

@Preview(name = "Immersive game — light", showBackground = true)
@Preview(
    name = "Immersive game — dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ImmersiveGameScreenPreview() {
    MageTheme {
        ImmersiveGameScreen(onExit = {})
    }
}
