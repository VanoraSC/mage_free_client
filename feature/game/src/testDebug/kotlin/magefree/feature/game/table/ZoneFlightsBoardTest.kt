package magefree.feature.game.table

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.feature.game.board.BoardUi
import magefree.feature.game.board.GameBoardUiState
import magefree.feature.game.board.controlsFor
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.MageObjectType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards changing zone on a real board, from one snapshot to the next.
 *
 * **The test the stack's flights did not have.** Every other flight test places its anchors by hand, and
 * that is how no stack flight ever ran on a real board while all of them passed — and how the land column
 * turned out never to have reported an anchor at all. This one draws the whole screen, lets it measure
 * itself, changes the snapshot, and looks for the card in the air. A region that forgets to report where
 * it is fails here and nowhere else.
 *
 * The clock is held once the first board has settled, because a flight is on screen for a third of a
 * second and an idling test would let it land before looking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class ZoneFlightsBoardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val snapshot = mutableStateOf(GameState(gameId = GAME))

    private fun play(
        before: GameState,
        after: GameState,
    ) {
        snapshot.value = before
        composeTestRule.setContent {
            MageTheme {
                val state = snapshot.value
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            snapshot = state,
                            isJoining = false,
                            controls = controlsFor(state),
                        ),
                    onExit = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = {},
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.autoAdvance = false
        snapshot.value = after
        // **The write has to be announced.** With the clock held nothing idles, and a state written from
        // the test is not seen by the composition until its apply notification is sent — without this the
        // board never recomposed at all, and every flight looked missing.
        Snapshot.sendApplyNotifications()
        // Frames to compose the new board, to measure it, and for the flight that measuring starts.
        repeat(FRAMES_TO_TAKE_OFF) { composeTestRule.mainClock.advanceTimeByFrame() }
    }

    private fun assertFlying(cardId: String) {
        composeTestRule.onNodeWithTag(CardFlightTestTags.card("zone:1:$cardId"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a land played onto an empty table flies from the hand`() {
        play(
            before = table(hand = listOf(swamp("s1"))),
            after = table(myLands = listOf(swamp("s1"))),
        )

        assertFlying("s1")
    }

    @Test
    fun `a land joining a stack already on the table flies to it`() {
        // The stack's front card does not move when a copy joins it, so the new copy's own box is never
        // reported — the stack's is, and that is what the flight has to find.
        play(
            before = table(hand = listOf(swamp("s2")), myLands = listOf(swamp("s1"))),
            after = table(myLands = listOf(swamp("s1"), swamp("s2"))),
        )

        assertFlying("s2")
    }

    @Test
    fun `a discarded card flies to the graveyard on the rail`() {
        play(
            before = table(hand = listOf(bolt("b1"), bolt("b2"))),
            after = table(hand = listOf(bolt("b2")), myGraveyard = listOf(bolt("b1"))),
        )

        assertFlying("b1")
    }

    @Test
    fun `an opponent's discard comes out of one card of their hand, not the whole of it`() {
        // Pete saw the discard as *"the details view and then shrinking it"*: the flight left from the whole
        // hand strip, which is as wide as the screen. It is a card, from where a card is.
        play(
            before = table(theirHand = 4),
            after = table(theirHand = 3, theirGraveyard = listOf(bolt("b9"))),
        )

        val flight = composeTestRule.onNodeWithTag(CardFlightTestTags.card("zone:1:b9"), useUnmergedTree = true).fetchSemanticsNode()
        val hand = composeTestRule.onNodeWithTag(OpponentHandTestTags.REGION).fetchSemanticsNode()

        assertTrue(
            "the card left ${flight.size.width}px wide from a hand ${hand.size.width}px wide",
            flight.size.width * 2 < hand.size.width,
        )
    }

    @Test
    fun `an opponent's land flies from their hand to their land column`() {
        play(
            before = table(theirHand = 4),
            after = table(theirHand = 3, theirLands = listOf(swamp("s9"))),
        )

        assertFlying("s9")
    }

    @Test
    fun `a resolved spell leaves the stack for its graveyard once it has been seen there`() {
        // The server resolves it at once; the stack goes on showing it for half a second after it arrived,
        // and only then does its card fly — out of the stack, not out of nowhere.
        composeTestRule.mainClock.autoAdvance = false
        snapshot.value = table(stack = listOf(boltSpell()))
        showBoard()
        settleFrames()
        snapshot.value = table(myGraveyard = listOf(bolt("b1")))
        settleFrames()

        composeTestRule.mainClock.advanceTimeBy(300)
        settleFrames()
        composeTestRule.onNodeWithTag(StackTestTags.entry(SPELL), useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(CardFlightTestTags.card("zone:1:b1"), useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.mainClock.advanceTimeBy(500)
        settleFrames()

        assertFlying("b1")
    }

    /** The board over [snapshot], without settling anything. */
    private fun showBoard() {
        composeTestRule.setContent {
            MageTheme {
                val state = snapshot.value
                TableBoardScreen(
                    uiState =
                        GameBoardUiState(
                            board = BoardUi.from(state),
                            snapshot = state,
                            isJoining = false,
                            controls = controlsFor(state),
                        ),
                    onExit = {},
                    onControlsVisibleChange = {},
                    onCardTap = {},
                    onAction = {},
                    artRenderer = PlaceholderCardArtRenderer,
                )
            }
        }
    }

    /** Announces what was written and draws it, with the clock held. */
    private fun settleFrames() {
        Snapshot.sendApplyNotifications()
        repeat(3) { composeTestRule.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `a creature that dies flies to its owner's graveyard`() {
        play(
            before = table(theirCreatures = listOf(bears("c1"))),
            after = table(theirGraveyard = listOf(bears("c1"))),
        )

        assertFlying("c1")
    }

    @Test
    fun `an exiled creature flies to its owner's count panel`() {
        play(
            before = table(theirCreatures = listOf(bears("c1"))),
            after = table(theirExile = listOf(bears("c1"))),
        )

        assertFlying("c1")
    }

    private fun table(
        hand: List<GameCard> = emptyList(),
        myLands: List<GameCard> = emptyList(),
        myGraveyard: List<GameCard> = emptyList(),
        theirCreatures: List<GameCard> = emptyList(),
        theirGraveyard: List<GameCard> = emptyList(),
        theirExile: List<GameCard> = emptyList(),
        theirHand: Int = 4,
        theirLands: List<GameCard> = emptyList(),
        stack: List<GameCard> = emptyList(),
    ) = GameState(
        gameId = GAME,
        hasSnapshot = true,
        turn = 3,
        viewerPlayerId = ME,
        activePlayerId = ME,
        hand = hand,
        stack = stack,
        players =
            listOf(
                GamePlayer(
                    playerId = THEM,
                    name = "Computer",
                    life = 20,
                    libraryCount = 40,
                    handCount = theirHand,
                    isHuman = false,
                    battlefield = (theirCreatures + theirLands).map { GamePermanent(card = it) },
                    graveyard = theirGraveyard,
                    exile = theirExile,
                ),
                GamePlayer(
                    playerId = ME,
                    name = "you",
                    isViewer = true,
                    life = 20,
                    libraryCount = 40,
                    handCount = hand.size,
                    battlefield = myLands.map { GamePermanent(card = it) },
                    graveyard = myGraveyard,
                ),
            ),
    )

    private fun swamp(id: String) =
        GameCard(
            id = id,
            name = "Swamp",
            setCode = "10E",
            collectorNumber = "371",
            typeLine = "Basic Land — Swamp",
            cardTypes = listOf(CardType.Land),
        )

    private fun bolt(id: String) =
        GameCard(
            id = id,
            name = "Lightning Bolt",
            setCode = "10E",
            collectorNumber = "203",
            manaCost = "{R}",
            cardTypes = listOf(CardType.Instant),
        )

    /** Lightning Bolt as a spell on the stack: an id of its own, and upstream's `SPELL` object type. */
    private fun boltSpell() = bolt(SPELL).copy(objectType = MageObjectType.Spell)

    private fun bears(id: String) =
        GameCard(
            id = id,
            name = "Grizzly Bears",
            setCode = "10E",
            collectorNumber = "268",
            power = "2",
            toughness = "2",
            isCreature = true,
            cardTypes = listOf(CardType.Creature),
        )
}

private const val GAME = "g-1"
private const val ME = "p-you"
private const val THEM = "p-opp"
private const val SPELL = "spell-1"

/**
 * Frames between a snapshot changing and a card being in the air — see [ZoneFlightsBoardTest.play].
 *
 * A composition, a layout that reports the new boxes, and a recomposition that starts the flight, with
 * room to spare; six frames is a tenth of a second, well inside the third of a second a flight lasts.
 */
private const val FRAMES_TO_TAKE_OFF = 6
