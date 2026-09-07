package magefree.feature.game.table

import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GameCounter
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which lands share a stack, and which pointedly do not.
 *
 * A stack promises *these are interchangeable — read one and you have read them all*, so every test
 * here is about a difference that breaks the promise. The failure mode a loose key produces is not a
 * crash: it is a board that says a player has four identical Plains when one of them is carrying an
 * Aura. It would be read and believed.
 *
 * The one difference that deliberately does **not** split a stack is tap state, because that is what
 * the stack has two halves for — and it is the whole reason a tapping card has somewhere to travel to.
 */
class TableLandStackTest {
    @Test
    fun `four identical lands are one stack`() {
        val stacks = sideWith((1..4).map { plains("p$it") }).landStacks()

        assertEquals(1, stacks.size)
        assertEquals(4, stacks.single().count)
    }

    @Test
    fun `tapping does not split the stack, it moves a card to its other half`() {
        // The worked example. Four Plains stay one stack throughout; what changes is how many are in
        // each half. Two separate stacks would drift apart across the turn and stop reading as one
        // thing the player has four of.
        val none = sideWith((1..4).map { plains("p$it") }).landStacks().single()
        val one = sideWith(listOf(plains("p1", tapped = true)) + (2..4).map { plains("p$it") }).landStacks().single()
        val two = sideWith((1..2).map { plains("p$it", tapped = true) } + (3..4).map { plains("p$it") }).landStacks().single()

        assertEquals(4 to 0, none.untapped.size to none.tapped.size)
        assertEquals(3 to 1, one.untapped.size to one.tapped.size)
        assertEquals(2 to 2, two.untapped.size to two.tapped.size)
    }

    @Test
    fun `a land the server is still offering stacks with one it is not`() {
        // The worked example above passes with `playable` empty, which is not how a real game arrives:
        // an untapped Swamp is in `canPlayObjects` — you can tap it for mana — and a tapped one is not.
        // With that signal left in the stack's key, using a land split the pile in two, and a board of
        // nine Swamps drew four upright in one stack and five leaning in another beside it. Found on a
        // real board, and invisible to every fixture that forgot to say what the server was offering.
        val permanents = (1..5).map { plains("t$it", tapped = true) } + (1..4).map { plains("u$it") }
        val state =
            stateWith(permanents).copy(
                // Exactly what the server sends: only the untapped ones can still be tapped for mana.
                playable = (1..4).map { PlayableObject("u$it") },
            )

        val stack = battlefieldModel(state).viewer!!.landStacks().single()

        assertEquals(4 to 5, stack.untapped.size to stack.tapped.size)
    }

    @Test
    fun `different lands are different stacks`() {
        val side = sideWith(listOf(plains("p1"), plains("p2"), forest("f1")))

        assertEquals(listOf(2, 1), side.landStacks().map { it.count })
    }

    @Test
    fun `a counter keeps a land out of the stack`() {
        // A Plains with a counter on it is not one of the other three Plains, however identical the
        // card is. Every property that makes one member different keeps it out.
        val marked = plains("p4").let { it.copy(card = it.card.copy(counters = listOf(GameCounter("charge", 1)))) }
        val side = sideWith((1..3).map { plains("p$it") } + marked)

        assertEquals(listOf(3, 1), side.landStacks().map { it.count })
    }

    @Test
    fun `an attachment keeps a land out of every stack, absolutely`() {
        // Not another field in the key: an attachment attaches to one specific instance. Two
        // identically-enchanted lands still do not stack, because each carries its own aura.
        //
        // The auras themselves are deliberately absent from this snapshot, which is the sharper half
        // of the test: the rule reads what the *server* said is attached, not what we managed to
        // resolve, so a partial snapshot cannot quietly merge two enchanted permanents into one.
        val enchanted = plains("p3").copy(attachments = listOf("aura"))
        val alsoEnchanted = plains("p4").copy(attachments = listOf("aura2"))
        val side = sideWith(listOf(plains("p1"), plains("p2"), enchanted, alsoEnchanted))

        assertEquals(listOf(2, 1, 1), side.landStacks().map { it.count })
    }

    @Test
    fun `playability does not split a land stack, because for a land it restates tap state`() {
        // **This test used to assert the opposite** — that a playable land and an unplayable one were
        // different stacks, on the reasoning that stacking them would draw a highlight over lands the
        // server had not offered. The reasoning held for the fixture and not for a game: what makes a
        // land unplayable is that it is *tapped*, so the rule split every pile the moment one of its
        // lands was used. A board of nine Swamps drew four upright in one stack and five leaning in
        // another beside it.
        //
        // The halves already carry the distinction, and each is drawn from its own copies, so nothing
        // borrows a mark from the other side.
        val state = stateWith((1..4).map { plains("p$it") }).copy(playable = listOf(PlayableObject(objectId = "p1")))
        val side = battlefieldModel(state).viewer!!

        assertEquals(listOf(4), side.landStacks().map { it.count })
    }

    @Test
    fun `a tap acts on the topmost untapped copy`() {
        // The one a hand would reach for: lowest and furthest right, which is the last of the upright
        // half. Which server id that is does not matter — they are identical — but it has to be one
        // that is actually still untapped, or the board would be asking to tap a tapped land.
        val stack = sideWith(listOf(plains("p1", tapped = true)) + (2..4).map { plains("p$it") }).landStacks().single()

        assertEquals(stack.untapped.last().id, stack.tapActionId)
        assertTrue("the tap target should be untapped", stack.untapped.any { it.id == stack.tapActionId })
    }

    @Test
    fun `a fully tapped stack offers nothing to tap`() {
        val stack = sideWith((1..3).map { plains("p$it", tapped = true) }).landStacks().single()

        assertNull(stack.tapActionId)
        assertEquals(3, stack.count)
    }

    @Test
    fun `only lands stack, because only lands are numerous and identical`() {
        // Two identical creatures are still two creatures: the moment either takes damage, blocks or
        // gains a counter they diverge, and a board that had collapsed them has to un-collapse mid
        // combat. Nothing is gained and the movement is a lie.
        val side = sideWith((1..4).map { bears("b$it") })

        assertEquals(4, side.inRole(PermanentRole.Creature).size)
        assertTrue("creatures should not have been gathered into stacks", side.landStacks().isEmpty())
    }
}

private fun stateWith(permanents: List<GamePermanent>) =
    GameState(
        gameId = "g",
        viewerPlayerId = "me",
        players = listOf(GamePlayer(playerId = "me", name = "Me", isViewer = true, battlefield = permanents)),
    )

private fun sideWith(permanents: List<GamePermanent>) = battlefieldModel(stateWith(permanents)).viewer!!

private fun plains(
    id: String,
    tapped: Boolean = false,
) = GamePermanent(
    card = GameCard(id = id, name = "Plains", setCode = "10E", collectorNumber = "364", cardTypes = listOf(CardType.Land)),
    isTapped = tapped,
)

private fun forest(id: String) =
    GamePermanent(
        card = GameCard(id = id, name = "Forest", setCode = "10E", collectorNumber = "380", cardTypes = listOf(CardType.Land)),
    )

private fun bears(id: String) =
    GamePermanent(
        card =
            GameCard(
                id = id,
                name = "Grizzly Bears",
                setCode = "10E",
                collectorNumber = "268",
                cardTypes = listOf(CardType.Creature),
                isCreature = true,
            ),
    )
