package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import magefree.designsystem.component.phase.PhaseBarTurn
import magefree.designsystem.component.phase.PhaseRailTestTags
import magefree.designsystem.component.phase.PhaseStop
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.board.BoardStops
import magefree.feature.game.board.TurnSide
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PhaseStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rail on the board, rather than the rail on its own.
 *
 * What is worth pinning here is what the rail was moved to the left *for*: it has to be pressable per
 * side, it has to sit between the two graveyards, and — the property 0123 promises and the board would
 * quietly break — it must cost the battlefield no width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class PhaseRailBoardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val pressed = mutableListOf<Pair<TurnSide, String>>()
    private val opened = mutableListOf<List<TableZoneKind>>()

    private fun show(
        state: GameState,
        stops: BoardStops = BoardStops.Default,
    ) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        vitals = tableVitals(state),
                        phases = phaseRailState(state, stops = stops),
                        zones = tableZones(state),
                        onToggleStop = { step, side -> pressed += side.asTurnSide() to step.id },
                        onOpenPiles = { piles -> opened += piles.map { it.kind } },
                    )
                }
            }
        }
    }

    @Test
    fun `pressing a step in one column sets that side, and the other column sets the other`() {
        // The whole reason the rail is two columns. Under one row of marks this distinction could not
        // be pressed at all, which is why 0115 merged the sides in the first place.
        show(twoSeats())

        composeTestRule.onNodeWithTag(PhaseRailTestTags.cell(StepIds.UPKEEP, PhaseBarTurn.Yours)).performClick()
        composeTestRule.onNodeWithTag(PhaseRailTestTags.cell(StepIds.DRAW, PhaseBarTurn.Opponents)).performClick()

        assertEquals(
            listOf(TurnSide.Yours to StepIds.UPKEEP, TurnSide.Theirs to StepIds.DRAW),
            pressed,
        )
    }

    @Test
    fun `a step whose stop is a rule refuses the press`() {
        // Declare blockers is `default: return true` in upstream's own `isPhaseStepSet` — the server
        // gives priority there whatever the flags say. A control that appeared to change it would be
        // this board claiming something the server will not honour.
        show(twoSeats())

        composeTestRule
            .onNodeWithTag(PhaseRailTestTags.cell(StepIds.DECLARE_BLOCKERS, PhaseBarTurn.Yours))
            .performClick()

        assertEquals(emptyList<Pair<TurnSide, String>>(), pressed)
    }

    @Test
    fun `the end step is marked in both columns before anybody presses anything`() {
        show(twoSeats())

        composeTestRule
            .onNodeWithTag(PhaseRailTestTags.stop(StepIds.END_TURN, PhaseBarTurn.Yours), useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithTag(PhaseRailTestTags.stop(StepIds.END_TURN, PhaseBarTurn.Opponents), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `a mark set on one side is drawn on that side only`() {
        val stops = BoardStops().withMode(TurnSide.Theirs, StepIds.UPKEEP, PhaseStop.Once)
        show(twoSeats(), stops = stops)

        composeTestRule
            .onNodeWithTag(PhaseRailTestTags.onceStop(StepIds.UPKEEP, PhaseBarTurn.Opponents), useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithTag(PhaseRailTestTags.onceStop(StepIds.UPKEEP, PhaseBarTurn.Yours), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the rail runs between the two graveyards, theirs above and yours below`() {
        // Mirrored exactly as the board is, so which pile belongs to whom is said by where it is.
        show(twoSeats())

        val theirs = composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("them")).fetchSemanticsNode()
        val rail = composeTestRule.onNodeWithTag(PhaseRailTestTags.RAIL).fetchSemanticsNode()
        val mine = composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("me")).fetchSemanticsNode()

        assertTrue("their graveyard should be above the rail", theirs.positionInRoot.y < rail.positionInRoot.y)
        assertTrue("mine should be below it", mine.positionInRoot.y > rail.positionInRoot.y)
    }

    @Test
    fun `pressing a graveyard opens that seat's graveyard`() {
        show(twoSeats())

        composeTestRule.onNodeWithTag(StatusRailTestTags.graveyard("me")).performClick()

        assertEquals(listOf(listOf(TableZoneKind.Graveyard)), opened)
    }

    @Test
    fun `pressing a count opens everything behind the counts at once`() {
        // The counts are one door, not four: *what has this player got that is not on the board* is a
        // single question, and answering it a pile at a time makes the player ask it four times to
        // find out three of the answers were empty.
        //
        // **Not the graveyard and not the hand.** The graveyard has its own card on the rail and its
        // own press; the hand is already drawn along the player's own edge, so a window onto it says
        // nothing. Exile comes even when it is empty — see [pilesBehindTheCounts].
        show(twoSeats(exile = listOf(card("ex-1", "Chandra, Torch of Defiance"))))

        composeTestRule.onNodeWithTag(VitalsTestTags.exile("me"), useUnmergedTree = true).performClick()

        assertEquals(listOf(listOf(TableZoneKind.Exile)), opened)
    }

    @Test
    fun `exile is offered even when there is nothing in it`() {
        // The one pile drawn at zero, and the reason: an empty exile is a thing a player checks *for*
        // — whether the card that vanished is coming back — and inferring "nothing there" from a
        // missing column is the one answer a board should never make somebody guess at.
        show(twoSeats(exile = emptyList()))

        composeTestRule.onNodeWithTag(VitalsTestTags.exile("me"), useUnmergedTree = true).performClick()

        assertEquals(listOf(listOf(TableZoneKind.Exile)), opened)
    }

    @Test
    fun `an empty graveyard still holds its place`() {
        // The rail's one promise is that it stays where it was. A region that appeared the first time
        // a creature died would push everything under it around at exactly the moment a player is
        // trying to read what just happened.
        show(twoSeats(graveyard = emptyList()))

        composeTestRule
            .onNodeWithTag(StatusRailTestTags.emptyGraveyard("me"), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `the turn costs the battlefield no width, and gives it back the height the bar took`() {
        // 0123's own claim, and the one a layout change would break silently: the rail is bounded by
        // the graveyard and the counts, which were already the widest things in this column, so the
        // steps went in *between* them. And the horizontal bar's forty-odd dp of the board's height
        // went back to the battlefield when it left the bottom of the screen.
        show(twoSeats())

        val rail = composeTestRule.onNodeWithTag(StatusRailTestTags.RAIL).fetchSemanticsNode()
        val creatures =
            composeTestRule
                .onNodeWithTag(BattlefieldTestTags.row("me", "front"))
                .fetchSemanticsNode()

        assertTrue(
            "the rail ends at ${rail.positionInRoot.x + rail.size.width}, the creatures start at ${creatures.positionInRoot.x}",
            rail.positionInRoot.x + rail.size.width <= creatures.positionInRoot.x,
        )
        assertTrue("a rail ${rail.size.width}px wide is holding more than a column of numbers", rail.size.width <= MAX_RAIL_PX)
    }

    @Test
    fun `the current step is drawn, and it is the server's own`() {
        show(twoSeats().copy(step = PhaseStep.PostcombatMain))

        composeTestRule.onNodeWithTag(PhaseRailTestTags.step(StepIds.POSTCOMBAT_MAIN)).assertIsDisplayed()
        assertEquals(StepIds.POSTCOMBAT_MAIN, phaseRailState(twoSeats().copy(step = PhaseStep.PostcombatMain)).currentStepId)
    }

    private fun twoSeats(
        graveyard: List<GameCard> = listOf(card("gy-1", "Llanowar Elves")),
        exile: List<GameCard> = emptyList(),
    ) = GameState(
        gameId = "g1",
        viewerPlayerId = "me",
        activePlayerId = "me",
        step = PhaseStep.PrecombatMain,
        hasSnapshot = true,
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    life = 20,
                    libraryCount = 30,
                    graveyardCount = graveyard.size,
                    exileCount = exile.size,
                    exile = exile,
                    graveyard = graveyard,
                    battlefield = listOf(GamePermanent(card = card("bears", "Grizzly Bears"))),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    life = 20,
                    libraryCount = 30,
                    battlefield = listOf(GamePermanent(card = card("wurm", "Craw Wurm"))),
                ),
            ),
    )

    private fun card(
        id: String,
        name: String,
    ) = GameCard(id = id, name = name, cardTypes = listOf(CardType.Creature), isCreature = true)

    private companion object {
        /** A column of numbers, a card and the turn. Past this it is taking width from the board. */
        const val MAX_RAIL_PX = 100
    }
}
