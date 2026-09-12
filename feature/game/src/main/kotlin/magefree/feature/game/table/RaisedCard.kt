package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewState
import magefree.feature.game.board.CandidateCardUi
import magefree.network.game.GameState

/*
 * The card the player has raised, wherever it was raised from.
 *
 * **One surface, one lookup.** A press on this board can name a permanent, an Aura on one, a card in
 * hand, a card offered from a graveyard, a card in an open pile, an object on the stack, an object in a
 * command zone, or a candidate a prompt carried with it. All eight open the same detail view, so all
 * eight are resolved here rather than at eight call sites that would each answer "what is this id"
 * slightly differently.
 *
 * **A permanent resolves to more than its card.** What a player wants from a raised permanent is what
 * it is *now* — the abilities it has after layers, and what is attached to it — and both are things
 * only the board's own model carries. That is the difference between reading a board and reading a
 * card, and it is why the order below tries the battlefield first.
 */

/**
 * What the raised card is, and what may be done to it.
 *
 * @param objectId the server's own id for whatever was pressed.
 * @param snapshot the server's last whole game view.
 * @param model the battlefield, already built from that snapshot.
 * @param stack the stack, likewise.
 * @param candidates cards the outstanding prompt carried with it, which are in no zone at all.
 * @param actionLabel what a press on this object means to the server right now, or null when it means
 *   nothing. The **server's** answer, by way of `PromptControlsUi` — never one worked out here.
 * @param onAct commits that action.
 */
internal fun raisedCard(
    objectId: String,
    snapshot: GameState?,
    model: BattlefieldModel,
    stack: List<TableStackObject>,
    candidates: List<CandidateCardUi>,
    actionLabel: String?,
    onAct: () -> Unit,
): RaisedCard? {
    val action = actionLabel?.let { CardPreviewAction(label = it, onAct = onAct) }

    model.permanentById(objectId)?.let { permanent ->
        return RaisedCard(permanentPreview(permanent).copy(action = action), permanent.art.full())
    }
    model.attachmentById(objectId)?.let { attachment ->
        return RaisedCard(attachmentPreview(attachment).copy(action = action), attachment.art.full())
    }
    stack.firstOrNull { it.id == objectId }?.let { entry ->
        return RaisedCard(stackPreview(entry).copy(action = action), entry.art.full())
    }
    // **A command object reads like a stack object.** An emblem is text acting on the game from outside
    // every zone, and its rules are the server's game-aware text for it, so they go where a stack
    // object's do.
    snapshot
        ?.let { state -> tableVitals(state).flatMap { it.commandObjects }.firstOrNull { it.id == objectId } }
        ?.let { command ->
            return RaisedCard(
                CardPreviewState(card = command.card, abilities = command.rules, action = action),
                command.fullArt,
            )
        }
    snapshot
        ?.let { state ->
            (handCards(state) + playableElsewhere(state) + tableZones(state).flatMap { it.cards })
                .firstOrNull { it.id == objectId }
        }?.let { card ->
            return RaisedCard(tableCardPreview(card).copy(action = action), card.fullArt)
        }

    // Last, because a candidate is the only kind that is in no zone at all — a library search's cards
    // are the server's answer to a question rather than a pile — and anything that *is* somewhere has
    // more to say about itself than the prompt carried.
    return candidates.firstOrNull { it.objectId == objectId }?.let { candidate ->
        RaisedCard(
            CardPreviewState(
                card = candidate.card.display,
                oracleText = candidate.card.display.oracleText,
                action = action,
            ),
            candidate.card.art?.copy(size = CardArtSize.LARGE),
        )
    }
}

/** A raised card: everything said about it, and the printing to draw it from at full resolution. */
internal data class RaisedCard(
    val state: CardPreviewState,
    val art: CardArtRequest?,
)

/**
 * One object on the stack, read.
 *
 * Its rules go in [CardPreviewState.abilities] rather than in `oracleText`, because on the stack they
 * are the same thing: `GameCard.rules` for a stack object *is* the ability as it exists now, and there
 * is no separate printed text to sit beside it.
 */
private fun stackPreview(entry: TableStackObject): CardPreviewState =
    CardPreviewState(
        card = entry.state.card,
        power = entry.state.power,
        toughness = entry.state.toughness,
        abilities = entry.rules,
    )

/** Full resolution, because a raised card fills three quarters of the screen. */
private fun CardArtRequest?.full(): CardArtRequest? = this?.copy(size = CardArtSize.LARGE)
