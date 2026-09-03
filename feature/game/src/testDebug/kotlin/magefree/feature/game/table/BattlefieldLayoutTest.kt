package magefree.feature.game.table

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Three of §7.4's rules have layout consequences a still picture would not catch and a flat row passes
 * silently:
 *
 * - **Creatures sit against the centre line.** The viewer's creatures are *above* their lands and the
 *   opponent's are *below* theirs, so the two front rows face each other. A layout that drew both
 *   sides in the same order would look plausible and be wrong.
 * - **An empty region holds no height.** Today's board reserves a fixed height for regions whether or
 *   not they contain anything, which is the thing this rule exists to stop — and it is invisible in a
 *   screenshot of a full board.
 * - **The card size is derived from the busiest row.** A board with one creature and a board with
 *   twelve cannot draw the same card, or the twelve do not fit.
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

    /** Two boards in one composition, so two derived sizes can be compared in a single render. */
    private fun showPair(
        left: GameState,
        right: GameState,
    ) {
        composeTestRule.setContent {
            MageTheme {
                Row(modifier = Modifier.fillMaxSize()) {
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

    /** The measured width of the first card in a row — the derived size, as actually drawn. */
    private fun cardWidthIn(tag: String): Int =
        composeTestRule
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .children
            .first()
            .size.width

    @Test
    fun `the two front rows face each other across the centre line`() {
        show(twoSided())

        assertTrue(
            "the opponent's lands should be furthest away",
            top(BattlefieldTestTags.row("them", "back")) < top(BattlefieldTestTags.row("them", "front")),
        )
        assertTrue(
            "the front rows should meet in the middle",
            top(BattlefieldTestTags.row("them", "front")) < top(BattlefieldTestTags.row("me", "front")),
        )
        assertTrue(
            "my lands should be nearest me",
            top(BattlefieldTestTags.row("me", "front")) < top(BattlefieldTestTags.row("me", "back")),
        )
    }

    @Test
    fun `a row with nothing in it is not drawn at all`() {
        show(oneSided("me", listOf(bears())))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "front")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "back")).assertDoesNotExist()
    }

    @Test
    fun `lands and other permanents share the back row`() {
        show(oneSided("me", listOf(forest(), talisman())))

        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "back")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BattlefieldTestTags.row("me", "front")).assertDoesNotExist()
    }

    @Test
    fun `a quiet board does not draw bigger cards, it draws the same cards`() {
        // The constraint the first cut had backwards: it sized cards to fill whatever space was
        // going, so an opening board of two lands drew two lands the height of the battlefield.
        // Nothing about a game says a Forest matters more when there is only one of it. A card has a
        // size; the board shrinks it when it gets busy and never grows it when it gets quiet.
        showPair(
            left = oneSided("sparse", listOf(forest())),
            right = oneSided("some", List(4) { creature(it) }),
        )

        assertEquals(
            cardWidthIn(BattlefieldTestTags.row("sparse", "back")),
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
                    GamePlayer(playerId = "me", name = "Me", isViewer = true, battlefield = listOf(bears(), forest())),
                    GamePlayer(playerId = "them", name = "Them", battlefield = listOf(creature(9), forest("their-forest"))),
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

private fun talisman() = permanent("talisman", "Talisman of Unity", listOf(CardType.Artifact))

/** An Aura attached to a host, which leaves the buckets and renders on the host instead. */
private fun aura(id: String) =
    GamePermanent(
        card = GameCard(id = id, name = "Pacifism", cardTypes = listOf(CardType.Enchantment)),
        attachedTo = "bears",
        isAttachedToPermanent = true,
    )
