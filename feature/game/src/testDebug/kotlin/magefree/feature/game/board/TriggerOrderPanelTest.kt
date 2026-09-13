package magefree.feature.game.board

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.theme.MageTheme
import magefree.feature.cards.PlaceholderCardArtRenderer
import magefree.network.game.GameCard
import magefree.network.game.TriggerAutoOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trigger-ordering panel: what it draws, and the one arrangement a press sends.
 *
 * Pete's four Soul Wardens, three Soul's Attendants and two Auriok Champions were nine cards and eighteen
 * presses. What is pinned here is that they are three things to order, that a drag reorders them, and that
 * the whole order leaves in one action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class TriggerOrderPanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val actions = mutableListOf<BoardAction>()

    private fun show(controls: PromptControlsUi.TriggerOrder = lifeTriggers()) {
        composeTestRule.setContent {
            MageTheme {
                TriggerOrderPanel(controls = controls, artRenderer = PlaceholderCardArtRenderer, onAction = { actions += it })
            }
        }
    }

    @Test
    fun `equivalent triggers are drawn once, with how many there are`() {
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.group("t1")).assertExists()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.group("t3")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.count("t1"), useUnmergedTree = true).assertTextEquals("×2")
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.count("t2"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `one press puts them all on the stack in the order shown, the left one resolving first`() {
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performClick()

        assertEquals(listOf(BoardAction.OrderTriggers(resolveOrder = listOf("t1", "t3", "t2"))), actions)
    }

    @Test
    fun `a group held and carried past its neighbour changes places with it`() {
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.group("t1")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(DRAG_STEPS) { moveBy(Offset(width * 1.2f / DRAG_STEPS, 0f)) }
            up()
        }
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performClick()

        assertEquals(listOf(BoardAction.OrderTriggers(resolveOrder = listOf("t2", "t1", "t3"))), actions)
    }

    @Test
    fun `a plain drag without holding first moves nothing`() {
        // The row scrolls; a drag that did not hold is that scroll, not a reorder.
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.group("t1")).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(width * 1.2f / DRAG_STEPS, 0f)) }
            up()
        }
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performClick()

        assertEquals(listOf(BoardAction.OrderTriggers(resolveOrder = listOf("t1", "t3", "t2"))), actions)
    }

    @Test
    fun `always first sends the rule the server keys it by, and moves the group to the front`() {
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.alwaysFirst("t2")).performClick()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performClick()

        assertEquals(
            listOf(
                BoardAction.AlwaysOrderTrigger(
                    ruleText = "When Soul's Attendant is around, you may gain 1 life.",
                    order = TriggerAutoOrder.ResolveFirst,
                ),
                BoardAction.OrderTriggers(resolveOrder = listOf("t2", "t1", "t3")),
            ),
            actions,
        )
    }

    @Test
    fun `always last moves the group to the end`() {
        show()

        composeTestRule.onNodeWithTag(TriggerOrderTestTags.alwaysLast("t1")).performClick()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).performClick()

        assertEquals(BoardAction.OrderTriggers(resolveOrder = listOf("t2", "t1", "t3")), actions.last())
    }

    @Test
    fun `while the arrangement is being placed, the panel says so and asks nothing`() {
        show(lifeTriggers().copy(isPlacing = true))

        composeTestRule.onNodeWithText(PLACING_TRIGGERS_NOTE).assertExists()
        composeTestRule.onNodeWithTag(TriggerOrderTestTags.CONFIRM).assertDoesNotExist()
    }

    private fun lifeTriggers() =
        PromptControlsUi.TriggerOrder(
            message = ORDER_TRIGGERS_MESSAGE,
            triggerGroups =
                triggerGroups(
                    listOf(
                        GameCard(
                            id = "t1",
                            name = "Soul Warden",
                            rules = listOf("Whenever another creature enters the battlefield, you gain 1 life."),
                        ),
                        GameCard(id = "t2", name = "Soul's Attendant", rules = listOf("When {this} is around, you may gain 1 life.")),
                        GameCard(
                            id = "t3",
                            name = "Soul Warden",
                            rules = listOf("Whenever another creature enters the battlefield, you gain 1 life."),
                        ),
                    ),
                ),
        )
}

/** A drag in several moves, as a finger makes one, so the gesture sees it travel. */
private const val DRAG_STEPS = 10
