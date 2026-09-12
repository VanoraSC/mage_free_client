package magefree.feature.game.table

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import magefree.designsystem.card.CardPreview
import magefree.designsystem.card.CardPreviewFlip
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.feature.game.board.BOARD_MENU_LABEL
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.CONCEDE_CONFIRM_LABEL
import magefree.feature.game.board.CONCEDE_LABEL
import magefree.feature.game.board.FLIP_FACE_LABEL
import magefree.feature.game.board.FULL_CONTROL_LABEL
import magefree.feature.game.board.FloatingControls
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.HiddenControlsToggle
import magefree.feature.game.board.JOIN_FAILED_PREFIX
import magefree.feature.game.board.PriorityUi
import magefree.feature.game.board.QUIT_MATCH_CONFIRM_LABEL
import magefree.feature.game.board.QUIT_MATCH_LABEL
import magefree.feature.game.board.TurnSide
import magefree.feature.game.board.WAITING_FOR_FIRST_SNAPSHOT

/*
 * The rebuilt board, playing a real game.
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  BattlefieldLayout — left rail, lands, battlefields, hand │  the board
 * ├───────────────────────────────────────────────────────────┤
 * │  ZoneViewer — piles, ordered, on a scrim                  │  a look
 * │  PlayerOverlay — one seat's piles, on a scrim             │  a look
 * ├───────────────────────────────────────────────────────────┤
 * │  FloatingControls / HiddenControlsToggle                  │  the question
 * ├───────────────────────────────────────────────────────────┤
 * │  CardPreview — the card, everything on it, and what may be done │  the decision
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
 * **A press raises a card, and the preview commits it.** One gesture, everywhere — the
 * battlefield, the hand, a land stack, a zone window — because a rule with an exception on one
 * surface is a rule a player has to learn twice.
 *
 * **The controls float in the top-right corner, which is the only corner they can have.** The
 * portrait board put them along the bottom, and that is wrong here for a reason worth stating: the
 * hand is drawn from the bottom edge upward, and pressing a card in the hand is *how a priority
 * prompt is answered*. A panel over the hand covers the answer to its own question. The other three
 * regions are spoken for too — the left rail runs down the whole left edge, the hand fills the bottom,
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
 * @param onPressStop cycles the stop on one step of the rail, on one side of the turn.
 * @param onSetFullControl turns Full Control on or off — see `BoardStops.fullControl`.
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
    onPressStop: (TurnSide, String) -> Unit = { _, _ -> },
    onSetFullControl: (Boolean) -> Unit = {},
) {
    val snapshot = uiState.snapshot
    val controls = uiState.controls

    // The seat whose piles are open. Purely a look: it sends nothing and it survives no snapshot, so
    // it is remembered here rather than carried in the UI state.
    var expandedSeat by remember { mutableStateOf<TableVitals?>(null) }

    // Which piles are open, and empty for none. One is a graveyard pressed on the rail; several is a
    // seat's counts pressed, which opens everything behind them at once. Held as the piles themselves
    // rather than as ids, because a pile is what the viewer draws.
    var openPiles by remember { mutableStateOf(emptyList<TableZonePile>()) }

    // **What a press on a card does.** Ordinarily it raises the card, and the raised card is where the
    // act is committed — one gesture everywhere, and a look at what you are about to do. While a cost
    // is being paid it commits directly: the player is tapping their own lands, several in a row, in
    // the middle of casting something else, and raising each one to press a second button turns four
    // mana into eight presses and four things to dismiss.
    //
    // **The exception is the prompt's own, not this screen's.** `PromptControlsUi.answersOnPress` is
    // true for mana payment and false everywhere else, so the rule lives with the thing that knows
    // what a press means. And nothing is skipped: a land with two mana abilities is a real choice, and
    // upstream asks it — `playManaAbility` sends its own prompt when there is more than one.
    val press: (String) -> Unit = { id ->
        val direct = controls?.takeIf { it.answersOnPress }?.actionFor(id)
        if (direct != null) onAction(direct) else onCardTap(id)
    }

    // Back closes whatever is open over the board, innermost first, before it leaves the board.
    BackHandler(enabled = uiState.selectedObjectId != null) { onCardTap(null) }
    BackHandler(enabled = uiState.selectedObjectId == null && openPiles.isNotEmpty()) { openPiles = emptyList() }
    BackHandler(enabled = uiState.selectedObjectId == null && openPiles.isEmpty() && expandedSeat != null) { expandedSeat = null }
    BackHandler(
        enabled = uiState.selectedObjectId == null && openPiles.isEmpty() && expandedSeat == null,
        onBack = onExit,
    )

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().testTag(TableBoardTestTags.SCREEN)) {
            if (snapshot != null) {
                val vitals = tableVitals(snapshot)
                // What the outstanding question is about, so the board can draw it. Without this a
                // prompt whose candidates are permanents draws a board indistinguishable from one with
                // nothing pending — see `PromptPicks`.
                val picks = controls.boardPicks()
                // What this client has been shown, kept across snapshots because the server clears
                // its reveals on the next update and will not say it twice. See [KnownHand].
                val seenCards = uiState.seenCards
                // Every seat's piles, built once: the rail draws each graveyard's top card from them
                // and a press opens one of them whole, so the card on the rail and the card at the top
                // of the opened list cannot disagree about which it is.
                val zones = tableZones(snapshot, picks, seenCards)
                // What this snapshot moved between zones, against the one before it — see [ZoneFlights].
                val zoneMoves = rememberZoneMoves(snapshot)

                BattlefieldLayout(
                    model = battlefieldModel(snapshot, picks),
                    // What the board is about, which decides which signal a card emphasises. Combat
                    // above all: an attacking creature and a merely tapped one both lean, and only
                    // one of them is in combat.
                    focus = boardFocus(snapshot),
                    hand = handCards(snapshot, picks),
                    playableElsewhere = playableElsewhere(snapshot),
                    vitals = vitals,
                    onExpandVitals = { seat -> expandedSeat = seat },
                    // A player is answered like any other target: by pressing the thing on the board
                    // that is them. Their id is what upstream targets them by, so it is sent as-is.
                    lifeTotals = lifeTotals(vitals, picks),
                    onPickPlayer = { playerId -> onAction(BoardAction.ChooseTarget(playerId)) },
                    // What has been seen of the one opponent's hand, drawn along their own edge.
                    // Empty for a multiplayer game, where the inference does not hold — see [KnownHand].
                    opponentHand =
                        snapshot.players
                            .firstOrNull { !it.isViewer }
                            ?.let { seenCards.knownHandFor(snapshot, it.playerId) }
                            ?: KnownHand(),
                    phases = phaseRailState(snapshot, stops = uiState.stops),
                    // Each seat's graveyard, drawn at its own end of the rail — the top card, which is
                    // what a graveyard looks like on a table and the one card most worth seeing.
                    zones = zones,
                    onOpenPiles = { piles -> openPiles = piles },
                    stack = tableStack(snapshot),
                    // *Show battlefield* takes the stack with it. It is the one layer left over the
                    // board once the panel is gone, and what it covers is exactly what the player
                    // pressed the control to reach — a permanent the question is about. One control,
                    // two states: the battlefield, or the question.
                    stackVisible = uiState.areControlsVisible,
                    combat = snapshot.combat,
                    zoneMoves = zoneMoves,
                    // A press on a step cycles its stop for the turn being played. What that then does
                    // is the pass policy's, which reads the same store this writes.
                    onToggleStop = { step, side -> onPressStop(side.asTurnSide(), step.id) },
                    artFor = artFor,
                    // Every press on a card is the same press: it raises the card. What may then be
                    // done to it is the preview's question, and the server's answer — except while a
                    // cost is being paid, where the press *is* the answer. See [press].
                    onInspect = press,
                    onPlayFromHand = press,
                    // **A drag out of the hand is the commit.** It does what the raised card's button
                    // would — the prompt's own `actionFor`, so it is Play in a priority window and a pick
                    // in a target question — without raising the card first. The hand only offers the
                    // drag on a card the server marked playable; should the question have moved on since,
                    // it falls back to the look rather than sending something the server no longer offers.
                    onDragFromHand = { id -> controls?.actionFor(id)?.let(onAction) ?: onCardTap(id) },
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
                        press(id ?: stack.inspectId)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // **The piles, opened and scrolled, in the server's own order.** A graveyard is not a
                // set — what died last is on top — and half the reason to open one is to answer *what
                // just went there*. A graveyard arrives here on its own from the rail; a press on the
                // counts brings everything behind them — exile always, and whatever else holds cards.
                if (openPiles.isNotEmpty()) {
                    ZoneViewer(
                        piles = openPiles,
                        onDismiss = { openPiles = emptyList() },
                        artFor = artFor,
                        onInspect = { id -> onCardTap(id) },
                        modifier = Modifier.zIndex(SEAT_LAYER_Z),
                    )
                }

                expandedSeat?.let { seat ->
                    PlayerOverlay(
                        vitals = seat,
                        onDismiss = { expandedSeat = null },
                        zones = zones.filter { it.playerId == seat.playerId },
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

            // **The question takes the screen when the screen is where it is answered.** A prompt that
            // carries its own cards — a library search, a pile — is answered by pressing one of them,
            // so covering the board costs nothing and the collapse control is there for the moment the
            // player wants to look. A prompt answered *on* the board keeps its corner, because the
            // answer to a priority prompt is a card in the hand and a panel over the hand covers the
            // answer to its own question.
            val answeredHere = controls != null && controls.candidateCards.isNotEmpty()

            // The menu and the question share one anchor and one column, so neither has to know where
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
                        .fillMaxWidth(if (answeredHere) 1f else CONTROLS_WIDTH_SHARE),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ControlsPadding),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ControlsPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.stops.fullControl) FullControlBadge()
                    BoardCornerMenu(
                        onExit = onExit,
                        onAction = onAction,
                        fullControl = uiState.stops.fullControl,
                        onSetFullControl = onSetFullControl,
                    )
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
                        // A card the prompt carried is raised, not answered: the same gesture every
                        // other card on this board answers to, and the only one that gives a card being
                        // read for the first time room to be read.
                        onRaiseCandidate = { objectId -> onCardTap(objectId) },
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
            // **The board's own preview, not the portrait board's detail overlay.** What a player wants
            // from a raised permanent is what it is *now* — the abilities it has after layers, and what
            // is attached to it, with the text of each — and only the board's own model carries those.
            // The overlay this replaces took a projection that knows a card and not a permanent, so an
            // enchanted creature opened with no mention of the Aura that is the reason it is not
            // attacking.
            uiState.selectedObjectId?.let { objectId ->
                snapshot?.let { state ->
                    // **A planeswalker of the viewer's is its abilities, as buttons** — see [abilityButtons].
                    // Every loyalty ability is drawn, always; the ones the server is offering can be pressed,
                    // and only while a press on the planeswalker would be a Play, which is the question its
                    // abilities answer. An opponent's is read as text: none of its buttons could ever work.
                    val planeswalkerButtons =
                        state.players
                            .filter { it.isViewer }
                            .flatMap { it.battlefield }
                            .firstOrNull { it.card.id == objectId }
                            ?.let { permanent ->
                                abilityButtons(
                                    card = permanent.card,
                                    playable = state.playable,
                                    activatable = controls?.actionFor(objectId) is BoardAction.PlayObject,
                                )
                            }.orEmpty()
                    raisedCard(
                        objectId = objectId,
                        snapshot = state,
                        model = battlefieldModel(state),
                        stack = tableStack(state),
                        candidates = controls?.candidateCards.orEmpty(),
                        actionLabel = controls?.actionLabelFor(objectId),
                        onAct = { controls?.actionFor(objectId)?.let(onAction) },
                    )?.let { plain ->
                        val raised =
                            plain.copy(
                                state =
                                    plain.state.withAbilityButtons(planeswalkerButtons) { abilityId ->
                                        onAction(BoardAction.ActivateAbility(objectId = objectId, abilityId = abilityId))
                                    },
                            )
                        // The peek at a double-faced card's other side, carried through unchanged from
                        // the overlay this replaced. It is offered only where the catalog says there
                        // *is* another face, and it is local: which face the object is actually showing
                        // stays the server's answer.
                        val face = uiState.detailFace
                        val shown =
                            if (face == null) {
                                raised
                            } else {
                                raised.copy(
                                    state = raised.state.copy(card = raised.state.card.copy(name = face.displayName)),
                                    art = raised.art?.copy(face = face.face),
                                )
                            }
                        CardPreview(
                            state =
                                shown.state.copy(
                                    flip =
                                        face
                                            ?.takeIf { it.canFlip }
                                            ?.let { CardPreviewFlip(label = FLIP_FACE_LABEL, onFlip = onFlipDetailFace) },
                                ),
                            onDismiss = { onCardTap(null) },
                            art = artFor?.invoke(shown.art, shown.state.card),
                            heightShare = DETAIL_HEIGHT_SHARE,
                            modifier = Modifier.zIndex(DETAIL_LAYER_Z),
                        )
                    }
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
    const val MENU: String = "table-board-menu"
    const val STANDING: String = "table-board-standing"

    /** The corner menu's Full Control item. */
    const val FULL_CONTROL: String = "table-board-full-control"

    /** What says Full Control is on, beside the menu. Absent while it is off. */
    const val FULL_CONTROL_BADGE: String = "table-board-full-control-badge"
}

