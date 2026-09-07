package magefree.app.di

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import magefree.app.MageApp
import magefree.app.navigation.CardsRoute
import magefree.app.navigation.DecksRoute
import magefree.app.navigation.HostTableNavRoute
import magefree.app.navigation.JoinTableNavRoute
import magefree.app.navigation.LobbyRoute
import magefree.app.navigation.MageNavHost
import magefree.app.navigation.TableRoomNavRoute
import magefree.designsystem.theme.MageTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import magefree.feature.game.GameBoardRoute as GameBoardFeatureRoute

/**
 * **Every `koinViewModel()` call site, resolved for real.**
 *
 * [KoinGraphTest] proves each declared binding can be *instantiated*, but it resolves through
 * `koin.get<T>()`. Composables reach their ViewModels through `koinViewModel()`, which is a
 * different path — Koin's ViewModel factory plus the `ViewModelStoreOwner` — so a `viewModel { }`
 * declaration that is wrong in a way specific to that path would pass the graph test and still crash
 * the screen. This test closes the difference by **rendering the destination**.
 *
 * It matters because the sibling Compose tests cannot cover it: `CardSearchTypingTest`,
 * `AddCardsTypingTest` and `TableBoardScreenTest` all construct their state directly and pass it in —
 * deliberately, since they test typing and rendering rather than wiring. Nothing else in
 * the suite exercises `koinViewModel()` at all, which left 18 call sites verified only by opening
 * each screen by hand.
 *
 * `@Config(application = MageApp::class)` is what makes this real rather than a mock-up: Robolectric
 * runs the production `Application`, so the container under test is the one `startKoin` builds from
 * [appModules] — not a list assembled here.
 *
 * **What this does not do** is replace the eyes-on pass. It answers "does the screen resolve its
 * dependencies and compose without throwing", not "does it look right" — art, layout and the feel of
 * an interaction still need a device. It converts a checklist item from the only line of defence
 * into a second one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MageApp::class, qualifiers = "w411dp-h891dp")
class ScreenViewModelResolutionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** See [KoinGraphTest.tearDown] — the global container outlives a Robolectric test method. */
    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Every shell destination that resolves at least one ViewModel through `koinViewModel()`.
     * Arguments are placeholders: nothing here talks to a server, and the point is that the
     * destination's ViewModels *resolve and construct*, which happens before any of them is used.
     */
    private val destinationsThatResolveViewModels =
        listOf<Pair<String, Any>>(
            "lobby (LobbyViewModel)" to LobbyRoute,
            "cards (CardSearch/CardInspection/CardArtSettings)" to CardsRoute,
            "decks (Library/Builder)" to DecksRoute,
            "host table (HostTableViewModel)" to HostTableNavRoute,
            "join table (JoinTableViewModel)" to
                JoinTableNavRoute(
                    tableId = "t-1",
                    tableName = "table",
                    gameType = "Two Player Duel",
                    deckType = "Constructed - Standard",
                    passworded = false,
                ),
            "table room (TableRoomViewModel)" to
                TableRoomNavRoute(
                    tableId = "t-1",
                    tableName = "table",
                    gameType = "Two Player Duel",
                    role = "Spectator",
                ),
        )

    @Test
    fun `every screen resolves its view models through koinViewModel`() {
        lateinit var navController: TestNavHostController

        composeTestRule.setContent {
            val context = LocalContext.current
            val nav =
                remember {
                    TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
                }
            navController = nav
            MageTheme {
                Column {
                    MageNavHost(navController = nav, modifier = Modifier.weight(1f))
                    // **The board, composed directly rather than navigated to.** 0112 moved it out to
                    // the root graph, so the loop below — which drives the shell's own host — can no
                    // longer reach it, and dropping it would have quietly retired the only coverage
                    // `GameBoardViewModel`'s `koinViewModel()` call site has. Composing the route is
                    // what this test is actually about; that the root graph mounts it is
                    // `FeatureDestinationWiringTest`'s question, and it asks it.
                    //
                    // It is in the same composition rather than in a class of its own because Koin's
                    // container is process-wide: a second Robolectric class calling `stopKoin` closes
                    // it under whichever of the two runs second, whichever order they are in.
                    GameBoardFeatureRoute(gameId = "g-1", onExit = {}, modifier = Modifier.height(1.dp))
                }
            }
        }
        composeTestRule.waitForIdle()

        // Each destination fails the test on its own rather than being collected into a summary.
        // Collecting was tried and is a trap here: a composition that throws also re-throws from the
        // Compose rule's teardown, so JUnit reports *that* and the summary never prints — dead code
        // that looks like diagnostics. Failing at the first bad screen keeps attribution honest.
        destinationsThatResolveViewModels.forEach { (name, route) ->
            try {
                composeTestRule.runOnUiThread { navController.navigate(route) }
                composeTestRule.waitForIdle()
            } catch (error: Throwable) {
                throw AssertionError(
                    "$name could not resolve its ViewModel(s) through koinViewModel(). This is the shape " +
                        "a missing `viewModel { }` declaration takes: the graph resolves, the app compiles, " +
                        "and the screen crashes the moment it is opened.",
                    error,
                )
            }
        }
    }
}
