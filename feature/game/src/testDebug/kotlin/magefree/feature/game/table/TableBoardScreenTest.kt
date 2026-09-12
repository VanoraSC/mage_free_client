package magefree.feature.game.table

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import magefree.designsystem.card.CardPreviewTestTags
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.game.board.BoardAction
import magefree.feature.game.board.BoardControlsTestTags
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.CONCEDE_CONFIRM_LABEL
import magefree.feature.game.board.CONCEDE_LABEL
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.NO_OUTSTANDING_PROMPT
import magefree.feature.game.board.PASS_LABEL
import magefree.feature.game.board.PLAY_ACTION_LABEL
import magefree.feature.game.board.TARGET_ACTION_LABEL
import magefree.feature.game.board.UNNAMED_CANDIDATE_LABEL
import magefree.feature.game.board.WAITING_FOR_FIRST_SNAPSHOT
import magefree.feature.game.board.WAITING_ON_YOU_WHILE_HIDDEN
import magefree.feature.game.board.controlsFor
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GamePrompt
import magefree.network.game.GameResult
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import magefree.network.game.PlayableObject
import magefree.network.game.TurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rebuilt board, playing a game.
 *
 * These run on the JVM under Robolectric (`src/testDebug`), so `:feature:game:check` covers them
 * pre-merge. That placement is deliberate and has a history: device-only tests do not run before a
 * merge, which is how an entire epic shipped built, tested and unreachable. A board that renders
 * nothing would pass a ViewModel suite perfectly.
 *
 * What is asserted here rather than in the table tier's own tests is the **joining**: that the board
 * is drawn from the server's snapshot at all, that the prompt surfaces reach it, that hiding them
 * cannot hide that the server is waiting, and that a press on a card really does arrive at the action
 * seam. How the battlefield itself arranges is [BattlefieldLayoutTest]'s question.
 *
 * Art goes through [PlaceholderCardArtRenderer] and the board's own resolver is left null, so no test
 * loads a network image. That production binds the real ones is `GameBoardRoute`'s and `AppNavHost`'s
 * single lines each, and is what the on-device pass confirms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    // A plain Application: the screen takes its renderers as parameters precisely so it needs no DI
    // container to render.
    application = Application::class,
    // Landscape, which is the only orientation the new UI has.
    qualifiers = "w891dp-h411dp",
)
class TableBoardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val visibilityRequests = mutableListOf<Boolean>()
    private val taps = mutableListOf<String?>()
    private val actions = mutableListOf<BoardAction>()
    private var exits = 0
    private val fullControlRequests = mutableListOf<Boolean>()

    private fun render(
        state: GameState?,
        controlsVisible: Boolean = true,
        selectedObjectId: String? = null,
        joinError: String? = null,
        fullControl: Boolean = false,
    ) {
        composeTestRule.setContent {
            MageTheme {
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = state?.let(BoardUi::from) ?: BoardUi(gameId = "g-1"),
                            snapshot = state,
                            isJoining = false,
                            joinError = joinError,
                            areControlsVisible = controlsVisible,
                            controls = state?.let { controlsFor(it) },
                            selectedObjectId = selectedObjectId,
                            stops = BoardStops.Default.copy(fullControl = fullControl),
                        ),
                    onExit = { exits += 1 },
                    onControlsVisibleChange = { visibilityRequests += it },
                    onCardTap = { taps += it },
                    onAction = { actions += it },
                    artRenderer = PlaceholderCardArtRenderer,
                    onSetFullControl = { fullControlRequests += it },
                )
            }
        }
    }

    /**
     * Presses the upright card in a land stack.
     *
     * Not the stack's centre: a stack reserves room for its turned half whether or not anything is in
     * it, so the middle of a one-land stack's box is empty board. This lands on the upright copy at the
     * top-left of the diagonal, which is where a stack of one draws.
     */
    private fun pressUprightLand(stackId: String) {
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack(stackId)).performTouchInput {
            click(Offset(width * 0.4f, height * 0.25f))
        }
    }

    // ---- the board is on screen -----------------------------------------------------------------

    @Test
    fun `a snapshot draws the rebuilt board, not a placeholder`() {
        // The story in one assertion. Before 0112 the live route composed the portrait board, and
        // every one of the table tier's stories had been judged only in the catalog.
        render(runningGame())

        composeTestRule.onNodeWithTag(BattlefieldTestTags.BOARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(StatusRailTestTags.RAIL).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HandTestTags.HAND).assertIsDisplayed()
    }

    @Test
    fun `before the first snapshot the board says so rather than looking finished`() {
        // **The seed, not a null.** `observeGame` opens with an empty `GameState` and the flow emits
        // it immediately, so this is the state the board is actually in while it waits — and a board
        // that keyed the line off having no snapshot at all would never show it. The board still
        // renders behind the line, empty.
        render(GameState(gameId = "g-1"))

        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertIsDisplayed()
    }

    @Test
    fun `a game in progress says nothing about waiting`() {
        render(runningGame())

        composeTestRule.onNodeWithText(WAITING_FOR_FIRST_SNAPSHOT).assertDoesNotExist()
    }

    @Test
    fun `a declined join keeps the server's own reason on screen`() {
        render(GameState(gameId = "g-1"), joinError = "table is full")

        composeTestRule.onNodeWithText("Couldn't join the game: table is full").assertIsDisplayed()
    }

    @Test
    fun `a finished game says so, in the server's own words`() {
        // Found by playing one: the board simply stopped moving and said nothing, which is
        // indistinguishable from a stalled one. Upstream's GAME_OVER is a single line of prose with no
        // winner id and no reason code, so the line is what is shown and nothing is inferred from it.
        render(runningGame().copy(result = GameResult(message = "pete has won the game")))

        composeTestRule.onNodeWithText("pete has won the game").assertIsDisplayed()
    }

    @Test
    fun `a game still being played says nothing about a result`() {
        render(runningGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.STANDING).assertDoesNotExist()
    }

    // ---- a card in a pile -----------------------------------------------------------------------

    @Test
    fun `a card pressed in a graveyard opens the same detail a card in hand opens`() {
        // Found by playing one: the piles opened, the cards were there, and pressing one did nothing
        // at all. `BoardUi` carries a graveyard as a *count* — all the portrait board ever drew of one
        // — so the detail had nothing to show and drew nothing, which is a dead affordance.
        render(runningGame(), selectedObjectId = "gy-1")

        // **Inside the preview**, because the name is now on the board twice: the rail draws the top
        // card of each graveyard, and the top card of this one is this card. Two nodes with the same
        // text is the rail working, so the assertion says which one it means.
        composeTestRule
            .onNode(
                hasText("Ancestral Recall") and hasAnyAncestor(hasTestTag(CardPreviewTestTags.PANEL)),
                useUnmergedTree = true,
            ).assertExists()
    }

    @Test
    fun `a card pressed in an exile pile opens too`() {
        render(runningGame(), selectedObjectId = "ex-1")

        composeTestRule.onNodeWithText("Chandra, Torch of Defiance").assertIsDisplayed()
    }

    // ---- the stack ------------------------------------------------------------------------------

    @Test
    fun `an empty stack draws no region at all`() {
        // The board's own rule: no empty region holds height. Most of a game has nothing on the stack,
        // and a band reserved for it would be a permanent hole in the middle of the battlefield.
        render(runningGame())

        composeTestRule.onNodeWithTag(StackTestTags.REGION).assertDoesNotExist()
    }

    @Test
    fun `something on the stack opens a band between the two sides, and says what it does`() {
        // The board draws no card text, which is right for a permanent being glanced at and wrong for
        // the one object the whole game is currently waiting on.
        render(castingGame())

        composeTestRule.onNodeWithTag(StackTestTags.REGION).assertIsDisplayed()
        composeTestRule.onNodeWithText(BOLT_TEXT).assertIsDisplayed()
    }

    @Test
    fun `pressing a stack object raises it rather than answering anything`() {
        render(castingGame())

        composeTestRule.onNodeWithTag(StackTestTags.entry("bolt")).performClick()

        assertEquals(listOf<String?>("bolt"), taps)
        assertTrue("a press on the stack sends nothing", actions.isEmpty())
    }

    @Test
    fun `a raised permanent shows what is attached to it and what that attachment says`() {
        // The detail this replaced took a projection that knows a card and not a permanent, so an
        // enchanted creature opened with no mention of the Aura that is the reason it cannot attack.
        render(enchantedGame(), selectedObjectId = "bears")

        // Scoped to the panel: the Aura's name is also on the board, on the band it shows behind its
        // host, which is exactly the band that is too small to read and the reason this panel lists it.
        val inPanel = hasAnyAncestor(hasTestTag(CardPreviewTestTags.PANEL))
        composeTestRule.onNode(hasText("Pacifism") and inPanel).assertIsDisplayed()
        composeTestRule.onNode(hasText(PACIFISM_TEXT) and inPanel).assertIsDisplayed()
    }

    // ---- paying a cost --------------------------------------------------------------------------

    @Test
    fun `while a cost is being paid, pressing a land taps it`() {
        // Every other press raises the card so the act can be committed on it. Paying is the exception:
        // the player is tapping their own lands, several in a row, mid-cast, and raising each one to
        // press a second button turns four mana into eight presses and four things to dismiss.
        render(payingGame())

        pressUprightLand("y-1")

        assertEquals(listOf<BoardAction>(BoardAction.PlayManaSource("y-1")), actions)
        assertTrue("nothing is raised — the press was the answer", taps.isEmpty())
    }

    @Test
    fun `a source the server has not offered is still only raised`() {
        // The exception is narrow: it is not "presses act during mana payment", it is "a press that the
        // server has an answer for acts". A land it did not offer has nothing to send, so it opens.
        render(payingGame())

        pressUprightLand("o-1")

        assertTrue("nothing may be sent for a source the server did not offer", actions.isEmpty())
        assertEquals(listOf<String?>("o-1"), taps)
    }

    @Test
    fun `outside a cost, pressing a land still raises it`() {
        render(priorityGame())

        pressUprightLand("y-1")

        assertEquals(listOf<String?>("y-1"), taps)
        assertTrue(actions.isEmpty())
    }

    // ---- the question ---------------------------------------------------------------------------

    @Test
    fun `holding priority draws the controls the server's prompt asks for`() {
        render(priorityGame())

        composeTestRule.onNodeWithText(PASS_LABEL).assertIsDisplayed()
    }

    @Test
    fun `nothing outstanding still says so, so a quiet game never reads as a stuck one`() {
        render(runningGame())

        composeTestRule.onNodeWithText(NO_OUTSTANDING_PROMPT).assertIsDisplayed()
    }

    @Test
    fun `hiding the controls cannot hide that the server is waiting`() {
        // The one guarantee the toggle owes: a hidden control set must never become an invisible
        // stall. The collapsed toggle restates it, so the finger that hid the panel is told.
        render(priorityGame(), controlsVisible = false)

        composeTestRule.onNodeWithText(PASS_LABEL).assertDoesNotExist()
        composeTestRule.onNodeWithText(WAITING_ON_YOU_WHILE_HIDDEN).assertIsDisplayed()
    }

    @Test
    fun `nothing on the board is modal`() {
        // §7.4's rule, and the reason the controls float rather than opening a dialog: a board the
        // player cannot look at while answering a question about it is a board they answer blind.
        render(priorityGame())

        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNode(isPopup()).assertDoesNotExist()
        composeTestRule.onRoot().assertIsDisplayed()
    }

    // ---- a press reaches the seam ---------------------------------------------------------------

    @Test
    fun `pressing a card in hand raises it rather than acting on it`() {
        render(priorityGame())

        composeTestRule.onNodeWithTag(HandTestTags.card("h-1")).performClick()

        assertEquals("a press raises the card; the detail commits it", listOf<String?>("h-1"), taps)
        assertTrue("nothing may be sent by a press alone", actions.isEmpty())
    }

    @Test
    fun `the raised card offers the action the server allows, and committing sends it`() {
        // The server said `h-1` is playable, so `controlsFor` offers it and the detail says what a
        // press means. Nothing here decides that — it reads `PromptControlsUi.actionFor`.
        render(priorityGame(), selectedObjectId = "h-1")

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).performClick()

        assertEquals(listOf<BoardAction>(BoardAction.PlayObject("h-1")), actions)
    }

    @Test
    fun `a card the server has not offered is raised but has nothing to commit`() {
        // The other half, and the one that matters: the board must not invent an action. `h-2` is in
        // the same hand and is not in `playable`.
        render(priorityGame(), selectedObjectId = "h-2")

        composeTestRule.onNodeWithText(PLAY_ACTION_LABEL).assertDoesNotExist()
    }

    // ---- the exit -------------------------------------------------------------------------------

    @Test
    fun `there is always a way off the board, and it is not inside the answer panel`() {
        // The board has no top bar to go back from — it takes the whole window — so leaving lives in
        // the corner menu, beside conceding and quitting. All three are acts of a different kind from
        // answering the server, and a player wants them at moments when there is nothing to answer.
        render(priorityGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithText(LEAVE_BOARD_LABEL).performClick()

        assertEquals(1, exits)
    }

    @Test
    fun `conceding from the corner menu asks first`() {
        // It ends the game, so the first press is the question and the second is the answer. Nothing
        // is sent until the second.
        render(priorityGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithText(CONCEDE_LABEL).performClick()
        assertTrue("the first press only asks", actions.isEmpty())

        composeTestRule.onNodeWithText(CONCEDE_CONFIRM_LABEL).performClick()
        assertEquals(listOf<BoardAction>(BoardAction.Concede), actions)
    }

    @Test
    fun `full control is turned on from the corner menu, in one press`() {
        // A mode rather than an act: it ends nothing, so it does not confirm, and nothing is sent to the
        // game from here — the request goes to the stops, which reach the server on their own.
        render(priorityGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithTag(TableBoardTestTags.FULL_CONTROL).performClick()

        assertEquals(listOf(true), fullControlRequests)
        assertTrue("no game action is sent", actions.isEmpty())
    }

    @Test
    fun `full control on says so beside the menu, and the menu turns it off`() {
        // A pinned mode has to look pinned: a player who forgot setting it would read every priority
        // window after their own casts as the board waiting for nothing.
        render(priorityGame(), fullControl = true)

        composeTestRule.onNodeWithTag(TableBoardTestTags.FULL_CONTROL_BADGE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TableBoardTestTags.MENU).performClick()
        composeTestRule.onNodeWithTag(TableBoardTestTags.FULL_CONTROL).performClick()

        assertEquals(listOf(false), fullControlRequests)
    }

    @Test
    fun `full control off draws no badge`() {
        render(priorityGame())

        composeTestRule.onNodeWithTag(TableBoardTestTags.FULL_CONTROL_BADGE).assertDoesNotExist()
    }

    // ---- a planeswalker's abilities ---------------------------------------------------------------

    @Test
    fun `a planeswalker's offered abilities are buttons on its card, in place of Play`() {
        // Pete: the abilities, in a column, instead of the Play button. The ultimate's name arrives clipped
        // at fifty characters — upstream's own `PlayableObjectStats` — and the button reads the whole line.
        render(planeswalkerGame(), selectedObjectId = "pw-1")

        composeTestRule.onNodeWithTag(CardPreviewTestTags.abilityAction(0)).assertTextEquals(LILIANA_PLUS)
        composeTestRule.onNodeWithTag(CardPreviewTestTags.abilityAction(1)).assertTextEquals(LILIANA_ULTIMATE)
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).assertDoesNotExist()
    }

    @Test
    fun `pressing one of them asks to activate that ability of that planeswalker`() {
        render(planeswalkerGame(), selectedObjectId = "pw-1")

        composeTestRule.onNodeWithTag(CardPreviewTestTags.abilityAction(1)).performScrollTo().performClick()

        assertEquals(listOf<BoardAction>(BoardAction.ActivateAbility(objectId = "pw-1", abilityId = "minus-6")), actions)
    }

    @Test
    fun `a planeswalker the server is not offering has neither buttons nor Play`() {
        render(planeswalkerGame().copy(playable = emptyList()), selectedObjectId = "pw-1")

        composeTestRule.onNodeWithTag(CardPreviewTestTags.ABILITY_ACTIONS).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CardPreviewTestTags.ACTION).assertDoesNotExist()
    }

    // ---- dragging out of the hand -----------------------------------------------------------------

    @Test
    fun `dragging an offered card out of the hand plays it, without raising it first`() {
        // Pete: a drag should *jump to play*. A tap raises the card so it can be read; a card pulled
        // out of the hand toward the table has been read already, and raising it made the player press
        // Play anyway.
        render(priorityGame())

        composeTestRule.onNodeWithTag(HandTestTags.card("h-1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 200f)
        }

        assertEquals(listOf<BoardAction>(BoardAction.PlayObject("h-1")), actions)
        assertTrue("nothing is raised", taps.isEmpty())
    }

    // ---- a question answered from its own content -----------------------------------------------

    @Test
    fun `a card the prompt carried is pressed, not chosen from a numbered button beside it`() {
        // Found by playing: the panel drew the cards *and* a "Choice 4" button per card, because
        // anything the board itself does not draw counted as unnameable. Three ways to offer one
        // choice, two of which say less than the picture does.
        render(searchGame())

        composeTestRule.onNodeWithText("$UNNAMED_CANDIDATE_LABEL 1").assertDoesNotExist()
        composeTestRule.onNodeWithTag(BoardControlsTestTags.candidate("lib-1")).performClick()

        assertEquals("pressing a candidate raises it; the detail commits it", listOf<String?>("lib-1"), taps)
        assertTrue("nothing may be sent by a press alone", actions.isEmpty())
    }

    @Test
    fun `a raised candidate is read at full size and confirmed there`() {
        // These are cards the player has not seen before — a search is a choice between cards being
        // read for the first time — so the detail view is the point rather than a formality.
        render(searchGame(), selectedObjectId = "lib-1")

        composeTestRule.onNodeWithText(TARGET_ACTION_LABEL).performClick()

        assertEquals(listOf<BoardAction>(BoardAction.ChooseTarget("lib-1")), actions)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun card(
        id: String,
        name: String,
        typeLine: String,
        manaCost: String? = null,
        types: List<CardType> = emptyList(),
    ) = GameCard(
        id = id,
        name = name,
        setCode = "M21",
        collectorNumber = "272",
        manaCost = manaCost,
        typeLine = typeLine,
        cardTypes = types,
    )

    /** A game in progress with the opponent holding priority — the board being watched. */
    private fun runningGame() =
        GameState(
            gameId = "g-1",
            turn = 3,
            phase = TurnPhase.PrecombatMain,
            step = PhaseStep.PrecombatMain,
            activePlayerId = "p-you",
            activePlayerName = "you",
            priorityPlayerName = "Computer",
            viewerPlayerId = "p-you",
            viewerHasPriority = false,
            hasSnapshot = true,
            players =
                listOf(
                    // Deliberately opponent-first: player order is not viewer-first, and the board
                    // must not care — it locates seats by `isViewer`.
                    GamePlayer(
                        playerId = "p-opp",
                        name = "Computer",
                        life = 18,
                        libraryCount = 51,
                        handCount = 5,
                        isHuman = false,
                        battlefield =
                            listOf(
                                GamePermanent(
                                    card = card("o-1", "Mountain", "Basic Land — Mountain", types = listOf(CardType.Land)),
                                ),
                            ),
                    ),
                    GamePlayer(
                        playerId = "p-you",
                        name = "you",
                        life = 20,
                        libraryCount = 53,
                        handCount = 2,
                        isViewer = true,
                        isActive = true,
                        battlefield =
                            listOf(
                                GamePermanent(
                                    card = card("y-1", "Forest", "Basic Land — Forest", types = listOf(CardType.Land)),
                                ),
                            ),
                        // The piles the rebuilt board opens and lets a card be pressed in. They reach
                        // `BoardUi` only as counts, which is why the detail has to read the snapshot.
                        graveyard = listOf(card("gy-1", "Ancestral Recall", "Instant", "U")),
                        exile = listOf(card("ex-1", "Chandra, Torch of Defiance", "Legendary Planeswalker — Chandra", "2RR")),
                    ),
                ),
            hand =
                listOf(
                    card("h-1", "Llanowar Elves", "Creature — Elf Druid", "G", listOf(CardType.Creature)),
                    card("h-2", "Grizzly Bears", "Creature — Bear", "1G", listOf(CardType.Creature)),
                ),
        )

    /**
     * A library search: the server asks for a card that is not on the board, and sends the cards.
     *
     * The shape that matters is that `cards` and the pickable ids are the same objects — that is what
     * used to produce a picture and a numbered button for each of them.
     */
    private fun searchGame(): GameState {
        val found = (1..3).map { card("lib-$it", "Island $it", "Basic Land — Island") }
        return runningGame().copy(
            prompt =
                GamePrompt.Target(
                    message = "Select a card",
                    targetIds = found.map { it.id },
                    cards = found,
                ),
        )
    }

    /**
     * A cost being paid: the server owes mana and has named the sources it will accept.
     *
     * Only the viewer's own Forest is offered, which is what makes "a source the server did not offer"
     * a real assertion rather than a restatement of the fixture.
     */
    private fun payingGame() =
        runningGame().copy(
            // The server's own list of what may be tapped right now — `canPlayObjects`, never a set
            // this app worked out.
            playable = listOf(PlayableObject("y-1")),
            prompt = GamePrompt.PlayMana(message = "Pay 1 mana"),
        )

    /** The same game with a spell on the stack, pointing at a creature the board is drawing. */
    private fun castingGame() =
        runningGame().copy(
            stack =
                listOf(
                    card("bolt", "Lightning Bolt", "Instant", "R").copy(
                        rules = listOf(BOLT_TEXT),
                        targets = listOf("o-1"),
                    ),
                ),
        )

    /** A creature with an Aura on it, which is the case a card-shaped detail view cannot describe. */
    private fun enchantedGame(): GameState {
        val base = runningGame()
        return base.copy(
            players =
                base.players.map { player ->
                    if (!player.isViewer) {
                        player
                    } else {
                        player.copy(
                            battlefield =
                                listOf(
                                    GamePermanent(
                                        card = card("bears", "Grizzly Bears", "Creature — Bear", "1G", listOf(CardType.Creature)),
                                        attachments = listOf("aura"),
                                    ),
                                    GamePermanent(
                                        card =
                                            card("aura", "Pacifism", "Enchantment — Aura", "W").copy(rules = listOf(PACIFISM_TEXT)),
                                        attachedTo = "bears",
                                        isAttachedToPermanent = true,
                                    ),
                                ),
                        )
                    }
                },
        )
    }

    /**
     * Liliana of the Veil on the viewer's side, with her +1 and her ultimate offered — the name of the
     * ultimate clipped at fifty characters, as upstream sends it.
     */
    private fun planeswalkerGame(): GameState {
        val base = priorityGame()
        return base.copy(
            players =
                base.players.map { player ->
                    if (!player.isViewer) {
                        player
                    } else {
                        val liliana =
                            card("pw-1", "Liliana of the Veil", "Legendary Planeswalker — Liliana", "1BB", listOf(CardType.Planeswalker))
                                .copy(rules = listOf(LILIANA_PLUS, LILIANA_MINUS, LILIANA_ULTIMATE))
                        player.copy(battlefield = player.battlefield + GamePermanent(card = liliana))
                    }
                },
            playable =
                base.playable +
                    PlayableObject(
                        objectId = "pw-1",
                        abilityIds = listOf("plus-1", "minus-6"),
                        abilityNames = listOf(LILIANA_PLUS, LILIANA_ULTIMATE.take(49) + "..."),
                    ),
        )
    }

    /** The same game with the viewer holding priority and one card offered — the board being played. */
    private fun priorityGame() =
        runningGame().copy(
            viewerHasPriority = true,
            priorityPlayerName = "you",
            playable = listOf(PlayableObject("h-1")),
            prompt = GamePrompt.Select(message = "Select an ability to play"),
        )
}

private const val LILIANA_PLUS = "+1: Each player discards a card."
private const val LILIANA_MINUS = "−2: Target player sacrifices a creature."
private const val LILIANA_ULTIMATE =
    "−6: Separate all permanents target player controls into two piles. That player sacrifices all permanents in the pile of their choice."

/** The server's own text for the spell the stack tests put on it. */
private const val BOLT_TEXT = "Lightning Bolt deals 3 damage to any target."

/** And for the Aura, which is the whole reason a permanent's detail has to list what is on it. */
private const val PACIFISM_TEXT = "Enchanted creature can't attack or block."
