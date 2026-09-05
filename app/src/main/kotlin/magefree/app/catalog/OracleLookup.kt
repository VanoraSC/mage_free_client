package magefree.app.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import magefree.cards.CardCatalog
import org.koin.compose.koinInject

/*
 * The printed text of a card, from the device's own catalog.
 *
 * **The wire does not carry it, and that is not an omission.** `CardView.rules` is the *game-aware*
 * form: a creature granted flying until end of turn has flying in it, and a card whose text has been
 * changed by an effect reads as changed. That is the right thing for the board to show about a
 * permanent in play, and the wrong thing to call "what the card says".
 *
 * The printed text is already on the device. `cards.sqlite` is Scryfall-derived and carries every
 * card's oracle rules, so this is a lookup rather than a request — no network, and it works on the
 * first turn of the first game after an install.
 *
 * Looked up by **name**, because that is what a game snapshot always has. The printing does not
 * matter: oracle text is per card, not per printing, which is the whole point of it being *oracle*
 * text.
 */

/**
 * Resolves a card's printed oracle text, or null for one the catalog does not know.
 *
 * A suspending resolver rather than a value, because the catalog is a database and the board asks only
 * for the one card a player is looking at.
 */
@Composable
fun rememberOracleLookup(): suspend (String) -> String? {
    val catalog: CardCatalog = koinInject()
    return remember(catalog) {
        { name ->
            catalog
                .cardByName(name)
                ?.rules
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
        }
    }
}
