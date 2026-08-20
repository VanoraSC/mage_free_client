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
import androidx.compose.ui.zIndex
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.network.game.CardType
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCounter
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.PlayableObject
import magefree.network.game.PromptOptions
import magefree.network.game.TurnPhase

/**
 * The **playable portrait board** (stories 0055 + 0057).
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
 *   …with the floating controls and the expanded hand drawn *over* the bottom of that column.
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
 * ## Playable, and nothing modal (story 0057)
 *
 * The board answers the server's prompts through **floating controls** ([FloatingControls]) that sit
 * over the bottom of the board and can be hidden ([HiddenControlsToggle]). There is **no `Dialog` and
 * no `Popup` on this screen**, for any prompt kind — §16.2 made §6.2's exception the rule.
 *
 * Two things the layout guarantees, because §16.3 says a hidden control set must never hide that the
 * server is waiting:
 * 1. the **priority banner is part of the board's status rail**, not of the controls, so the toggle
 *    cannot take it away;
 * 2. the collapsed toggle itself restates it.
 *
 * Every gesture leaves through [onAction] as a [BoardAction]; this screen holds no client and decides
 * nothing about the rules. What may be tapped comes from [GameBoardUiState.controls], which is the
 * server's own candidate list for the outstanding prompt.
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
    onControlsVisibleChange: (Boolean) -> Unit,
    onCardTap: (String?) -> Unit,
    onAction: (BoardAction) -> Unit,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
    onFlipDetailFace: () -> Unit = {},
) {
    val board = uiState.board
    val controls = uiState.controls
    // Back closes whatever is open over the board, innermost first, before it leaves the board.
    BackHandler(enabled = uiState.selectedObjectId != null) { onCardTap(null) }
    BackHandler(enabled = uiState.selectedObjectId == null && uiState.isHandExpanded) { onHandExpandedChange(false) }

    val pickOf: (String) -> CardPickState = { objectId ->
        when {
            controls == null -> CardPickState.None
            objectId in controls.chosenObjectIds -> CardPickState.Chosen
            objectId in controls.pickableObjectIds -> CardPickState.Pickable
            else -> CardPickState.None
        }
    }

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
                    picks = pickOf,
                    selectedObjectId = uiState.selectedObjectId,
                    onCardTap = onCardTap,
                )
                StatusRail(board = board, artRenderer = artRenderer)
                // **Your vitals sit above your battlefield, not below it.** The floating controls are
                // bottom-anchored, and with the bar at the foot of the column the panel covered it — the
                // only way to read your own life was to hide the controls. That is merely annoying most
                // of the time and actively wrong when *you* are one of the candidates being chosen
                // between (a player-target prompt). Above the band it sits against the status rail,
                // mirroring the opponent's bar, and nothing that must stay legible is under the panel.
                SeatVitalsBar(seat = board.viewerSeat, fallbackLabel = VIEWER_SEAT_LABEL)
                BattlefieldBand(
                    seat = board.viewerSeat,
                    artRenderer = artRenderer,
                    modifier = Modifier.weight(1f),
                    picks = pickOf,
                    selectedObjectId = uiState.selectedObjectId,
                    onCardTap = onCardTap,
                )
                NoticeStrip(board = board)
                HandPeek(
                    hand = board.hand,
                    expanded = uiState.isHandExpanded,
                    modifier = Modifier.clickable { onHandExpandedChange(!uiState.isHandExpanded) },
                )
            }

            // The expanded hand and the floating controls both draw *over* the board rather than
            // displacing it (§16.1: height is the scarce axis in portrait, so nothing may take it
            // twice). They stack, controls above hand, so neither hides the other.
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        // Explicit rather than incidental: these controls deliberately overlap the
                        // board's own bottom regions, and which layer receives the touch there is a
                        // property worth stating instead of inheriting from declaration order.
                        .zIndex(FLOATING_LAYER_Z)
                        .fillMaxWidth()
                        .padding(bottom = HandPeekHeight),
            ) {
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
                        // The board's own priority banner says this too; this is the second, independent
                        // statement §16.3 requires (see [HiddenControlsToggle]).
                        isServerWaiting = board.priority is PriorityUi.Yours || board.priority == PriorityUi.Asked,
                        onShow = { onControlsVisibleChange(true) },
                    )
                }
                if (uiState.isHandExpanded) {
                    ExpandedHand(
                        hand = board.hand,
                        artRenderer = artRenderer,
                        picks = pickOf,
                        selectedObjectId = uiState.selectedObjectId,
                        onCardTap = onCardTap,
                    )
                }
            }

            // §5.1/§11.1: the first tap raised the card; this is where it is inspected and committed.
            uiState.selectedObjectId?.let { objectId ->
                board.cardFor(objectId)?.let { card ->
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
 * The card [objectId] names, wherever the board is drawing it: the hand, either battlefield, or the
 * stack. Null when the object is not on the board at all — a target the server offered that lives
 * somewhere this board does not draw (a graveyard, a library) simply has no detail view.
 */
internal fun BoardUi.cardFor(objectId: String): CardUi? {
    hand.cards.firstOrNull { it.objectId == objectId }?.let { return it.card }
    (listOfNotNull(viewerSeat) + opponentSeats)
        .flatMap { it.battlefield }
        .firstOrNull { it.objectId == objectId }
        ?.let { return it.card }
    return stack.entries.firstOrNull { it.objectId == objectId }?.card
}

