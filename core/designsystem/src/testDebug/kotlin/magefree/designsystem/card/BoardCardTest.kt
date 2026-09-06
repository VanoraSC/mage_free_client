package magefree.designsystem.card

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Board tier rendered.
 *
 * The assertions worth having are about **footprint**, because tap state and attachments are the two
 * pieces of card state with layout consequences. A component that rotated only its pixels, or drew
 * attachments without claiming the space they occupy, would leave the board overlapping its own cards
 * while every other test stayed green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w891dp-h411dp")
class BoardCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(state: BoardCardState) {
        composeTestRule.setContent {
            MageTheme {
                Box { BoardCard(state = state, width = CARD_WIDTH, modifier = Modifier.testTag(FOOTPRINT)) }
            }
        }
    }

    @Test
    fun `the card face carries the name, so the tier overlays none of its own`() {
        show(BoardCardState(card = BEARS))

        composeTestRule.onNodeWithTag(BoardCardTestTags.CARD).assertIsDisplayed()
        // A real card already prints its name where a player looks for it, and an overlay covers the
        // art it is printed on. Power and toughness are the exception, because those go stale.
        composeTestRule.onNodeWithText("Grizzly Bears").assertDoesNotExist()
    }

    @Test
    fun `an untapped card's footprint is the width it was given`() {
        show(BoardCardState(card = BEARS))

        composeTestRule.onNodeWithTag(FOOTPRINT).assertWidthIsEqualTo(CARD_WIDTH)
        composeTestRule.onNodeWithTag(FOOTPRINT).assertHeightIsEqualTo(cardHeight())
    }

    @Test
    fun `a tapped card's footprint turns with it, not just its picture`() {
        show(BoardCardState(card = BEARS, tapped = true))

        composeTestRule.onNodeWithTag(FOOTPRINT).assertWidthIsEqualTo(cardHeight())
        composeTestRule.onNodeWithTag(FOOTPRINT).assertHeightIsEqualTo(CARD_WIDTH)
    }

    @Test
    fun `a tapped card keeps its card proportions rather than being squashed to fit`() {
        // The bug this exists for: the rotated card sits in a landscape box shorter than the card, and
        // a plain size modifier is clamped by the parent's constraints — so the card was measured as a
        // square and its art cropped, before the rotation ever turned it. Rotation must move the card,
        // not resize it, and only the card's own measured size can show that.
        //
        // **The shape it must keep is the Board tier's, not a Magic card's.** A card cut below its
        // art box is wider than it is tall, so "keeps its proportions" can no longer be spelled
        // `height > width`: it has to be the tier's own ratio, which is what the untapped card below
        // measures too. The card being the same shape either way is the whole claim — rotation moves
        // it, the footprint around it turns, and the card itself is untouched.
        show(BoardCardState(card = BEARS, tapped = true))

        val face = composeTestRule.onNodeWithTag(BoardCardTestTags.CARD).fetchSemanticsNode().size
        val ratio = face.width.toFloat() / face.height.toFloat()
        assertTrue(
            "a tapped card measured ${face.width}x${face.height}, a ratio of $ratio",
            abs(ratio - BOARD_CARD_ASPECT_RATIO) < 0.05f,
        )
    }

    @Test
    fun `an untapped card is the Board tier's own shape, a card cut below its art`() {
        show(BoardCardState(card = BEARS))

        val face = composeTestRule.onNodeWithTag(BoardCardTestTags.CARD).fetchSemanticsNode().size
        val ratio = face.width.toFloat() / face.height.toFloat()
        assertTrue(
            "a card measured ${face.width}x${face.height}, a ratio of $ratio",
            abs(ratio - BOARD_CARD_ASPECT_RATIO) < 0.05f,
        )
    }

    @Test
    fun `the card image is laid out whole, and the face clips the bottom off it`() {
        // **The crop, asserted at the one place it can go wrong.** The image is measured at a whole
        // card's height inside a face that is only [BOARD_CARD_CROP] of it, so the card keeps its own
        // proportions and loses its text box. Give the image the face's height instead — which is what
        // a plain `Modifier.height` does, because it is clamped by the parent — and the renderer's
        // centre-crop takes the top and the bottom in equal measure: a card with no title bar, no
        // name, and no type line, which is a card nobody can read on a board.
        show(BoardCardState(card = BEARS))

        val face = composeTestRule.onNodeWithTag(BoardCardTestTags.CARD).fetchSemanticsNode().size
        val art =
            composeTestRule
                .onNodeWithTag(BoardCardTestTags.ART, useUnmergedTree = true)
                .fetchSemanticsNode()
                .size

        assertEquals("the image should be the card's full width", face.width, art.width)
        val expected = (CARD_WIDTH.value / CARD_ASPECT_RATIO).roundToInt()
        assertTrue(
            "the image measured ${art.width}x${art.height}, and a whole card is ${CARD_WIDTH.value.roundToInt()}x$expected",
            abs(art.height - expected) <= 1,
        )
        assertTrue(
            "the face must be shorter than the image it clips: ${face.height} against ${art.height}",
            face.height < art.height,
        )
    }

    @Test
    fun `a turned attachment keeps its card proportions too`() {
        show(BoardCardState(card = BEARS, attachments = listOf(EQUIPPED_TAPPED)))

        val face = composeTestRule.onNodeWithTag(BoardCardTestTags.ATTACHMENT).fetchSemanticsNode().size
        val ratio = face.width.toFloat() / face.height.toFloat()
        assertTrue(
            "a turned attachment measured ${face.width}x${face.height}, a ratio of $ratio",
            abs(ratio - BOARD_CARD_ASPECT_RATIO) < 0.05f,
        )
    }

    @Test
    fun `counters render on the face carrying their count`() {
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("+1/+1", 7))))

        composeTestRule.onNodeWithTag(BoardCardTestTags.COUNTERS).assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        // The kind is a symbol and a colour, never its written name: `+1/+1` spelled out does not fit
        // at board size and would crowd out the number, which is the part that changes.
        composeTestRule.onNodeWithText("+1/+1").assertDoesNotExist()
    }

    @Test
    fun `a counter kind this build has never heard of still gets a chip`() {
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("wibble", 3))))

        composeTestRule.onNodeWithTag(BoardCardTestTags.COUNTERS).assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `several counter kinds each get their own chip`() {
        show(
            BoardCardState(
                card = BEARS,
                counters = listOf(BoardCounter("+1/+1", 1), BoardCounter("poison", 2), BoardCounter("energy", 3)),
            ),
        )

        listOf("1", "2", "3").forEach { count ->
            composeTestRule.onNodeWithText(count).assertIsDisplayed()
        }
    }

    @Test
    fun `badges render along the bottom edge, and still say which keyword they are`() {
        show(BoardCardState(card = BEARS, badges = listOf(BoardBadge.Flying, BoardBadge.Trample)))

        composeTestRule.onNodeWithTag(BoardCardTestTags.BADGES).assertIsDisplayed()
        // A badge is a picture now, so the keyword lives in the description rather than in visible
        // text — and it is the full word, not the `FLY` the placeholder used to show. Losing this is
        // the one way the change could take information away instead of adding it.
        composeTestRule.onNodeWithContentDescription(BoardBadge.Flying.label).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(BoardBadge.Trample.label).assertIsDisplayed()
    }

    @Test
    fun `a badge kind this build does not recognise still shows something`() {
        show(BoardCardState(card = BEARS, badges = listOf(BoardBadge.Unknown)))

        composeTestRule.onNodeWithTag(BoardCardTestTags.BADGES).assertIsDisplayed()
        // No font has a picture of "the server named something we do not know", so this one keeps its
        // short form. A badge that drew nothing at all would hide the fact that something is there.
        composeTestRule.onNodeWithText(BoardBadge.Unknown.shortLabel).assertIsDisplayed()
    }

    @Test
    fun `a counter says its kind as well as its count`() {
        // The colour alone said "different from that one"; the symbol says which. The count has to
        // survive it — that is the number the player is actually reading.
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("+1/+1", 3))))

        composeTestRule.onNodeWithTag(BoardCardTestTags.COUNTERS).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("+1/+1").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `a counter kind the font has never heard of still shows its count`() {
        show(BoardCardState(card = BEARS, counters = listOf(BoardCounter("moonsilver", 2))))

        composeTestRule.onNodeWithTag(BoardCardTestTags.COUNTERS).assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `an attachment shows its own name and cost, which is what the stack is for`() {
        show(BoardCardState(card = BEARS, attachments = listOf(PACIFISM)))

        composeTestRule.onNodeWithTag(BoardCardTestTags.ATTACHMENT).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pacifism").assertIsDisplayed()
        composeTestRule.onNodeWithText("1W").assertIsDisplayed()
    }

    @Test
    fun `each attachment claims the space it occupies`() {
        // The cost of the design, asserted rather than discovered during board layout: an enchanted
        // creature is taller than a bare one, by one readable band per attachment.
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(BARE),
                    )
                    BoardCard(
                        state = BoardCardState(card = BEARS, attachments = listOf(PACIFISM)),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(ENCHANTED),
                    )
                }
            }
        }

        val bare =
            composeTestRule
                .onNodeWithTag(BARE)
                .fetchSemanticsNode()
                .size.height
        val enchanted =
            composeTestRule
                .onNodeWithTag(ENCHANTED)
                .fetchSemanticsNode()
                .size.height

        assertTrue(
            "an attachment added no height, so the board would draw it over a neighbour",
            enchanted > bare,
        )
    }

    @Test
    fun `a tapped attachment is turned, so it reaches out past the host`() {
        // An Equipment tapped for improvise is still equipping. Turned a quarter turn it can only
        // show its right edge, so it steps sideways and has to sit far enough out for that edge to
        // clear the host. It is also a card's *width* tall, which is more than a card cut below its
        // art box, so it hangs below the host as well. The assembly has to measure itself around
        // both, or the board draws a neighbour over the name the stack exists to expose.
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(BARE),
                    )
                    BoardCard(
                        state = BoardCardState(card = BEARS, attachments = listOf(EQUIPPED_TAPPED)),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(ENCHANTED),
                    )
                }
            }
        }

        val bare = composeTestRule.onNodeWithTag(BARE).fetchSemanticsNode().size
        val withTapped = composeTestRule.onNodeWithTag(ENCHANTED).fetchSemanticsNode().size

        assertTrue(
            "a turned attachment must widen the assembly — it sticks out past the host",
            withTapped.width > bare.width,
        )
        assertEquals(
            "the assembly must claim the height a turned card hangs below the host, which is a card's width",
            CARD_WIDTH.value.roundToInt(),
            withTapped.height,
        )
    }

    @Test
    fun `an upright attachment claims height and a turned one claims width`() {
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS, attachments = listOf(PACIFISM)),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(BARE),
                    )
                    BoardCard(
                        state = BoardCardState(card = BEARS, attachments = listOf(EQUIPPED_TAPPED)),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(ENCHANTED),
                    )
                }
            }
        }

        val upright = composeTestRule.onNodeWithTag(BARE).fetchSemanticsNode().size
        val turned = composeTestRule.onNodeWithTag(ENCHANTED).fetchSemanticsNode().size

        assertTrue("the upright stack is the taller one", upright.height > turned.height)
        assertTrue("the turned stack is the wider one", turned.width > upright.width)
    }

    @Test
    fun `turned attachments step sideways, so no card covers another's name`() {
        // A stack steps perpendicular to the band it has to expose. A turned card's name and cost run
        // down its right edge, so a second one stepping downward would cut the first name in half;
        // stepping sideways leaves every name whole. The cost is width, and it has to be claimed.
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(
                        state = BoardCardState(card = BEARS, attachments = listOf(EQUIPPED_TAPPED)),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(BARE),
                    )
                    BoardCard(
                        state =
                            BoardCardState(
                                card = BEARS,
                                attachments = listOf(EQUIPPED_TAPPED, SECOND_EQUIPPED_TAPPED),
                            ),
                        width = CARD_WIDTH,
                        modifier = Modifier.testTag(ENCHANTED),
                    )
                }
            }
        }

        val one = composeTestRule.onNodeWithTag(BARE).fetchSemanticsNode().size
        val two = composeTestRule.onNodeWithTag(ENCHANTED).fetchSemanticsNode().size

        assertTrue("a second turned attachment must claim more width", two.width > one.width)
        assertEquals("turned attachments step sideways, so they add no height", one.height, two.height)
    }

    @Test
    fun `a creature carrying one of each renders both attachments`() {
        show(BoardCardState(card = BEARS, attachments = listOf(PACIFISM, EQUIPPED_TAPPED)))

        assertEquals(
            2,
            composeTestRule.onAllNodesWithTag(BoardCardTestTags.ATTACHMENT).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `two attachments render two bands`() {
        show(BoardCardState(card = BEARS, attachments = listOf(PACIFISM, HOLY_STRENGTH)))

        assertEquals(
            2,
            composeTestRule.onAllNodesWithTag(BoardCardTestTags.ATTACHMENT).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a creature shows its stats and a land shows none`() {
        show(BoardCardState(card = BEARS, power = "2", toughness = "2"))
        composeTestRule.onNodeWithTag(BoardCardTestTags.STATS).assertIsDisplayed()
        composeTestRule.onNodeWithText("2/2").assertIsDisplayed()
    }

    @Test
    fun `a card with no stats renders no stat label`() {
        show(BoardCardState(card = FOREST))

        composeTestRule.onNodeWithTag(BoardCardTestTags.STATS).assertDoesNotExist()
    }

    // The Board tier is a card cut below its art box, so its height comes from its own ratio.
    private fun cardHeight(): Dp = (CARD_WIDTH.value / BOARD_CARD_ASPECT_RATIO).roundToInt().dp

    @Test
    fun `a tapped card's assembly still contains the Auras stacked on it`() {
        // The combination nothing showed until the inspect view put it on screen: a **tapped** host
        // with **upright** attachments. Tapping makes the host only a card's width tall, but an Aura
        // behind it is not rotated and stays a whole card tall — so the stack reaches below the host,
        // and an assembly measured as "host plus the stack above it" does not contain it. Whatever
        // clips first takes the name band off the top, which is the one thing the stack is there for.
        show(
            BoardCardState(
                card = BEARS,
                tapped = true,
                attachments = listOf(HOLY_STRENGTH, PACIFISM),
            ),
        )

        val footprint = composeTestRule.onNodeWithTag(FOOTPRINT).fetchSemanticsNode()
        val top = footprint.positionInRoot.y
        val bottom = top + footprint.size.height
        val attachments =
            composeTestRule
                .onAllNodesWithTag(BoardCardTestTags.ATTACHMENT, useUnmergedTree = true)
                .fetchSemanticsNodes()

        assertEquals("both Auras must render, or this proves nothing", 2, attachments.size)
        attachments.forEach { node ->
            assertTrue(
                "an attachment runs from ${node.positionInRoot.y} to " +
                    "${node.positionInRoot.y + node.size.height}, outside the card's own footprint " +
                    "$top..$bottom — the assembly is smaller than what it draws",
                node.positionInRoot.y >= top - ROUNDING_SLACK_PX &&
                    node.positionInRoot.y + node.size.height <= bottom + ROUNDING_SLACK_PX,
            )
        }
    }

    @Test
    fun `the stack's steps grow with the card, so a band is a name plate at any size`() {
        // The defect the inspect view exposed: the steps were fixed distances tuned for a card on a
        // battlefield. 15dp exposes a whole name plate on a 68dp card and a sliver of one on the 250dp
        // card a zoom draws, and a 4dp sideways step that reads at board size vanishes beside a card
        // four times as wide. Asserted as a **ratio**, because the point is that it scales — not that
        // it happens to be any particular number.
        val enchanted = BoardCardState(card = BEARS, attachments = listOf(PACIFISM))
        composeTestRule.setContent {
            MageTheme {
                Box {
                    BoardCard(state = enchanted, width = CARD_WIDTH, modifier = Modifier.testTag(SMALL))
                    BoardCard(state = enchanted, width = CARD_WIDTH * 2f, modifier = Modifier.testTag(LARGE))
                }
            }
        }

        val small =
            composeTestRule
                .onNodeWithTag(SMALL)
                .fetchSemanticsNode()
                .size.height
                .toFloat()
        val large =
            composeTestRule
                .onNodeWithTag(LARGE)
                .fetchSemanticsNode()
                .size.height
                .toFloat()

        // The assembly is a card plus one band, so the whole thing scales with the card.
        assertEquals(
            "the stack does not scale with the card: 2x the card gave ${large / small}x the assembly",
            2f,
            large / small,
            0.02f,
        )
    }

    private companion object {
        val CARD_WIDTH: Dp = 72.dp
        const val FOOTPRINT = "footprint"

        /**
         * One pixel, for the containment assertions.
         *
         * Compose rounds each offset and each size to whole pixels independently, so a child placed at
         * `round(a)` with height `round(b)` can end one pixel past a parent measured as `round(a + b)`.
         * That is arithmetic, not a layout defect - a name plate is not clipped by a pixel. The bug this
         * guards against was 84dp.
         */
        const val ROUNDING_SLACK_PX = 1

        const val SMALL = "footprint-small"
        const val LARGE = "footprint-large"
        const val BARE = "bare"
        const val ENCHANTED = "enchanted"

        val BEARS = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear")
        val FOREST = CardDisplay(name = "Forest", typeLine = "Basic Land — Forest")
        val PACIFISM = BoardAttachment(name = "Pacifism", manaCost = "1W")
        val HOLY_STRENGTH = BoardAttachment(name = "Holy Strength", manaCost = "W")

        /** An Equipment tapped to help pay a cost — improvise and convoke both do this. */
        val EQUIPPED_TAPPED = BoardAttachment(name = "Bonesplitter", manaCost = "1", tapped = true)
        val SECOND_EQUIPPED_TAPPED = BoardAttachment(name = "Short Sword", manaCost = "1", tapped = true)
    }
}
