package magefree.feature.game.table

import magefree.designsystem.card.CardDisplay
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.CandidateCardUi
import magefree.feature.game.board.CardUi
import magefree.feature.game.board.ControlButton
import magefree.feature.game.board.PromptControlsUi
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A target question with more than one answer, as two piles.
 *
 * The assertions worth having are the ones a plausible implementation gets wrong: that the second pile
 * is *derived* rather than sent, that the columns are read from the server rather than accumulated,
 * and that a prompt answered somewhere else does not open the overlay at all.
 */
class TargetPilesTest {
    @Test
    fun `the second pile is every candidate the player has not chosen`() {
        // Upstream never sends pile 2: `LilianaOfTheVeilEffect` computes it as every permanent that
        // player controls minus pile 1. `possibleTargets` for this prompt *is* every permanent that
        // player controls — `TargetPermanent.possibleTargets` keeps the chosen ones — so the same
        // subtraction here lands on the same set the server will use.
        val piles = pilesFor(pickable = setOf("swamp", "forest", "bears"), chosen = setOf("forest"))!!

        assertEquals(listOf("swamp", "bears"), piles.available.map { it.id })
        assertEquals(listOf("forest"), piles.chosen.map { it.id })
    }

    @Test
    fun `the chosen column is the server's own answer, not a tally of what was sent`() {
        // Every move sends one `chooseTarget` and the columns redraw from the reply. A client that
        // accumulated locally would show a pile the server does not hold the moment one was declined.
        val before = pilesFor(pickable = setOf("swamp", "forest"), chosen = emptySet())!!
        val after = pilesFor(pickable = setOf("swamp", "forest"), chosen = setOf("swamp"))!!

        assertEquals(emptyList<String>(), before.chosen.map { it.id })
        assertEquals(listOf("swamp"), after.chosen.map { it.id })
    }

    @Test
    fun `a card moves back out of the chosen pile, because upstream takes it back`() {
        // `HumanPlayer.choose` removes a response id it already holds, and keeps chosen permanents in
        // `possibleTargets` so they can be sent again. So a chosen card is still in both columns'
        // universe, and the move back is the same message as the move in.
        val piles = pilesFor(pickable = setOf("swamp", "forest"), chosen = setOf("forest"))!!

        assertEquals(listOf("forest"), piles.chosen.map { it.id })
        assertEquals(BoardAction.ChooseTarget("forest"), targeting(setOf("swamp", "forest"), setOf("forest")).actionFor("forest"))
    }

    @Test
    fun `the prompt's own buttons come with it, so no answer is left behind`() {
        // A candidate the board cannot draw — a player, above all — is one of these buttons. Moving
        // the question into the overlay without them would make some prompts unanswerable.
        val buttons = listOf(ControlButton(label = "Done", action = BoardAction.FinishTargeting, isPrimary = true))
        val piles = pilesFor(pickable = setOf("swamp"), chosen = emptySet(), buttons = buttons)!!

        assertEquals(buttons, piles.buttons)
    }

    @Test
    fun `a prompt that carried its own cards is not a pile question`() {
        // A library search's cards are in no zone at all — they are the server's answer to a question
        // rather than a pile — and 0113's panel is already built to draw them.
        val controls =
            targeting(setOf("swamp"), emptySet()).copy(
                candidateCards =
                    listOf(
                        CandidateCardUi(
                            objectId = "swamp",
                            card =
                                CardUi(
                                    name = "Swamp",
                                    display = CardDisplay(name = "Swamp"),
                                    art = null,
                                    powerToughness = null,
                                    isCreature = false,
                                    counters = emptyList(),
                                    isFaceDown = false,
                                ),
                        ),
                    ),
            )

        assertNull(targetPiles(controls, battlefieldModel(boardState())))
    }

    @Test
    fun `a prompt with no candidate on the board has nothing for a column to hold`() {
        assertNull(targetPiles(targeting(setOf("a-player"), emptySet()), battlefieldModel(boardState())))
    }

    @Test
    fun `only a targeting prompt opens the piles`() {
        val priority =
            PromptControlsUi.Priority(
                message = "Play something",
                pickableObjectIds = setOf("swamp"),
                buttons = emptyList(),
            )

        assertNull(targetPiles(priority, battlefieldModel(boardState())))
        assertNull(targetPiles(null, battlefieldModel(boardState())))
    }

    @Test
    fun `the columns follow the board's own order, so a card is where the player last saw it`() {
        // Opponents above, the viewer below, which is top to bottom on screen.
        val piles = pilesFor(pickable = setOf("bears", "swamp", "forest"), chosen = emptySet())!!

        assertEquals(listOf("swamp", "forest", "bears"), piles.available.map { it.id })
    }

    private fun pilesFor(
        pickable: Set<String>,
        chosen: Set<String>,
        buttons: List<ControlButton> = emptyList(),
    ) = targetPiles(targeting(pickable, chosen, buttons), battlefieldModel(boardState()))

    private fun targeting(
        pickable: Set<String>,
        chosen: Set<String>,
        buttons: List<ControlButton> = emptyList(),
    ) = PromptControlsUi.Targeting(
        message = "Select permanents to put in the first pile",
        pickableObjectIds = pickable,
        chosenObjectIds = chosen,
        candidateCards = emptyList(),
        buttons = buttons,
        hasPicked = chosen.isNotEmpty(),
    )

    /** An opponent with two lands, and the viewer with a creature. */
    private fun boardState() =
        GameState(
            gameId = "g",
            viewerPlayerId = "me",
            players =
                listOf(
                    GamePlayer(
                        playerId = "them",
                        name = "Them",
                        battlefield = listOf(land("swamp", "Swamp"), land("forest", "Forest")),
                    ),
                    GamePlayer(
                        playerId = "me",
                        name = "Me",
                        isViewer = true,
                        battlefield = listOf(creature("bears", "Grizzly Bears")),
                    ),
                ),
        )

    private fun land(
        id: String,
        name: String,
    ) = GamePermanent(card = GameCard(id = id, name = name, cardTypes = listOf(CardType.Land)))

    private fun creature(
        id: String,
        name: String,
    ) = GamePermanent(card = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true))
}
