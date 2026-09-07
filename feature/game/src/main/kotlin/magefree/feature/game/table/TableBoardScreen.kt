package magefree.feature.game.table

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.CardDetailOverlay
import magefree.feature.game.board.FloatingControls
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.HiddenControlsToggle
import magefree.feature.game.board.JOIN_FAILED_PREFIX
import magefree.feature.game.board.PriorityUi
import magefree.feature.game.board.WAITING_FOR_FIRST_SNAPSHOT
import magefree.feature.game.board.cardFor
import magefree.feature.game.board.cardInAPile

/*
 * The rebuilt board, playing a real game.
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  BattlefieldLayout — rail, lands, battlefields, phase     │  the board
 * │  bar, hand                                                │
 * ├───────────────────────────────────────────────────────────┤
 * │  PlayerOverlay — one seat's piles, on a scrim             │  a look
 * ├───────────────────────────────────────────────────────────┤
 * │  FloatingControls / HiddenControlsToggle                  │  the question
 * ├───────────────────────────────────────────────────────────┤
 * │  CardDetailOverlay — the card, and what may be done to it │  the decision
 * └───────────────────────────────────────────────────────────┘
 * ```
 *
 * **The board draws from the server's snapshot and the prompt surfaces draw from the projection.**
 * Every table-tier model — [battlefieldModel], [tableVitals], [tableZones], [handCards],
 * [playableElsewhere] — is a function of one `GameState`, so the board is derived here rather than
 * carried through the UI state in a second shape that could disagree with the first. The controls,
 * the detail overlay and the priority statement read `GameBoardUiState`'s own projections, which are
 * the ones proven against a live server and are reused here unchanged.
 *
 * **Nothing on this screen decides a rule.** What may be pressed is `PromptControlsUi`, which
 * `controlsFor` projects from `GameState.prompt` alone; what a press *means* is
 * `PromptControlsUi.actionFor`; and every gesture leaves as a [BoardAction] for the ViewModel to
 * translate into one client verb. This screen holds no client.
 *
 * **A press raises a card, and the detail overlay commits it.** One gesture, everywhere — the
 * battlefield, the hand, a land stack, a zone window — because a rule with an exception on one
 * surface is a rule a player has to learn twice.
 *
 * **The controls float in the top-right corner, which is the only corner they can have.** The
 * portrait board put them along the bottom, and that is wrong here for a reason worth stating: the
 * hand is drawn from the bottom edge upward, and pressing a card in the hand is *how a priority
 * prompt is answered*. A panel over the hand covers the answer to its own question. The other three
 * regions are spoken for too — the status rail runs down the left, the phase bar sits on the hand,
 * and the creature rows meet on the centre line, which is the one thing a glance at a battlefield has
 * to be able to read. What is left is the strip above the opponent's non-creature permanents, and
 * that is where it goes.
 *
 * It is not where a thumb naturally rests, and that is the trade this story makes deliberately: 0112
 * borrows the panel that works rather than designing a new one, and where a question *should* live on
 * a board this shape is the next story's question.
 *
 * **Hiding the panel must never hide that the server is waiting**, so the collapsed toggle restates
 * it — the same guarantee the portrait board made, and the reason [HiddenControlsToggle] takes that
 * flag at all.
 */

/**
 * The board for one game.
 *
 * @param uiState the projected snapshot, the outstanding prompt, and the acts in flight.
 * @param onExit leaves the board; also bound to system back, so there is one exit path.
 * @param onControlsVisibleChange shows or hides the floating panel. View state — it sends nothing.
 * @param onCardTap raises a card by its server id, or clears the raised one with null.
 * @param onAction the single seam from a gesture to a server verb.
 * @param artRenderer how the prompt surfaces draw card art — the Coil-backed renderer in production,
 *   the placeholder in previews and hermetic tests.
 * @param modifier the [Modifier] for the screen.
 * @param artFor how the *board* resolves art, which is a different tier and a different request. Null
 *   draws the board's cards as their name plates alone, which is what a test sees.
 * @param onFlipDetailFace peeks at a double-faced card's other side in the detail overlay.
 */
