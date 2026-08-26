package magefree.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.theme.MageTheme

/** Text rendered by [DecksPlaceholderScreen]; shared with tests so the two agree. */
const val DECKS_SCREEN_LABEL: String = "Decks"

/** Label on the entry into the card catalog browser. */
const val DECKS_BROWSE_CARDS_LABEL: String = "Browse cards"

/**
 * Placeholder for the Decks destination. The touch-first deck builder is owned by its feature epic
 * this route exists so the shell can reach it. A read-only entry
 * into the card catalog browser via [onBrowseCards].
 */
@Composable
fun DecksPlaceholderScreen(
    modifier: Modifier = Modifier,
    onBrowseCards: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(text = DECKS_SCREEN_LABEL, style = MaterialTheme.typography.headlineMedium)
        MageSecondaryButton(text = DECKS_BROWSE_CARDS_LABEL, onClick = onBrowseCards)
    }
}

@Preview(name = "Decks — light", showBackground = true)
@Preview(name = "Decks — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DecksPlaceholderScreenPreview() {
    MageTheme { DecksPlaceholderScreen() }
}
