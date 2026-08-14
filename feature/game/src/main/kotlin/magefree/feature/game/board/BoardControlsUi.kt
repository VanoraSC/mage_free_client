package magefree.feature.game.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.Corner
import magefree.designsystem.theme.Elevation
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer

/*
 * The **floating controls** of requirements §16.2, and the visibility toggle of §16.3 (story 0057).
 *
 * ## Nothing here is modal, and that is structural rather than a promise
 *
 * §6.1 originally sent "self-contained" prompts — ask, get-amount, choose-choice, choose-ability,
 * choose-pile — to a **modal dialog**, and §6.2 carved out targeting and mana payment because a modal
 * would hide the very board being tapped. §16.2 makes the exception the rule: *every* prompt kind is
 * answered by the same panel floating over a board that stays visible and live.
 *
 * Concretely, and checkably:
 * - there is **no `Dialog` and no `Popup`** anywhere in this file, so nothing takes its own window and
 *   nothing intercepts the board's touches;
 * - the panel is **bottom-anchored and height-capped** ([ControlsMaxHeight]), scrolling internally
 *   rather than growing, so a long list of choices cannot swallow the board;
 * - the distinction §6.2 drew survives as data, not as presentation: a prompt answered *by touching the
 *   board* publishes [PromptControlsUi.pickableObjectIds] and the board lights those cards up; a prompt
 *   answered *from its own content* publishes buttons and candidate cards instead.
 *
 * ## The toggle's hard constraint (§16.3)
 *
 * Hiding the controls must **never** hide that the server is waiting on the player. Two independent
 * things guarantee it here: the priority banner lives in the board's own status rail (see [StatusRail])
 * and is not part of this panel at all, and [HiddenControlsToggle] restates it on the collapsed control
 * itself. Both are asserted in `GameBoardScreenTest`.
 */

/**
 * The tallest the floating panel is allowed to get before it scrolls inside itself.
 *
 * Chosen against the portrait layout rather than picked: with the hand's peek edge below it, a panel this
 * tall stops above the **tap point of the viewer's own permanents**, which is what mana payment (§6.3)
 * and targeting (§5.2) both need to stay reachable while the panel is open. `GameBoardScreenTest`
 * measures exactly that, so the number cannot drift without a test noticing.
 */
internal val ControlsMaxHeight: Dp = 200.dp

/** Width of a candidate card the prompt carried itself (a scry card, a pile). */
internal val CandidateCardWidth: Dp = 72.dp

/**
 * The floating control panel: the outstanding question, the cast in flight, whatever answers the
 * question, and the board menu.
 *
 * Rendered even when nothing is outstanding — that is what keeps concede/quit reachable and what lets
 * the board state [NO_OUTSTANDING_PROMPT] plainly, so a quiet game never reads as a stuck one.
 */
