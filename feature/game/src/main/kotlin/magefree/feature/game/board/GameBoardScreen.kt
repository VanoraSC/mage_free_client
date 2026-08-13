package magefree.feature.game.board

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.TurnPhase

/**
 * The **read-only portrait board** (story 0055).
 *
 * ## The layout, and why it is this one
 *
 * ```
 * ┌──────────────────────────────┐
 * │ Game board            (back) │  slim top bar
 * ├──────────────────────────────┤
 * │ 20  Opponent   Lib 53 Hand 6 │  opponent vitals   (fixed)
 * ├──────────────────────────────┤
 * │      opponent battlefield    │  band              (weight)
 * ├──────────────────────────────┤
 * │ Turn 3 · Main 1 · your turn  │
 * │ Your turn to act             │  STATUS RAIL       (fixed)
 * │ Stack (1) · as last pushed   │
 * ├──────────────────────────────┤
 * │        your battlefield      │  band              (weight)
 * ├──────────────────────────────┤
 * │ 20  You        Lib 53 Hand 7 │  your vitals       (fixed)
 * ├──────────────────────────────┤
 * │ Server asks: … / narration   │  notice strip      (fixed)
 * ├──────────────────────────────┤
 * │ In hand 7  ▮▮▮▮▮▮▮ Show hand │  hand peek         (fixed)
 * └──────────────────────────────┘
 * ```
 *
 * - **Opponent above, you below** (§3.1) — unchanged by the portrait revision, and it reads better in
 *   portrait than it did in landscape. Both seats are found via `GamePlayer.isViewer`, never by index.
 * - **§4.1's side panel becomes a centre band.** See [StatusRail] for the full argument: portrait
 *   inverts which axis is scarce, so an edge panel would tax both battlefields' width forever for a
 *   region that is empty most of the game. The band costs the scarce axis once, and puts the stack
 *   where a table puts it.
 * - **Everything except the two battlefield bands has a fixed height.** The stack fills abruptly, and a
 *   prompt arrives abruptly; neither may reflow the battlefields, so both live in reserved space
 *   ([StatusRailHeight], [NOTICE_STRIP_HEIGHT]) that is the same size empty or full.
 * - **The hand is peek-and-expand** (§3.2): the peek edge is part of the column, and the expanded hand
 *   is drawn *over* the board so opening it costs no battlefield height.
 *
 * ## Read-only, stated and enforced
 *
 * The board offers **no way to act**. There is exactly one interactive element on the whole screen —
 * the hand's show/hide toggle — and it changes how much of the player's *own* hand is drawn, sending
 * nothing. No card is clickable; the outstanding prompt is rendered as text with no control attached;
 * there is no pass-priority. [READ_ONLY_NOTICE] says so out loud, because a player who cannot tell "the
 * app won't let me" from "the game isn't asking me" would read this board as broken.
 *
 * @param artRenderer how card art is drawn. Production passes 0032's Coil-backed renderer (see
 *   `GameBoardRoute`); previews and hermetic tests pass [PlaceholderCardArtRenderer], so no test or
 *   preview ever loads a network image.
 */
@Composable
fun GameBoardScreen(
    uiState: GameBoardUiState,
    onExit: () -> Unit,
    onHandExpandedChange: (Boolean) -> Unit,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    val board = uiState.board
    // Back closes the expanded hand before it leaves the board.
    BackHandler(enabled = uiState.isHandExpanded) { onHandExpandedChange(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = BOARD_TITLE,
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                StandingHeader(uiState = uiState)
                SeatVitalsBar(seat = board.opponentSeats.firstOrNull(), fallbackLabel = OPPONENT_SEAT_LABEL)
                BattlefieldBand(
                    seat = board.opponentSeats.firstOrNull(),
                    artRenderer = artRenderer,
                    modifier = Modifier.weight(1f),
                )
                StatusRail(board = board, artRenderer = artRenderer)
                BattlefieldBand(
                    seat = board.viewerSeat,
                    artRenderer = artRenderer,
                    modifier = Modifier.weight(1f),
                )
                SeatVitalsBar(seat = board.viewerSeat, fallbackLabel = VIEWER_SEAT_LABEL)
                NoticeStrip(board = board)
                HandPeek(
                    hand = board.hand,
                    expanded = uiState.isHandExpanded,
                    modifier = Modifier.clickable { onHandExpandedChange(!uiState.isHandExpanded) },
                )
            }

            // The expanded hand floats over the board rather than displacing it (§16.1: height is the
            // scarce axis in portrait, so nothing may take it twice).
            if (uiState.isHandExpanded) {
                ExpandedHand(
                    hand = board.hand,
                    artRenderer = artRenderer,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = HandPeekHeight),
                )
            }
        }
    }
}

