package magefree.app.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.feature.cards.rememberCardArtRenderer
import magefree.feature.cards.slotFor

/*
 * Real card art for the component catalog.
 *
 * The design system carries no image dependency on purpose — art reaches it through the abstract
 * `CardArtSlot`, and something outside supplies the loader. That is exactly what happens here: the
 * catalog is hosted by `:app`, which already has the Coil-backed renderer, so the catalog can show
 * real cards without the design system gaining a dependency it deliberately does not have.
 *
 * It matters that the catalog shows real art rather than placeholders. Card components are judged on
 * whether a stat line, a counter and a badge stay readable *over an illustration*, and a flat
 * placeholder is the one background that makes every one of those look fine. It matters twice over
 * now that the card face carries its own name and mana cost: with real art there is nothing to
 * overlay, and with a placeholder there is nothing to read.
 */

/**
 * Resolves a catalog card's name to its art, or null for a card the catalog does not pin.
 *
 * A resolver rather than a single slot, because the board sections show several cards at once — a
 * creature with an Aura on it needs both faces, and an Aura rendered as a blank rectangle would not
 * demonstrate the thing the attachment stack exists to show.
 */
@Composable
fun rememberCatalogArtResolver(): (String) -> CardArtSlot? {
    val renderer = rememberCardArtRenderer()
    return remember(renderer) {
        { name ->
            CatalogPrintings[name]?.let { request ->
                renderer.slotFor(request = request, display = CardDisplay(name = name))
            }
        }
    }
}

/**
 * The printings the catalog shows, pinned rather than looked up.
 *
 * The catalog is a fixed visual-QA surface: a card whose art changed between runs would make it
 * useless for comparing one build against the next. Tenth Edition throughout, so the frames match and
 * the only differences on screen are the ones the components introduce.
 */
private val CatalogPrintings: Map<String, CardArtRequest> =
    mapOf(
        "Grizzly Bears" to request("10E", "268"),
        "Pacifism" to request("10E", "31"),
        "Holy Strength" to request("10E", "22"),
        "Forest" to request("10E", "380"),
        // An Equipment, so the gallery can show a tapped attachment: improvise and convoke tap
        // artifacts to help pay a cost, and an equipped Sword tapped that way is still equipping.
        "Bonesplitter" to request("MRD", "146"),
    )

private fun request(
    setCode: String,
    collectorNumber: String,
) = CardArtRequest(setCode = setCode, collectorNumber = collectorNumber, size = CardArtSize.SMALL)
