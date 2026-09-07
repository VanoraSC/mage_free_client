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

/**
 * Which image of a printing to request.
 *
 * [SMALL] and [LARGE] are the same picture — the whole card face, frame and all — at two
 * resolutions. [ART_CROP] is a **different picture**: Scryfall's crop of the illustration alone, with
 * no frame, no name plate and no text box.
 *
 * That distinction is what the board is built on. A tier that wants only the art must ask for only
 * the art, rather than being handed a whole card and made to clip the parts it does not want: the
 * clip has to be right against the frame's proportions, and it is silently wrong the moment anything
 * about the box it is drawn in changes. An art crop simply fills whatever box it is given.
 */
enum class CardArtSize {
    SMALL,
    LARGE,

    /** The illustration on its own, as Scryfall crops it. Roughly 4:3, and never a whole card. */
    ART_CROP,
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
         * shows the placeholder and falls back to the always-available card text.
         */
        fun frontOf(
            card: Card,
            size: CardArtSize = CardArtSize.SMALL,
        ): CardArtRequest? = card.printings.firstOrNull()?.let { of(it, CardArtFace.FRONT, size) }
    }
}
