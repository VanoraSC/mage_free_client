package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.designsystem.card.BoardCardState
import magefree.feature.game.board.ControlButton
import magefree.feature.game.board.PromptControlsUi

/*
 * A target question with more than one answer, as two piles.
 *
 * **A set being assembled has no home on a board that draws one card at a time.** Liliana of the
 * Veil's `-6` — "separate all permanents target player controls into two piles" — arrived as an
 * ordinary target prompt over an opponent's whole battlefield. Marking the candidates made it visible;
 * it did not make it *playable*, because what the player is building is a partition and the board can
 * only show one card at a time being pressed.
 *
 * **Two columns are a correct rendering of any such prompt, so nothing here asks what kind it is.**
 * The wire cannot say: `HumanPlayer.getOptions` sends `UI.right.btn.text`, `targetZone` and
 * `chosenTargets`, and the cardinality lives only in the prose of `TargetImpl.getMessage`, which this
 * app does not parse. For Liliana the two columns are the two piles; for "sacrifice up to three" they
 * are available and sacrificing; for a single target the second column holds one card briefly.
 *
 * **The second pile is derived, not guessed.** `LilianaOfTheVeilEffect` computes it server-side as
 * every permanent that player controls minus pile 1, and `possibleTargets` for this prompt *is* every
 * permanent that player controls — `TargetPermanent.possibleTargets` does not drop the chosen ones.
 * So [TargetPiles.available] is `pickable − chosen` and it is the same set the server will use.
 *
 * **Both columns are the server's state, never an accumulator.** `chosenTargets` is sent by both
 * prompt loops (`HumanPlayer.choose` and `HumanPlayer.chooseTarget`), so the right column is *read*.
 * Every move sends one `chooseTarget` and the columns redraw from the reply — the same rule the rest
 * of the board follows, and the reason a move back needs no new verb: upstream removes a target sent
 * a second time.
 */

/** One card in a pile, drawn exactly as the battlefield draws it. */
data class PileCard(
    val id: String,
    val state: BoardCardState,
    val art: CardArtRequest?,
)

/**
 * The two piles for the outstanding prompt.
 *
 * @property message the server's own question.
 * @property available what has not been chosen — pile 2, where the prompt is a pile split.
 * @property chosen what has, in the server's own `chosenTargets`.
 * @property buttons the prompt's own buttons, carried through unchanged. They already include any
 *   candidate the board cannot draw (a player, above all) as well as Done and Cancel, so moving the
 *   question into this overlay loses none of its answers.
 */
data class TargetPiles(
    val message: String?,
    val available: List<PileCard>,
    val chosen: List<PileCard>,
    val buttons: List<ControlButton>,
)

/**
 * The piles for [controls], or `null` when this prompt is not one the overlay answers.
 *
 * Null for three reasons, each of them about where the answer *is*:
 * - not a targeting prompt at all;
 * - a prompt that carried its own cards (a library search, an ability picker) — those are in no zone,
 *   and 0113's panel is already built to draw them;
 * - a prompt whose candidates are none of them on the board, which is the same case seen from the
 *   other side: there is nothing for a column to hold.
 */
fun targetPiles(
    controls: PromptControlsUi?,
    model: BattlefieldModel,
): TargetPiles? {
    if (controls !is PromptControlsUi.Targeting) return null
    if (controls.candidateCards.isNotEmpty()) return null

    val chosen = controls.chosenObjectIds
    // The server's own order is not meaningful for a set, so the board's is used: a player reading
    // two columns is looking for a card they can see on the battlefield, and finding it in the same
    // order it sits there is the only ordering that helps.
    val onBoard = model.boardOrder().filter { it.id in controls.pickableObjectIds }
    if (onBoard.isEmpty()) return null

    return TargetPiles(
        message = controls.message,
        available = onBoard.filterNot { it.id in chosen },
        chosen = onBoard.filter { it.id in chosen },
        buttons = controls.buttons,
    )
}

/**
 * Every permanent on the table, in the order the board lays it out.
 *
 * Opponents first, then the viewer, which is top-to-bottom on screen. Attachments are included: an
 * Aura is a permanent its controller's opponent may well be asked to put in a pile, and one that
 * could not be moved would make the partition unanswerable.
 */
private fun BattlefieldModel.boardOrder(): List<PileCard> =
    (opponents + listOfNotNull(viewer)).flatMap { side ->
        side.permanents.flatMap { permanent ->
            listOf(PileCard(id = permanent.id, state = permanent.state, art = permanent.art)) +
                permanent.attached.map { attachment ->
                    PileCard(
                        id = attachment.id,
                        // An attachment's own drawing state is its host's band on the battlefield, so
                        // there is no `BoardCardState` to reuse. It is a card like any other here.
                        state = BoardCardState(card = attachment.card),
                        art = attachment.art,
                    )
                }
        }
    }
