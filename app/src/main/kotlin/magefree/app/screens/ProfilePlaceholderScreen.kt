package magefree.app.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.theme.MageTheme

/** Text rendered by [ProfilePlaceholderScreen]; shared with tests so the two agree. */
const val PROFILE_SCREEN_LABEL: String = "Profile"

/**
 * Placeholder for the Profile/Social destination. Presence, friends, and account content arrive in
 * their owning epics; this route exists so the shell can reach it.
 */
@Composable
fun ProfilePlaceholderScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = PROFILE_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(name = "Profile — light", showBackground = true)
@Preview(name = "Profile — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ProfilePlaceholderScreenPreview() {
    MageTheme { ProfilePlaceholderScreen() }
}
