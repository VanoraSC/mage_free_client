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

/** Text rendered by [HomePlaceholderScreen]; shared with tests so the two agree. */
const val HOME_SCREEN_LABEL: String = "Home"

/**
 * Placeholder for the Home/Play destination. Story 0008 delivers only the shell — the Arena-style
 * home hub and the prominent "Play" CTA arrive in 0009. This just names itself so navigation is
 * observable.
 */
@Composable
fun HomePlaceholderScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = HOME_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(name = "Home — light", showBackground = true)
@Preview(name = "Home — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomePlaceholderScreenPreview() {
    MageTheme { HomePlaceholderScreen() }
}