/**
 * Says Full Control is on, beside the menu that turns it off.
 *
 * **A pinned mode has to look pinned.** Off is the ordinary game. On, priority comes back after every one
 * of the player's own casts, and a player who had forgotten setting it would read each of those windows
 * as the board waiting for no reason.
 */
@Composable
private fun FullControlBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.testTag(TableBoardTestTags.FULL_CONTROL_BADGE),
    ) {
        Text(
            text = FULL_CONTROL_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
        )
    }
}

/** What the corner menu calls leaving the board, shared with tests so the two agree. */
const val LEAVE_BOARD_LABEL: String = "Leave game"

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

/**
 * The board's own corner menu: leaving, conceding, quitting the match.
 *
 * **These are not answers to the server's question and they no longer live with them.** The game menu
 * used to be a button inside the answer panel, and the exit a second floating control beside it — so
 * the corner held two unrelated things, and the way out of a game was reachable only through a surface
 * that exists to answer prompts. A player wants to leave at a moment when there may be nothing
 * outstanding at all.
 *
 * Conceding and quitting confirm, because both end something. Leaving does not: the game stays running
 * and the room is still under the board, so it is a look away rather than a decision.
 */
@Composable
private fun BoardCornerMenu(
    onExit: () -> Unit,
    onAction: (BoardAction) -> Unit,
    fullControl: Boolean = false,
    onSetFullControl: (Boolean) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }

    Box {
        FilledIconButton(
            onClick = { open = true },
            modifier = Modifier.testTag(TableBoardTestTags.MENU),
        ) {
            Icon(imageVector = Icons.Filled.Menu, contentDescription = BOARD_MENU_LABEL)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                confirming = null
            },
        ) {
            // **A mode, not an act**, so it does not confirm: it ends nothing, and a second press puts
            // it back. The check says which way it is set.
            DropdownMenuItem(
                text = { Text(FULL_CONTROL_LABEL) },
                trailingIcon = {
                    if (fullControl) Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    open = false
                    onSetFullControl(!fullControl)
                },
                modifier = Modifier.testTag(TableBoardTestTags.FULL_CONTROL),
            )
            DropdownMenuItem(
                text = { Text(LEAVE_BOARD_LABEL) },
                onClick = {
                    open = false
                    onExit()
                },
            )
            DropdownMenuItem(
                text = { Text(if (confirming == CONCEDE_LABEL) CONCEDE_CONFIRM_LABEL else CONCEDE_LABEL) },
                onClick = {
                    if (confirming == CONCEDE_LABEL) {
                        open = false
                        confirming = null
                        onAction(BoardAction.Concede)
                    } else {
                        confirming = CONCEDE_LABEL
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(if (confirming == QUIT_MATCH_LABEL) QUIT_MATCH_CONFIRM_LABEL else QUIT_MATCH_LABEL) },
                onClick = {
                    if (confirming == QUIT_MATCH_LABEL) {
                        open = false
                        confirming = null
                        onAction(BoardAction.QuitMatch)
                    } else {
                        confirming = QUIT_MATCH_LABEL
                    }
                },
            )
        }
    }
}

/**
 * How much of the height a raised card takes.
 *
 * Larger than the tier's own default. A raised card is the one thing on screen at that moment and it
 * carries more than it used to — a permanent's abilities as they are now, and the text of everything
 * attached to it — so the panel beside it needs the room to be read rather than skimmed.
 */
private const val DETAIL_HEIGHT_SHARE = 0.88f
