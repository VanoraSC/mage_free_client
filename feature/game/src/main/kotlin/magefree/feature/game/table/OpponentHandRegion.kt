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
import magefree.cards.art.CardArtSize
import magefree.cards.art.cardBackRequest
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
 * face-up, because you have seen it and the game does not take that back. Everything else is the back
 * of a card: it says *there is a card here* and nothing more, which is exactly what the server told us
 * — `PlayerView.handCount` and no cards at all.
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
 * @param artFor resolves a card's art, including the back's.
 * @param onInspect called with a known card's id when it is tapped. A back answers nothing, because
 *   there is nothing behind it to open.
 * @param lastCardModifier applied to the last card in the hand, face-up or not — the card-sized place a
 *   card leaves the hand from and arrives in, which the region as a whole is not.
 */
@Composable
fun OpponentHandRegion(
    hand: KnownHand,
    tileWidth: Dp,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
    lastCardModifier: Modifier = Modifier,
) {
    if (hand.count == 0) return

    Row(
        modifier = modifier.fillMaxWidth().testTag(OpponentHandTestTags.REGION),
        horizontalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        // Known cards first, so a hand you have seen part of does not reshuffle what you know into the
        // middle of what you do not.
        hand.cards.forEachIndexed { index, card ->
            BoardCard(
                state = BoardCardState(card = card.card),
                width = tileWidth,
                art = artFor?.invoke(card.boardArt, card.card),
                onTap = onInspect?.let { inspect -> { inspect(card.id) } },
                modifier =
                    Modifier
                        .then(if (hand.hidden == 0 && index == hand.cards.lastIndex) lastCardModifier else Modifier)
                        .testTag(OpponentHandTestTags.known(card.id)),
            )
        }

        repeat(hand.hidden) { index ->
            CardBack(
                width = tileWidth,
                artFor = artFor,
                modifier =
                    Modifier
                        .then(if (index == hand.hidden - 1) lastCardModifier else Modifier)
                        .testTag(OpponentHandTestTags.hidden(index)),
            )
        }
    }
}

/**
 * The back of a card, as Scryfall serves it.
 *
 * **The real picture, not an impression of one.** This said "Face-down" on a white card first, which
 * was wrong twice over: *face-down* is a game state in Magic — a morph, a manifest — and none of that
 * is true of a card in somebody's hand; and a card with a word on it reads as a card whose *name* is
 * that word. A flat brown rectangle was no better, being precisely what a card back is not.
 *
 * Scryfall serves the one back every Magic card shares, from its own host — `backs.scryfall.io`, not
 * the card CDN, which answers 404 for that id. Both were checked with a request before this was
 * written rather than reasoned about from the card URLs, because they look near enough alike to guess
 * wrong. See `cardBackRequest`.
 *
 * **Empty name, on purpose.** A back has no name, and the card tier draws its name band from what it
 * is given — so anything here would be printed across a picture with no room for it. Until the image
 * loads it falls back to the same placeholder every other card does, which is what a card that has not
 * loaded should look like.
 */
@Composable
private fun CardBack(
    width: Dp,
    artFor: TableArtResolver?,
    modifier: Modifier = Modifier,
) {
    val blank = CardDisplay(name = "")
    BoardCard(
        state = BoardCardState(card = blank, isFaceDown = true),
        width = width,
        art = artFor?.invoke(cardBackRequest(CardArtSize.SMALL), blank),
        modifier = modifier,
    )
}

/** Test tags for the opponent's hand. */
object OpponentHandTestTags {
    const val REGION: String = "opponent-hand"

    /** A card that has been revealed to this client, by its server id. */
    fun known(cardId: String): String = "opponent-hand-known-$cardId"

    /** A card back, by position. It has no id, because the server never sent one. */
    fun hidden(index: Int): String = "opponent-hand-hidden-$index"
}

/** The same gap the viewer's own hand uses, so the two rows read as the same thing mirrored. */
private val TileGap: Dp = 4.dp
