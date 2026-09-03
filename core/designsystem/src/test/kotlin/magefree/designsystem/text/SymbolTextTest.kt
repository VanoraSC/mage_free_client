package magefree.designsystem.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Splitting the server's text into literals and symbols.
 *
 * The cases worth pinning are the ones a naive split gets wrong: a hybrid symbol is **one** symbol
 * with a slash in it, and a stray brace in prose is prose. Both appear in real server strings — mana
 * costs are full of the first, and rules text and prompt messages are full of ordinary English that
 * happens to contain punctuation.
 */
class SymbolTextTest {
    @Test
    fun `a mana cost is nothing but symbols`() {
        // The format the server actually sends. Not "1G" — that is a shape no upstream call produces,
        // and it is what most of this project's fixtures wrongly used.
        assertEquals(
            listOf(SymbolChunk.Symbol("1"), SymbolChunk.Symbol("G")),
            parseSymbolText("{1}{G}"),
        )
    }

    @Test
    fun `symbols sit inside real sentences`() {
        assertEquals(
            listOf(
                SymbolChunk.Symbol("T"),
                SymbolChunk.Literal(": Add "),
                SymbolChunk.Symbol("G"),
                SymbolChunk.Literal("."),
            ),
            parseSymbolText("{T}: Add {G}."),
        )
    }

    @Test
    fun `a hybrid is one symbol, not two`() {
        // `{W/U}` is a single printed symbol. Splitting on the slash would draw two halves of a thing
        // that does not exist, and would ask for art by a code the server never used.
        assertEquals(listOf(SymbolChunk.Symbol("W/U")), parseSymbolText("{W/U}"))
        assertEquals(listOf(SymbolChunk.Symbol("2/W")), parseSymbolText("{2/W}"))
        assertEquals(listOf(SymbolChunk.Symbol("G/P")), parseSymbolText("{G/P}"))
    }

    @Test
    fun `text with no symbols is left exactly alone`() {
        assertEquals(listOf(SymbolChunk.Literal("Choose a target")), parseSymbolText("Choose a target"))
        assertEquals(emptyList<SymbolChunk>(), parseSymbolText(""))
    }

    @Test
    fun `an unclosed brace is prose, not half a symbol`() {
        // Server messages are English. A lone brace is far likelier to be a stray character than an
        // intent to draw something, and swallowing the rest of the line would lose real words.
        assertEquals(listOf(SymbolChunk.Literal("Pay {2 or more")), parseSymbolText("Pay {2 or more"))
    }

    @Test
    fun `an empty pair of braces is kept as it arrived`() {
        assertEquals(listOf(SymbolChunk.Literal("a {} b")), parseSymbolText("a {} b"))
    }

    @Test
    fun `the surrounding words survive on both sides`() {
        assertEquals(
            listOf(SymbolChunk.Literal("Pay "), SymbolChunk.Symbol("2"), SymbolChunk.Symbol("R"), SymbolChunk.Literal(" now")),
            parseSymbolText("Pay {2}{R} now"),
        )
    }

    @Test
    fun `nothing is lost, whatever the input`() {
        // The invariant that matters more than any single case: putting the pieces back together must
        // give the original string. A parser that silently drops a character is a parser that edits
        // the server's words.
        listOf(
            "{1}{G}",
            "{T}: Add {G}.",
            "Pay {2}{R} now",
            "Pay {2 or more",
            "a {} b",
            "no symbols here",
            "{W/U}{B/G}{2/W}",
            "}{",
        ).forEach { original ->
            val rebuilt =
                parseSymbolText(original).joinToString("") { chunk ->
                    when (chunk) {
                        is SymbolChunk.Literal -> chunk.text
                        is SymbolChunk.Symbol -> "{${chunk.code}}"
                    }
                }
            assertEquals("parsing lost something in \"$original\"", original, rebuilt)
        }
    }
}
