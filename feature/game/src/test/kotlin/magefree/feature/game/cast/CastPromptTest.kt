package magefree.feature.game.cast

import magefree.network.game.AbilityChoice
import magefree.network.game.ChoiceOption
import magefree.network.game.GamePrompt
import magefree.network.game.PromptOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a cast's questions are put to the player.
 *
 * The assertions that matter are about **the way out**, because it is the part that cannot be guessed
 * and the part a player is hurt by getting wrong. The server accepts a decline for some of these
 * prompts and not others; offering one where it does not is a control the server discards, and the
 * player is left pressing a button that does nothing while the game waits on them.
 *
 * Every rule here is transcribed from `docs/upstream-cast-sequence.md` §3, which was read out of the
 * pinned XMage source rather than inferred from behaviour.
 */
class CastPromptTest {
    @Test
    fun `a mana prompt can always be backed out of`() {
        // Upstream's payment loop takes a boolean as "cancel" at every single prompt, so this is the
        // one place a player can always leave — and, once X has been announced, the only place.
        val model = castPromptModel(GamePrompt.PlayMana(message = "Pay {2}{R}"))

        assertEquals(CastExit.Offered(CANCEL_PAYMENT), model.exit)
    }

    @Test
    fun `an amount prompt offers no way out, and says why`() {
        // The one point of no return in a cast. `announceX` loops until a valid value arrives — there
        // is no cancel arm at all — so a button here would be a lie, and silence would be a mystery.
        val model = castPromptModel(GamePrompt.GetAmount(message = "Announce the value for {X}", min = 0, max = 9))

        val exit = model.exit
        assertTrue("an amount prompt must not offer a way out: $exit", exit is CastExit.NotAccepted)
        assertEquals(AMOUNT_IS_FINAL, (exit as CastExit.NotAccepted).because)
        assertEquals("and the surface still needs the range to ask with", 0..9, model.amount)
    }

    @Test
    fun `a target the server marked required cannot be declined`() {
        // Upstream forces `required = true` for a cast without paying its cost — Suspend and friends.
        // The prompt carries the answer; nothing here re-derives which spells those are.
        val model = castPromptModel(GamePrompt.Target(message = "Choose a target", isRequired = true))

        assertTrue(model.exit is CastExit.NotAccepted)
    }

    @Test
    fun `an optional target is finished rather than cancelled`() {
        // Same wire message as a mana cancel, and the opposite meaning: the player is telling the
        // server they have chosen enough. Labelling it "Cancel" would offer to abandon a cast that is
        // in fact about to proceed.
        val model = castPromptModel(GamePrompt.Target(message = "Choose up to two targets", isRequired = false))

        assertEquals(CastExit.Offered(DONE_CHOOSING), model.exit)
    }

    @Test
    fun `the server's own wording for the way out wins`() {
        // The same rule the Prompt's buttons already follow: upstream phrases these itself and its
        // phrasing is part of the question being asked.
        val model =
            castPromptModel(
                GamePrompt.PlayMana(
                    message = "Pay {1}{G}",
                    options = PromptOptions(text = mapOf(PromptOptions.RIGHT_BUTTON_TEXT to "Give up")),
                ),
            )

        assertEquals(CastExit.Offered("Give up"), model.exit)
    }

    @Test
    fun `a special mana action is offered exactly when the server offers it`() {
        // This is what makes "convoke only while it is still usable" true by construction rather than
        // by a rule of our own: once a special payment has been used, upstream locks out normal mana
        // abilities for the rest of the cast and stops sending the button.
        val offered =
            castPromptModel(
                GamePrompt.PlayMana(
                    message = "Pay {3}",
                    options = PromptOptions(text = mapOf(PromptOptions.SPECIAL_BUTTON to "Convoke")),
                ),
            )
        val withdrawn = castPromptModel(GamePrompt.PlayMana(message = "Pay {3}"))

        assertEquals("Convoke", offered.special)
        assertNull("nothing is offered once the server has stopped offering it", withdrawn.special)
    }

