package magefree.decks.io.internal

/**
 * Card-name normalization ported from XMage's `mage.cards.decks.CardNameUtil.normalizeCardName`
 * (ref e0fe4b6f6a). Third-party exports carry accented/unicode glyphs and inconsistent split-card
 * separators; every ported importer runs its lines through [normalize] first, so we reproduce the
 * same replacements to match XMage's card names byte-for-byte.
 */
internal object CardNames {
    /**
     * XMage's `CardNameUtil.CARD_NAME_PATTERN` — the character class a card name may contain. Used by
     * the MTGA grammar to separate the name from a trailing `(SET) NUM`.
     */
    const val CARD_NAME_PATTERN: String = "[+ !\"&',\\-./0-9:A-Za-z]+"

    private val replacements: List<Pair<String, String>> =
        listOf(
            "&amp;" to "//",
            "///" to "//",
            "Ã†" to "Ae", // Ã†
            "Ã¶" to "A", //  Ã¶
            "ö" to "o", // ö
            "û" to "u", // û
            "í" to "i", // í
            "ï" to "i", // ï
            "î" to "i", // î
            "â" to "a", // â
            "á" to "a", // á
            "à" to "a", // à
            "é" to "e", // é
            "ú" to "u", // ú
            "†" to "+", // †
            "★" to "*", // ★
            "Φ" to "Ph", // Φ
            "ó" to "o", // ó
            "ō" to "o", // ō
            "ä" to "a", // ä
            "ü" to "u", // ü
            "É" to "E", // É
            "ñ" to "n", // ñ
            "꞉" to "", // ꞉
            "®" to "", // ®
            "—" to "", // —
        )

    /** Apply XMage's unicode → ascii replacement chain to a raw card name. */
    fun normalize(name: String): String {
        var out = name
        for ((from, to) in replacements) {
            out = out.replace(from, to)
        }
        return out
    }

    private val singleSlash = Regex("(?<=[^/])\\s*/\\s*(?=[^/])")

    /**
     * Reproduce `TxtDeckImporter`'s split-card separator fix: turn `A//B` into `A // B` and a single
     * `A / B` into `A // B`, so split names match the catalog's `"Fire // Ice"` form.
     */
    fun fixSplitSeparator(name: String): String {
        var out = name
        if (out.contains("//") && !out.contains(" // ")) {
            out = out.replace("//", " // ")
        }
        return singleSlash.replaceFirst(out, " // ")
    }
}
