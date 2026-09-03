package magefree.designsystem.card

/*
 * The counter kinds the shipped font can draw.
 *
 * A counter used to be told apart only by the colour [CounterPalette] handed it, which says *this is a
 * different kind from that one* and nothing about which kind. The font draws thirty-odd of them by
 * name, so the common ones can say what they are: a charge counter is a lightning bolt, a stun counter
 * is a stun counter.
 *
 * **The colour stays.** It is what still distinguishes the kinds with no glyph, and it is what makes
 * two glyphless counters on one card tellable apart at all. The glyph is added information, not a
 * replacement for the system underneath it.
 *
 * **The names are upstream's, checked against `CounterType`.** The stylesheet has art for several
 * things that are not counters at all — goad, damage, a skull — and those are left out rather than
 * mapped onto something that merely sounds similar.
 *
 * The rule cuts the other way too, and **poison** is the case worth naming: it is one of the most
 * common counters in play, the stylesheet draws no icon for it, and there is an obvious candidate
 * sitting next door in the infect and toxic glyphs. It is left unmapped anyway. Those glyphs mean the
 * ability that hands out the counter, not the counter, and a picture that is nearly right is the kind
 * of thing nobody ever goes back and checks.
 */

/**
 * Glyphs for the counter kinds upstream actually names, by that name.
 *
 * Every key here was checked against `mage.counters.CounterType`'s own strings; the values come from
 * `mana.css`'s `ms-counter-*` rules.
 */
private val CounterGlyphs: Map<String, Char> =
    mapOf(
        "loyalty" to Char(0xE937),
        "charge" to Char(0xE92D),
        "stun" to Char(0xE9C2),
        "shield" to Char(0xE9C3),
        "lore" to Char(0xE936),
        "time" to Char(0xE942),
        "flame" to Char(0xE931),
        "flood" to Char(0xE932),
        "gold" to Char(0xE934),
        "ki" to Char(0xE935),
        "doom" to Char(0xE92F),
        "echo" to Char(0xE930),
        "fungus" to Char(0xE933),
        "muster" to Char(0xE93A),
        "verse" to Char(0xE945),
        "vortex" to Char(0xE946),
        "slime" to Char(0xE941),
        "scream" to Char(0xE93E),
        "brick" to Char(0xE92C),
        "pin" to Char(0xE93C),
        "devotion" to Char(0xE92E),
        "rad" to Char(0xEA50),
        "finality" to Char(0xEA54),
        "mining" to Char(0xE938),
        "arrow" to Char(0xE92B),
        "void" to Char(0xE9EC),
        "deathtouch" to Char(0xEA51),
        // Not under `ms-counter-*` in the stylesheet, but the same picture by another name: the energy
        // counter is drawn with the `{E}` symbol, and tickets and acorns have their own.
        "energy" to Char(0xE907),
        "ticket" to Char(0xE9C4),
        "acorn" to Char(0xE929),
    )

/** A `+1/+1`, `-1/-1`, `+1/+0` and so on — upstream builds these names in `CardUtil`. */
private val BoostCounterName = Regex("""([+-])(\d+)/([+-])(\d+)""")

private val BoostPlus = Char(0xE93D)
private val BoostMinus = Char(0xE939)
private val BoostPlusUneven = Char(0xE944)
private val BoostMinusUneven = Char(0xE943)

/**
 * The glyph for [counterName], or `null` when the font has none.
 *
 * `null` means the counter draws as it always did — its colour and its count — which is a working
 * counter and not a broken one. The kinds are an open set upstream, hundreds of them and a new set can
 * add one, so a table that covers everything is not a thing that can exist.
 */
fun counterGlyph(counterName: String): Char? {
    CounterGlyphs[counterName]?.let { return it }

    // The boost counters are generated names rather than listed ones, so they are matched by shape.
    // `mana.css` draws an even `+1/+1` differently from an uneven `+1/+0`, which is the distinction a
    // player cares about: one is a straight buff, the other changes the creature's shape.
    val boost = BoostCounterName.matchEntire(counterName) ?: return null
    val (firstSign, firstAmount, secondSign, secondAmount) = boost.destructured
    val even = firstSign == secondSign && firstAmount == secondAmount
    return when {
        firstSign == "+" && even -> BoostPlus
        firstSign == "+" -> BoostPlusUneven
        even -> BoostMinus
        else -> BoostMinusUneven
    }
}
