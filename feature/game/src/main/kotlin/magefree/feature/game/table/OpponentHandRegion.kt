package magefree.feature.game.table

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay

/*
 * The opponent's hand, along their own edge.
 *
 * **Mirrored, like everything else on this board.** Your hand runs along the bottom; theirs runs along
 * the top, drawn the same way and at the same size, so *how many cards they are holding* is a thing you
 * read rather than a number you go and look up.
 *
 * **Two kinds of card, and the difference is the whole point.** A card you have been shown is drawn
 * face-up, because you have seen it and the game does not take that back. Everything else is a card
 * back: it says *there is a card here* and nothing more, which is exactly what the server told us —
 * `PlayerView.handCount` and no cards at all.
 *
 * What is known comes from [SeenCards], and everything about how that is worked out — including that
 * it is inference, and that it holds for one opponent and not for several — is in [KnownHand].
 *
 * **It hangs off the top edge for the same reason the player's hangs off the bottom.** Only the part
 * of a card that carries its name is worth the room; the rest can fall off the screen.
 */

/**
 * One opponent's hand: what has been seen, face-up, and a card back for everything else.
 *
 * @param hand what this client knows, from [SeenCards.knownHandFor].
 * @param tileWidth the width a card is drawn at — the same the viewer's own hand uses.
 * @param artFor resolves a known card's art. A card back has no art to resolve.
 * @param onInspect called with a known card's id when it is tapped. A card back answers nothing,
 *   because there is nothing to say about it.
 */
@Composable
fun OpponentHandRegion(
    hand: KnownHand,
    tileWidth: Dp,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
) {
    if (hand.count == 0) return

    Row(
        modifier = modifier.fillMaxWidth().testTag(OpponentHandTestTags.REGION),
        horizontalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        // Known cards first, so a hand you have seen part of does not reshuffle what you know into the
        // middle of what you do not.
        hand.cards.forEach { card ->
            BoardCard(
                state = BoardCardState(card = card.card),
                width = tileWidth,
                art = artFor?.invoke(card.boardArt, card.card),
                onTap = onInspect?.let { inspect -> { inspect(card.id) } },
                modifier = Modifier.testTag(OpponentHandTestTags.known(card.id)),
            )
        }

        // **A card back is not a card with a blank name.** It carries no art, no cost and no type, and
        // it takes no press, because there is nothing behind it to open — the server sent a count.
        repeat(hand.hidden) { index ->
            BoardCard(
                state = BoardCardState(card = CardDisplay(name = FACE_DOWN_LABEL)),
                width = tileWidth,
                modifier = Modifier.testTag(OpponentHandTestTags.hidden(index)),
            )
        }
    }
}

/** Test tags for the opponent's hand. */
object OpponentHandTestTags {
    const val REGION: String = "opponent-hand"

    /** A card that has been revealed to this client, by its server id. */
    fun known(cardId: String): String = "opponent-hand-known-$cardId"

    /** A card back, by position. It has no id, because the server never sent one. */
    fun hidden(index: Int): String = "opponent-hand-hidden-$index"
}

/**
 * What a card back is called.
 *
 * The same word the board already uses for a face-down permanent, so a player learns it once.
 */
private const val FACE_DOWN_LABEL = "Face-down"

/** The same gap the viewer's own hand uses, so the two rows read as the same thing mirrored. */
private val TileGap: Dp = 4.dp
