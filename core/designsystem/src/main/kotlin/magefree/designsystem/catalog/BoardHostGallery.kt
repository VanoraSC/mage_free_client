package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardAnimationHost
import magefree.designsystem.board.BoardHostScope
import magefree.designsystem.board.BoardObject
import magefree.designsystem.board.BoardObjectId
import magefree.designsystem.board.BoardSequencer
import magefree.designsystem.board.BoardSlotId
import magefree.designsystem.board.BoardSnapshot
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.rememberBoardSequencer
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The animation host, driven by a script.
 *
 * Ordered playback is the one thing about this subsystem that cannot be judged from a still picture,
 * and there is no live game to watch yet, so the harness is the only practical way to see it. The
 * script is written to make the rules visible one at a time:
 *
 * - **A chain plays in order.** The step that puts five tokens on the battlefield arrives in a single
 *   snapshot. If they appear together, the sequencer has collapsed the chain and the player is being
 *   shown a result instead of a sequence.
 * - **The board trails, and says by how much.** The line under the buttons reports the backlog, which
 *   is the lag made legible: while a sequence plays, the board is deliberately behind the server.
 * - **A prompt drains.** Press *Ask me something* while the chain is playing. The rest of it goes past
 *   quickly rather than being cut off or waited out, because nobody should answer on a stale board.
 * - **A resync snaps.** Press *Reconnect* while a sequence is playing: the board arrives at the state
 *   the server is already in, with the rest of the running order discarded rather than replayed. Press
 *   it on a still board and nothing happens, which is correct — there was nothing to catch up on.
 */

/**
 * A scripted board with the host driving it: press through the script and watch what moves.
 *
 * @param modifier the [Modifier] for the harness.
 * @param artFor resolves card art, as elsewhere in the catalog; without it the cards fall back to the
 *   placeholder and the harness still demonstrates everything it is here to demonstrate.
 */
@Composable
fun BoardHostGallery(
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
) {
    val sequencer = rememberBoardSequencer(initial = Opening)
    var step by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            val next = Script.getOrNull(step)
            MageSecondaryButton(
                text = next?.label ?: "Start again",
                onClick = {
                    if (next == null) {
                        sequencer.onResync(Opening)
                        step = 0
                    } else {
                        next.play(sequencer)
                        step += 1
                    }
                },
            )
            // Disabled while the board is still, because that is when it genuinely does nothing: there
            // is no running order left to drain. A button that silently no-ops is indistinguishable
            // from one that is broken, which is how this read the first time it was tried.
            MageSecondaryButton(
                text = "Ask me something",
                onClick = { sequencer.onPrompt() },
                enabled = !sequencer.isIdle,
            )
            MageSecondaryButton(
                text = "Reconnect",
                onClick = {
                    // The server's own current state, which is what a reconnect actually delivers.
                    // Not the end of the script: jumping the board somewhere the game has not reached
                    // would be demonstrating the opposite of the rule, since the point of a resync is
                    // that it shows you where things stand and narrates nothing on the way.
                    sequencer.onResync(sequencer.latest)
                },
            )
        }

        Text(
            text =
                if (sequencer.isIdle) {
                    "The board is showing the server's own state."
                } else {
                    "The board is ${sequencer.backlogMillis} ms behind the server, with " +
                        "${sequencer.remainingChanges} more changes to show."
                },
            style = MaterialTheme.typography.labelMedium,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(BoardSurface.ground, MageShapes.medium)
                    .padding(Spacing.small),
        ) {
            BoardAnimationHost(
                snapshot = sequencer.presented,
                objectContent = { shown -> ScriptedCard(shown, artFor) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    ScriptedRow(label = "Hand", slot = Hand)
                    ScriptedRow(label = "Stack", slot = Stack)
                    ScriptedRow(label = "Battlefield", slot = Battlefield)
                    ScriptedRow(label = "Graveyard", slot = Graveyard)
                }
            }
        }
    }
}