@Composable
fun TableBoardScreen(
    uiState: GameBoardUiState,
    onExit: () -> Unit,
    onControlsVisibleChange: (Boolean) -> Unit,
    onCardTap: (String?) -> Unit,
    onAction: (BoardAction) -> Unit,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onFlipDetailFace: () -> Unit = {},
) {
    val snapshot = uiState.snapshot
    val controls = uiState.controls

    // The seat whose piles are open. Purely a look: it sends nothing and it survives no snapshot, so
    // it is remembered here rather than carried in the UI state.
    var expandedSeat by remember { mutableStateOf<TableVitals?>(null) }

    // Back closes whatever is open over the board, innermost first, before it leaves the board.
    BackHandler(enabled = uiState.selectedObjectId != null) { onCardTap(null) }
    BackHandler(enabled = uiState.selectedObjectId == null && expandedSeat != null) { expandedSeat = null }
    BackHandler(enabled = uiState.selectedObjectId == null && expandedSeat == null, onBack = onExit)

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().testTag(TableBoardTestTags.SCREEN)) {
            if (snapshot != null) {
                val vitals = tableVitals(snapshot)
                BattlefieldLayout(
                    model = battlefieldModel(snapshot),
                    hand = handCards(snapshot),
                    playableElsewhere = playableElsewhere(snapshot),
                    vitals = vitals,
                    onExpandVitals = { seat -> expandedSeat = seat },
                    phases = phaseBarState(snapshot),
                    artFor = artFor,
                    // Every press on a card is the same press: it raises the card. What may then be
                    // done to it is the detail overlay's question, and the server's answer.
                    onInspect = { id -> onCardTap(id) },
                    onPlayFromHand = { id -> onCardTap(id) },
                    // A stack's two halves name two different permanents, and the board says which.
                    // Pressing an upright copy reaches the one a hand would pick up — which, mid-cast,
                    // is the copy whose mana ability pays for the spell. Pressing a turned one reaches
                    // a copy already lying down, so it can be read; there is no untapping at will and
                    // nothing here pretends there is.
                    onLandPress = { stack, half ->
                        val id =
                            when (half) {
                                LandStackHalf.Upright -> stack.tapActionId
                                LandStackHalf.Turned -> stack.tapped.lastOrNull()?.id
                            }
                        onCardTap(id ?: stack.inspectId)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                expandedSeat?.let { seat ->
                    PlayerOverlay(
                        vitals = seat,
                        onDismiss = { expandedSeat = null },
                        zones = tableZones(snapshot).filter { it.playerId == seat.playerId },
                        artFor = artFor,
                        // A card read out of a pile opens the same detail as a card on the
                        // battlefield, so a target the server offered from a graveyard is answerable
                        // from where the player found it.
                        onInspect = { id -> onCardTap(id) },
                        modifier = Modifier.zIndex(SEAT_LAYER_Z),
                    )
                }
            }

            StandingStatements(uiState = uiState, modifier = Modifier.align(Alignment.TopStart))

            // The exit and the question share one anchor and one column, so neither has to know where
            // the other ended up. Explicitly z-ordered rather than left to declaration order, because
            // a control drawn on top but not *hit* on top is a dead button — the failure mode this
            // layout is one edit away from at all times.
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(FLOATING_LAYER_Z)
                        .safeDrawingPadding()
                        .padding(ControlsPadding)
                        .fillMaxWidth(CONTROLS_WIDTH_SHARE),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ControlsPadding),
            ) {
                FilledIconButton(onClick = onExit, modifier = Modifier.testTag(TableBoardTestTags.EXIT)) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = EXIT_BOARD_DESCRIPTION)
                }

                if (uiState.areControlsVisible) {
                    FloatingControls(
                        controls = controls,
                        cast = uiState.cast,
                        declaration = uiState.declaration,
                        actionError = uiState.actionError,
                        artRenderer = artRenderer,
                        onAction = onAction,
                        onHide = { onControlsVisibleChange(false) },
                    )
                } else {
                    HiddenControlsToggle(
                        isServerWaiting =
                            uiState.board.priority is PriorityUi.Yours || uiState.board.priority == PriorityUi.Asked,
                        onShow = { onControlsVisibleChange(true) },
                    )
                }
            }

            // The first press raised the card; this is where it is read and committed.
            //
            // **Two lookups, because the board draws cards the projection does not carry.** `BoardUi`
            // knows the hand, the battlefields and the stack; a seat's piles reach it only as counts,
            // which is all the portrait board drew of them. This board opens the piles and lets a card
            // be pressed in one, so a card out of a graveyard is resolved off the snapshot — through
            // the same conversion the hand goes through, so it reads identically either way. Without
            // it, pressing a card in a graveyard selected it and drew nothing at all.
            uiState.selectedObjectId?.let { objectId ->
                (uiState.board.cardFor(objectId) ?: snapshot?.cardInAPile(objectId))?.let { card ->
                    CardDetailOverlay(
                        card = card,
                        actionLabel = controls?.actionLabelFor(objectId),
                        artRenderer = artRenderer,
                        onCommit = { controls?.actionFor(objectId)?.let(onAction) },
                        onClose = { onCardTap(null) },
                        modifier = Modifier.zIndex(DETAIL_LAYER_Z),
                        detailFace = uiState.detailFace,
                        onFlip = onFlipDetailFace,
                    )
                }
            }
        }
    }
}

