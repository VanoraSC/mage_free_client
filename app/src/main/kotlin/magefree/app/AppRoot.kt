package magefree.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import magefree.app.connection.SessionViewModel
import magefree.app.navigation.AppNavHost
import magefree.designsystem.theme.MageTheme
import org.koin.androidx.compose.koinViewModel

/**
 * Application root: hosts the [AppNavHost] — the **root** Navigation-Compose graph that switches
 * between the connect/sign-in flow (story 0047), the tabbed shell (story 0008) and the immersive
 * full-screen game route (story 0011). The shell's bottom-bar/rail chrome lives inside the shell, so
 * the connect and game routes render without it.
 *
 * This is where the graph's hoisted session teardown is bound to the real thing: [SessionViewModel]
 * over `ConnectionRepository.signOut()`. Resolving it here — the one composable that only production
 * uses — is what keeps [AppNavHost] and [magefree.app.navigation.AppShell] drivable **without a Hilt
 * graph** in the shell's navigation tests (the 0035/0038 pattern), while production still gets a real
 * sign-out rather than a no-op default.
 *
 * The [MageTheme] wrapper is supplied by the caller ([MainActivity]), keeping `AppRoot` a thin,
 * theme-agnostic entry point consistent with the 0007 structure.
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val sessionViewModel: SessionViewModel = koinViewModel()
    AppNavHost(
        modifier = modifier,
        onSignOut = sessionViewModel::signOut,
    )
}
