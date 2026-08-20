package magefree.bridge.mapping

import mage.choices.ChoiceColor
import mage.choices.ChoiceImpl
import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.util.MultiAmountMessage
import mage.view.AbilityPickerView
import mage.view.GameClientMessage
import mage.view.GameView
import mage.view.TableClientMessage
import magefree.protocol.AskPrompt
import magefree.protocol.ChooseAbilityPrompt
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.ChoosePilePrompt
import magefree.protocol.GameError
import magefree.protocol.GameInformed
import magefree.protocol.GameOver
import magefree.protocol.GamePromptOptions
import magefree.protocol.GamePrompted
import magefree.protocol.GameStarted
import magefree.protocol.GameStateUpdated
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.PlayManaPrompt
import magefree.protocol.PlayXManaPrompt
import magefree.protocol.SelectPrompt
import magefree.protocol.TargetPrompt
import magefree.protocol.WatchingGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * Hermetic tests for the in-game branches of [CallbackMapper] (story 0051). Real [ClientCallback]s are
 * built in memory — its 3-arg constructor compresses the payload, so `map` really does the decompress +
 * cast it does in production — and each is asserted to reach the right app-schema message.
 *
 * Two things this pins down beyond "the fields line up":
 * - the **game id is the callback's object id**, not anything in the payload (upstream fires every game
 *   callback with `game.getId()`), so every assertion checks it;
 * - the mapper **never throws**: a malformed payload for a known game method, and `GAME_REDRAW_GUI`
 *   (deliberately unmapped), both come back `null`.
 */
class GameCallbackMapperTest {
    private val gameId = UUID.randomUUID()
    private val alice = UUID.randomUUID()

    /** One crafted view for the whole class — the mapped form is compared against, so ids must be stable. */
    private val gameView: GameView =
        GameViews.game(
            turn = 2,
            myPlayerId = alice,
            activePlayerId = alice,
            players = listOf(GameViews.player(playerId = alice, name = "alice")),
            hand = listOf(GameViews.card(name = "Forest")),
        )

    private fun view(): GameView = gameView

    private fun callback(
        method: ClientCallbackMethod,
        payload: Any?,
    ): ClientCallback = ClientCallback(method, gameId, payload)

    private fun clientMessage(
        message: String? = null,
        options: Map<String, Serializable>? = null,
    ): GameClientMessage = GameClientMessage(view(), options, message)

