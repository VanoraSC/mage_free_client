package magefree.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme

/** Text rendered by [SettingsPlaceholderScreen]; shared with tests so the two agree. */
const val SETTINGS_SCREEN_LABEL: String = "Settings"

/**
 * Visible label on the temporary developer entry into the immersive game mode. Shared with tests so
 * the two agree; clearly marked as a stub so it reads as non-production.
 */
const val ENTER_GAME_STUB_LABEL: String = "Enter game (dev stub)"

/**
 * Visible label on the developer entry into the design-system component catalog (story 0015). Shared
 * with tests so the two agree; clearly marked as a dev-only affordance.
 */
const val OPEN_CATALOG_STUB_LABEL: String = "Component catalog (dev)"

/**
 * Visible label on the sign-out action (story 0047). Shared with tests so the two agree. This is the
 * shell's only deliberate exit from a session: it ends the session (story 0046's `signOut()`, which
 * sends `Logout` rather than letting the bridge park the session for a resume) and returns the player
 * to the connect flow, from which they can sign in again.
 */
const val SIGN_OUT_LABEL: String = "Sign out"

/**
 * Placeholder for the Settings destination. Real preferences (DataStore-backed) arrive later; story
 * 0008 only proves the shell can reach this route.
 *
 * Story 0011 adds a clearly-marked **dev stub** ([ENTER_GAME_STUB_LABEL]) that navigates into the
 * immersive [GameRoute][magefree.app.game.GameRoute]. This is a temporary entry point only — real
 * entry into the game comes from the lobby/table flows in EPIC-06/07 and this button is expected to
 * be removed then.
 */
@Composable
fun SettingsPlaceholderScreen(
    modifier: Modifier = Modifier,
    onEnterGame: () -> Unit = {},
    onOpenCatalog: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = SETTINGS_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)

        // Temporary developer affordance to reach the immersive game route (story 0011); removed
        // once real entry from lobby/table lands (EPIC-06/07).
        OutlinedButton(
            onClick = onEnterGame,
            modifier =
                Modifier
                    .padding(top = 24.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(text = ENTER_GAME_STUB_LABEL, textAlign = TextAlign.Center)
        }

        // Debug-only affordance to open the design-system component catalog (story 0015) — a visual-QA
        // surface, not a production feature.
        OutlinedButton(
            onClick = onOpenCatalog,
            modifier =
                Modifier
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(text = OPEN_CATALOG_STUB_LABEL, textAlign = TextAlign.Center)
        }

        // Story 0047: the deliberate way out of a session. Real preferences will re-home this when the
        // Settings screen stops being a placeholder; what matters is that the shell has *an* exit at
        // all — before 0047 the app could neither sign in nor sign out from a running APK.
        OutlinedButton(
            onClick = onSignOut,
            modifier =
                Modifier
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(text = SIGN_OUT_LABEL, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Settings — light", showBackground = true)
@Preview(name = "Settings — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPlaceholderScreenPreview() {
    MageTheme { SettingsPlaceholderScreen() }
}
