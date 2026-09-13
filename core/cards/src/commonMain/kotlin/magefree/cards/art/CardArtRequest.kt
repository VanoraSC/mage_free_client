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
 * `Ob Nixilis Reignited Emblem` — a difference of name shape that no rule recovers. Emblems are
 * therefore looked up in upstream's table itself, which is [emblemArtRequest].
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

/**
 * The back of a Magic card — the same picture for every card there has ever been.
 *
 * **Not a printing, and marked as one deliberately.** It is requested by a sentinel [setCode] rather
 * than by inventing a nullable field on every request in the app: a back has no set, no collector
 * number and no name, and the one thing an image source needs to know is that this is *the* back.
 *
 * Used for a card the viewer has not been shown — a card in an opponent's hand — where the honest
 * picture is the one they are actually looking at across the table.
 */
fun cardBackRequest(size: CardArtSize = CardArtSize.SMALL): CardArtRequest =
    CardArtRequest(setCode = CARD_BACK_SET_CODE, collectorNumber = "", size = size)

/**
 * The sentinel set code that means *the card back*.
 *
 * Not a real set, and it cannot collide with one: XMage set codes are alphanumeric, and this is not.
 */
const val CARD_BACK_SET_CODE: String = "//back"

/**
 * The picture for a face-down permanent of a given kind — a morph, a manifest, a foretold card.
 *
 * **A face-down permanent is not just "a card back".** Morph, manifest, cloak, disguise and foretell
 * are five different things a player has to tell apart at a glance: what may be turned up, for how
 * much, and by whom. Magic prints a distinct *helper card* for each, and Scryfall has them, so the
 * board can show the right one instead of five identical brown rectangles.
 *
 * **The printings are upstream's own.** `TokenRepository.createXmageToken` lists a Scryfall URL per
 * kind, gathered under Scryfall's `assistant-cards` tag; these are the set and collector number out of
 * the first URL it registers for each. Taking the *first* rather than one at random is deliberate —
 * upstream randomises among its alternatives for visual variety, and a board where the same morph
 * changes picture between snapshots would be worse than one that always shows the same one.
 *
 * `null` for a kind this build has not heard of, which falls back to the plain [cardBackRequest] —
 * the honest answer for "face-down, and we do not know more than that".
 *
 * @param kind upstream's own image name, from `CardView.getImageFileName()`, which is non-empty
 *   exactly for a face-down or inner-named object (`CardUtil.getCardNameForGUI`).
 */
fun faceDownArtRequest(
    kind: String,
    size: CardArtSize = CardArtSize.SMALL,
): CardArtRequest? =
    when (kind.trim().lowercase()) {
        // TokenRepository: XMAGE_IMAGE_NAME_FACE_DOWN_MORPH → tktk/11
        "morph" -> printing("tktk", "11", size)
        // XMAGE_IMAGE_NAME_FACE_DOWN_MANIFEST → tfrf/4
        "manifest" -> printing("tfrf", "4", size)
        // XMAGE_IMAGE_NAME_FACE_DOWN_DISGUISE and _CLOAK share a printing upstream → tmkm/21
        "disguise", "cloak" -> printing("tmkm", "21", size)
        // XMAGE_IMAGE_NAME_FACE_DOWN_FORETELL → tkhm/23
        "foretell" -> printing("tkhm", "23", size)
        // XMAGE_IMAGE_NAME_FACE_DOWN_MANUAL. Upstream links Wikipedia here with a TODO saying it could
        // not find a Scryfall URL for the back; `cardBackRequest` is that URL, so this is the one
        // place this client is ahead of it.
        "face down" -> cardBackRequest(size)
        else -> null
    }

private fun printing(
    setCode: String,
    collectorNumber: String,
    size: CardArtSize,
): CardArtRequest = CardArtRequest(setCode = setCode, collectorNumber = collectorNumber, size = size)
