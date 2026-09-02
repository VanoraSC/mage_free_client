package magefree.app.catalog

import androidx.compose.runtime.Composable
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
 * catalog is hosted by `:app`, which already has the Coil-backed renderer, so the catalog can show a
 * real card without the design system gaining a dependency it deliberately does not have.
 *
 * It matters that the catalog shows real art. Card components are judged on whether a name, a stat
 * line and a counter stay readable *over an illustration*, and a flat placeholder is the one
 * background that makes every one of those look fine.
 */

/**
 * A [CardArtSlot] showing a real card, or the design system's placeholder when the art is not cached
 * and the device is offline.
 *
 * The printing is pinned rather than looked up: the catalog is a fixed visual-QA surface, and a card
 * whose art changed between runs would make it useless for comparing one build to the next.
 */
@Composable
fun rememberCatalogCardArt(): CardArtSlot {
    val renderer = rememberCardArtRenderer()
    return renderer.slotFor(request = CatalogArtRequest, display = CatalogCard)
}

/** Grizzly Bears, Tenth Edition — a plain creature with unremarkable art, which is what makes it useful. */
private val CatalogArtRequest =
    CardArtRequest(
        setCode = "10E",
        collectorNumber = "268",
        size = CardArtSize.SMALL,
    )

/** The display fields behind the art, used for the placeholder when art cannot be loaded. */
private val CatalogCard =
    CardDisplay(
        name = "Grizzly Bears",
        manaCost = "1G",
        typeLine = "Creature — Bear",
    )