    @Test
    fun `GAME_INIT maps to a GameStarted carrying the first real snapshot`() {
        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.GAME_INIT, view()))

        assertTrue(mapped is GameStarted, "expected a GameStarted, got $mapped")
        val started = mapped as GameStarted
        assertEquals(gameId.toString(), started.gameId, "the callback's object id is the game id")
        assertEquals(2, started.state.turn)
        assertEquals(listOf("Forest"), started.state.hand.map { it.name })
        assertEquals(alice.toString(), started.state.viewerPlayerId)
    }

    @Test
    fun `GAME_UPDATE maps to a GameStateUpdated`() {
        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.GAME_UPDATE, view()))

        assertTrue(mapped is GameStateUpdated, "expected a GameStateUpdated, got $mapped")
        assertEquals(gameId.toString(), (mapped as GameStateUpdated).gameId)
        assertEquals(2, mapped.state.turn)
    }

    @Test
    fun `GAME_UPDATE_AND_INFORM and GAME_INFORM_PERSONAL differ only in the personal flag`() {
        val shared = CallbackMapper.map(callback(ClientCallbackMethod.GAME_UPDATE_AND_INFORM, clientMessage("alice draws a card")))
        val personal = CallbackMapper.map(callback(ClientCallbackMethod.GAME_INFORM_PERSONAL, clientMessage("You lost the roll")))

        assertEquals(
            GameInformed(gameId = gameId.toString(), message = "alice draws a card", state = GameViewMapper.map(view()), personal = false),
            shared,
        )
        assertTrue(personal is GameInformed && personal.personal, "the personal form must be flagged, got $personal")
        assertNotNull((personal as GameInformed).state, "the personal form still carries the snapshot upstream sends")
    }

    @Test
    fun `GAME_OVER maps to a GameOver with the server's final line and snapshot`() {
        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.GAME_OVER, clientMessage("alice has won the game")))

        assertTrue(mapped is GameOver, "expected a GameOver, got $mapped")
        assertEquals("alice has won the game", (mapped as GameOver).message)
        assertEquals(gameId.toString(), mapped.gameId)
        assertNotNull(mapped.state)
    }

    @Test
    fun `GAME_ERROR maps to a GameError from the bare string payload`() {
        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.GAME_ERROR, "something exploded"))

        assertEquals(GameError(gameId = gameId.toString(), message = "something exploded"), mapped)
    }

    @Test
    fun `WATCHGAME maps to a WatchingGame naming the game and its table`() {
        val tableId = UUID.randomUUID()
        val parentId = UUID.randomUUID()
        val message = TableClientMessage().withTable(tableId, parentId)

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.WATCHGAME, message))

        assertEquals(
            WatchingGame(gameId = gameId.toString(), tableId = tableId.toString(), parentTableId = parentId.toString()),
            mapped,
            "upstream fires WATCHGAME with the game id as the object id and the table in the payload",
        )
    }

    @Test
    fun `GAME_REDRAW_GUI is deliberately mapped to nothing`() {
        // A CLIENT_SIDE_EVENT whose desktop handler is a bare repaint of state the app already holds.
        // Mapping it would put an event on the wire that means nothing to a non-Swing client.
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.GAME_REDRAW_GUI, view())))
    }

    @Test
    fun `GAME_SELECT maps to a SelectPrompt carrying the state and the server's UI hints`() {
        val attacker = UUID.randomUUID()
        val options: Map<String, Serializable> =
            mapOf(
                GamePromptOptions.SPECIAL_BUTTON to "All attack",
                GamePromptOptions.POSSIBLE_ATTACKERS to ArrayList(listOf(attacker)),
            )

        val mapped = CallbackMapper.map(callback(ClientCallbackMethod.GAME_SELECT, clientMessage("Select attackers", options)))

        val prompted = mapped as GamePrompted
        assertEquals(gameId.toString(), prompted.gameId)
        assertEquals(2, prompted.state.turn, "the prompt carries the snapshot, mapped once")
        val prompt = prompted.prompt as SelectPrompt
        assertEquals("Select attackers", prompt.message)
        assertEquals("All attack", prompt.options.text[GamePromptOptions.SPECIAL_BUTTON])
        assertEquals(listOf(attacker.toString()), prompt.options.ids[GamePromptOptions.POSSIBLE_ATTACKERS])
    }

    @Test
    fun `GAME_TARGET maps to a TargetPrompt with candidates, marked targets and the required flag`() {
        val candidate = GameViews.card(name = "Grizzly Bears")
        val marked = UUID.randomUUID()
        val payload =
            GameClientMessage(
                view(),
                null,
                "Choose target creature",
                GameViews.cardsView(listOf(candidate)),
                setOf(marked),
                true,
            )

        val prompt = (CallbackMapper.map(callback(ClientCallbackMethod.GAME_TARGET, payload)) as GamePrompted).prompt as TargetPrompt

        assertEquals("Choose target creature", prompt.message)
        assertEquals(listOf("Grizzly Bears"), prompt.cards.map { it.name })
        assertEquals(listOf(marked.toString()), prompt.targetIds)
        assertTrue(prompt.required, "the payload's `flag` is upstream's `required`")
    }

    @Test
    fun `GAME_TARGET with null targets falls back to every candidate being pickable (story 0072)`() {
        // GameController.java's PICK_ABILITY overload (ordering simultaneous triggered abilities)
        // always sends `targets = null` -- unlike PICK_TARGET, which always sends a real, possibly
        // empty Set. Prove the fix discriminates the actual bug: against the unfixed mapper,
        // targetIds came back empty here and every rendered candidate was unpickable.
        val first = GameViews.card(name = "Guide of Souls")
        val second = GameViews.card(name = "Ajani, Nacatl Pariah")
        val payload =
            GameClientMessage(
                view(),
                null,
                "Pick triggered ability (goes to the stack first)",
                GameViews.cardsView(listOf(first, second)),
                null,
                false,
            )

        val prompt = (CallbackMapper.map(callback(ClientCallbackMethod.GAME_TARGET, payload)) as GamePrompted).prompt as TargetPrompt

        assertEquals(
            setOf(first.id.toString(), second.id.toString()),
            prompt.targetIds.toSet(),
            "with null targets, every card in cardsView1 must be pickable",
        )
    }

    @Test
    fun `GAME_TARGET for a library search prefers possibleTargets over the whole cardsView1 (story 0079)`() {
        // Found live (Pete, 2026-08-20): activating Marsh Flats offered no usable candidates.
        // TargetCardInLibrary sends cardsView1 as the WHOLE remaining library (read directly against
        // HumanPlayer.chooseTarget/GameController.target), with targets = null and the real, narrow
        // answer only in options[possibleTargets] -- a different shape from story 0072's PICK_ABILITY,
        // where the whole cardsView1 candidate set really is the answer set. Falling back to
        // cardsView1 here (as 0072's fix does) would offer the entire library as if every card were a
        // legal fetch.
        val plains = GameViews.card(name = "Plains")
        val forest = GameViews.card(name = "Forest") // not a legal Marsh Flats target
        val mountain = GameViews.card(name = "Mountain")
        val payload =
            GameClientMessage(
                view(),
                mapOf(GamePromptOptions.POSSIBLE_TARGETS to ArrayList(listOf(plains.id, mountain.id))),
                "Search your library for a Plains or Island card",
                GameViews.cardsView(listOf(plains, forest, mountain)),
                null,
                false,
            )

        val prompt = (CallbackMapper.map(callback(ClientCallbackMethod.GAME_TARGET, payload)) as GamePrompted).prompt as TargetPrompt

        assertEquals(3, prompt.cards.size, "the server's own whole-library view is still carried through")
        assertEquals(
            setOf(plains.id.toString(), mountain.id.toString()),
            prompt.targetIds.toSet(),
            "targetIds must be the narrow possibleTargets answer, not every card in the library",
        )
    }

    @Test
    fun `GAME_ASK maps to an AskPrompt carrying the server's own button labels`() {
        val options: Map<String, Serializable> =
            mapOf(
                GamePromptOptions.LEFT_BUTTON_TEXT to "Mulligan",
                GamePromptOptions.RIGHT_BUTTON_TEXT to "Keep",
            )

        val prompt =
            (CallbackMapper.map(callback(ClientCallbackMethod.GAME_ASK, clientMessage("Mulligan?", options))) as GamePrompted).prompt

        assertTrue(prompt is AskPrompt, "expected an AskPrompt, got $prompt")
        assertEquals("Mulligan?", (prompt as AskPrompt).message)
        assertEquals("Mulligan", prompt.options.text[GamePromptOptions.LEFT_BUTTON_TEXT])
        assertEquals("Keep", prompt.options.text[GamePromptOptions.RIGHT_BUTTON_TEXT])
    }

    @Test
    fun `GAME_CHOOSE_ABILITY maps its AbilityPickerView payload, preserving choice order`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val picker =
            AbilityPickerView(
                view(),
                linkedMapOf(first to "1. Cast Bolt", second to "2. Cast Bolt with kicker"),
                "Choose ability",
            )

        val prompted = CallbackMapper.map(callback(ClientCallbackMethod.GAME_CHOOSE_ABILITY, picker)) as GamePrompted

        assertEquals(2, prompted.state.turn, "the picker carries its own GameView, which must still be mapped")
        val prompt = prompted.prompt as ChooseAbilityPrompt
        assertEquals("Choose ability", prompt.message)
        assertEquals(listOf(first.toString(), second.toString()), prompt.choices.map { it.abilityId })
        assertEquals(listOf("1. Cast Bolt", "2. Cast Bolt with kicker"), prompt.choices.map { it.text })
    }

    @Test
    fun `GAME_CHOOSE_PILE maps both piles`() {
        val payload =
            GameClientMessage(
                view(),
                null,
                "Choose a pile",
                GameViews.cardsView(listOf(GameViews.card(name = "Forest"))),
                GameViews.cardsView(listOf(GameViews.card(name = "Island"), GameViews.card(name = "Mountain"))),
            )

        val prompt =
            (CallbackMapper.map(callback(ClientCallbackMethod.GAME_CHOOSE_PILE, payload)) as GamePrompted).prompt as ChoosePilePrompt

        assertEquals(listOf("Forest"), prompt.pile1.map { it.name })
        assertEquals(listOf("Island", "Mountain"), prompt.pile2.map { it.name })
    }

    @Test
    fun `GAME_CHOOSE_CHOICE normalises a plain string choice to key-equals-label options`() {
        val choice = ChoiceColor(true)
        choice.message = "Choose a color"
        choice.subMessage = "for Prismatic Lens"

        val prompt =
            (
                CallbackMapper.map(
                    callback(ClientCallbackMethod.GAME_CHOOSE_CHOICE, GameClientMessage(view(), null, choice)),
                ) as GamePrompted
            ).prompt as ChooseChoicePrompt

        assertEquals("Choose a color", prompt.message)
        assertEquals("for Prismatic Lens", prompt.subMessage)
        assertTrue(prompt.required, "ChoiceColor(true) is a required choice")
        assertTrue(prompt.choices.isNotEmpty(), "the colour choices should be carried")
        assertTrue(
            prompt.choices.all { it.key == it.label },
            "a plain (non-keyed) Choice is its own key upstream, got ${prompt.choices}",
        )
        assertTrue(prompt.choices.any { it.key == "Green" }, "expected the colours, got ${prompt.choices}")
    }

    @Test
    fun `GAME_CHOOSE_CHOICE carries a keyed choice's keys and labels separately`() {
        val choice = ChoiceImpl(false)
        choice.message = "Choose a card name"
        choice.keyChoices = linkedMapOf("bolt" to "Lightning Bolt", "bear" to "Grizzly Bears")

        val prompt =
            (
                CallbackMapper.map(
                    callback(ClientCallbackMethod.GAME_CHOOSE_CHOICE, GameClientMessage(view(), null, choice)),
                ) as GamePrompted
            ).prompt as ChooseChoicePrompt

        assertEquals(listOf("bolt", "bear"), prompt.choices.map { it.key }, "the reply carries the KEY, not the label")
        assertEquals(listOf("Lightning Bolt", "Grizzly Bears"), prompt.choices.map { it.label })
        assertFalse(prompt.required)
        assertNull(prompt.specialText, "no special option was enabled")
    }

    @Test
    fun `GAME_PLAY_MANA and GAME_PLAY_XMANA map to their own prompt types`() {
        val mana = CallbackMapper.map(callback(ClientCallbackMethod.GAME_PLAY_MANA, clientMessage("Pay {G}"))) as GamePrompted
        val xMana = CallbackMapper.map(callback(ClientCallbackMethod.GAME_PLAY_XMANA, clientMessage("Announce X"))) as GamePrompted

        assertEquals("Pay {G}", (mana.prompt as PlayManaPrompt).message)
        assertEquals("Announce X", (xMana.prompt as PlayXManaPrompt).message)
    }

    @Test
    fun `GAME_GET_AMOUNT carries the single number's bounds`() {
        val payload = GameClientMessage(view(), null, "How many?", 1, 4)

        val prompt =
            (CallbackMapper.map(callback(ClientCallbackMethod.GAME_GET_AMOUNT, payload)) as GamePrompted).prompt as GetAmountPrompt

        assertEquals("How many?", prompt.message)
        assertEquals(1, prompt.min)
        assertEquals(4, prompt.max)
    }

    @Test
    fun `GAME_GET_MULTI_AMOUNT carries a row per entry plus the total bounds`() {
        val payload =
            GameClientMessage(
                view(),
                null,
                listOf(MultiAmountMessage("Bear", 0, 3, 1), MultiAmountMessage("Wall", 0, 2)),
                0,
                3,
            )

        val prompt =
            (
                CallbackMapper.map(callback(ClientCallbackMethod.GAME_GET_MULTI_AMOUNT, payload)) as GamePrompted
            ).prompt as GetMultiAmountPrompt

        assertEquals(listOf("Bear", "Wall"), prompt.entries.map { it.message })
        assertEquals(listOf(3, 2), prompt.entries.map { it.max })
        assertEquals(listOf(1, 0), prompt.entries.map { it.defaultValue })
        assertEquals(0, prompt.min, "min/max on the payload bound the TOTAL, not one entry")
        assertEquals(3, prompt.max)
    }

    @Test
    fun `a game callback with a malformed payload maps to null instead of throwing`() {
        // Upstream drift: a GAME_INIT whose decompressed payload is not a GameView. It must be logged
        // and dropped, never thrown — a bad push cannot be allowed to evict the live session.
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.GAME_INIT, "definitely not a GameView")))
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.GAME_ASK, 42)))
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.GAME_CHOOSE_ABILITY, clientMessage("wrong shape"))))
    }

    @Test
    fun `an out-of-scope game-adjacent callback maps to null`() {
        // END_GAME_INFO (a GameEndView) and the draft/tournament family are not in this story's scope.
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.END_GAME_INFO, view())))
        assertNull(CallbackMapper.map(callback(ClientCallbackMethod.DRAFT_INIT, view())))
    }
}
