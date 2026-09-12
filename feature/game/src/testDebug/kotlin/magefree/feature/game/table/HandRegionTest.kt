package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hand on the board.
 *
 * §7.4's rule about the hand is the one worth testing, and it is not a rule about how the hand looks:
 * *"the hand never collapses"*. The failure it forbids is a card that is on the board's own terms
 * present but on the player's terms a gesture away — off the edge of a scroll, or behind a peek edge.
 * That is invisible in a screenshot of a seven-card hand and only shows up when the hand is large, so
 * the assertion that matters is **every card is on screen, however many there are**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class HandRegionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val played = mutableListOf<String>()
    private val inspected = mutableListOf<String>()
    private val dragged = mutableListOf<String>()

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        hand = handCards(state),
                        onPlayFromHand = { played += it },
                        onDragFromHand = { dragged += it },
                        onInspect = { inspected += it },
                    )
                }
            }
        }
    }

    /** Where a card sits and how wide it is, in root coordinates. */
    private fun bounds(cardId: String): Pair<Float, Float> {
        val node = composeTestRule.onNodeWithTag(HandTestTags.card(cardId)).fetchSemanticsNode()
        return node.positionInRoot.x to node.size.width.toFloat()
    }

    @Test
    fun `a hand of seven is all on screen and does not overlap`() {
        show(stateWith(7))

        (0 until 7).forEach { index ->
            composeTestRule.onNodeWithTag(HandTestTags.card("h$index")).assertIsDisplayed()
        }
        val (firstX, firstWidth) = bounds("h0")
        val (secondX, _) = bounds("h1")
        assertTrue("a comfortable hand should not overlap", secondX >= firstX + firstWidth)
    }

    @Test
    fun `a hand too wide to lay out flat overlaps rather than scrolling away`() {
        // The test a scrolling hand fails and a plain row fails differently: a row of twenty runs off
        // the edge, and a scroller puts them a swipe away. Both are the collapse §7.4 rules out.
        show(stateWith(20))

        val (firstX, firstWidth) = bounds("h0")
        val (secondX, _) = bounds("h1")
        assertTrue("twenty cards should overlap", secondX < firstX + firstWidth)

        val (lastX, lastWidth) = bounds("h19")
        assertTrue("the last card should still be on screen, ending at $lastX + $lastWidth", lastX + lastWidth <= BOARD_WIDTH_PX)
        assertTrue("the first card should still be on screen, starting at $firstX", firstX >= 0f)
    }

    @Test
    fun `an empty hand takes no room at all`() {
        show(stateWith(0))

        composeTestRule.onNodeWithTag(HandTestTags.HAND).assertDoesNotExist()
    }

    @Test
    fun `tap plays and long press inspects, which is the vocabulary everywhere else uses`() {
        show(stateWith(3))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performClick()
        composeTestRule.onNodeWithTag(HandTestTags.card("h2")).performTouchInput { longClick() }

        assertEquals(listOf("h1"), played)
        assertEquals(listOf("h2"), inspected)
    }

    @Test
    fun `dragging a playable card out of the hand plays it, rather than raising it`() {
        // §7.1's accelerator. It is the commit, not the look a tap gives: it used to go down the tap's
        // path, which raised the card and left the player pressing Play anyway. The threshold matters —
        // this gesture submits a game action, and the cost of firing it by accident is a spell on the
        // stack the player did not intend.
        show(stateWith(3))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 200f)
        }

        assertEquals(listOf("h1"), dragged)
        assertEquals("a drag is not a tap", emptyList<String>(), played)
    }

    @Test
    fun `a short drag that stays in the hand plays nothing`() {
        show(stateWith(3))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 20f)
        }

        assertEquals(emptyList<String>(), dragged)
    }

    @Test
    fun `dragging a card the server has not offered plays nothing`() {
        // Dragging an uncastable card either does nothing, which is confusing, or submits an action
        // the server never offered, which is worse. It returns to the hand.
        show(stateWith(3, playable = false))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 200f)
        }

        assertEquals(emptyList<String>(), dragged)
        assertEquals(emptyList<String>(), played)
    }

    @Test
    fun `a hand of twelve costs the battlefield no more than a hand of one`() {
        // The hand has a place of its own at the bottom of the screen, and what has to stay true is
        // that the place does not *grow*: a player holding twelve cards has as much battlefield as one
        // holding one, because the tiles overlap rather than the strip getting taller. A hand that
        // took height per card would shrink the board every time somebody drew.
        composeTestRule.setContent {
            MageTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Board(state = stateWith(12, playerId = "holding"), modifier = Modifier.weight(1f))
                    Board(state = stateWith(1, playerId = "one"), modifier = Modifier.weight(1f))
                }
            }
        }

        val holding =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.side("holding"))
                .fetchSemanticsNode()
                .size.height
        val one =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.side("one"))
                .fetchSemanticsNode()
                .size.height

        // Within a pixel: the two boards are stacked to share a render, so their halves differ by the
        // odd row.
        assertTrue(
            "with twelve cards the side measured $holding, with one $one",
            kotlin.math.abs(holding - one) <= 2,
        )
    }

    @Test
    fun `the hand sits below the lands, never over them`() {
        // A hand covering the lands would put the cards you tap for mana under the cards you tap to
        // spend it — and the land column is the one region whose whole purpose is being tappable.
        show(stateWith(12, playerId = "me", lands = 3))

        val lands =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.row("me", BattlefieldTestTags.LAND_ZONE))
                .fetchSemanticsNode()
        val landsBottom = lands.positionInRoot.y + lands.size.height
        val handTop =
            composeTestRule
                .onNodeWithTag(HandTestTags.card("h0"))
                .fetchSemanticsNode()
                .positionInRoot.y

        assertTrue("the lands end at $landsBottom and the hand starts at $handTop", handTop >= landsBottom)
    }

    @Composable
    private fun Board(
        state: GameState,
        modifier: Modifier = Modifier,
    ) {
        BattlefieldLayout(model = battlefieldModel(state), hand = handCards(state), modifier = modifier)
    }
}

