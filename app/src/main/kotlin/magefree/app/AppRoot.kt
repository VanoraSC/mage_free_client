package magefree.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import magefree.app.theme.MageTheme

/**
 * Placeholder application root. This is intentionally the seam that story 0008 replaces with the
 * Navigation-Compose host — for now it just renders a centered title so the scaffold, theme, and
 * edge-to-edge insets can be verified end to end.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = APP_ROOT_PLACEHOLDER,
                modifier = Modifier.semantics { contentDescription = APP_ROOT_PLACEHOLDER },
            )
        }
    }
}

/** Placeholder text asserted by the Compose smoke test; kept as a constant so the two agree. */
const val APP_ROOT_PLACEHOLDER: String = "Mage Free Client"

@Preview(name = "AppRoot — light", showBackground = true)
@Preview(name = "AppRoot — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootPreview() {
    MageTheme {
        AppRoot()
    }
}
