package magefree.feature.game.board

import magefree.network.game.AbilityChoice
import magefree.network.game.ChoiceOption
import magefree.network.game.GameCard
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.ManaPool
import magefree.network.game.ManaType
import magefree.network.game.MultiAmountEntry
import magefree.network.game.PlayableObject
import magefree.network.game.PromptOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic coverage of [controlsFor] — the projection of the server's outstanding prompt onto the
 * floating controls that answer it (story 0057).
 *
 * Every test here exists because of a specific way the controls could offer the player something the
 * server did not: a target the prompt never listed, a cancel where the prompt is required, a button on
 * a prompt kind nothing knows how to answer, or a mana payment worked out on the device. Each was
 * demonstrated failing against an implementation that did exactly that before the correct one was
 * written (verification standard 1).
 */
class BoardControlsTest {
    // ---- there are controls exactly when the server is waiting --------------------------------------

    @Test
    fun `offers no controls at all when the server is not waiting on the viewer`() {
        // `GameState.prompt` is 0052's "**the** outstanding question, or null when the server is not
        // waiting on the viewer". No prompt means no question, so there is nothing to answer — and in
        // particular a board with playable objects but no prompt offers nothing.
        val state = baseState().copy(prompt = null, playable = listOf(PlayableObject("h-1")))

        assertNull(controlsFor(state))
    }

    // ---- Select: play what the server offered, or pass ----------------------------------------------

