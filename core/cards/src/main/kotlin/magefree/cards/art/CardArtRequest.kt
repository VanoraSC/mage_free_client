package magefree.cards.art

import magefree.cards.model.Card
import magefree.cards.model.CardPrinting

/** Which physical face of a card to fetch art for. */
enum class CardArtFace {
    /** The front / only face (also the single image of a split card). */
    FRONT,

    /** The back face of a transforming or modal double-faced card. */
    BACK,
}

/** Image quality to request. Small is the list/grid thumbnail; large is the detail view. */
enum class CardArtSize {
    SMALL,
    LARGE,
}

/**
 * The identity of one card image to load — a specific *printing* (set code + collector number, which
 * is exactly how XMage disambiguates "various art"), a [face], and a [size].
 *
 * This, not [magefree.cards.model.CardId], is the loader's cache key: art is per-printing, and the
 * same oracle card ([CardId]) can have many printings with different art. Build one from a
 * [CardPrinting] via [of], or from a whole [Card] via [frontOf] (which picks a printing for you).
 */
data class CardArtRequest(
    val setCode: String,
    val collectorNumber: String,
    val face: CardArtFace = CardArtFace.FRONT,
    val size: CardArtSize = CardArtSize.SMALL,
) {
    companion object {
        /** A request for one [printing]'s [face] at [size]. */
        fun of(
            printing: CardPrinting,
            face: CardArtFace = CardArtFace.FRONT,
            size: CardArtSize = CardArtSize.SMALL,
        ): CardArtRequest =
            CardArtRequest(
                setCode = printing.setCode,
                collectorNumber = printing.collectorNumber,
                face = face,
                size = size,
            )

        /**
         * The front-face request for [card], using its first printing (the catalog orders printings
         * by set + collector number). Returns `null` for a card with no printings — the caller then
         * shows the placeholder and falls back to the always-available card text (0030).
         */
        fun frontOf(
            card: Card,
            size: CardArtSize = CardArtSize.SMALL,
        ): CardArtRequest? = card.printings.firstOrNull()?.let { of(it, CardArtFace.FRONT, size) }
    }
}
