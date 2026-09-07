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
 * Visible label on the developer entry into the design-system component catalog. Shared
 * with tests so the two agree; clearly marked as a dev-only affordance.
 */
const val OPEN_CATALOG_STUB_LABEL: String = "Component catalog (dev)"

/**
 * Visible label on the sign-out action. Shared with tests so the two agree. This is the
 * shell's only deliberate exit from a session: it ends the session (the `signOut()`, which
 * sends `Logout` rather than letting the bridge park the session for a resume) and returns the player
 * to the connect flow, from which they can sign in again.
 */
const val SIGN_OUT_LABEL: String = "Sign out"

/**
 * Placeholder for the Settings destination. Real preferences (DataStore-backed) arrive later;
 * only proves the shell can reach this route.
 *
 * It carried a dev stub into the immersive game placeholder until 0112, which said the stub would go
 * once real entry from the lobby and table flows landed. It has: the table room's match-start signal
 * opens the board, and the placeholder the stub reached no longer exists.
 */
@Composable
fun SettingsPlaceholderScreen(
    modifier: Modifier = Modifier,
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

        // Debug-only affordance to open the design-system component catalog — a visual-QA
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

        // the deliberate way out of a session. Real preferences will re-home this when the
        // Settings screen stops being a placeholder; what matters is that the shell has *an* exit at
        // all — without it the app can neither sign in nor sign out from a running APK.
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
