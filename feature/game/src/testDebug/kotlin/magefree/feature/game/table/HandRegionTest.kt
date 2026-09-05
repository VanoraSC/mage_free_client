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

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        hand = handCards(state),
                        onPlayFromHand = { played += it },
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
    fun `dragging a playable card out of the hand plays it`() {
        // §7.1's accelerator, and it does exactly what the button does. The threshold matters: this
        // gesture submits a game action, and the cost of firing it by accident is a spell on the stack
        // the player did not intend.
        show(stateWith(3))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 200f)
        }

        assertEquals(listOf("h1"), played)
    }

    @Test
    fun `dragging a card the server has not offered plays nothing`() {
        // Dragging an uncastable card either does nothing, which is confusing, or submits an action
        // the server never offered, which is worse. It returns to the hand.
        show(stateWith(3, playable = false))

        composeTestRule.onNodeWithTag(HandTestTags.card("h1")).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 200f)
        }

        assertEquals(emptyList<String>(), played)
    }

    @Test
    fun `the hand costs the battlefields no height at all`() {
        // The rule that replaced the first cut's. Cutting the board into bands — opponent, viewer,
        // hand — held the hand's band open across the *full width* even though a hand only occupies
        // the middle of it, and the visible cost was a land corner floating above the bottom of the
        // screen with a rectangle of nothing under it. The regions overlay instead, so a player
        // holding twelve cards has exactly as much battlefield as one holding none.
        composeTestRule.setContent {
            MageTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    Board(state = stateWith(12, playerId = "holding"), modifier = Modifier.weight(1f))
                    Board(state = stateWith(0, playerId = "empty"), modifier = Modifier.weight(1f))
                }
            }
        }

        val holding =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.side("holding"))
                .fetchSemanticsNode()
                .size.height
        val empty =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.side("empty"))
                .fetchSemanticsNode()
                .size.height

        // Within a pixel: the two boards are stacked to share a render, so their halves differ by the
        // odd row. What matters is that the hand costs nothing, not that it costs exactly nothing.
        assertTrue(
            "with a hand the side measured $holding, without it $empty",
            kotlin.math.abs(holding - empty) <= 2,
        )
    }

    @Test
    fun `the hand sits beside the land corner, never over it`() {
        // A hand covering the lands would put the cards you tap for mana under the cards you tap to
        // spend it — and the land corner is the one region whose whole purpose is being tappable.
        show(stateWith(12, playerId = "me", lands = 3))

        val lands =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.row("me", BattlefieldTestTags.LAND_ZONE))
                .fetchSemanticsNode()
        val landsRight = lands.positionInRoot.x + lands.size.width
        val (handLeft, _) = bounds("h0")

        assertTrue("the lands end at $landsRight and the hand starts at $handLeft", handLeft >= landsRight)
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
