package magefree.feature.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.feature.cards.rememberCardArtRenderer
import magefree.feature.game.board.GameBoardViewModel
import magefree.feature.game.table.TableArtResolver
import magefree.feature.game.table.TableBoardScreen
import org.koin.androidx.compose.koinViewModel

/*
 * The `:feature:game` entry point, following the same shape `:feature:tables` established:
 * the feature owns its Hilt ViewModel and its stateless screen, and the `:app` navigation graph owns
 * the type-safe route and supplies the navigation callbacks.
 */

/**
 * The playable board for one game.
 *
 * @param gameId the game to observe — in production the id carried by the table's `MatchStarting`
 *   push, which is the only place a game id comes from.
 * @param onExit pops the board.
 * @param modifier the [Modifier] for the board.
 * @param artFor how the board's own cards resolve their art. Supplied by `:app`, which owns the
 *   catalog lookup the resolver needs; null draws name plates, which is what a test sees.
 * @param viewModel the board's ViewModel, injected.
 */
@Composable
fun GameBoardRoute(
    gameId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    viewModel: GameBoardViewModel = koinViewModel(),
) {
    LaunchedEffect(gameId) { viewModel.observe(gameId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The Coil-backed renderer, bound to the app-wide, policy-correct ImageLoader - the
    // same one the card browser and deck builder draw with, so the board shares their disk cache
    // instead of warming a second one. This is the only networked thing on the board.
    val artRenderer = rememberCardArtRenderer()

    TableBoardScreen(
        uiState = uiState,
        onExit = onExit,
        onControlsVisibleChange = viewModel::setControlsVisible,
        onCardTap = viewModel::selectCard,
        // The one seam from a gesture to the server: the screen holds no client and the ViewModel is the
        // only thing that translates an action into a game verb.
        onAction = viewModel::act,
        artRenderer = artRenderer,
        modifier = modifier,
        artFor = artFor,
        onFlipDetailFace = viewModel::flipDetailFace,
    )
}