@Composable
internal fun FloatingControls(
    controls: PromptControlsUi?,
    cast: CastUi?,
    declaration: DeclarationUi?,
    actionError: String?,
    artRenderer: CardArtRenderer,
    onAction: (BoardAction) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = ControlsMaxHeight),
        // Every corner the same radius, deliberately. `Surface` clips to its shape, and a shape whose
        // corners differ (e.g. top-only rounding) produces a **non-simple** outline that hit-testing has
        // to resolve through a path — which Robolectric's graphics shadows cannot do, so every button
        // inside the panel rendered, reported a click action, and silently did nothing under the
        // hermetic gate. Found exactly that way; keep the radii equal.
        shape = RoundedCornerShape(Corner.medium),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.level4,
    ) {
        var menuExpanded by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (controls == null) NO_OUTSTANDING_PROMPT else CONTROLS_TITLE,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = { menuExpanded = !menuExpanded }) { Text(text = BOARD_MENU_LABEL, maxLines = 1) }
                TextButton(onClick = onHide) { Text(text = HIDE_CONTROLS_LABEL, maxLines = 1) }
            }

            // Kept directly under the header, and out of the answering controls, because leaving a game
            // is a different kind of act from answering a question — and because it must stay reachable
            // whatever the prompt is, including one this build cannot answer at all.
            if (menuExpanded) {
                BoardMenu(onAction = onAction, onDone = { menuExpanded = false })
            }

            // §6.4: the spell does not vanish behind each new question.
            cast?.let { CastContext(cast = it) }

            // …and the same for a declaration: which of combat's two roles this is, and — while the
            // server is asking a pairing question — which creature it is asking about (§7.4/§7.5).
            declaration?.let { DeclarationContext(declaration = it) }

            controls?.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = MAX_MESSAGE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            (controls as? PromptControlsUi.Choices)?.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = MAX_MESSAGE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (controls) {
                is PromptControlsUi.Priority ->
                    if (controls.pickableObjectIds.isEmpty()) {
                        Note(text = NOTHING_OFFERED_NOTE)
                    } else {
                        Note(text = TAP_TO_PLAY_NOTE)
                    }

                // One role's instruction, never both (§7.4): the note comes off the role the *server*
                // put the board in, so there is no state here that can drift out of step with it.
                is PromptControlsUi.Declaration -> Note(text = controls.role.note())

                is PromptControlsUi.Targeting -> Note(text = TAP_TO_TARGET_NOTE)
                is PromptControlsUi.Mana ->
                    Note(text = if (controls.pickableObjectIds.isEmpty()) NO_MANA_SOURCE_NOTE else TAP_FOR_MANA_NOTE)

                // A notice is exactly that: it carries no buttons, because nothing knows a valid answer.
                is PromptControlsUi.Notice -> Note(text = UNANSWERABLE_NOTE)
                else -> Unit
            }

            controls?.amountRequest?.let { request ->
                AmountControl(request = request, promptKey = controls.message.orEmpty(), onAction = onAction)
            }
            if (controls != null && controls.amountRows.isNotEmpty()) {
                MultiAmountControl(rows = controls.amountRows, promptKey = controls.message.orEmpty(), onAction = onAction)
            }

            // The answering buttons come **before** the prompt's own cards, deliberately: the panel is
            // height-capped so it cannot swallow the board (see [ControlsMaxHeight]), and a prompt that
            // carries a wide set of cards would otherwise push *done* and *cancel* past the fold. The
            // cards below are informational for a pile and a second route for a scry — the board itself
            // is the primary route for anything that is on it.
            val buttons = controls?.buttons.orEmpty()
            if (buttons.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    buttons.forEach { button ->
                        key(button.label) {
                            ControlButtonUi(button = button, promptKey = controls?.message.orEmpty(), onAction = onAction)
                        }
                    }
                }
            }

            if (controls != null && controls.candidateCards.isNotEmpty()) {
                CandidateRow(
                    candidates = controls.candidateCards,
                    artRenderer = artRenderer,
                    onPick = { objectId -> controls.actionFor(objectId)?.let(onAction) },
                    isPickable = controls is PromptControlsUi.Targeting,
                )
            }

            actionError?.let { reason ->
                Text(
                    text = "$ACTION_FAILED_PREFIX $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The cast in flight (§6.4): what is being cast, which decision is open, and whether it is rewinding. */
@Composable
private fun CastContext(cast: CastUi) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text =
                if (cast.stepLabel ==
                    null
                ) {
                    "$CASTING_PREFIX ${cast.cardName}"
                } else {
                    "$CASTING_PREFIX ${cast.cardName} — ${cast.stepLabel}"
                },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (cast.isCancelling) {
            Text(
                text = CANCELLING_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The declaration in flight (story 0061): which of combat's two roles the board is in, and — while the
 * server is asking a **pairing** question — which creature that question is about.
 *
 * The second line exists because the server's own prose does not say. `Select attacker to block` names
 * no blocker, and `TargetDefender`'s prompt names no attacker: without this the player is asked to pick
 * something with no statement of what they are picking *for*. The board adds only the creature's name,
 * which it knows because it is the creature the player just tapped — it never decides that a question
 * should be asked, and never answers one on the player's behalf.
 */
@Composable
private fun DeclarationContext(declaration: DeclarationUi) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = declaration.role.title(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        declaration.pairingLine?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One instruction line inside the panel — how to answer a prompt that is answered on the board. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = MAX_MESSAGE_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One button in the panel — and, for a button that carries a [ControlButton.confirmLabel], §16.4's
 * confirm step.
 *
 * The armed flag is **view state and nothing else**: arming sends nothing, so a player who arms "All
 * attack" and then taps a creature instead has not told the server anything. It is keyed on the prompt
 * (and on the button's own label), so a new question can never inherit the previous one's armed button —
 * the failure that would turn a confirmation into a mis-tap that commits the whole team.
 *
 * The same two-tap idiom as the board menu's concede/quit, for the same reason: what it commits cannot be
 * taken back.
 */
@Composable
private fun ControlButtonUi(
    button: ControlButton,
    promptKey: String,
    onAction: (BoardAction) -> Unit,
) {
    val confirmLabel = button.confirmLabel
    if (confirmLabel == null) {
        if (button.isPrimary) {
            Button(onClick = { onAction(button.action) }) { Text(text = button.label, maxLines = 1) }
        } else {
            OutlinedButton(onClick = { onAction(button.action) }) { Text(text = button.label, maxLines = 1) }
        }
        return
    }
    var armed by rememberSaveable(promptKey, button.label) { mutableStateOf(false) }
    if (armed) {
        Button(
            onClick = {
                armed = false
                onAction(button.action)
            },
        ) { Text(text = confirmLabel, maxLines = 1) }
    } else {
        OutlinedButton(onClick = { armed = true }) { Text(text = button.label, maxLines = 1) }
    }
}

/** Cards the prompt carried itself — a scry card, the two piles — which are not on the board to tap. */
@Composable
private fun CandidateRow(
    candidates: List<CandidateCardUi>,
    artRenderer: CardArtRenderer,
    isPickable: Boolean,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        candidates.forEach { candidate ->
            key(candidate.objectId) {
                Column(
                    modifier = Modifier.width(CandidateCardWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = RoundedCornerShape(Corner.small),
                        color =
                            if (candidate.isChosen) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        tonalElevation = Elevation.level1,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BoardCardFace(
                            card = candidate.card,
                            artRenderer = artRenderer,
                            modifier = Modifier.padding(Spacing.extraSmall),
                        )
                    }
                    if (isPickable) {
                        TextButton(onClick = { onPick(candidate.objectId) }) {
                            Text(text = candidate.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Text(
                            text = candidate.card.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A number inside the server's own bounds.
 *
 * The draft value lives here rather than in the ViewModel because it is not a game fact until it is
 * confirmed — nothing is sent while the player is still counting. [promptKey] re-seeds it whenever the
 * question changes, so a new prompt never inherits the previous one's half-typed answer.
 */
@Composable
private fun AmountControl(
    request: AmountRequestUi,
    promptKey: String,
    onAction: (BoardAction) -> Unit,
) {
    var value by rememberSaveable(promptKey, request.min, request.max) { mutableStateOf(request.min) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { value = (value - 1).coerceAtLeast(request.min) },
            enabled = value > request.min,
        ) { Text(text = AMOUNT_DECREASE_LABEL) }
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
            onClick = { value = (value + 1).coerceAtMost(request.max) },
            enabled = value < request.max,
        ) { Text(text = AMOUNT_INCREASE_LABEL) }
        Button(
            onClick = {
                onAction(
                    when (request.kind) {
                        AmountKind.Amount -> BoardAction.ChooseAmount(value)
                        AmountKind.AnnounceX -> BoardAction.AnnounceX(value)
                    },
                )
            },
        ) { Text(text = CONFIRM_AMOUNT_LABEL, maxLines = 1) }
    }
}

/**
 * Distribute a total across the server's rows.
 *
 * The amounts are sent **positionally, in the prompt's order** — upstream parses exactly that form, so a
 * reordered or short list is a wrong answer rather than a partial one (0052).
 */
@Composable
private fun MultiAmountControl(
    rows: List<AmountRowUi>,
    promptKey: String,
    onAction: (BoardAction) -> Unit,
) {
    val values = remember(promptKey, rows) { mutableStateOf(rows.map { it.initial }) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        rows.forEachIndexed { index, row ->
            key(index, row.label) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    OutlinedButton(
                        onClick = {
                            values.value =
                                values.value.toMutableList().also { it[index] = (it[index] - 1).coerceAtLeast(row.min) }
                        },
                    ) { Text(text = AMOUNT_DECREASE_LABEL) }
                    Text(
                        text = "${values.value.getOrElse(index) { row.initial }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(
                        onClick = {
                            values.value =
                                values.value.toMutableList().also { it[index] = (it[index] + 1).coerceAtMost(row.max) }
                        },
                    ) { Text(text = AMOUNT_INCREASE_LABEL) }
                }
            }
        }
        Button(onClick = { onAction(BoardAction.DistributeAmounts(values.value)) }) {
            Text(text = CONFIRM_AMOUNT_LABEL, maxLines = 1)
        }
    }
}

/**
 * Concede and quit — **separate, explicit actions** (§12.2), mirroring upstream's separate verbs
 * (`concedeGame` and `quitMatch`). Each is behind its own second tap, because both are irreversible and
 * neither should ever be reachable by a mis-tap during a game.
 */
@Composable
private fun BoardMenu(
    onAction: (BoardAction) -> Unit,
    onDone: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (confirming) {
            CONCEDE_LABEL ->
                Button(onClick = {
                    confirming = null
                    onDone()
                    onAction(BoardAction.Concede)
                }) { Text(text = CONCEDE_CONFIRM_LABEL, maxLines = 1) }

            QUIT_MATCH_LABEL ->
                Button(onClick = {
                    confirming = null
                    onDone()
                    onAction(BoardAction.QuitMatch)
                }) { Text(text = QUIT_MATCH_CONFIRM_LABEL, maxLines = 1) }

            else -> {
                OutlinedButton(onClick = { confirming = CONCEDE_LABEL }) { Text(text = CONCEDE_LABEL, maxLines = 1) }
                OutlinedButton(onClick = { confirming = QUIT_MATCH_LABEL }) { Text(text = QUIT_MATCH_LABEL, maxLines = 1) }
            }
        }
    }
}

/**
 * What replaces the panel when the controls are hidden (§16.3).
 *
 * It is deliberately more than a "show" button: whenever the server is waiting on the player it says so
 * on the control itself, so a hidden control set can never become an invisible stall. The board's own
 * priority banner says the same thing independently — this is the belt to that pair of braces.
 */
@Composable
internal fun HiddenControlsToggle(
    isServerWaiting: Boolean,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isServerWaiting) {
            Surface(
                shape = RoundedCornerShape(Corner.small),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = Elevation.level2,
            ) {
                Text(
                    text = WAITING_ON_YOU_WHILE_HIDDEN,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                )
            }
        }
        Button(onClick = onShow) { Text(text = SHOW_CONTROLS_LABEL, maxLines = 1) }
    }
}

/**
 * The tapped card's detail (§5.1, §11.1): first tap raises the card and shows what it is; the detail is
 * where the action is committed, and **any tap elsewhere closes it**.
 *
 * The action button appears only when the *server* has offered this object for the outstanding prompt —
 * so a card with no button means "the server is not offering this right now", never "the app decided
 * you may not".
 */
@Composable
internal fun CardDetailOverlay(
    card: CardUi,
    actionLabel: String?,
    artRenderer: CardArtRenderer,
    onCommit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The "tap elsewhere closes it" surface. It is transparent: the board stays fully visible, which
        // is the whole point of §16.2 — the detail floats, it does not black the game out.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
        )
        Surface(
            shape = RoundedCornerShape(Corner.medium),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevation.level4,
            modifier = Modifier.padding(Spacing.large),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.medium).width(DETAIL_WIDTH),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoardCardFace(card = card, artRenderer = artRenderer, modifier = Modifier.width(DETAIL_ART_WIDTH))
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.display.manaCost?.let {
                    Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                card.display.typeLine?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // What the card **currently** is, which the type line above cannot say: the printed line
                // still reads "Basic Land — Mountain" while an effect has it attacking as a 0/3. Present
                // only for a creature, per [CardUi.powerToughness].
                card.powerToughness?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                // The permanent's own counter row is one ellipsised line 76dp wide, so this is where a
                // player actually reads four counters. Same generic name/count rendering.
                if (card.counters.isNotEmpty()) {
                    Text(
                        text = card.counters.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                card.display.oracleText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = MAX_RULES_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                    actionLabel?.let { label ->
                        Button(onClick = onCommit) { Text(text = label, maxLines = 1) }
                    }
                    TextButton(onClick = onClose) { Text(text = CLOSE_DETAIL_LABEL) }
                }
            }
        }
    }
}

/** How many lines of the server's question the panel shows before it ellipsises. */
private const val MAX_MESSAGE_LINES = 3

/** How many lines of rules text the detail shows. */
private const val MAX_RULES_LINES = 8

private val DETAIL_WIDTH: Dp = 240.dp

private val DETAIL_ART_WIDTH: Dp = 140.dp

/** What the panel says while a prompt is answered by tapping the board. */
internal const val TAP_TO_PLAY_NOTE: String = "Tap a highlighted card to play it."

internal const val TAP_TO_TARGET_NOTE: String = "Tap a highlighted target. Each pick is sent as you make it."

internal const val TAP_FOR_MANA_NOTE: String = "Tap your own highlighted sources to pay."

internal const val NO_MANA_SOURCE_NOTE: String = "The server offers no source to tap — you can still cancel."

internal const val UNANSWERABLE_NOTE: String = "Nothing to press: this one can't be answered by this version."
