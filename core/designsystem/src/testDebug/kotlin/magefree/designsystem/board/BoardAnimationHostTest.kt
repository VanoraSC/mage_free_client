package magefree.designsystem.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Compose half of the host.
 *
 * One thing is worth asserting here and the sequencer's own tests cover the rest: that an object
 * whose slot changes is **the same object afterwards**. Everything the move is supposed to tell the
 * player depends on it — a host that recreated the object would be showing a disappearance beside an
 * appearance, and would pass any test that only looked at where things ended up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BoardAnimationHostTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var creations = 0

    /** Two stacked slots and one object, with the board driven by whatever the test sets. */
    private fun showBoard(initial: BoardSnapshot): () -> Unit {
        var state by mutableStateOf(initial)
        composeTestRule.setContent {
            MageTheme {
                BoardAnimationHost(
                    snapshot = state,
                    objectContent = { shown ->
                        // Counted once per object the host actually builds: a recreated object counts twice.
                        remember { creations++ }
                        Box(modifier = Modifier.size(CARD_SIZE).testTag(shown.id.value))
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.height(SLOT_HEIGHT).fillMaxWidth().testTag(HAND.value)) {
                            SlotObjects(HAND)
                        }
                        Box(modifier = Modifier.height(SLOT_HEIGHT).fillMaxWidth().testTag(BATTLEFIELD.value)) {
                            SlotObjects(BATTLEFIELD)
                        }
                    }
                }
            }
        }
        return { state = BoardSnapshot(listOf(BoardObject(BEAR, BATTLEFIELD))) }
    }

    @Test
    fun `an object that changes slot is the same object afterwards`() {
        val moveToBattlefield = showBoard(BoardSnapshot(listOf(BoardObject(BEAR, HAND))))
        composeTestRule.onNodeWithTag(BEAR.value).assertIsDisplayed()
        assertEquals("built once to begin with", 1, creations)

        composeTestRule.runOnIdle(moveToBattlefield)
        composeTestRule.waitForIdle()

        assertEquals(
            "the move recreated the object — the player was shown a disappearance and an appearance",
            1,
            creations,
        )
        composeTestRule.onNodeWithTag(BEAR.value).assertIsDisplayed()
    }

    @Test
    fun `an object owns one slot, and is on screen once`() {
        // "One owning slot per snapshot" is enforced by the runtime rather than by convention: movable
        // content cannot be composed in two places, so a snapshot that tried would fail here.
        showBoard(BoardSnapshot(listOf(BoardObject(BEAR, HAND))))

        assertEquals(1, composeTestRule.onAllNodesWithTag(BEAR.value).fetchSemanticsNodes().size)
    }

    @Test
    fun `a slot change lands the object in its new slot`() {
        val moveToBattlefield = showBoard(BoardSnapshot(listOf(BoardObject(BEAR, HAND))))
        val startedAt = topOf(BEAR.value)

        composeTestRule.runOnIdle(moveToBattlefield)
        composeTestRule.waitForIdle()

        val settledAt = topOf(BEAR.value)
        assertTrue("the object never left the hand", settledAt > startedAt)
        assertEquals("and it settled in the battlefield slot", topOf(BATTLEFIELD.value), settledAt, 1f)
    }

    @Test
    fun `the object travels, rather than jumping`() {
        // The assertion the shared coordinate space exists for: part-way through the move the object is
        // in neither slot. A host that reparented without animating would already be at its destination.
        composeTestRule.mainClock.autoAdvance = false
        val moveToBattlefield = showBoard(BoardSnapshot(listOf(BoardObject(BEAR, HAND))))
        val startedAt = topOf(BEAR.value)

        composeTestRule.runOnIdle(moveToBattlefield)

        val destination = topOf(BATTLEFIELD.value)
        val trace =
            (1..SAMPLES).map {
                composeTestRule.mainClock.advanceTimeBy(BoardDuration.ZONE_MOVE / SAMPLES.toLong())
                topOf(BEAR.value)
            }

        assertTrue(
            "the object was never between $startedAt and $destination during the move: $trace — it " +
                "jumped rather than travelled",
            trace.any { it > startedAt && it < destination },
        )
    }

    /** Where a node's top edge sits in the window, in pixels. */
    private fun topOf(tag: String): Float =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.y

    private companion object {
        val HAND = BoardSlotId("hand")
        val BATTLEFIELD = BoardSlotId("battlefield")
        val BEAR = BoardObjectId("bear")

        val SLOT_HEIGHT = 120.dp
        val CARD_SIZE = 40.dp

        /** How many readings are taken across one move. */
        const val SAMPLES = 8
    }
}