    @Test
    fun `a select offers exactly the objects the server listed in playable`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt = GamePrompt.Select(message = "Select an ability to play"),
                    playable = listOf(PlayableObject("h-1"), PlayableObject("y-1")),
                ),
            )

        assertTrue(controls is PromptControlsUi.Priority)
        assertEquals(setOf("h-1", "y-1"), controls!!.pickableObjectIds)
        assertEquals(BoardAction.PlayObject("h-1"), controls.actionFor("h-1"))
        assertNull("nothing the server did not offer may be actioned", controls.actionFor("o-1"))
    }

    @Test
    fun `a select always offers a pass, including when the server offers nothing to play`() {
        // Holding priority with nothing playable is a real state (requirements §4.2, observed live), and
        // it is the one where a board that only lit up cards would look frozen.
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.Select(message = "Select"), playable = emptyList()))

        assertEquals(emptySet<String>(), controls!!.pickableObjectIds)
        assertEquals(listOf(BoardAction.PassPriority), controls.buttons.map { it.action })
        assertEquals(PASS_LABEL, controls.buttons.single().label)
    }

    @Test
    fun `a select offers the special button only when the server sent one`() {
        val withoutSpecial = controlsFor(baseState().copy(prompt = GamePrompt.Select(message = "Select")))
        assertFalse(withoutSpecial!!.buttons.any { it.action == BoardAction.UseSpecial })

        val withSpecial =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Select(
                            message = "Select",
                            options = PromptOptions(text = mapOf(PromptOptions.SPECIAL_BUTTON to "Cast with delve")),
                        ),
                ),
            )
        assertEquals(
            "the special button exists because the server said it does",
            "Cast with delve",
            withSpecial!!.buttons.single { it.action == BoardAction.UseSpecial }.label,
        )
    }

    // ---- Target: the server's candidates, one pick at a time ----------------------------------------

    @Test
    fun `a target prompt offers exactly the ids the server marked, from both places it sends them`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select targets (selected 0 of 2, min 1) to divide 2 damage",
                            targetIds = listOf("o-1"),
                            options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_TARGETS to listOf("y-1"))),
                        ),
                ),
            )

        assertTrue(controls is PromptControlsUi.Targeting)
        assertEquals(setOf("o-1", "y-1"), controls!!.pickableObjectIds)
        assertEquals(BoardAction.ChooseTarget("y-1"), controls.actionFor("y-1"))
        assertNull(controls.actionFor("h-1"))
    }

    @Test
    fun `a tap on a candidate is one pick, never an accumulator`() {
        // §17.2, verified live: the server re-prompts with an updated count and a narrowed candidate set
        // after each pick, so the action a tap produces must be the pick itself.
        val controls =
            controlsFor(baseState().copy(prompt = GamePrompt.Target(message = "Select targets", targetIds = listOf("o-1", "y-1"))))

        assertEquals(BoardAction.ChooseTarget("o-1"), controls!!.actionFor("o-1"))
        assertEquals(BoardAction.ChooseTarget("y-1"), controls.actionFor("y-1"))
    }

    @Test
    fun `the closing button reads cancel before a pick and done after one`() {
        val prompt = GamePrompt.Target(message = "Select targets", targetIds = listOf("o-1"), isRequired = false)

        val beforeAPick = controlsFor(baseState().copy(prompt = prompt), hasPickedTarget = false)!!
        assertEquals(listOf(CANCEL_CAST_LABEL), beforeAPick.buttons.map { it.label })
        assertEquals(listOf(BoardAction.CancelPrompt), beforeAPick.buttons.map { it.action })

        val afterAPick = controlsFor(baseState().copy(prompt = prompt), hasPickedTarget = true)!!
        assertEquals(
            "the player's confirmation is the final done (§17.2)",
            BoardAction.FinishTargeting,
            afterAPick.buttons.first { it.label == DONE_LABEL }.action,
        )
        assertTrue("backing out is still available", afterAPick.buttons.any { it.action == BoardAction.CancelPrompt })
    }

    @Test
    fun `the server's own chosenTargets is preferred over our record of what we sent`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select targets",
                            targetIds = listOf("o-1"),
                            options = PromptOptions(ids = mapOf(PromptOptions.CHOSEN_TARGETS to listOf("y-1"))),
                        ),
                ),
                hasPickedTarget = false,
            )

        assertEquals(setOf("y-1"), controls!!.chosenObjectIds)
        assertTrue((controls as PromptControlsUi.Targeting).hasPicked)
        assertTrue(controls.buttons.any { it.label == DONE_LABEL })
    }

    @Test
    fun `a required target prompt offers no cancel until something has been picked`() {
        // The server refuses a decline of a required prompt with too few targets and re-asks, so
        // offering the button before then would be offering the player a control that cannot work.
        val required = GamePrompt.Target(message = "Select a starting player", targetIds = listOf("p-1"), isRequired = true)

        assertEquals(emptyList<String>(), controlsFor(baseState().copy(prompt = required))!!.buttons.map { it.label })
        assertEquals(
            listOf(DONE_LABEL),
            controlsFor(baseState().copy(prompt = required), hasPickedTarget = true)!!.buttons.map { it.label },
        )
    }

    @Test
    fun `a target prompt carrying its own cards offers them as candidates`() {
        // The scry decision arrives exactly like this (requirements §11.3, verified live): an ordinary
        // Target prompt carrying the real card, which is not on the board to be tapped.
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select up to one card to PUT on the BOTTOM of your library (Scry)",
                            cards = listOf(GameCard(id = "c-1", name = "Thoughtseize")),
                            targetIds = listOf("c-1"),
                        ),
                ),
            )

        assertEquals(listOf("Thoughtseize"), controls!!.candidateCards.map { it.card.name })
        assertEquals(BoardAction.ChooseTarget("c-1"), controls.actionFor("c-1"))
    }

    // ---- mana: render the choice, never compute a payment -------------------------------------------

    @Test
    fun `a mana prompt offers the sources the server listed and the pool it can see`() {
        val controls =
            controlsFor(
                baseState(pool = ManaPool(red = 2)).copy(
                    prompt = GamePrompt.PlayMana(message = "Pay 1R"),
                    playable = listOf(PlayableObject("y-1")),
                ),
            )

        assertTrue(controls is PromptControlsUi.Mana)
        assertEquals(setOf("y-1"), controls!!.pickableObjectIds)
        assertEquals(BoardAction.PlayManaSource("y-1"), controls.actionFor("y-1"))
        assertEquals(
            "only mana actually floating may be spent",
            listOf(BoardAction.UnlockMana(ManaType.Red)),
            controls.buttons.map { it.action }.filterIsInstance<BoardAction.UnlockMana>(),
        )
        assertTrue("the mana step is where cancel was first proven (§6.4a)", controls.buttons.any { it.action == BoardAction.CancelPrompt })
    }

    @Test
    fun `an empty pool offers nothing to unlock`() {
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.PlayMana(message = "Pay 1R")))

        assertTrue(controls!!.buttons.none { it.action is BoardAction.UnlockMana })
    }

    // ---- the prompts answered from their own content ------------------------------------------------

    @Test
    fun `an ask prefers the server's own button labels over yes and no`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Ask(
                            message = "Mulligan down to 6 cards?",
                            options =
                                PromptOptions(
                                    text =
                                        mapOf(
                                            PromptOptions.LEFT_BUTTON_TEXT to "Mulligan",
                                            PromptOptions.RIGHT_BUTTON_TEXT to "Keep",
                                        ),
                                ),
                        ),
                ),
            )

        assertEquals(listOf("Mulligan", "Keep"), controls!!.buttons.map { it.label })
        assertEquals(
            listOf(BoardAction.AnswerAsk(yes = true), BoardAction.AnswerAsk(yes = false)),
            controls.buttons.map { it.action },
        )
    }

    @Test
    fun `an ask falls back to yes and no only when the server sent no labels`() {
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.Ask(message = "Do you want to?")))

        assertEquals(listOf(YES_LABEL, NO_LABEL), controls!!.buttons.map { it.label })
    }

    @Test
    fun `a choose-ability offers each ability and a way out of the cast`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.ChooseAbility(
                            message = "Choose an ability",
                            choices = listOf(AbilityChoice("a-1", "{T}: Add {R}"), AbilityChoice("a-2", "{T}: Add {C}")),
                        ),
                ),
            )

        assertEquals(
            listOf(BoardAction.ChooseAbility("a-1"), BoardAction.ChooseAbility("a-2"), BoardAction.CancelPrompt),
            controls!!.buttons.map { it.action },
        )
    }

    @Test
    fun `a choose-choice offers each option, and the special arm only when the server named one`() {
        val plain =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.ChooseChoice(
                            message = "Choose a colour",
                            choices = listOf(ChoiceOption("R", "Red"), ChoiceOption("G", "Green")),
                        ),
                ),
            )
        assertEquals(listOf("Red", "Green"), plain!!.buttons.map { it.label })
        assertEquals(BoardAction.ChooseChoice("R"), plain.buttons.first().action)

        val special =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.ChooseChoice(
                            message = "Choose a colour",
                            choices = listOf(ChoiceOption("R", "Red")),
                            specialText = "Choose at random",
                        ),
                ),
            )
        assertEquals(
            BoardAction.ChooseChoice("R", special = true),
            special!!.buttons.single { it.label == "Choose at random" }.action,
        )
    }

    @Test
    fun `a choose-pile offers both piles and shows what is in them`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.ChoosePile(
                            message = "Choose a pile",
                            pile1 = listOf(GameCard(id = "c-1", name = "Mountain")),
                            pile2 = listOf(GameCard(id = "c-2", name = "Forest"), GameCard(id = "c-3", name = "Island")),
                        ),
                ),
            )

        assertEquals(listOf("$PILE_LABEL 1 (1)", "$PILE_LABEL 2 (2)"), controls!!.buttons.map { it.label })
        assertEquals(
            listOf(BoardAction.ChoosePile(first = true), BoardAction.ChoosePile(first = false)),
            controls.buttons.map { it.action },
        )
        assertEquals(listOf("Mountain", "Forest", "Island"), controls.candidateCards.map { it.card.name })
    }

    @Test
    fun `a get-amount asks for a number inside the server's own bounds`() {
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.GetAmount(message = "How many?", min = 1, max = 4)))

        assertEquals(AmountRequestUi(min = 1, max = 4, kind = AmountKind.Amount), controls!!.amountRequest)
    }

    @Test
    fun `an X announcement is answered with announceX and can be cancelled`() {
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.PlayXMana(message = "Announce X")))

        assertEquals(AmountKind.AnnounceX, controls!!.amountRequest?.kind)
        assertEquals(listOf(BoardAction.CancelPrompt), controls.buttons.map { it.action })
    }

    @Test
    fun `a multi-amount keeps the server's rows in the server's order`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.GetMultiAmount(
                            message = "Divide 2 damage",
                            entries =
                                listOf(
                                    MultiAmountEntry(message = "Goblin A", min = 0, max = 2, defaultValue = 1),
                                    MultiAmountEntry(message = "Goblin B", min = 0, max = 2),
                                ),
                            min = 2,
                            max = 2,
                        ),
                ),
            )

        // Upstream parses the reply positionally, so the order here is the order it is sent in.
        assertEquals(listOf("Goblin A", "Goblin B"), controls!!.amountRows.map { it.label })
        assertEquals(listOf(1, 0), controls.amountRows.map { it.initial })
    }

    // ---- the prompt that must never become a control ------------------------------------------------

    @Test
    fun `an unrecognised prompt is a notice with nothing to press`() {
        // 0052 gives `Unrecognised` no answering method on purpose: nothing knows what a valid reply is.
        // A control here would be one the player cannot satisfy.
        val controls = controlsFor(baseState().copy(prompt = GamePrompt.Unrecognised(type = "GAME_SOMETHING_NEW")))

        assertTrue(controls is PromptControlsUi.Notice)
        assertEquals(emptyList<ControlButton>(), controls!!.buttons)
        assertFalse(controls.isAnswerable)
        assertEquals(emptySet<String>(), controls.pickableObjectIds)
        assertNull("no board object may be actioned either", controls.actionFor("h-1"))
        assertEquals(UNRECOGNISED_PROMPT_NOTICE, controls.message)
    }

    // ---- the server's HTML never reaches a control ---------------------------------------------------

    @Test
    fun `prompt text and button labels are stripped of the server's markup`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Ask(
                            message = "Mulligan to <font color='#20B2AA'>6</font>?",
                            options = PromptOptions(text = mapOf(PromptOptions.LEFT_BUTTON_TEXT to "<b>Mulligan</b>")),
                        ),
                ),
            )

        assertEquals("Mulligan to 6?", controls!!.message)
        assertEquals("Mulligan", controls.buttons.first().label)
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private fun baseState(pool: ManaPool = ManaPool()) =
        GameState(
            gameId = "g-1",
            hasSnapshot = true,
            viewerPlayerId = "p-you",
            viewerHasPriority = true,
            players =
                listOf(
                    GamePlayer(playerId = "p-opp", name = "Computer"),
                    GamePlayer(playerId = "p-you", name = "you", isViewer = true, manaPool = pool),
                ),
        )
}