    @Test
    fun `an optional cost is a question, not something to escape`() {
        // Both answers carry on with the spell, so there is nothing to back out of — and a Cancel
        // beside Yes and No would suggest a third outcome the server has no idea about.
        val model = castPromptModel(GamePrompt.Ask(message = "Pay the kicker cost?"))

        assertTrue(model.exit is CastExit.NotAccepted)
    }

    @Test
    fun `a mana prompt and a target prompt are answered on the board`() {
        // The change of *form* the design allows: "Pay {2}{R}" is the board highlighting what can pay
        // it, not a list of names in a dialog. A yes/no is not a board question and must not pretend
        // to be one.
        assertTrue(castPromptModel(GamePrompt.PlayMana(message = "Pay {2}{R}")).choosesOnBoard)
        assertTrue(castPromptModel(GamePrompt.Target(message = "Choose a target")).choosesOnBoard)
        assertTrue(!castPromptModel(GamePrompt.Ask(message = "Kick it?")).choosesOnBoard)
    }

    @Test
    fun `every option the server listed is offered, and none is dropped`() {
        // The narrowing is upstream's and has already happened by the time this arrives: it suppresses
        // the picker entirely for a single mana ability, and `tryToAutoPay` cuts a permanent's
        // abilities down against the unpaid cost first. So a picker that reaches us is one where the
        // choice is **real**, and dropping an option would be choosing which mana to produce — content
        // rather than form, and the one thing the safety rule forbids.
        val model =
            castPromptModel(
                GamePrompt.ChooseAbility(
                    message = "Choose a mana ability",
                    choices =
                        listOf(
                            AbilityChoice(abilityId = "a-1", text = "{T}: Add {R}"),
                            AbilityChoice(abilityId = "a-2", text = "{T}: Add {W}"),
                        ),
                ),
            )

        assertEquals(
            listOf(CastChoice("a-1", "{T}: Add {R}"), CastChoice("a-2", "{T}: Add {W}")),
            model.choices,
        )
    }

    @Test
    fun `an option's label is the server's own text, never rebuilt from its id`() {
        val model =
            castPromptModel(
                GamePrompt.ChooseAbility(
                    message = "Choose a mana ability",
                    choices = listOf(AbilityChoice(abilityId = "6f2c-…", text = "{T}: Add {G}")),
                ),
            )

        assertEquals("{T}: Add {G}", model.choices.single().label)
        assertEquals("6f2c-…", model.choices.single().reply)
    }

    @Test
    fun `a mode choice carries its key, which is not what is shown`() {
        // The key is what the reply carries and the label is what a player reads. Sending the label
        // would be a wrong action submitted to a live game.
        val model =
            castPromptModel(
                GamePrompt.ChooseChoice(
                    message = "Choose a mode",
                    choices = listOf(ChoiceOption(key = "mode-1", label = "Destroy target creature")),
                ),
            )

        assertEquals(CastChoice(reply = "mode-1", label = "Destroy target creature"), model.choices.single())
    }

    @Test
    fun `every prompt the server can send is put somehow`() {
        // The guard on the seam. A prompt subtype added to `:core:network` and not handled here would
        // otherwise reach a player as a blank surface with the game waiting on them — the failure mode
        // that is hardest to notice and worst to hit mid-cast.
        val everyKind =
            listOf(
                GamePrompt.Select(message = "Select"),
                GamePrompt.Target(message = "Target"),
                GamePrompt.Ask(message = "Ask"),
                GamePrompt.ChooseAbility(message = "Ability"),
                GamePrompt.ChoosePile(message = "Pile"),
                GamePrompt.ChooseChoice(message = "Choice"),
                GamePrompt.PlayMana(message = "Mana"),
                GamePrompt.PlayXMana(message = "XMana"),
                GamePrompt.GetAmount(message = "Amount"),
                GamePrompt.GetMultiAmount(message = "MultiAmount"),
                GamePrompt.Unrecognised(type = "SOMETHING_NEW"),
            )

        everyKind.forEach { prompt ->
            val model = castPromptModel(prompt)
            assertTrue("$prompt was put with an empty headline", model.headline.isNotBlank())
            val exit = model.exit
            val explained = exit is CastExit.Offered || (exit as CastExit.NotAccepted).because.isNotBlank()
            assertTrue("$prompt neither offers a way out nor says why not", explained)
        }
    }
}