/** One zone: a label and the objects that currently own it, laid out left to right. */
@Composable
private fun BoardHostScope.ScriptedRow(
    label: String,
    slot: BoardSlotId,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = BoardSurface.valueRamp.last())
        Row(
            modifier = Modifier.fillMaxWidth().height(CardWidth / CARD_ASPECT_RATIO),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            SlotObjects(slot)
        }
    }
}

/** One object, drawn as the Board card it is — the real tier, not a stand-in rectangle. */
@Composable
private fun ScriptedCard(
    shown: BoardObject,
    artFor: ((String) -> CardArtSlot?)?,
) {
    val card = shown.payload as? CardDisplay ?: return
    BoardCard(
        state =
            BoardCardState(
                card = card,
                counters = shown.counters.map { (name, count) -> BoardCounter(name, count) },
                tapped = shown.tapped,
            ),
        width = CardWidth,
        art = artFor?.invoke(card.name),
    )
}

/** One press of the harness: what it is called, and what it does to the sequencer. */
private class ScriptStep(
    val label: String,
    val play: (BoardSequencer) -> Unit,
)

private val Hand = BoardSlotId("hand")
private val Stack = BoardSlotId("stack")
private val Battlefield = BoardSlotId("battlefield")
private val Graveyard = BoardSlotId("graveyard")

private val CardWidth = 56.dp

private val BearsCard = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear")
private val PacifismCard = CardDisplay(name = "Pacifism", manaCost = "1W", typeLine = "Enchantment — Aura")
private val ForestCard = CardDisplay(name = "Forest", typeLine = "Basic Land — Forest")

private val Bears = BoardObjectId("bears")
private val Pacifism = BoardObjectId("pacifism")
private val Forest = BoardObjectId("forest")

private fun bears(
    slot: BoardSlotId,
    tapped: Boolean = false,
    counters: Map<String, Int> = emptyMap(),
) = BoardObject(id = Bears, slot = slot, tapped = tapped, counters = counters, payload = BearsCard)

private fun pacifism(slot: BoardSlotId) = BoardObject(id = Pacifism, slot = slot, payload = PacifismCard)

private fun forest(tapped: Boolean = false) = BoardObject(id = Forest, slot = Battlefield, tapped = tapped, payload = ForestCard)

/** A token by number, so a chain of them is a chain of distinct objects rather than one repeated. */
private fun token(index: Int) =
    BoardObject(
        id = BoardObjectId("saproling-$index"),
        slot = Battlefield,
        payload = CardDisplay(name = "Saproling", typeLine = "Token Creature — Saproling"),
    )

private val Opening = BoardSnapshot(listOf(bears(Hand), pacifism(Hand), forest()))

/** The five tokens the chain adds, which every later step carries along. */
private val Chain = (1..5).map(::token)

private val Ending =
    BoardSnapshot(listOf(pacifism(Hand), forest(tapped = true), bears(Graveyard)) + Chain.drop(1))

private val Script =
    listOf(
        ScriptStep("Cast Grizzly Bears") { sequencer ->
            sequencer.onSnapshot(BoardSnapshot(listOf(bears(Stack), pacifism(Hand), forest(tapped = true))))
        },
        ScriptStep("It resolves") { sequencer ->
            sequencer.onSnapshot(BoardSnapshot(listOf(bears(Battlefield), pacifism(Hand), forest(tapped = true))))
        },
        ScriptStep("Five triggers resolve") { sequencer ->
            // One snapshot, five entries. The whole subsystem is here: if they arrive together, the
            // player has been told the result and not the sequence.
            sequencer.onSnapshot(
                BoardSnapshot(listOf(bears(Battlefield), pacifism(Hand), forest(tapped = true)) + Chain),
            )
        },
        ScriptStep("Attack with the bears") { sequencer ->
            sequencer.onSnapshot(
                BoardSnapshot(
                    listOf(
                        bears(Battlefield, tapped = true, counters = mapOf("+1/+1" to 2)),
                        pacifism(Hand),
                        forest(tapped = true),
                    ) + Chain,
                ),
            )
        },
        ScriptStep("It dies, and a token goes with it") { sequencer ->
            sequencer.onSnapshot(Ending)
        },
    )