private const val BOARD_WIDTH_PX = 891f * 1f

private fun stateWith(
    handSize: Int,
    playerId: String = "me",
    lands: Int = 0,
    playable: Boolean = true,
) = GameState(
    gameId = "g",
    viewerPlayerId = playerId,
    hand = (0 until handSize).map { GameCard(id = "h$it", name = "Grizzly Bears", manaCost = "{1}{G}") },
    // Every card in hand is playable, which is what a snapshot on your own main phase looks like.
    playable = if (playable) (0 until handSize).map { PlayableObject(objectId = "h$it") } else emptyList(),
    players =
        listOf(
            GamePlayer(
                playerId = playerId,
                name = playerId,
                isViewer = true,
                battlefield =
                    (0 until lands).map { index ->
                        GamePermanent(
                            card = GameCard(id = "l$index", name = "Forest", cardTypes = listOf(CardType.Land)),
                        )
                    },
            ),
        ),
)

/**
 * Cards the server is offering that are **not in the hand**, beside it.
 *
 * The whole design is one claim — *these are castable, and they are not in your hand* — and it is
 * carried entirely by position. So the assertions are about position: the group is to the right of the
 * hand, and the space between the two groups is clearly more than the space inside either. A layout
 * that put them in the row would be telling the player they hold cards they do not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class PlayableElsewhereRegionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val played = mutableListOf<String>()

    private fun show(
        hand: Int,
        elsewhere: List<TableCard>,
    ) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(stateWith(hand)),
                        hand = handCards(stateWith(hand)),
                        playableElsewhere = elsewhere,
                        onPlayFromHand = { played += it },
                    )
                }
            }
        }
    }

    @Test
    fun `they sit to the right of the hand, set further apart than the hand's own cards`() {
        show(hand = 5, elsewhere = listOf(graveyardCard("gy1"), graveyardCard("gy2")))

        val lastInHand = right("h4")
        val firstElsewhere = left("gy1")
        val betweenGroups = firstElsewhere - lastInHand
        val withinGroup = left("gy2") - right("gy1")

        assertTrue("the group should be right of the hand, at $firstElsewhere against $lastInHand", betweenGroups > 0)
        assertTrue(
            "the gap between the groups ($betweenGroups) must beat the gap inside one ($withinGroup)",
            betweenGroups > withinGroup * 2,
        )
    }

    @Test
    fun `each says which pile it is in, because the gap only says it is not the hand`() {
        show(hand = 2, elsewhere = listOf(graveyardCard("gy1")))

        composeTestRule
            .onNodeWithTag(HandTestTags.zoneLabel("gy1"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `pressing one asks to play it, the same as a card in hand`() {
        show(hand = 2, elsewhere = listOf(graveyardCard("gy1")))

        composeTestRule.onNodeWithTag(HandTestTags.card("gy1")).performClick()

        assertEquals(listOf("gy1"), played)
    }

    @Test
    fun `a hand with nothing else offered draws only the hand`() {
        show(hand = 3, elsewhere = emptyList())

        composeTestRule.onNodeWithTag(HandTestTags.card("h0")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HandTestTags.zoneLabel("gy1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `and a group with no hand behind it is still drawn`() {
        // A player who has emptied their hand can still have a flashback card, and that is exactly the
        // turn they most need to see it.
        show(hand = 0, elsewhere = listOf(graveyardCard("gy1")))

        composeTestRule.onNodeWithTag(HandTestTags.card("gy1")).assertIsDisplayed()
    }

    private fun left(cardId: String): Float =
        composeTestRule
            .onNodeWithTag(HandTestTags.card(cardId))
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun right(cardId: String): Float =
        composeTestRule.onNodeWithTag(HandTestTags.card(cardId)).fetchSemanticsNode().let { node ->
            node.positionInRoot.x + node.size.width
        }

    private fun graveyardCard(id: String) =
        TableCard(
            id = id,
            card = CardDisplay(name = "Serra Angel", manaCost = "{3}{W}{W}"),
            signal = BoardCardSignal.Playable,
            zone = TableCardZone.Graveyard,
            playableAbilities = listOf("Flashback {3}{W}{W}"),
        )
}
