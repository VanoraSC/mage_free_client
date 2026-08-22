package magefree.feature.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.feature.cards.rememberCardArtRenderer
import magefree.feature.game.board.GameBoardScreen
import magefree.feature.game.board.GameBoardViewModel

/*
 * The `:feature:game` entry point (story 0055), following the same shape `:feature:tables` established:
 * the feature owns its Hilt ViewModel and its stateless screen, and the `:app` navigation graph owns
 * the type-safe route and supplies the navigation callbacks.
 */

/**
 * The playable board for one game.
 *
 * @param gameId the game to observe — in production the id carried by the table's `MatchStarting`
 *   push, which is the only place a game id comes from (story 0051/0037).
 * @param onExit pops the board.
 */
@Composable
fun GameBoardRoute(
    gameId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GameBoardViewModel = hiltViewModel(),
) {
    LaunchedEffect(gameId) { viewModel.observe(gameId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Story 0031/0032's Coil-backed renderer, bound to the app-wide, policy-correct ImageLoader — the
    // same one the card browser and deck builder draw with, so the board shares their disk cache
    // instead of warming a second one. This is the only networked thing on the board.
    val artRenderer = rememberCardArtRenderer()

    GameBoardScreen(
        uiState = uiState,
        onExit = onExit,
        onHandExpandedChange = viewModel::setHandExpanded,
        onControlsVisibleChange = viewModel::setControlsVisible,
        onCardTap = viewModel::selectCard,
        // The one seam from a gesture to the server: the screen holds no client and the ViewModel is the
        // only thing that translates an action into a game verb (story 0057).
        onAction = viewModel::act,
        artRenderer = artRenderer,
        modifier = modifier,
        onFlipDetailFace = viewModel::flipDetailFace,
    )
}
