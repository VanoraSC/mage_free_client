package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import magefree.designsystem.theme.MageTheme
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The battlefield arranged.
 *
 * §7.4's rules have layout consequences a still picture would not catch and a flat row passes
 * silently:
 *
 * - **Creatures sit against the centre line**, and the two land corners mirror across it. A layout
 *   that drew both sides in the same order would look plausible and be wrong.
 * - **An empty region holds no height.** Today's board reserves a fixed height for regions whether or
 *   not they contain anything — and an empty-but-present region is invisible in a screenshot of a
 *   full board.
 * - **A card has a size.** A quiet board draws the same card as a comfortable one; only a busy board
 *   shrinks it.
 * - **Lands are bounded.** However many there are, they may not take space from the creatures — which
 *   is the entire reason they have a corner instead of a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BattlefieldLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val inspected = mutableListOf<String>()

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(model = battlefieldModel(state), onInspect = { inspected += it })
                }
            }
        }
    }

    /**
     * Two boards in one composition, so two derived sizes can be compared in a single render.
     *
     * Stacked rather than side by side: splitting the width halves the main area, and a comparison
     * between "quiet" and "comfortable" then has no room to be quiet in. Both boards get the same
     * constraints either way, which is all the comparisons need.
     */
    private fun showPair(
        left: GameState,
        right: GameState,
    ) {
        composeTestRule.setContent {
            MageTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(model = battlefieldModel(left), modifier = Modifier.weight(1f))
                    BattlefieldLayout(model = battlefieldModel(right), modifier = Modifier.weight(1f))
                }
            }
        }
    }

    private fun top(tag: String): Float =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.y

    /** The measured width of the first card in a region — the derived size, as actually drawn. */
    private fun cardWidthIn(tag: String): Int =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .children
            .first()
            .size.width

    private fun lands(playerId: String) = BattlefieldTestTags.row(playerId, BattlefieldTestTags.LAND_ZONE)

    @Test
    fun `the two front rows face each other across the centre line`() {
        show(twoSided())

        assertTrue(
            "the opponent's other permanents should be furthest away",
            top(BattlefieldTestTags.row("them", "back")) < top(BattlefieldTestTags.row("them", "front")),
        )
        assertTrue(
            "the front rows should meet in the middle",
            top(BattlefieldTestTags.row("them", "front")) < top(BattlefieldTestTags.row("me", "front")),
        )
        assertTrue(
            "my other permanents should be nearest me",
            top(BattlefieldTestTags.row("me", "front")) < top(BattlefieldTestTags.row("me", "back")),
        )
    }

    @Test
    fun `the land corners mirror across the centre line`() {
        // The opponent's lands sit above their creatures and mine sit below mine, so the two zones
        // are in opposite corners. Packing both the same way is the plausible wrong answer: it looks
        // fine on one side and puts the opponent's lands in the middle of the board.
        show(twoSided())

        assertTrue(
            "the opponent's lands should be above their creatures",
            top(lands("them")) < top(BattlefieldTestTags.row("them", "front")),
        )
        assertTrue(
            "my lands should be below my creatures",
            top(lands("me")) > top(BattlefieldTestTags.row("me", "front")),
        )
    }

    @Test
    fun `lands live in their own corner, not in a row with anything else`() {
        show(oneSided("me", listOf(forest(), talisman())))

        composeTestRule.onNodeWithTag(lands("me")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "back")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "front")).assertDoesNotExist()
    }

    @Test
    fun `a region with nothing in it is not drawn at all`() {
        show(oneSided("me", listOf(bears())))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "front")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "back")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(lands("me")).assertDoesNotExist()
    }

    @Test
    fun `however many lands there are, they take nothing from the creatures`() {
        // The whole reason lands have a bounded corner rather than a row. A shared row makes a
        // twelve-land board shrink its creatures to fit lands the player barely looks at.
        showPair(
            left = oneSided("few", listOf(bears(), forest("f0"))),
            right = oneSided("many", listOf(bears()) + List(12) { forest("f$it") }),
        )

        assertEquals(
            cardWidthIn(BattlefieldTestTags.row("few", "front")),
            cardWidthIn(BattlefieldTestTags.row("many", "front")),
        )
    }

    @Test
    fun `a quiet board does not draw bigger cards, it draws the same cards`() {
        // The constraint the first cut had backwards: it sized cards to fill whatever space was
        // going, so an opening board of two lands drew two lands the height of the battlefield.
        // Nothing about a game says a Forest matters more when there is only one of it. A card has a
        // size; the board shrinks it when it gets busy and never grows it when it gets quiet.
        showPair(
            left = oneSided("sparse", listOf(creature(0))),
            right = oneSided("some", List(4) { creature(it) }),
        )

        assertEquals(
            cardWidthIn(BattlefieldTestTags.row("sparse", "front")),
            cardWidthIn(BattlefieldTestTags.row("some", "front")),
        )
    }

    @Test
    fun `a busier row draws smaller cards, which is what makes them fit`() {
        // The test a fixed card width fails and everything else here passes: a flat row of twelve at
        // one creature's size runs off the board rather than shrinking to fit it.
        showPair(
            left = oneSided("roomy", listOf(creature(0))),
            right = oneSided("crowded", List(12) { creature(it) }),
        )

        val roomy = cardWidthIn(BattlefieldTestTags.row("roomy", "front"))
        val crowded = cardWidthIn(BattlefieldTestTags.row("crowded", "front"))

        assertTrue("one card measured $roomy, twelve measured $crowded", crowded < roomy)
    }

    @Test
    fun `a card carrying attachments is sized as the assembly it actually is`() {
        // The bug 0100 shipped once: an assembly is taller and wider than its host, because upright
        // attachments stack above it and turned ones reach right. Sizing to the card clips the host's
        // name band — the one thing the stack exists to show — and only the board with an Aura on it
        // is affected, so it survives every other test here.
        showPair(
            left = oneSided("plain", listOf(bears())),
            right = oneSided("laden", listOf(bears().copy(attachments = listOf("aura1", "aura2")), aura("aura1"), aura("aura2"))),
        )

        val plain = cardWidthIn(BattlefieldTestTags.row("plain", "front"))
        val laden = cardWidthIn(BattlefieldTestTags.row("laden", "front"))

        assertTrue("bare measured $plain, enchanted measured $laden", laden < plain)
    }

    @Test
    fun `a stack shows three faces and then starts counting`() {
        // The fan caps so ten Plains cost the width of three, and the count only appears where the
        // picture stops answering the question: one, two and three are visible by looking, and four
        // is the first number a glance cannot give you.
        show(oneSided("me", (1..4).map { plains("p$it") }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack("p1")).assertIsDisplayed()
        composeTestRule.onNodeWithText("×4").assertIsDisplayed()
    }

    @Test
    fun `three of a kind need no count, because three is countable`() {
        show(oneSided("me", (1..3).map { plains("p$it") }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1")).assertDoesNotExist()
    }

    @Test
    fun `tapping one splits the stack and the count goes away`() {
        // Pete's worked example, rendered: four Plains with a count, one tapped, and the untapped
        // stack is back to three faces and no badge — beside a tapped stack of one.
        show(oneSided("me", listOf(plains("p1", tapped = true)) + (2..4).map { plains("p$it") }))

        // Still one stack — tapping moved a copy into its other half rather than splitting it in two.
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack("p2")).assertIsDisplayed()
        composeTestRule.onNodeWithText("×4").assertDoesNotExist()
    }

    @Test
    fun `each half counts only itself`() {
        // Four upright and two turned is not a stack of six with a badge saying so: the two turned
        // ones are right there, visible, and counting them again would be counting cards the player
        // can already see. Only the half that has run out of places to draw says a number.
        show(oneSided("me", (1..4).map { plains("p$it") } + (5..6).map { plains("p$it", tapped = true) }))

        composeTestRule.onNodeWithText("×4").assertIsDisplayed()
        composeTestRule.onNodeWithText("×6").assertDoesNotExist()
        composeTestRule.onNodeWithText("×2").assertDoesNotExist()
    }

    @Test
    fun `a fully tapped stack counts on its turned half`() {
        // The end of the worked example: tap the last of four and there are three turned faces and a
        // count, with nothing upright at all.
        show(oneSided("me", (1..4).map { plains("p$it", tapped = true) }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackTappedCount("p1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1")).assertDoesNotExist()
    }

    @Test
    fun `a stack is acted on through one of its own members`() {
        show(oneSided("me", (1..4).map { plains("p$it") }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack("p1")).performClick()

        assertTrue("tapping the stack reported $inspected", inspected.single().startsWith("p"))
    }

    @Test
    fun `ten of a land cost about what three of it cost`() {
        // The whole point of stacking. Without it the land corner has to shrink its cards to fit ten,
        // and shrinking is exactly what the corner exists to avoid.
        showPair(
            left = oneSided("three", (1..3).map { plains("p$it") }),
            right = oneSided("ten", (1..10).map { plains("p$it") }),
        )

        // Within a pixel: the two boards are stacked to share a render, so their halves differ by the
        // odd row of pixels. What matters is that ten does not cost measurably more than three.
        val three = cardWidthIn(BattlefieldTestTags.row("three", BattlefieldTestTags.LAND_ZONE))
        val ten = cardWidthIn(BattlefieldTestTags.row("ten", BattlefieldTestTags.LAND_ZONE))
        assertTrue("three measured $three, ten measured $ten", kotlin.math.abs(three - ten) <= 2)
    }

    @Test
    fun `nothing is drawn against the edge of the screen`() {
        // A card in the very corner is awkward to touch, and finding that out per device is expensive.
        show(oneSided("me", listOf(forest())))

        val leftEdge =
            composeTestRule
                .onNodeWithTag(lands("me"))
                .fetchSemanticsNode()
                .positionInRoot.x
        assertTrue("the land zone started at $leftEdge", leftEdge > 0f)
    }

    @Test
    fun `tapping a permanent reports the server's own id`() {
        show(oneSided("me", listOf(bears())))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "front")).performClick()

        assertEquals(listOf("bears"), inspected)
    }

    private fun oneSided(
        playerId: String,
        permanents: List<GamePermanent>,
    ) = GameState(
        gameId = "g",
        viewerPlayerId = playerId,
        players = listOf(GamePlayer(playerId = playerId, name = playerId, isViewer = true, battlefield = permanents)),
    )

    private fun twoSided() =
        GameState(
            gameId = "g",
            viewerPlayerId = "me",
            players =
                listOf(
                    GamePlayer(
                        playerId = "me",
                        name = "Me",
                        isViewer = true,
                        battlefield = listOf(bears(), forest(), talisman()),
                    ),
                    GamePlayer(
                        playerId = "them",
                        name = "Them",
                        battlefield = listOf(creature(9), forest("their-forest"), talisman("their-talisman")),
                    ),
                ),
        )
}

private fun permanent(
    id: String,
    name: String,
    types: List<CardType>,
    isCreature: Boolean = false,
) = GamePermanent(card = GameCard(id = id, name = name, cardTypes = types, isCreature = isCreature))

private fun bears() = permanent("bears", "Grizzly Bears", listOf(CardType.Creature), isCreature = true)

private fun creature(index: Int) = permanent("creature-$index", "Saproling", listOf(CardType.Creature), isCreature = true)

private fun forest(id: String = "forest") = permanent(id, "Forest", listOf(CardType.Land))

private fun talisman(id: String = "talisman") = permanent(id, "Talisman of Unity", listOf(CardType.Artifact))

/** An Aura attached to a host, which leaves the buckets and renders on the host instead. */
private fun aura(id: String) =
    GamePermanent(
        card = GameCard(id = id, name = "Pacifism", cardTypes = listOf(CardType.Enchantment)),
        attachedTo = "bears",
        isAttachedToPermanent = true,
    )

/** A basic land with a real printing, so two of them are identical in every respect that matters. */
private fun plains(
    id: String,
    tapped: Boolean = false,
) = GamePermanent(
    card = GameCard(id = id, name = "Plains", setCode = "10E", collectorNumber = "364", cardTypes = listOf(CardType.Land)),
    isTapped = tapped,
)