/**
 * The two standing statements that must survive whatever else is on screen: that this board cannot be
 * acted on, and — before the first snapshot — that nothing has arrived yet.
 *
 * Requirements §16.3 makes the equivalent point about hiding floating controls: whatever is hidden, the
 * player must never be left unable to tell a waiting game from a frozen one.
 */
@Composable
private fun StandingHeader(
    uiState: GameBoardUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.medium)) {
        if (!uiState.board.hasSnapshot) {
            Text(
                text = WAITING_FOR_FIRST_SNAPSHOT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        uiState.joinError?.let { reason ->
            Text(
                text = "$JOIN_FAILED_PREFIX $reason",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = READ_ONLY_NOTICE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Previews ------------------------------------------------------------------------------------
//
// Previews use [PlaceholderCardArtRenderer]: the design system's rule is that a preview never loads a
// network image, and it holds here too.

private fun previewCard(
    id: String,
    name: String,
    typeLine: String,
    manaCost: String? = null,
) = GameCard(
    id = id,
    name = name,
    setCode = "M21",
    collectorNumber = "272",
    manaCost = manaCost,
    typeLine = typeLine,
)

private fun previewState(): GameState =
    GameState(
        gameId = "g-1",
        turn = 3,
        phase = TurnPhase.PrecombatMain,
        step = PhaseStep.PrecombatMain,
        activePlayerId = "p-you",
        activePlayerName = "You",
        priorityPlayerName = "You",
        viewerPlayerId = "p-you",
        viewerHasPriority = true,
        hasSnapshot = true,
        players =
            listOf(
                // Deliberately opponent-first: player order is not viewer-first, and the board must not
                // care (it locates seats by `isViewer`).
                GamePlayer(
                    playerId = "p-opp",
                    name = "Computer",
                    life = 18,
                    libraryCount = 51,
                    handCount = 5,
                    isHuman = false,
                    battlefield =
                        listOf(
                            GamePermanent(card = previewCard("o-1", "Forest", "Basic Land — Forest"), isTapped = true),
                            GamePermanent(
                                card = previewCard("o-2", "Grizzly Bears", "Creature — Bear", "1G"),
                                hasSummoningSickness = true,
                            ),
                        ),
                ),
                GamePlayer(
                    playerId = "p-you",
                    name = "You",
                    life = 20,
                    libraryCount = 53,
                    handCount = 6,
                    isViewer = true,
                    isActive = true,
                    hasPriority = true,
                    battlefield = listOf(GamePermanent(card = previewCard("y-1", "Forest", "Basic Land — Forest"))),
                ),
            ),
        hand =
            listOf(
                previewCard("h-1", "Forest", "Basic Land — Forest"),
                previewCard("h-2", "Llanowar Elves", "Creature — Elf Druid", "G"),
            ),
        stack = listOf(previewCard("s-1", "Giant Growth", "Instant", "G")),
    )

@Preview(name = "Board — portrait (light)", showBackground = true, widthDp = 411, heightDp = 891)
@Preview(
    name = "Board — portrait (dark)",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GameBoardPreview() {
    MageTheme {
        GameBoardScreen(
            uiState = GameBoardUiState(board = BoardUi.from(previewState()), isJoining = false),
            onExit = {},
            onHandExpandedChange = {},
            artRenderer = PlaceholderCardArtRenderer,
        )
    }
}

@Preview(name = "Board — first snapshot, empty everywhere", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardEmptyPreview() {
    MageTheme {
        GameBoardScreen(
            uiState = GameBoardUiState(board = BoardUi(gameId = "g-1")),
            onExit = {},
            onHandExpandedChange = {},
            artRenderer = PlaceholderCardArtRenderer,
        )
    }
}

@Preview(name = "Board — hand expanded", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardExpandedHandPreview() {
    MageTheme {
        GameBoardScreen(
            uiState = GameBoardUiState(board = BoardUi.from(previewState()), isJoining = false, isHandExpanded = true),
            onExit = {},
            onHandExpandedChange = {},
            artRenderer = PlaceholderCardArtRenderer,
        )
    }
}

