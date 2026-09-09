package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BOARD_CARD_ASPECT_RATIO
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState

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

        // **A card back is not a card, and must not be drawn as one.** It carries no name, no art, no
        // cost and no type, and it takes no press, because there is nothing behind it to open — the
        // server sent a count.
        repeat(hand.hidden) { index ->
            CardBack(
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
 * The back of a card.
 *
 * **It said "Face-down" and that was wrong twice over.** *Face-down* is a game state in Magic — a
 * morph, a manifest, a permanent turned over by an effect — and none of that is true of a card in
 * somebody's hand. It is simply a card this player has not been shown. And a white card with a word
 * printed on it does not read as a card back at all; it reads as a card whose name is "Face-down".
 *
 * So it is drawn as what it is: the black border every Magic card has, and a plain ground inside it.
 * No name band, because a back has no name to put in one.
 */
@Composable
private fun CardBack(
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(width = width, height = width / BOARD_CARD_ASPECT_RATIO)
                .clip(CardShape)
                .background(BoardSurface.cardBorder)
                .padding(CardBorder),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(CardShape).background(CardBackGround))
    }
}

/**
 * The colour of a card back.
 *
 * A deep, desaturated brown — the value a real card's back sits at, and far enough from every
 * [magefree.designsystem.board.BoardSignal] that it can never be mistaken for the board saying
 * something.
 */
private val CardBackGround = Color(0xFF4A3B32)

private val CardShape = RoundedCornerShape(3.dp)

private val CardBorder = 2.dp

/** The same gap the viewer's own hand uses, so the two rows read as the same thing mirrored. */
private val TileGap: Dp = 4.dp
