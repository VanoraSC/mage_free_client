package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards arriving on the stack, drawn travelling.
 *
 * **What is worth pinning is which flights are *started*, not how they look.** The animation is an
 * overlay that lands on a stack card already drawn underneath it, so a wrong-looking flight costs a
 * player nothing; a flight that starts for the wrong object, or twice, or from an origin the board
 * never measured, is what would put a card where the game does not have one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class CardFlightsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val anchors = BoardAnchors()
    private val stack = mutableStateOf(emptyList<TableStackObject>())
    private var seen: List<CardFlight> = emptyList()

    private fun show() {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    seen = rememberCardFlights(stack = stack.value, anchors = anchors)
                }
            }
        }
    }

    @Test
    fun `a spell flies from where its own card was, because it kept its id`() {
        // A card on the stack has the same object id it had in hand, so the card that left is the card
        // that arrived — the origin needs no matching and no guessing.
        anchors.placeForTest(handAnchorId("spell-1"), from())
        anchors.placeForTest("spell-1", to())
        // The stack's anchor is the *current* box; the hand's is what it was before the card left, and
        // the anchors keep both because nothing prunes them.
        show()

        stack.value = listOf(entry("spell-1"))
        composeTestRule.waitForIdle()

        assertEquals(listOf("spell-1"), seen.map { it.id })
    }

    @Test
    fun `a spell still flies when its destination is only measured after it arrives`() {
        // **The order a real board produces, and it is why no card ever flew.** An object is on the
        // stack in the snapshot *before* it has been laid out there — [BoardAnchors] is written from
        // `onGloballyPositioned`, which runs after composition — so on the composition that first sees
        // the arrival there is no destination yet. It was dropped, with its id already recorded as
        // known, and nothing was ever flown on a real board while every test here passed: each of them
        // places both anchors before touching the stack, which is the one order the board never does.
        anchors.placeForTest(handAnchorId("spell-1"), from())
        show()

        stack.value = listOf(entry("spell-1"))
        composeTestRule.waitForIdle()
        assertEquals("there is nowhere to fly to yet", emptyList<String>(), seen.map { it.id })

        anchors.placeForTest("spell-1", to())
        composeTestRule.waitForIdle()

        assertEquals("the arrival was still waiting for its destination", listOf("spell-1"), seen.map { it.id })
    }

    @Test
    fun `an ability flies from the permanent that produced it`() {
        // An ability has no continuity with anything the player has seen — its id is new. `sourceId` is
        // upstream's own `sourceCard.getId()`, the permanent on the battlefield.
        anchors.placeForTest("bears", from())
        anchors.placeForTest("ability-1", to())
        show()

        stack.value = listOf(entry("ability-1", sourceId = "bears"))
        composeTestRule.waitForIdle()

        val flight = seen.single()
        assertEquals("ability-1", flight.id)
        assertEquals(from(), flight.from)
        assertEquals(to(), flight.to)
    }

    @Test
    fun `an object already on the stack is never flown again`() {
        // An arrival is *new to the stack*. Re-flying on every snapshot would send a card across the
        // board each time the opponent gained a life point.
        anchors.placeForTest("bears", from())
        anchors.placeForTest("ability-1", to())
        show()

        stack.value = listOf(entry("ability-1", sourceId = "bears"))
        composeTestRule.waitForIdle()
        anchors.placeForTest("ability-2", to())
        stack.value = listOf(entry("ability-1", sourceId = "bears"), entry("ability-2", sourceId = "bears"))
        composeTestRule.waitForIdle()

        assertEquals(
            "the first is not re-flown when the second arrives",
            listOf("ability-1", "ability-2"),
            seen.map { it.id },
        )
        assertEquals(1, seen.count { it.id == "ability-1" })
    }

    @Test
    fun `an origin the board never measured produces no flight, not a flight from nowhere`() {
        // The board is correct without the animation, so the honest failure is no movement at all.
        anchors.placeForTest("ability-1", to())
        show()

        stack.value = listOf(entry("ability-1", sourceId = "never-drawn"))
        composeTestRule.waitForIdle()

        assertEquals(emptyList<String>(), seen.map { it.id })
    }

    @Test
    fun `a spell that resolved before its flight finished is dropped`() {
        anchors.placeForTest("bears", from())
        anchors.placeForTest("ability-1", to())
        show()

        stack.value = listOf(entry("ability-1", sourceId = "bears"))
        composeTestRule.waitForIdle()
        stack.value = emptyList()
        composeTestRule.waitForIdle()

        assertEquals("nothing left to land on", emptyList<String>(), seen.map { it.id })
    }

    @Test
    fun `the overlay draws a card for each flight`() {
        composeTestRule.setContent {
            MageTheme {
                CardFlightOverlay(
                    flights = listOf(CardFlight(id = "s1", entry = entry("s1"), from = from(), to = to())),
                    palette = magefree.designsystem.card.rememberCounterPalette(),
                    artFor = null,
                    onLanded = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CardFlightTestTags.card("s1")).assertIsDisplayed()
    }

    private fun from() = Rect(left = 10f, top = 400f, right = 70f, bottom = 460f)

    private fun to() = Rect(left = 300f, top = 150f, right = 420f, bottom = 270f)

    private fun entry(
        id: String,
        sourceId: String? = null,
    ) = TableStackObject(
        id = id,
        state = BoardCardState(card = CardDisplay(name = "Something")),
        sourceId = sourceId,
    )
}
