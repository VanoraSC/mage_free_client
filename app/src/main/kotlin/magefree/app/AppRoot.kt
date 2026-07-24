package magefree.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.app.navigation.AppNavHost
import magefree.designsystem.theme.MageTheme

/**
 * Application root: hosts the [AppNavHost] — the **root** Navigation-Compose graph that switches
 * between the tabbed shell (story 0008) and the immersive full-screen game route (story 0011). The
 * shell's bottom-bar/rail chrome lives inside the shell, so the game route renders without it.
 *
 * The [MageTheme] wrapper is supplied by the caller ([MainActivity] and the previews below), keeping
 * `AppRoot` a thin, theme-agnostic entry point consistent with the 0007 structure.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    AppNavHost(modifier = modifier)
}

@Preview(name = "AppRoot — light", showBackground = true)
@Preview(name = "AppRoot — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRootPreview() {
    MageTheme {
        AppRoot()
    }
}
