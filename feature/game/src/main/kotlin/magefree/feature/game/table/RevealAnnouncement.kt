package magefree.feature.game.table

import magefree.network.game.GameCard
import magefree.network.game.GamePrompt
import magefree.network.game.GameState

/*
 * A reveal, announced.
 *
 * **The server reveals things it never asks about.** Inquisition of Kozilek against a hand with no
 * legal target reveals the hand, finds nothing to choose, and fires no event — so no prompt ever shows
 * the cards. The reveal still reaches the client: upstream pushes the update before it clears
 * `GameState.revealed`, and 0120 folds it into what this seat has seen. What was missing was the moment:
 * nothing said *you have just been shown this*, and upstream keeps a reveal for exactly one snapshot.
 *
 * **Decided here, as a function, rather than in the composition.** Whether a reveal is news is a
 * question about the snapshot and about what has already been shown, and it is the kind of rule that
 * gets quietly wrong when it lives in a `LaunchedEffect`. As a function it is tested on its own.
 */

/**
 * One reveal waiting to be shown.
 *
 * @property key what makes two sightings of a reveal the same reveal — see [revealsToAnnounce].
 * @property name the server's own title for it, which upstream takes from the effect that caused it.
 * @property cards the revealed cards, in the server's order.
 */
data class RevealAnnouncement(
    val key: String,
    val name: String,
    val cards: List<GameCard>,
)

/**
 * The reveals in this snapshot that nothing on screen already shows, oldest first.
 *
 * **Three rules, in order:**
 *
 * 1. **An empty reveal is not a reveal.** A zone with no cards in it announces nothing.
 * 2. **One already queued is not queued again.** Upstream can carry the same reveal across more than one
 *    push — an update, then a narration carrying state — and a player putting it down must not see it
 *    come straight back. A reveal is identified by the **turn**, its **name**, and its **card ids**, which
 *    distinguishes two separate reveals of one hand by two effects without needing an id the wire does
 *    not carry.
 * 3. **One the outstanding question already shows is not announced.** Duress with a legal target reveals
 *    the hand *and* asks for a card from it: the target prompt carries those cards as its candidates, so
 *    they are already in front of the player, and an overlay would cover the question they are part of.
 *    A pile choice carries its cards the same way. No other prompt kind puts cards on screen.
 *
 * @param announced every [RevealAnnouncement.key] already queued this game.
 */
fun GameState.revealsToAnnounce(announced: Set<String>): List<RevealAnnouncement> {
    val onScreen = cardsTheQuestionShows()
    return revealed
        .filter { zone -> zone.cards.isNotEmpty() }
        .map { zone ->
            RevealAnnouncement(
                key = "$turn|${zone.name}|${zone.cards.map { it.id }.sorted().joinToString(",")}",
                name = zone.name,
                cards = zone.cards,
            )
        }.filterNot { reveal -> reveal.key in announced }
        .filterNot { reveal -> onScreen.containsAll(reveal.cards.map { it.id }) }
        // Two sightings of one reveal inside a single snapshot are still one reveal.
        .distinctBy { it.key }
}

/** The ids of every card the outstanding prompt is itself putting in front of the player. */
private fun GameState.cardsTheQuestionShows(): Set<String> =
    when (val question = prompt) {
        is GamePrompt.Target -> question.cards.map { it.id }.toSet()
        is GamePrompt.ChoosePile -> (question.pile1 + question.pile2).map { it.id }.toSet()
        else -> emptySet()
    }
