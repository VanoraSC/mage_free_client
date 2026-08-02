package magefree.decks.io

import magefree.decks.model.Deck

/**
 * Outcome of importing deck text: the resolved [deck] plus every [issue] encountered while parsing
 * and resolving. No line is ever silently dropped — a line that cannot become a card is reported as a
 * [DeckImportIssueKind.MALFORMED] or [DeckImportIssueKind.UNRESOLVED] issue, and a line resolved to a
 * default printing (because it named a multi-printing card without a set) is reported as
 * [DeckImportIssueKind.AMBIGUOUS] while still being included in [deck].
 */
data class DeckImportResult(
    val deck: Deck,
    val issues: List<DeckImportIssue> = emptyList(),
) {
    /** Lines that did not match the format grammar and produced no card. */
    val malformed: List<DeckImportIssue> get() = issues.filter { it.kind == DeckImportIssueKind.MALFORMED }

    /** Card names the catalog does not know; these produced no card. */
    val unresolved: List<DeckImportIssue> get() = issues.filter { it.kind == DeckImportIssueKind.UNRESOLVED }

    /** Cards resolved to a default printing because no set was given and several printings exist. */
    val ambiguous: List<DeckImportIssue> get() = issues.filter { it.kind == DeckImportIssueKind.AMBIGUOUS }

    /** True when every line became a card and no line was dropped (ambiguous-but-included is still an error-free import only if you ignore it). */
    val hasErrors: Boolean get() = issues.any { it.kind.isError }
}

/** The kind of problem a single source line hit during import. */
enum class DeckImportIssueKind {
    /** The line did not match the format's grammar (e.g. a bad count, a missing `[SET:NUM]`). No card produced. */
    MALFORMED,

    /** The line parsed, but its card name is unknown to the offline catalog. No card produced. */
    UNRESOLVED,

    /** The line named a multi-printing card with no set, so a default printing was chosen. The card *is* included. */
    AMBIGUOUS,
    ;

    /** Whether this kind means a card was dropped (`MALFORMED`/`UNRESOLVED`) vs. merely flagged (`AMBIGUOUS`). */
    val isError: Boolean get() = this != AMBIGUOUS
}

/**
 * One structured problem tied to a source line. [lineNumber] is 1-based (0 when not line-specific);
 * [text] is the offending line or card name; [message] is a human-readable explanation.
 */
data class DeckImportIssue(
    val kind: DeckImportIssueKind,
    val lineNumber: Int,
    val text: String,
    val message: String,
)