/**
 * The statements that must survive whatever else is on screen: that nothing has arrived yet, that a
 * join was declined, and that the game is over.
 *
 * All three say the same kind of thing — *the board you are looking at is not the game you think it
 * is* — and a player who cannot tell a waiting game from a finished one has no way to find out except
 * by leaving. Drawn over the board's own top-left corner, above the status rail, because a board in
 * any of these states has nothing there and an ordinary one has no reason to show any of them.
 *
 * **"Nothing has arrived" is a fact about the snapshot, not about whether there is one.** The
 * subscription opens with an empty `GameState` seed and the flow emits it immediately, so a board
 * that waited for a *null* snapshot would wait forever and say nothing while it did. The server's own
 * `hasSnapshot` is the flag, and it is false on exactly that seed.
 *
 * **The result is the server's own line and nothing is inferred from it.** Upstream's `GAME_OVER`
 * payload is one sentence of prose with no winner id and no reason code, so this shows the sentence.
 * It is stated rather than merely implied by a board that stopped moving: without it a finished game
 * and a stalled one look identical, which is exactly the ambiguity the waiting line exists to remove
 * at the other end of a game.
 */
@Composable
private fun StandingStatements(
    uiState: GameBoardUiState,
    modifier: Modifier = Modifier,
) {
    val board = uiState.board
    if (board.hasSnapshot && uiState.joinError == null && board.resultNotice == null) return
    Column(
        modifier =
            modifier
                .zIndex(FLOATING_LAYER_Z)
                .safeDrawingPadding()
                .padding(horizontal = Spacing.medium)
                .testTag(TableBoardTestTags.STANDING),
    ) {
        if (!board.hasSnapshot) {
            Text(
                text = WAITING_FOR_FIRST_SNAPSHOT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        board.resultNotice?.let { notice ->
            // Louder than the other two, and it should be: the game ending is the one thing on this
            // screen a player must not be able to miss, and the board behind it has stopped changing.
            Text(
                text = notice,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
    }
}

/** Test tags for the board's own layers, which carry no distinctive text of their own. */
object TableBoardTestTags {
    const val SCREEN: String = "table-board"
    const val EXIT: String = "table-board-exit"
    const val STANDING: String = "table-board-standing"
}

/** The exit control's screen-reader description, shared with tests so the two agree. */
const val EXIT_BOARD_DESCRIPTION: String = "Leave the game and return to the app"

/**
 * The z of each layer over the board.
 *
 * A seat's window sits under the question — an outstanding prompt is not something a look should
 * cover — and the card detail sits over everything, because it is where a decision is made.
 */
private const val SEAT_LAYER_Z = 1f

private const val FLOATING_LAYER_Z = 2f

private const val DETAIL_LAYER_Z = 3f

/**
 * How much of the width the floating column takes.
 *
 * Wide enough for the panel's own buttons and its row of candidate cards, narrow enough that the
 * board's right-hand rows are still readable beside it — the opponent's non-creature permanents sit
 * under this corner, and they are the permanents a player checks rather than watches.
 */
private const val CONTROLS_WIDTH_SHARE = 0.44f

private val ControlsPadding = 8.dp
