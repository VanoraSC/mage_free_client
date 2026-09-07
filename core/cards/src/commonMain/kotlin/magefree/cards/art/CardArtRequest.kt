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
 *
 * **A token is identified by its name instead**, because it has no collector number to be identified
 * by — see [tokenName].
 *
 * @property tokenName the token's own name, for an object that has no printing to name. Null for
 *   every ordinary card, and the flag that tells the image source to look the image up by name rather
 *   than by collector number. [collectorNumber] is empty in that case and is not part of the answer.
 */
data class CardArtRequest(
    val setCode: String,
    val collectorNumber: String,
    val face: CardArtFace = CardArtFace.FRONT,
    val size: CardArtSize = CardArtSize.SMALL,
    val tokenName: String? = null,
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

/**
 * The image request for a **token**, which has no printing to be identified by.
 *
 * Upstream gives a token an `expansionSetCode` and an image number but leaves its `cardNumber` empty
 * — `TokenImpl` has the line that would set one commented out — so the ordinary set-plus-number
 * identity simply is not there, and every token on the board fell back to the placeholder.
 *
 * The desktop client answers this with a hand-maintained table of three thousand entries mapping
 * `SET/TokenName` to a Scryfall URL (`ScryfallImageSupportTokens`). The table is not derived, but the
 * *shape* of what it contains is: measured across all 2,661 of its entries, 2,502 point at
 * `t` + the set code, and almost all of the rest at the set code itself. So the set is derivable with
 * one fallback, and Scryfall's `cards/named` endpoint resolves a token by name within a set — which
 * `cards/search` does not, since it hides tokens behind `include_extras`.
 *
 * Verified end to end against a sample of the table's own entries: twelve of fourteen resolved to the
 * exact set and collector number the table names, including two that needed the bare-set fallback.
 * The two that did not are **emblems**, which upstream calls `Emblem Nixilis` where Scryfall calls it
 * `Ob Nixilis Reignited Emblem` — a difference of name shape that no rule recovers. Emblems keep the
 * placeholder, and the table stays available as a later refinement rather than a prerequisite.
 *
 * @param setCode the set upstream chose for the token's image, not the set of the card that made it.
 * @param name the token's name as the server sends it. The `" Token"` suffix is stripped here for the
 *   same reason upstream strips it (`CardImageUtils`): it is XMage's own word and not part of the
 *   card's name on Scryfall.
 */
fun tokenArtRequest(
    setCode: String,
    name: String,
    size: CardArtSize = CardArtSize.SMALL,
): CardArtRequest? {
    val token = name.removeSuffix(TOKEN_NAME_SUFFIX).trim()
    if (setCode.isBlank() || token.isEmpty()) return null
    return CardArtRequest(setCode = setCode, collectorNumber = "", size = size, tokenName = token)
}

/** XMage's own word for a token, which is not part of the name any image source knows it by. */
private const val TOKEN_NAME_SUFFIX = " Token"
