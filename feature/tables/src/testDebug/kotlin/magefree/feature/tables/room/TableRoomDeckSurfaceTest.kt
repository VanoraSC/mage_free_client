package magefree.feature.tables.room

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import magefree.designsystem.theme.MageTheme
import magefree.feature.tables.TableRole
import magefree.network.table.Seat
import magefree.network.table.TableState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The table room offers no deck surface, in any role.
 *
 * The deck is chosen and bound when the seat is taken: upstream's `TableController.joinTable` loads
 * it, validates it against the table's format and seats the player with `match.addPlayer(player,
 * deck)`. Its `submitDeck`/`updateDeck` return early unless the table is in `SIDEBOARDING` or
 * `CONSTRUCTING`, which a constructed duel's room never is — so a picker here would be a control the
 * server discards.
 *
 * Each case also asserts the room still renders what it is *for*, so a screen that failed to compose
 * at all could not pass by simply showing nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp")
class TableRoomDeckSurfaceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(role: TableRole) {
        composeTestRule.setContent {
            MageTheme {
                TableRoomScreen(
                    uiState =
                        TableRoomUiState(
                            table =
                                TableState(
                                    tableId = "t-1",
                                    seats = listOf(Seat(index = 0, name = "pete"), Seat(index = 1, name = null)),
                                ),
                            role = role,
                            isLoading = false,
                        ),
                    onBack = {},
                    onStart = {},
                    onRemove = {},
                    onLeave = {},
                )
            }
        }
    }

    @Test
    fun aHostSeesSeatsAndStartButNoDeckSurface() {
        show(TableRole.Host)

        composeTestRule.onNodeWithText(DECK_SECTION_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("Start match").assertIsDisplayed()
    }

    @Test
    fun aSeatedPlayerSeesLeaveButNoDeckSurface() {
        show(TableRole.Player)

        composeTestRule.onNodeWithText(DECK_SECTION_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("Leave table").assertIsDisplayed()
    }

    @Test
    fun aSpectatorSeesNeither() {
        show(TableRole.Spectator)

        composeTestRule.onNodeWithText(DECK_SECTION_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("You are spectating this table.").assertIsDisplayed()
    }

    private companion object {
        /** The heading the removed picker sat under. */
        const val DECK_SECTION_TITLE = "Submit your deck"
    }
}