/**
 * The standing statements that must survive whatever else is on screen: before the first snapshot, that
 * nothing has arrived yet, and — always — a declined join.
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
    power: String? = null,
    toughness: String? = null,
    isCreature: Boolean = false,
    counters: List<GameCounter> = emptyList(),
) = GameCard(
    id = id,
    name = name,
    setCode = "M21",
    collectorNumber = "272",
    manaCost = manaCost,
    typeLine = typeLine,
    // A land really does arrive with "0"/"0" and no CREATURE type; a creature arrives with both. The
    // preview says so, because a preview that disagrees with the server is a preview that lies.
    power = power ?: "0",
    toughness = toughness ?: "0",
    isCreature = isCreature,
    cardTypes = if (isCreature) listOf(CardType.Creature) else emptyList(),
    counters = counters,
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
                                card =
                                    previewCard(
                                        "o-2",
                                        "Grizzly Bears",
                                        "Creature — Bear",
                                        "1G",
                                        power = "2",
                                        toughness = "2",
                                        isCreature = true,
                                        counters = listOf(GameCounter("+1/+1", 2)),
                                    ),
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
        playable = listOf(PlayableObject("h-1"), PlayableObject("h-2")),
        prompt = GamePrompt.Select(message = "Select an ability to play"),
    )

private fun previewUiState(state: GameState = previewState()) =
    GameBoardUiState(
        board = BoardUi.from(state),
        isJoining = false,
        controls = controlsFor(state),
    )

@Composable
private fun PreviewBoard(
    uiState: GameBoardUiState,
    onCardTap: (String?) -> Unit = {},
) {
    MageTheme {
        GameBoardScreen(
            uiState = uiState,
            onExit = {},
            onHandExpandedChange = {},
            onControlsVisibleChange = {},
            onCardTap = onCardTap,
            onAction = {},
            artRenderer = PlaceholderCardArtRenderer,
        )
    }
}

@Preview(name = "Board — priority, floating controls", showBackground = true, widthDp = 411, heightDp = 891)
@Preview(
    name = "Board — priority, floating controls (dark)",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun GameBoardPreview() {
    PreviewBoard(previewUiState())
}

@Preview(name = "Board — first snapshot, empty everywhere", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardEmptyPreview() {
    PreviewBoard(GameBoardUiState(board = BoardUi(gameId = "g-1")))
}

@Preview(name = "Board — mid-cast, choosing targets", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardTargetingPreview() {
    val state =
        previewState().copy(
            prompt =
                GamePrompt.Target(
                    message = "Select targets (selected 0 of 2, min 1) to divide 2 damage",
                    isRequired = false,
                    options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to listOf("o-2", "y-1"))),
                ),
        )
    PreviewBoard(
        previewUiState(state).copy(cast = CastUi(cardName = "Forked Bolt", stepLabel = CAST_STEP_TARGETS)),
    )
}

@Preview(name = "Board — declaring attackers", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardDeclaringAttackersPreview() {
    // As the server sends it (§7.2): the ids in `possibleAttackers`, the shortcut in `specialButton`,
    // and **`playable` empty** — the preview would be dishonest with anything in it.
    val state =
        previewState().copy(
            phase = TurnPhase.Combat,
            step = PhaseStep.DeclareAttackers,
            playable = emptyList(),
            prompt =
                GamePrompt.Select(
                    message = "Select attackers",
                    options =
                        PromptOptions(
                            text = mapOf(PromptOptions.SPECIAL_BUTTON to ALL_ATTACK_LABEL),
                            ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to listOf("y-1")),
                        ),
                ),
        )
    PreviewBoard(previewUiState(state).copy(declaration = DeclarationUi(role = CombatRole.Attacking)))
}

@Preview(name = "Board — blocking, with the fight legible", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardBlockingPreview() {
    val base = previewState()
    val state =
        base.copy(
            phase = TurnPhase.Combat,
            step = PhaseStep.DeclareBlockers,
            playable = emptyList(),
            players =
                base.players.map { player ->
                    if (!player.isViewer) {
                        player
                    } else {
                        player.copy(
                            battlefield =
                                player.battlefield +
                                    GamePermanent(
                                        card =
                                            previewCard(
                                                "y-2",
                                                "Goblin Token",
                                                "Creature — Goblin",
                                                power = "1",
                                                toughness = "1",
                                                isCreature = true,
                                            ),
                                    ),
                        )
                    }
                },
            combat =
                listOf(
                    CombatGroup(
                        defenderId = "p-you",
                        defenderName = "You",
                        isBlocked = true,
                        attackerIds = listOf("o-2"),
                        blockerIds = listOf("y-2"),
                    ),
                ),
            prompt =
                GamePrompt.Select(
                    message = "Select blockers",
                    // No special button: blocking has no shortcut, and none is invented (§7.3).
                    options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_BLOCKERS to listOf("y-2"))),
                ),
        )
    PreviewBoard(previewUiState(state).copy(declaration = DeclarationUi(role = CombatRole.Blocking)))
}

@Preview(name = "Board — controls hidden while the server waits", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardControlsHiddenPreview() {
    PreviewBoard(previewUiState().copy(areControlsVisible = false))
}

@Preview(name = "Board — hand expanded", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun GameBoardExpandedHandPreview() {
    PreviewBoard(previewUiState().copy(isHandExpanded = true))
}

/**
 * The z of the floating layer (controls, expanded hand) and the card detail above it.
 *
 * Stated rather than inherited from declaration order, because a control that is drawn on top but not
 * *hit* on top is a dead button — a failure mode this screen is one layout edit away from at all times.
 */
private const val FLOATING_LAYER_Z = 1f

private const val DETAIL_LAYER_Z = 2f
