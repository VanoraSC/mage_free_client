package magefree.feature.game.board

import magefree.network.game.AbilityChoice
import magefree.network.game.ChoiceOption
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameState
import magefree.network.game.ManaPool
import magefree.network.game.ManaType
import magefree.network.game.MultiAmountEntry
import magefree.network.game.PhaseStep
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
    fun `an optional target prompt offers both done and cancel from the start (story 0075)`() {
        // Story 0075: an optional (`isRequired = false`) prompt is answerable with zero targets by
        // definition, so Done must be offered from the moment it arrives, not only after a pick — the
        // previous behavior (Done withheld until `hasPicked`) left an "up to one, no legal targets"
        // prompt with no honestly-labeled way to finish, only the unrelated cast-cancel button.
        val prompt = GamePrompt.Target(message = "Select targets", targetIds = listOf("o-1"), isRequired = false)

        val beforeAPick = controlsFor(stateWithBoardCards().copy(prompt = prompt), hasPickedTarget = false)!!
        assertEquals(
            "Done must be available with zero picks on an optional prompt",
            BoardAction.FinishTargeting,
            beforeAPick.buttons.first { it.label == DONE_LABEL }.action,
        )
        assertTrue("cancel is still offered too", beforeAPick.buttons.any { it.action == BoardAction.CancelPrompt })

        val afterAPick = controlsFor(stateWithBoardCards().copy(prompt = prompt), hasPickedTarget = true)!!
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
    fun `an up-to-one target prompt with no legal candidates uses the server's own Done label (story 0075)`() {
        // The exact live scenario: Gideon, Battle-Forged's +2 ("up to one target creature an opponent
        // controls") against a board with no opposing creatures — targetIds empty, required false,
        // and upstream sends its own "Done" label rather than relying on our fallback.
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select up to one creature an opponent controls",
                            targetIds = emptyList(),
                            isRequired = false,
                            options = PromptOptions(text = mapOf(PromptOptions.RIGHT_BUTTON_TEXT to "Done")),
                        ),
                ),
                hasPickedTarget = false,
            )!!

        val doneButton = controls.buttons.singleOrNull { it.action == BoardAction.FinishTargeting }
        assertEquals("the server's own Done label must be used, not our fallback", "Done", doneButton?.label)
    }

    @Test
    fun `a required target prompt offers no cancel until something has been picked`() {
        // The server refuses a decline of a required prompt with too few targets and re-asks, so
        // offering the button before then would be offering the player a control that cannot work.
        // A permanent on the board, so the only buttons in play are the closing ones.
        val required = GamePrompt.Target(message = "Select a target", targetIds = listOf("o-1"), isRequired = true)

        assertEquals(emptyList<String>(), controlsFor(stateWithBoardCards().copy(prompt = required))!!.buttons.map { it.label })
        assertEquals(
            listOf(DONE_LABEL),
            controlsFor(stateWithBoardCards().copy(prompt = required), hasPickedTarget = true)!!.buttons.map { it.label },
        )
    }

    // ---- combat: declaring attackers and blockers (story 0061) --------------------------------------
    //
    // Reachability (standard 2): every id these gate on is produced by the server's own
    // `GamePrompt.Select` during `DeclareAttackers`/`DeclareBlockers` — `possibleAttackers` and
    // `possibleBlockers` in `PromptOptions.ids`, measured live and recorded in requirements §7.2/§7.3.
    // They travel the bridge's generic `optionsView()` (any collection-valued option becomes an id
    // list), so nothing in `:protocol` or `:bridge` is specific to combat.
    //
    // **Every fixture here has `playable` empty**, because that is how the server really sends a
    // declaration (§7.2: `playable = 0`). A fixture whose creatures also appeared in `playable` would
    // pass against the projection that reads `playable`, and would prove nothing.

    @Test
    fun `a declaration offers the attackers the server named, with playable empty`() {
        val controls = controlsFor(declareAttackersState())!!

        assertEquals(
            "the attackers come from possibleAttackers; playable is empty during a declaration",
            setOf("y-1", "y-2"),
            controls.pickableObjectIds,
        )
        assertEquals(BoardAction.ChooseTarget("y-1"), controls.actionFor("y-1"))
        assertEquals(BoardAction.ChooseTarget("y-2"), controls.actionFor("y-2"))
        assertNull("nothing the server did not offer may be declared", controls.actionFor("o-1"))

        assertTrue("a declaration is its own projection, not a priority window", controls is PromptControlsUi.Declaration)
        assertEquals(CombatRole.Attacking, (controls as PromptControlsUi.Declaration).role)
        assertEquals(DECLARE_ATTACKER_ACTION_LABEL, controls.actionLabelFor("y-1"))
    }

    @Test
    fun `a declaration offers the blockers the server named, with playable empty`() {
        val controls = controlsFor(declareBlockersState())!!

        assertEquals(setOf("y-1", "y-2"), controls.pickableObjectIds)
        assertEquals(BoardAction.ChooseTarget("y-1"), controls.actionFor("y-1"))
        assertEquals(CombatRole.Blocking, (controls as PromptControlsUi.Declaration).role)
        assertEquals(DECLARE_BLOCKER_ACTION_LABEL, controls.actionLabelFor("y-1"))
    }

    @Test
    fun `a declaration ends with a done that declines the prompt, never a pass`() {
        // Upstream's *done* for a declaration is the same `false` arm targeting uses (`cancelPrompt`).
        // "Pass priority" would be the wrong word for it and the wrong button on this prompt.
        val controls = controlsFor(declareAttackersState())!!

        assertTrue(controls.buttons.any { it.action == BoardAction.FinishTargeting })
        assertFalse("a declaration is not a priority window", controls.buttons.any { it.action == BoardAction.PassPriority })
    }

    @Test
    fun `blocking is never offered while attackers are being declared, nor the other way round`() {
        // §7.4 (Pete): combat is two assignment problems that never belong to the same player at the
        // same moment. The board must be in exactly one of them.
        val attacking = controlsFor(declareAttackersState())!!
        assertEquals("Select attackers", attacking.message)
        assertEquals(setOf("y-1", "y-2"), attacking.pickableObjectIds)
        assertEquals(CombatRole.Attacking, (attacking as PromptControlsUi.Declaration).role)
        assertEquals(DECLARE_ATTACKERS_NOTE, attacking.role.note())

        val blocking = controlsFor(declareBlockersState())!!
        assertEquals("Select blockers", blocking.message)
        assertEquals(setOf("y-1", "y-2"), blocking.pickableObjectIds)
        assertEquals(CombatRole.Blocking, (blocking as PromptControlsUi.Declaration).role)
        assertEquals(DECLARE_BLOCKERS_NOTE, blocking.role.note())

        // …and even if both keys arrived together — which the server never does — only one role's
        // creatures are offered, never the union.
        val both =
            controlsFor(
                declareAttackersState().copy(
                    prompt =
                        GamePrompt.Select(
                            message = "Select attackers",
                            options =
                                PromptOptions(
                                    ids =
                                        mapOf(
                                            PromptOptions.POSSIBLE_ATTACKERS to listOf("y-1"),
                                            PromptOptions.POSSIBLE_BLOCKERS to listOf("y-2"),
                                        ),
                                ),
                        ),
                ),
            )!!
        assertEquals(setOf("y-1"), both.pickableObjectIds)
    }

    @Test
    fun `all attack is offered only when the server sent it, and never for blocking`() {
        // The server supplies the shortcut for attacking (§7.2) and none for blocking (§7.3). Inventing
        // an "all block" would be inventing a reply the server has no arm for.
        val attacking = controlsFor(declareAttackersState())!!
        val allAttack = attacking.buttons.single { it.action == BoardAction.UseSpecial }
        assertEquals("the label is the server's own specialButton text", "All attack", allAttack.label)
        assertEquals(
            "committing the whole team confirms first (§16.4)",
            ALL_ATTACK_CONFIRM_LABEL,
            allAttack.confirmLabel,
        )
        assertTrue(
            "nothing else in a declaration confirms — a single creature is one tap",
            attacking.buttons.filter { it.confirmLabel != null }.map { it.action } == listOf(BoardAction.UseSpecial),
        )

        val blocking = controlsFor(declareBlockersState())!!
        assertFalse("there is no all-block", blocking.buttons.any { it.action == BoardAction.UseSpecial })

        val noShortcut =
            controlsFor(
                declareAttackersState().copy(
                    prompt =
                        GamePrompt.Select(
                            message = "Select attackers",
                            options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to listOf("y-1"))),
                        ),
                ),
            )!!
        assertFalse(
            "no special button means no shortcut, whatever the step",
            noShortcut.buttons.any { it.action == BoardAction.UseSpecial },
        )
    }

    @Test
    fun `the pairing question the server asks mid-declaration is an ordinary target prompt`() {
        // Both follow-ups — `TargetDefender` and `Select attacker to block` — arrive through
        // `fireSelectTargetEvent` as `GAME_TARGET` (§7.5), which 0057 already answers with
        // `chooseTarget`. Nothing here decides *when* the server asks.
        val controls =
            controlsFor(
                declareAttackersState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select attacker to block",
                            targetIds = listOf("o-1", "o-2"),
                            isRequired = true,
                        ),
                ),
            )!!

        assertTrue(controls is PromptControlsUi.Targeting)
        assertEquals(setOf("o-1", "o-2"), controls.pickableObjectIds)
        assertEquals(BoardAction.ChooseTarget("o-1"), controls.actionFor("o-1"))
    }

    @Test
    fun `a declaration with no eligible creature is still a declaration, not a priority window`() {
        // Read out of `HumanPlayer.selectAttackers`: `possibleAttackers` goes into the options
        // **unconditionally**, and the prompt can still be fired — a player whose creatures all fail
        // `canAttack` gets `Select attackers` with an empty list. Projecting that as priority would put
        // a "Pass priority" button on a prompt that is not a priority window.
        val controls =
            controlsFor(
                declareAttackersState().copy(
                    prompt =
                        GamePrompt.Select(
                            message = "Select attackers",
                            options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to emptyList())),
                        ),
                ),
            )!!

        assertTrue(controls is PromptControlsUi.Declaration)
        assertEquals(CombatRole.Attacking, (controls as PromptControlsUi.Declaration).role)
        assertEquals(emptySet<String>(), controls.pickableObjectIds)
        assertEquals(listOf(BoardAction.FinishTargeting), controls.buttons.map { it.action })
        assertFalse("still not a priority window", controls.buttons.any { it.action == BoardAction.PassPriority })
    }

    @Test
    fun `a select with no combat options is still the ordinary priority window`() {
        // The guard in the other direction: a declaration is distinguished by its options, not by its
        // type, so an ordinary `Select` must not be mistaken for one.
        val controls =
            controlsFor(
                declareAttackersState().copy(
                    prompt = GamePrompt.Select(message = "Play spells and abilities"),
                    playable = listOf(PlayableObject("y-1")),
                ),
            )!!

        assertTrue(controls is PromptControlsUi.Priority)
        assertEquals(BoardAction.PlayObject("y-1"), controls.actionFor("y-1"))
    }

    // ---- candidates that are not cards --------------------------------------------------------------
    //
    // The gap these cover, found on a real device: a Target prompt's candidate ids do not have to name
    // anything the board draws. **Choosing the starting player** — the very first prompt of every game,
    // asked before priority exists — carries two *player* ids and an empty `cards` list. Players are not
    // cards, so nothing on the board carries those ids, and a projection that only lit up matching cards
    // produced a prompt with zero affordances: no candidate, no done (nothing picked), no cancel
    // (`isRequired` is true). The game could not begin.
    //
    // Reachability (standard 2), written out because its absence is what let this ship: *what produces a
    // Target prompt whose ids match no card?* Upstream's start-of-game "who goes first" question does,
    // every game. The same shape reaches us wherever the server offers a player as a target — any burn
    // spell aimed at a face.

    @Test
    fun `a target prompt whose candidates are players is answerable from the panel`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt =
                        GamePrompt.Target(
                            message = "Select a starting player",
                            targetIds = listOf("p-you", "p-opp"),
                            isRequired = true,
                        ),
                ),
            )!!

        // Nothing on the board carries a player id, and the prompt sent no cards…
        assertEquals(emptyList<CandidateCardUi>(), controls.candidateCards)
        // …so the panel itself must offer them, named, or the prompt cannot be answered at all.
        assertEquals(
            listOf(BoardAction.ChooseTarget("p-you"), BoardAction.ChooseTarget("p-opp")),
            controls.buttons.map { it.action },
        )
        assertEquals(listOf("you (you)", "Computer"), controls.buttons.map { it.label })
    }

    @Test
    fun `a candidate the board does draw stays a board tap, not a button`() {
        // The complement: what the board can show, the board shows. Only what it cannot draw is promoted
        // into the panel, so targeting a permanent stays the tap model of §5.2.
        val controls =
            controlsFor(
                stateWithBoardCards().copy(
                    prompt = GamePrompt.Target(message = "Select a target", targetIds = listOf("y-1", "p-opp")),
                ),
            )!!

        assertTrue("the permanent is still tappable on the board", "y-1" in controls.pickableObjectIds)
        assertEquals(
            "only the player needs a button",
            listOf(BoardAction.ChooseTarget("p-opp")),
            controls.buttons.map { it.action }.filterIsInstance<BoardAction.ChooseTarget>(),
        )
    }

    @Test
    fun `a select offering an object the board cannot draw is still playable`() {
        // Same class of gap on the priority prompt: `canPlayObjects` can name a card the board does not
        // render — flashback from a graveyard is the everyday case, and the board draws no graveyard.
        val controls =
            controlsFor(
                baseState().copy(
                    prompt = GamePrompt.Select(message = "Play spells and abilities"),
                    playable = listOf(PlayableObject("gy-1")),
                ),
            )!!

        assertTrue(
            "an offer the board cannot draw must still be reachable",
            controls.buttons.any { it.action == BoardAction.PlayObject("gy-1") },
        )
    }

    @Test
    fun `a mana source the board cannot draw is still tappable from the panel`() {
        val controls =
            controlsFor(
                baseState().copy(
                    prompt = GamePrompt.PlayMana(message = "Pay {R}"),
                    playable = listOf(PlayableObject("off-1")),
                ),
            )!!

        assertTrue(
            "a source the board cannot draw must still be payable",
            controls.buttons.any { it.action == BoardAction.PlayManaSource("off-1") },
        )
    }

    @Test
    fun `an unnamed candidate still gets a control rather than nothing`() {
        // The board can name players, cards in hand, on either battlefield, on the stack, in exile and in
        // revealed sets. A graveyard is a *count* upstream, so a card there cannot be named — and an
        // unnameable candidate must still be answerable, because the alternative is the stall this whole
        // section exists to prevent.
        val controls =
            controlsFor(
                baseState().copy(
                    prompt = GamePrompt.Target(message = "Select a target", targetIds = listOf("mystery-1"), isRequired = true),
                ),
            )!!

        assertEquals(listOf(BoardAction.ChooseTarget("mystery-1")), controls.buttons.map { it.action })
        assertEquals(listOf("$UNNAMED_CANDIDATE_LABEL 1"), controls.buttons.map { it.label })
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

    /** [baseState] with a permanent on each battlefield, so "the board draws it" is a real distinction. */
    private fun stateWithBoardCards(): GameState {
        val base = baseState()
        return base.copy(
            players =
                base.players.map { player ->
                    if (player.isViewer) {
                        player.copy(battlefield = listOf(GamePermanent(card = GameCard(id = "y-1", name = "Mountain"))))
                    } else {
                        player.copy(battlefield = listOf(GamePermanent(card = GameCard(id = "o-1", name = "Forest"))))
                    }
                },
        )
    }

    /**
     * A declaration exactly as the server sends one (§7.2/§7.3): two creatures of the viewer's own on
     * the battlefield, two of the opponent's, the ids in the prompt's **options** — and **`playable`
     * empty**, which is the fact the whole story turns on.
     */
    private fun declarationState(
        message: String,
        idsKey: String,
        options: PromptOptions,
    ): GameState {
        val base = baseState()
        return base.copy(
            step = if (idsKey == PromptOptions.POSSIBLE_ATTACKERS) PhaseStep.DeclareAttackers else PhaseStep.DeclareBlockers,
            players =
                base.players.map { player ->
                    if (player.isViewer) {
                        player.copy(battlefield = listOf(creature("y-1", "Goblin Token"), creature("y-2", "Goblin Token")))
                    } else {
                        player.copy(battlefield = listOf(creature("o-1", "Grizzly Bears"), creature("o-2", "Grizzly Bears")))
                    }
                },
            // The server sends nothing playable during a declaration; the creatures come only from the
            // options. Stated here rather than left to the default, because it is the point.
            playable = emptyList(),
            prompt = GamePrompt.Select(message = message, options = options),
        )
    }

    private fun declareAttackersState() =
        declarationState(
            message = "Select attackers",
            idsKey = PromptOptions.POSSIBLE_ATTACKERS,
            options =
                PromptOptions(
                    text = mapOf(PromptOptions.SPECIAL_BUTTON to "All attack"),
                    ids = mapOf(PromptOptions.POSSIBLE_ATTACKERS to listOf("y-1", "y-2")),
                ),
        )

    private fun declareBlockersState() =
        declarationState(
            message = "Select blockers",
            idsKey = PromptOptions.POSSIBLE_BLOCKERS,
            // No special button: blocking has no shortcut (§7.3).
            options = PromptOptions(ids = mapOf(PromptOptions.POSSIBLE_BLOCKERS to listOf("y-1", "y-2"))),
        )

    private fun creature(
        id: String,
        name: String,
    ) = GamePermanent(
        card = GameCard(id = id, name = name, power = "1", toughness = "1", isCreature = true),
    )

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
