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

/** Text rendered by [DecksPlaceholderScreen]; shared with tests so the two agree. */
const val DECKS_SCREEN_LABEL: String = "Decks"

/**
 * Placeholder for the Decks destination. The touch-first deck builder is owned by its feature epic;
 * story 0008 only proves the shell can reach this route.
 */
@Composable
fun DecksPlaceholderScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = DECKS_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)
    }
}

@Preview(name = "Decks — light", showBackground = true)
@Preview(name = "Decks — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DecksPlaceholderScreenPreview() {
    MageTheme { DecksPlaceholderScreen() }
}
