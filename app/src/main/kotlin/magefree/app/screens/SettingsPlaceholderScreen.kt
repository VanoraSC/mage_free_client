package magefree.app.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.app.theme.MageTheme

/** Text rendered by [SettingsPlaceholderScreen]; shared with tests so the two agree. */
const val SETTINGS_SCREEN_LABEL: String = "Settings"

/**
 * Placeholder for the Settings destination. Real preferences (DataStore-backed) arrive later; story
 * 0008 only proves the shell can reach this route.
 */
@Composable
fun SettingsPlaceholderScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = SETTINGS_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(name = "Settings — light", showBackground = true)
@Preview(name = "Settings — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPlaceholderScreenPreview() {
    MageTheme { SettingsPlaceholderScreen() }
}
