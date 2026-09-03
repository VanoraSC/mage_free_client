package magefree.designsystem.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import magefree.designsystem.text.SymbolText
import magefree.designsystem.theme.Spacing

/*
 * The symbols, drawn the way the app will actually meet them.
 *
 * Two things are worth looking at here and neither shows up in a unit test. The first is that a symbol
 * sits *on the line* — a mana cost inside a sentence has to share a baseline with the words around it,
 * and a glyph that rides high or low is the tell that the placeholder alignment is wrong. The second is
 * that an unknown code still reads: the last row sends something no font has a glyph for, and it must
 * come out as the literal token rather than as a blank or a wrong picture.
 */

/** Every symbol family the shipped font covers, plus the fallback, in the server's own text. */
@Composable
fun SymbolGallery(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SymbolRow("Colours", "{W} {U} {B} {R} {G} {C}")
        SymbolRow("Generic", "{0} {1} {2} {3} {5} {10} {16} {20} {X}")
        SymbolRow("Hybrid", "{W/U} {U/B} {B/R} {R/G} {G/W} {W/B} {U/R}")
        SymbolRow("Two-generic hybrid", "{2/W} {2/U} {2/B} {2/R} {2/G}")
        SymbolRow("Phyrexian", "{W/P} {U/P} {B/P} {R/P} {G/P} {P}")
        SymbolRow("Other", "{T} {Q} {S} {E} {Y} {Z}")

        // Symbols inside sentences, which is the only place the app ever puts them: a cost on a card,
        // a rules line on the battlefield, a payment the server is asking for.
        SymbolRow("In a cost", "{1}{G}{G}")
        SymbolRow("In a rules line", "{T}: Add {G}. Activate only if you control a Forest.")
        SymbolRow("In a prompt", "Pay {2}{R} to keep Grizzly Bears on the battlefield?")

        // The fallback. A set adds a symbol, the font has never heard of it, and the player still gets
        // the information rather than a hole in the sentence.
        SymbolRow("Unknown code", "Pay {WUBRG} somehow")
    }
}

/** One labelled line of the gallery: what it is showing, and the server string that shows it. */
@Composable
private fun SymbolRow(
    label: String,
    text: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SymbolText(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
