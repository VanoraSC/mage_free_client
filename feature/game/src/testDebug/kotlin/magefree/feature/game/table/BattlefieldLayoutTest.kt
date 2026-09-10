package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCardTestTags
import magefree.designsystem.card.CardDisplay
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
    private val landPresses = mutableListOf<LandStackHalf>()

    private fun show(state: GameState) {
        composeTestRule.setContent {
            MageTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BattlefieldLayout(
                        model = battlefieldModel(state),
                        onInspect = { inspected += it },
                        onLandPress = { _, half -> landPresses += half },
                    )
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

    private fun left(tag: String): Float =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .positionInRoot.x

    private fun right(tag: String): Float =
        composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().let { node ->
            node.positionInRoot.x + node.size.width
        }

    /** The measured width of the first card in a region — the derived size, as actually drawn. */
    private fun cardWidthIn(tag: String): Int =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .children
            .first()
            .size.width

    private fun bounds(tag: String): Rect = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /**
     * Every card face drawn inside one region, as it actually lands on the screen.
     *
     * `boundsInRoot` puts the node's box through the transforms above it *and* through any clip
     * between it and the root — so a card that has been leaned over reports the wider box a leaning
     * card occupies, unless something is cutting it off, in which case it reports the cut. Both
     * halves of that matter, and each was a defect: a land stack that reserved a leaning copy the
     * room an upright one takes, and a creature row that clipped one to it.
     *
     * The region is any tagged ancestor, so the same helper answers for a stack and for a row.
     */
    private fun cardsIn(regionTag: String): List<Rect> =
        composeTestRule
            .onAllNodes(
                hasTestTag(BoardCardTestTags.CARD) and hasAnyAncestor(hasTestTag(regionTag)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .map { it.boundsInRoot }

    private fun cardsInStack(stackId: String): List<Rect> = cardsIn(BattlefieldTestTags.stack(stackId))

    private fun cardCentre(stackId: String): Offset = cardsInStack(stackId).first().center

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
    fun `the land column mirrors across the centre line, and stays left of the battlefield`() {
        // The opponent's lands pack up into the top of the column and mine pack down into the bottom,
        // so the two halves meet in the middle exactly as the battlefields do. Packing both the same
        // way is the plausible wrong answer: it looks fine on one side and puts the opponent's lands
        // in the middle of the board.
        show(twoSided())

        assertTrue(
            "the opponent's lands should be above mine",
            top(lands("them")) < top(lands("me")),
        )
        // And the column is a column: lands never share a horizontal with the creatures, which is what
        // stops a fourth kind of land from pushing the creatures around.
        assertTrue(
            "the lands should be left of the creatures",
            right(lands("me")) <= left(BattlefieldTestTags.row("me", "front")),
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
    fun `however many lands there are, they take all but nothing from the creatures`() {
        // The whole reason lands have a bounded column rather than a row. A shared row makes a
        // twelve-land board shrink its creatures to fit lands the player barely looks at.
        //
        // **Within a pixel, not to the pixel.** This was exact while every board clamped its cards at
        // a ceiling neither could reach; now that the preferred size is larger than the board can give,
        // a creature is sized by the width actually left over, and the land column is part of what is
        // taken out of it first. What the bounded column buys is that the difference between one land
        // and twelve is a rounding error rather than a third of a card.
        showPair(
            left = oneSided("few", listOf(bears(), forest("f0"))),
            right = oneSided("many", listOf(bears()) + List(12) { forest("f$it") }),
        )

        val few = cardWidthIn(BattlefieldTestTags.row("few", "front"))
        val many = cardWidthIn(BattlefieldTestTags.row("many", "front"))
        assertTrue("one land drew $few and twelve drew $many", kotlin.math.abs(few - many) <= ROUNDING_SLACK_PX)
    }

    @Test
    fun `a quiet board does not draw bigger cards, it draws the same cards`() {
        // The constraint the first cut had backwards: it sized cards to fill whatever space was
        // going, so an opening board of two lands drew two lands the height of the battlefield.
        // Nothing about a game says a Forest matters more when there is only one of it. A card has a
        // size; the board shrinks it when it gets busy and never grows it when it gets quiet.
        //
        // **Never past the preferred size**, and no bigger than a board with four creatures on it.
        // The equality this used to assert held only while both boards clamped at a ceiling; the
        // preferred size is now larger than an 891x411 board can give a row, so both are sized by the
        // space instead and land within a pixel of each other by different arithmetic. The half of
        // the rule about a *busy* board shrinking is asserted by the test below this one.
        showPair(
            left = oneSided("sparse", listOf(creature(0))),
            right = oneSided("some", List(4) { creature(it) }),
        )

        val sparse = cardWidthIn(BattlefieldTestTags.row("sparse", "front"))
        val some = cardWidthIn(BattlefieldTestTags.row("some", "front"))
        assertTrue("a sparse board drew $sparse, past the preferred $PreferredCreatureWidth", sparse <= PreferredCreatureWidth.value)
        assertTrue("a sparse board drew $sparse against a busier board's $some", sparse - some <= ROUNDING_SLACK_PX)
    }

    @Test
    fun `a creature is drawn a quarter larger, and stays that way once the side has to scale`() {
        // 0121's whole point. A creature is the permanent a player is asked about most — what is
        // attacking, what can block, what its power has become — and it carries the counters and
        // badges that say so on top of the picture. One shared size spent the same room on an
        // enchantment that is read once and remembered.
        //
        // **This board is already the scaled case, which is why the ratio is worth asserting here.**
        // Neither row is crowded, so both start at their preferred width — and the two preferred
        // widths together are taller than a phone in landscape can give one side, so both are scaled
        // down to fit. The ratio surviving that is the proof they scaled by the *same* factor, which
        // is the one thing the shared height budget could get wrong.
        show(oneSided("me", listOf(bears(), talisman())))

        val creature = cardWidthIn(BattlefieldTestTags.row("me", "front"))
        val other = cardWidthIn(BattlefieldTestTags.row("me", "back"))

        assertTrue("the creature measured $creature against a non-creature's $other", creature > other)
        assertTrue(
            "the ratio came out ${creature.toFloat() / other} rather than $CREATURE_RATIO",
            kotlin.math.abs(creature.toFloat() / other.toFloat() - CREATURE_RATIO) <= RATIO_SLACK,
        )
    }

    @Test
    fun `the opponent's hand costs the board height, and the board sizes itself for it`() {
        // **The hand at the top was added after this arithmetic was written and never entered it.**
        // It is a whole card tall — unlike the viewer's, none of it hangs off the edge — so every card
        // on the board was sized against height the board did not have, and the viewer's back row ran
        // off the bottom and over their own hand.
        //
        // Measured as the thing that has to be true rather than as a number: every row the board draws
        // must end above the hand it is drawn over.
        // **Measured as the sizing seeing it**, rather than as the rows staying inside the board.
        // Below `MinCardWidth` the board has admitted it is out of room and a card overflows whatever
        // the arithmetic says, so "nothing overflows" is not a property that holds at the floor — but
        // "a hand at the top costs the cards height" holds everywhere, and it is the actual fault.
        composeTestRule.setContent {
            MageTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    listOf("empty" to KnownHand(), "held" to KnownHand(hidden = 7)).forEach { (id, theirHand) ->
                        val board = oneSided(id, listOf(bears(), talisman()))
                        BattlefieldLayout(
                            model = battlefieldModel(board),
                            hand = handCards(board.copy(hand = listOf(forestCard()))),
                            opponentHand = theirHand,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val withNoHand = cardWidthIn(BattlefieldTestTags.row("empty", "front"))
        val withAHand = cardWidthIn(BattlefieldTestTags.row("held", "front"))

        assertTrue(
            "a creature measured $withNoHand against an empty hand and $withAHand against seven cards",
            withAHand < withNoHand,
        )
    }

    @Test
    fun `a crowded creature row caps the permanents behind it rather than being dwarfed by them`() {
        // **Found on a real board, and it read as the sizing being backwards.** A row's width problem
        // is its own, so a crowded creature row does not shrink the back row *to pay for it* — but
        // leaving the back row entirely alone drew six enchantments half again the size of the twelve
        // creatures in front of them. The cards a player is asked about most, drawn smallest, on
        // exactly the board where it matters.
        //
        // So the back row is capped at the creature width: never larger, and no smaller than it has
        // to be. Equal rather than the ratio applied downward, because shrinking a card that has the
        // room buys nothing.
        show(oneSided("me", List(12) { creature(it) } + listOf(talisman())))

        val creature = cardWidthIn(BattlefieldTestTags.row("me", "front"))
        val other = cardWidthIn(BattlefieldTestTags.row("me", "back"))

        assertTrue(
            "twelve creatures measured $creature and the artifact behind them $other",
            other <= creature + ROUNDING_SLACK_PX,
        )
        assertTrue(
            "the artifact was shrunk past the creatures it sits behind: $other against $creature",
            other >= creature - ROUNDING_SLACK_PX,
        )
    }

    @Test
    fun `a crowded back row does not shrink the creatures in front of it`() {
        // The surviving half of the rule, and the direction that still holds outright: the creature
        // row's width is decided by the creature row. Twelve artifacts say nothing about how big a
        // Bear should be, and the ordering the two sizes exist to state is unaffected.
        show(oneSided("me", listOf(bears()) + List(12) { talisman("t$it") }))

        val creature = cardWidthIn(BattlefieldTestTags.row("me", "front"))
        val other = cardWidthIn(BattlefieldTestTags.row("me", "back"))

        assertTrue("one creature measured $creature behind twelve artifacts at $other", creature > other)
    }

    @Test
    fun `a crowded creature row is capped on its own, and the ratio gives way to it`() {
        // The ratio is a *preference*, not an invariant — it is what the two roles start from, and a
        // cap that binds on one of them is the board doing its job. Three creatures across a board
        // this size cannot each have their preferred width, so the creature row is capped below it
        // while the lone artifact behind is not capped at all, and the pair ends up closer together
        // than a quarter apart.
        //
        // Worth pinning because the obvious wrong fix is to hold the ratio by shrinking the
        // *uncapped* role to match — which would take room from a card that has it, to preserve a
        // proportion nobody asked to be preserved at the cost of card size.
        show(oneSided("me", List(3) { creature(it) } + listOf(talisman())))

        val creature = cardWidthIn(BattlefieldTestTags.row("me", "front"))
        val other = cardWidthIn(BattlefieldTestTags.row("me", "back"))

        assertTrue(
            "a crowded creature row measured $creature against an uncrowded $other",
            creature.toFloat() / other.toFloat() < CREATURE_RATIO,
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
    fun `a stack of four draws one card and says four`() {
        // **The picture answers *which land* and the tally answers *how many*.** The fan drew up to
        // three faces per half and only counted past that, which spent a card and a half of width on
        // a number — and the number was the part a glance could not give you anyway.
        show(oneSided("me", (1..4).map { plains("p$it") }))

        assertEquals("one card, however many copies", 1, cardsInStack("p1").size)
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1"), useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("4").assertIsDisplayed()
    }

    @Test
    fun `one of a land still says one, because nothing else does`() {
        // The fan's badges appeared at four, when the picture stopped being countable. Nothing is
        // countable now — one Plains and four Plains draw the same face — so the number is always
        // there, and a stack that hid it would be a stack that answers *how many* with silence.
        show(oneSided("me", listOf(plains("p1"))))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a mixed stack is one turned card over one upright card`() {
        // Pete's own wording. Two faces and no more, whatever the counts are: what a player needs from
        // the picture is *is there anything left to tap*, and one of each state answers it.
        show(oneSided("me", (1..4).map { plains("p$it") } + (5..6).map { plains("p$it", tapped = true) }))

        assertEquals("one upright and one turned", 2, cardsInStack("p1").size)
    }

    @Test
    fun `each number counts its own half`() {
        // Four standing and two turned is two numbers, not a total: *how many can I still tap* is the
        // question the bar exists to answer, and a six would answer a different one.
        show(oneSided("me", (1..4).map { plains("p$it") } + (5..6).map { plains("p$it", tapped = true) }))

        composeTestRule.onNodeWithText("4").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText("6").assertDoesNotExist()
    }

    @Test
    fun `a half with nothing in it writes nothing`() {
        // A grey zero beside an untouched stack is noise, and the absence says the same thing. Both
        // ends of the rule: nothing standing writes no standing number, and nothing turned writes no
        // turned one.
        show(oneSided("me", (1..4).map { plains("p$it", tapped = true) }))
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackTappedCount("p1"), useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an untouched stack writes no turned number`() {
        show(oneSided("me", (1..4).map { plains("p$it") }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stackCount("p1"), useUnmergedTree = true).assertExists()
        composeTestRule
            .onNodeWithTag(BattlefieldTestTags.stackTappedCount("p1"), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `pressing an upright copy is a different action from pressing a turned one`() {
        // The two halves are two affordances. Hit testing has to separate them without any coordinate
        // arithmetic in the caller: the turned cards are drawn over the upright ones, so the strip that
        // shows past them reaches a turned card and the top half reaches an upright one.
        show(oneSided("me", listOf(plains("p1", tapped = true)) + (2..4).map { plains("p$it") }))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack("p2")).performTouchInput {
            click(Offset(center.x, height * 0.2f))
        }
        // Low enough to be past the bottom of every upright copy, high enough to still be inside the
        // leaning one. A leaning card is a diamond, so the very bottom of the stack's box is the one
        // place in that strip it does *not* reach — its bottom corner is the only thing down there.
        composeTestRule.onNodeWithTag(BattlefieldTestTags.stack("p2")).performTouchInput {
            click(Offset(center.x, height * 0.85f))
        }

        assertEquals(listOf(LandStackHalf.Upright, LandStackHalf.Turned), landPresses)
    }

    @Test
    fun `a tapped creature is not cut off by the row it is in`() {
        // Found by playing a game: a lone tapped token had both its corners sliced flat. The row wraps
        // its cards in a horizontal scroll, and a scroll container clips to its bounds — while a card
        // leaning forty-five degrees reaches a further √2⁄2 of a card past the box it was laid out in.
        // The card tier's claim that a square "leans inside its own footprint" is true of the space it
        // reserves and not of the pixels it draws.
        //
        // Measured against the upright card beside it: a leaning card must report a *wider* box than an
        // upright one of the same size. Clipped, the two measure the same, which is the bug.
        show(oneSided("me", listOf(bears("upright"), bears("leaning", tapped = true))))

        val cards = cardsIn(BattlefieldTestTags.row("me", "front"))
        val widest = cards.maxOf { it.width }
        val narrowest = cards.minOf { it.width }

        assertTrue(
            "the leaning card measured $widest beside an upright $narrowest — it is being clipped to the upright footprint",
            widest > narrowest * LEAN_MARGIN,
        )
    }

    @Test
    fun `a lone land leans where it stood rather than sliding down the diagonal`() {
        // Shipped wrong: the turned half was anchored to the *front* of the diagonal whatever was in
        // the upright half, so a player's single tapped land jumped to slot two of an otherwise empty
        // stack — it read as the card having come loose and drifted away from its place. A tap turns a
        // card over where it is standing, so a stack of one draws in the same place either way.
        show(oneSided("me", listOf(plains("up"), island("down", tapped = true))))

        val standing = cardCentre("up").x - left(BattlefieldTestTags.stack("up"))
        val leaning = cardCentre("down").x - left(BattlefieldTestTags.stack("down"))

        assertTrue(
            "the upright copy sat $standing into its stack and the leaning one $leaning",
            kotlin.math.abs(standing - leaning) <= ROUNDING_SLACK_PX,
        )
    }

    @Test
    fun `a leaning card stays inside the room its stack asked for`() {
        // The other half of the same shipped bug, and the one that made a pile of tapped lands read as
        // a smear. The footprint modelled a tap as a quarter turn — width and height swapping — which
        // is a no-op on a square card, so a stack reserved a leaning copy exactly the room an upright
        // one takes. A square on its corner is √2 across: the overflow drew over the stack beside it.
        show(oneSided("me", (1..4).map { plains("p$it", tapped = true) }))

        val stack = bounds(BattlefieldTestTags.stack("p1"))

        cardsInStack("p1").forEach { card ->
            assertTrue(
                "a leaning card drew $card inside a stack of $stack",
                card.left >= stack.left - ROUNDING_SLACK_PX &&
                    card.right <= stack.right + ROUNDING_SLACK_PX &&
                    card.top >= stack.top - ROUNDING_SLACK_PX &&
                    card.bottom <= stack.bottom + ROUNDING_SLACK_PX,
            )
        }
    }

    @Test
    fun `a spell on the stack does not swallow the presses meant for the board`() {
        // Found in a game: with Thoughtseize on the stack asking for a player, neither life total
        // answered a tap. The stack had just become a floating layer, and it kept `fillMaxWidth` and a
        // `horizontalScroll` from the days when it had a band of the board to itself — with a
        // `fillMaxHeight` inside a wrap-content row, which takes the height constraint of the *board*.
        // The result was one scrollable strip the size of the whole table, drawn last, over everything.
        //
        // A player targeted by their own life total is the strictest case: it is the furthest thing
        // from the stack that a press has to reach, and the game cannot go on without it.
        val picked = mutableListOf<String>()
        composeTestRule.setContent {
            MageTheme {
                BattlefieldLayout(
                    model = battlefieldModel(oneSided("me", listOf(bears()))),
                    lifeTotals =
                        LifeTotals(
                            opponents = emptyList(),
                            viewer = LifeTotalState(playerId = "me", life = 20, isPickable = true),
                        ),
                    onPickPlayer = { picked += it },
                    stack = listOf(thoughtseize()),
                )
            }
        }

        composeTestRule.onNodeWithTag(LifeTotalTestTags.total("me")).performClick()

        assertEquals("the stack layer is over the board and taking its presses", listOf("me"), picked)
    }

    @Test
    fun `show battlefield takes the stack with it`() {
        // The stack is the one layer left over the board once the panel is gone, and what it covers is
        // exactly what the control was pressed to reach: a permanent the question is about. One
        // control, two states — the battlefield, or the question.
        val visible = mutableStateOf(true)
        composeTestRule.setContent {
            MageTheme {
                BattlefieldLayout(
                    model = battlefieldModel(oneSided("me", listOf(bears()))),
                    stack = listOf(thoughtseize()),
                    stackVisible = visible.value,
                )
            }
        }

        composeTestRule.onNodeWithTag(StackTestTags.REGION).assertExists()

        visible.value = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(StackTestTags.REGION).assertDoesNotExist()
    }

    @Test
    fun `a pile of tapped tokens fills its own box rather than hanging below it`() {
        // Found on a real board: two tapped Zombies drew a title bar lower than everything beside
        // them, while a *single* tapped one looked right — because a single one is drawn as a card and
        // never reserves a half. A pile reserved an upright half it can never have (a token that taps
        // leaves for a pile of its own) and then dropped its cards onto the card that would have been
        // there. Measured as the empty band left at the top of the pile's own box.
        show(oneSided("me", listOf(bears("standing")) + (1..2).map { zombie("z$it", tapped = true) }))

        val box = bounds(BattlefieldTestTags.stack("z1"))
        val highest = cardsInStack("z1").minOf { it.top }

        assertTrue(
            "the pile's cards started ${highest - box.top}px below the top of a box that is theirs alone",
            highest - box.top <= ROUNDING_SLACK_PX,
        )
    }

    @Test
    fun `a pile of upright tokens keeps no room for a lean it can never have`() {
        // The same fault in the other direction, and the expensive one: every upright pile claimed the
        // leaning half's overhang and the drop below it, so a board that made tokens shrank every card
        // on it — both sides, every row — to fit room nothing would ever occupy.
        //
        // Measured from the end of the tally rather than from the box's edge: the bar is on the left
        // of every stack now and the card starts after it. What must not be there is the *overhang*,
        // which is a further fifth of a card and belongs to a lean this pile can never have.
        show(oneSided("me", listOf(bears("standing")) + (1..2).map { zombie("z$it") }))

        val tally = bounds(BattlefieldTestTags.stackTally("z1"))
        val nearest = cardsInStack("z1").minOf { it.left }

        assertTrue(
            "the pile's card started ${nearest - tally.right}px right of the tally beside it",
            nearest - tally.right <= ROUNDING_SLACK_PX,
        )
    }

    @Test
    fun `a land stack still reserves both halves, because a land taps without leaving it`() {
        // The reason the reservation is the caller's answer rather than a reading of the contents. A
        // land moves between the halves of the stack it is already in, so a footprint that fitted only
        // what is in it right now would resize the land corner — and the card size with it — the first
        // time a player tapped a land.
        showPair(
            left = oneSided("dry", (1..4).map { plains("dry$it") }),
            right = oneSided("wet", (1..3).map { plains("wet$it") } + plains("wet4", tapped = true)),
        )

        val untouched = bounds(BattlefieldTestTags.stack("dry1"))
        val tapped = bounds(BattlefieldTestTags.stack("wet1"))

        assertTrue(
            "the stack measured ${untouched.height} untapped and ${tapped.height} with one land turned",
            kotlin.math.abs(untouched.height - tapped.height) <= ROUNDING_SLACK_PX,
        )
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
    tapped: Boolean = false,
) = GamePermanent(card = GameCard(id = id, name = name, cardTypes = types, isCreature = isCreature), isTapped = tapped)

private fun bears(
    id: String = "bears",
    tapped: Boolean = false,
) = permanent(id, "Grizzly Bears", listOf(CardType.Creature), isCreature = true, tapped = tapped)

private fun forestCard() = GameCard(id = "h-1", name = "Forest", cardTypes = listOf(CardType.Land))

private fun creature(index: Int) = permanent("creature-$index", "Saproling", listOf(CardType.Creature), isCreature = true)

/** One object on the stack, with text beside it — the shape that fills the region's width. */
private fun thoughtseize() =
    TableStackObject(
        id = "thoughtseize",
        state = BoardCardState(card = CardDisplay(name = "Thoughtseize")),
        rules = listOf("Target player reveals their hand. You choose a nonland card from it."),
    )

/**
 * A token, which is the only thing that piles — and identical to every other one made here, which is
 * what makes two of them one pile. Upstream says so itself with `isToken`; nothing is inferred.
 */
private fun zombie(
    id: String,
    tapped: Boolean = false,
) = GamePermanent(
    card =
        GameCard(
            id = id,
            name = "Zombie",
            setCode = "TDDL",
            cardTypes = listOf(CardType.Creature),
            isCreature = true,
            isToken = true,
        ),
    isTapped = tapped,
)

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

/** A second basic, so a board can hold two land stacks and compare one against the other. */
private fun island(
    id: String,
    tapped: Boolean = false,
) = GamePermanent(
    card = GameCard(id = id, name = "Island", setCode = "10E", collectorNumber = "361", cardTypes = listOf(CardType.Land)),
    isTapped = tapped,
)

/**
 * How far two card widths may differ and still count as the same size.
 *
 * A card's width is a division of the space left over, rounded to a pixel, so two boards that differ
 * only in something the layout bounds — how many lands they hold — land within one of each other. The
 * bug this slack must never hide is a *shrink*, which is a third of a card and not a pixel.
 */
private const val ROUNDING_SLACK_PX = 2

/**
 * How much wider a leaning card must measure than an upright one before the lean counts as visible.
 *
 * A square turned forty-five degrees is √2 across — about 1.41 — so this is a floor well under the
 * real figure rather than a restatement of it. What it has to rule out is the clipped case, where the
 * two measure exactly the same.
 */
private const val LEAN_MARGIN = 1.35f

/** How much larger a creature is drawn than a permanent that is not one. 0121's own figure. */
private const val CREATURE_RATIO = 1.25f

/**
 * How far the ratio may drift and still count as held.
 *
 * Both widths are divisions rounded to a whole pixel, so at board sizes the quotient of two of them
 * lands a percent or so either side. What this must not admit is the two roles being scaled by
 * different factors, which moves the ratio by tens of percent rather than by one.
 */
private const val RATIO_SLACK = 0.04f
