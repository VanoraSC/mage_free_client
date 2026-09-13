package magefree.feature.game.table

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card stays on the stack long enough to be seen.
 *
 * Pete: *"at least 500ms so it can be seen animating."* A spell the server resolves between two snapshots
 * used to leave the stack before its flight had landed, so it was never drawn at all. The clock is held,
 * because what is being tested is time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class StackDwellTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val stack = mutableStateOf(emptyList<TableStackObject>())
    private var presented: List<TableStackObject> = emptyList()

    private fun show() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            MageTheme {
                presented = rememberPresentedStack(stack.value)
            }
        }
        settle()
    }

    private fun serverStack(vararg ids: String) {
        stack.value = ids.map { TableStackObject(id = it, state = BoardCardState(card = CardDisplay(name = it))) }
        settle()
    }

    /** Announces what was written and lets a frame draw it — a held clock idles nothing on its own. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a spell the server resolves at once stays on the stack long enough to be seen`() {
        show()
        serverStack("s1")
        serverStack()

        assertEquals("it has not been seen yet", listOf("s1"), presented.map { it.id })

        composeTestRule.mainClock.advanceTimeBy(600)
        settle()
        assertEquals("still there after its flight, for at least half a second more", listOf("s1"), presented.map { it.id })

        composeTestRule.mainClock.advanceTimeBy(400)
        settle()
        assertTrue("and then it goes", presented.isEmpty())
    }

    @Test
    fun `a spell that has been on the stack long enough leaves when the server's does`() {
        show()
        serverStack("s1")
        composeTestRule.mainClock.advanceTimeBy(1_000)
        settle()

        serverStack()

        assertTrue(presented.isEmpty())
    }

    @Test
    fun `a new arrival goes on top of a spell that is still being shown`() {
        show()
        serverStack("s1")
        serverStack()
        serverStack("s2")

        assertEquals(listOf("s2", "s1"), presented.map { it.id })
    }
}
