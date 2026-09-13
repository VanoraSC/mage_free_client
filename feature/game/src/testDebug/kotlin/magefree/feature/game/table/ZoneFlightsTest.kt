package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import magefree.designsystem.theme.MageTheme
import magefree.network.game.CardType
import magefree.network.game.GameCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cards changing zone, drawn travelling.
 *
 * As with the stack's flights, what is worth pinning is **which flights start and where they go**: an
 * animation that looks wrong costs nothing, and one that starts from a place the board never measured,
 * lands on where a card *used* to be, or hides a card that is not travelling, is the board saying
 * something the game did not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class ZoneFlightsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val anchors = BoardAnchors()
    private val batch = mutableStateOf(ZoneMoveBatch())
    private lateinit var flights: ZoneFlights

    private fun show() {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    flights = rememberZoneFlights(batch = batch.value, anchors = anchors)
                }
            }
        }
    }

    @Test
    fun `a discard flies from the hand card to the graveyard`() {
        anchors.placeForTest(handCardAnchorId("b1"), from())
        anchors.placeForTest(graveyardAnchorId("me"), to())
        show()

        batch.value = ZoneMoveBatch(1, listOf(discard("b1")))
        composeTestRule.waitForIdle()

        val flight = flights.flights.single()
        assertEquals(from(), flight.from)
        assertEquals(to(), flight.to)
        assertEquals(setOf("b1"), flights.hidden)
    }

    @Test
    fun `a destination drawn for the first time is waited for, and never taken from where the card used to be`() {
        // The card was on the battlefield once before, so its id still answers with that old box. It must
        // not fly there: it waits for the box its new place reports.
        anchors.placeForTest(handCardAnchorId("s1"), from())
        anchors.placeForTest("s1", Rect(left = 0f, top = 0f, right = 5f, bottom = 5f))
        show()

        batch.value = ZoneMoveBatch(1, listOf(played("s1")))
        composeTestRule.waitForIdle()
        assertTrue("nothing to land on yet", flights.flights.isEmpty())
        assertEquals("its place is held while it waits", setOf("s1"), flights.hidden)

        anchors.placeForTest("s1", to())
        composeTestRule.waitForIdle()

        assertEquals(to(), flights.flights.single().to)
    }

    @Test
    fun `a land joining a stack lands on the stack`() {
        anchors.placeForTest(handCardAnchorId("s2"), from())
        anchors.placeForTest("s1", to())
        show()

        batch.value = ZoneMoveBatch(1, listOf(played("s2").copy(to = listOf("s1", "s2"))))
        composeTestRule.waitForIdle()

        assertEquals(to(), flights.flights.single().to)
    }

    @Test
    fun `an origin the board never measured flies nothing and hides nothing`() {
        anchors.placeForTest(graveyardAnchorId("them"), to())
        show()

        batch.value =
            ZoneMoveBatch(
                1,
                listOf(ZoneMove("b9", ZoneMoveKind.Discarded, bolt("b9"), opponentHandAnchorId("them"), listOf(graveyardAnchorId("them")))),
            )
        composeTestRule.waitForIdle()

        assertTrue(flights.flights.isEmpty())
        assertTrue(flights.hidden.isEmpty())
    }

    @Test
    fun `the moves a board opens on are not flown`() {
        // They happened before this board was drawn, from a place it never measured.
        anchors.placeForTest(handCardAnchorId("b1"), from())
        anchors.placeForTest(graveyardAnchorId("me"), to())
        batch.value = ZoneMoveBatch(4, listOf(discard("b1")))
        show()
        composeTestRule.waitForIdle()

        assertTrue(flights.flights.isEmpty())
    }

    @Test
    fun `a landed flight gives its card back`() {
        anchors.placeForTest(handCardAnchorId("b1"), from())
        anchors.placeForTest(graveyardAnchorId("me"), to())
        show()
        batch.value = ZoneMoveBatch(1, listOf(discard("b1")))
        composeTestRule.waitForIdle()

        flights.landed(flights.flights.single().id)
        composeTestRule.waitForIdle()

        assertTrue(flights.flights.isEmpty())
        assertTrue(flights.hidden.isEmpty())
    }

    @Test
    fun `a spell's card waits for the stack to stop showing the spell, then flies from it`() {
        anchors.placeForTest("s1", from())
        anchors.placeForTest(graveyardAnchorId("me"), to())
        val onStack = mutableStateOf(setOf("s1"))
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    flights = rememberZoneFlights(batch = batch.value, anchors = anchors, onStack = onStack.value)
                }
            }
        }

        batch.value =
            ZoneMoveBatch(
                1,
                listOf(
                    ZoneMove("b1", ZoneMoveKind.SpellToGraveyard, bolt("b1"), "s1", listOf(graveyardAnchorId("me")), leavesStack = "s1"),
                ),
            )
        composeTestRule.waitForIdle()
        assertTrue("the stack is still showing it", flights.flights.isEmpty())
        assertEquals("its graveyard keeps the old top card meanwhile", setOf("b1"), flights.hidden)

        onStack.value = emptySet()
        composeTestRule.waitForIdle()

        assertEquals(from(), flights.flights.single().from)
    }

    @Test
    fun `a move still waiting when the next moves arrive is given up on all the same`() {
        // Its wait used to be keyed by its batch, so the next batch cancelled it and the card stayed hidden.
        composeTestRule.mainClock.autoAdvance = false
        anchors.placeForTest(handCardAnchorId("s1"), from())
        anchors.placeForTest(handCardAnchorId("b1"), from())
        anchors.placeForTest(graveyardAnchorId("me"), to())
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    flights = rememberZoneFlights(batch = batch.value, anchors = anchors)
                }
            }
        }
        settle()

        batch.value = ZoneMoveBatch(1, listOf(played("s1")))
        settle()
        batch.value = ZoneMoveBatch(2, listOf(discard("b1")))
        settle()
        assertTrue("its destination was never measured, so it waits", "s1" in flights.hidden)

        composeTestRule.mainClock.advanceTimeBy(3_000)
        settle()

        assertTrue("and is given up on, so its card is shown again", "s1" !in flights.hidden)
    }

    /** Announces what was written and draws a frame — a held clock idles nothing on its own. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun from() = Rect(left = 10f, top = 400f, right = 70f, bottom = 460f)

    private fun to() = Rect(left = 300f, top = 150f, right = 420f, bottom = 270f)

    private fun discard(id: String) = ZoneMove(id, ZoneMoveKind.Discarded, bolt(id), handCardAnchorId(id), listOf(graveyardAnchorId("me")))

    private fun played(id: String) =
        ZoneMove(
            cardId = id,
            kind = ZoneMoveKind.PlayedFromHand,
            card = GameCard(id = id, name = "Swamp", cardTypes = listOf(CardType.Land)),
            from = handCardAnchorId(id),
            to = listOf(id),
            freshDestination = true,
        )

    private fun bolt(id: String) = GameCard(id = id, name = "Lightning Bolt", cardTypes = listOf(CardType.Instant))
}
