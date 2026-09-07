package magefree.designsystem.card

import androidx.compose.ui.graphics.Color
import magefree.designsystem.text.SymbolChunk
import magefree.designsystem.text.parseSymbolText

/*
 * What colour a card's frame is.
 *
 * **The colours are sampled, not chosen.** Every value below is the median pixel of a real card's name
 * plate, read off the printing Scryfall serves: Tenth Edition for the five colours and the artifact,
 * and a modern gold card for the multicoloured one, because Tenth Edition has no multicoloured cards
 * at all. Sampling the *name plate* rather than the text box is deliberate and is the one place this
 * departs from the obvious reading of "the text box background": a modern text box is pale paper with
 * a wash of the frame's hue over it, and the white and black ones come out three units apart in every
 * channel — indistinguishable at the size a board card is drawn, which defeats the whole point of
 * colouring it. The name plate is the same frame with the colour actually in it.
 */

/** A card's frame colour — its colour identity, as a card's own printing shows it. */
enum class CardFrameIdentity(
    /** The median pixel of that identity's name plate, from the printing named beside it. */
    val color: Color,
) {
    /** Pacifism, 10E #31. */
    White(Color(0xFFDAD7D2)),

    /** Air Elemental, 10E #64. */
    Blue(Color(0xFFADBAC7)),

    /** Hypnotic Specter, 10E #151. */
    Black(Color(0xFFA3989B)),

    /** Shivan Dragon, 10E #230. */
    Red(Color(0xFFDDB49E)),

    /** Grizzly Bears, 10E #268. */
    Green(Color(0xFFB2BCB4)),

    /** Two colours or more. Aang, Swift Savior, TLA #204 — Tenth Edition prints no gold card. */
    Gold(Color(0xFFCBB780)),

    /** Icy Manipulator, 10E #326. Anything with no colour in its cost lands here. */
    Colorless(Color(0xFFA5AAB2)),

    /**
     * Every land, however many colours it taps for. Yavimaya Coast, SOC #425.
     *
     * A land has its own frame in every set that prints one, and it is a grey-tan that is not any of
     * the five: a dual land is not gold and a Forest is not the green of a green spell. The board says
     * the same thing its printing does.
     */
    Land(Color(0xFFCCC5C2)),
}

/**
 * The frame a card with this [manaCost] and [typeLine] is printed in.
 *
 * **Read off the printed cost, because that is what a frame is.** A card's frame colour follows the
 * colours in its mana cost for all but a handful of cards, and the handful — devoid, colour
 * indicators, a permanent an effect has recoloured — are cases where the *server* knows the answer and
 * the wire does not carry it yet. When it does, this takes the answer instead of working one out.
 *
 * Hybrid and Phyrexian symbols count as every colour they contain, so `{W/U}` is two colours and gold.
 * That is what a hybrid card's frame is: two-toned, which reads as gold at board size.
 *
 * **A land is a land, whatever it taps for**, and is checked before the cost for that reason. Every set
 * that prints a land prints it in the same grey-tan, and colouring a Forest green or a dual land gold
 * would say something its own printing does not.
 */
fun cardFrameIdentity(
    manaCost: String?,
    typeLine: String? = null,
): CardFrameIdentity {
    if (isLand(typeLine)) return CardFrameIdentity.Land
    val colors = colorsIn(manaCost)
    return when {
        colors.size > 1 -> CardFrameIdentity.Gold
        colors.size == 1 -> colors.single()
        else -> CardFrameIdentity.Colorless
    }
}

/** Every colour named anywhere in a cost's symbols, hybrid halves and Phyrexian marks included. */
private fun colorsIn(manaCost: String?): Set<CardFrameIdentity> {
    val cost = manaCost?.takeIf { it.isNotBlank() } ?: return emptySet()
    return parseSymbolText(cost)
        .filterIsInstance<SymbolChunk.Symbol>()
        .flatMap { symbol -> symbol.code.uppercase().mapNotNull(::colorOf) }
        .toSet()
}

/**
 * Whether the server's own type line says this is a land.
 *
 * The type line is upstream's, after continuous effects, so an animated Mutavault is still a land and
 * a creature that an effect turned into one is a land while it is one — which is what its frame would
 * be if somebody printed it in that state.
 */
private fun isLand(typeLine: String?): Boolean = typeLine?.contains(LAND_TYPE, ignoreCase = true) == true

private const val LAND_TYPE = "Land"

private fun colorOf(symbol: Char): CardFrameIdentity? =
    when (symbol) {
        'W' -> CardFrameIdentity.White
        'U' -> CardFrameIdentity.Blue
        'B' -> CardFrameIdentity.Black
        'R' -> CardFrameIdentity.Red
        'G' -> CardFrameIdentity.Green
        else -> null
    }
