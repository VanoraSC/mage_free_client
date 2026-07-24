package magefree.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.app.navigation.AppShell
import magefree.app.theme.MageTheme

/**
 * Application root: hosts the [AppShell] — the Navigation-Compose shell with the four top-level
 * destinations and adaptive bottom-bar/rail chrome (story 0008). It replaces the 0007 placeholder
 * that this seam previously rendered.
 *
 * The [MageTheme] wrapper is supplied by the caller ([MainActivity] and the previews below), keeping
 * `AppRoot` a thin, theme-agnostic entry point consistent with the 0007 structure.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    AppShell(modifier = modifier)
}

@Preview(name = "AppRoot — light", showBackground = true)
@Preview(name = "AppRoot — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootPreview() {
    MageTheme {
        AppRoot()
    }
}
