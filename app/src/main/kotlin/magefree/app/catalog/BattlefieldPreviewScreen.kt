package magefree.app.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.app.game.ImmersiveSystemUi
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.table.BattlefieldLayout
import magefree.feature.game.table.LandStackHalf
import magefree.feature.game.table.TableArtResolver
import magefree.feature.game.table.battlefieldModel
import magefree.feature.game.table.handCards

/*
 * The battlefield, filling the window.
 *
 * **A board inside the catalog's scrolling column cannot be judged.** The catalog is portrait and it
 * shares its width with everything else in it, so the board gets a letterbox a few hundred dp wide —
 * and every rule §7.4 states is about fitting a real window: the card size that only shrinks when the
 * board is busy, the rows that vanish when empty, the two front rows meeting in the middle. Seen in a
 * letterbox they are all technically visible and none of them can be assessed.
 *
 * So the catalog's section is an entry point, and this is the surface: the whole window, landscape,
 * system bars out of the way, with nothing on it but the board and a control to change what is on it.
 * It is a preview and not the game screen — no session, no `GameState` from a server, no interaction
 * beyond inspection — but it is the same composable in the same shape of window, which is the only
 * place the arrangement can actually be iterated on.
 */

/**
 * The full-window battlefield preview.
 *
 * @param onExit leaves the preview; also bound to system back, so there is one exit path.
 * @param modifier the [Modifier] for the surface.
 * @param artFor resolves art from the printing each fixture names. Without it the board is grey
 *   rectangles, and a grey rectangle is exactly the background that makes every card component look
 *   fine — the point of this surface is whether a real card is readable at the size it was given.
 */
@Composable
fun BattlefieldPreviewScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
) {
    // Landscape and immersive for as long as this surface is composed, restored on the way out.
    LandscapeOnly()
    ImmersiveSystemUi()
    BackHandler(onBack = onExit)

    var step by remember { mutableIntStateOf(0) }
    var inspected by remember { mutableStateOf<String?>(null) }
    var tappedPlains by remember { mutableIntStateOf(0) }
    val board = catalogBoard(step)

    // The stacking rule is about a *transition* — a card turning a quarter and travelling into the
    // other half of its stack — and a transition cannot be posed. So one board is played rather than
    // stepped through: tapping its Plains taps a Plains, and the animation is the thing being judged.
    val state = if (board.tappable) tappedPlainsBoard(tappedPlains) else board.state

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().testTag(BattlefieldPreviewTestTags.SCREEN)) {
            BattlefieldLayout(
                model = battlefieldModel(state),
                hand = handCards(state),
                onPlayFromHand = { id -> inspected = "played $id" },
                artFor = artFor,
                onInspect = { id -> inspected = id },
                // The two halves of a stack are two affordances, and the board decides what each
                // means. Here that is: press an upright copy to tap one, press the strip where a
                // turned copy shows past them to untap one — which is what makes the turn-and-drop
                // watchable in both directions without a reset button between every try.
                onLandPress = { stack, half ->
                    inspected = stack.inspectId
                    if (board.tappable) {
                        tappedPlains =
                            when (half) {
                                LandStackHalf.Upright -> (tappedPlains + 1).coerceAtMost(CATALOG_PLAINS)
                                LandStackHalf.Turned -> (tappedPlains - 1).coerceAtLeast(0)
                            }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The controls float over the board rather than taking a strip beside it, for the same
            // reason §7.4 floats the stack: a band of chrome across the top is board the player
            // cannot have, and this surface exists to show how much board there is.
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(ControlPadding),
                horizontalArrangement = Arrangement.spacedBy(ControlPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MageSecondaryButton(text = "Back", onClick = onExit)
                MageSecondaryButton(
                    text = board.label,
                    onClick = {
                        step += 1
                        inspected = null
                        tappedPlains = 0
                    },
                )
                inspected?.let { Text(text = it, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** Test tag for the preview surface, which carries no distinctive text of its own. */
object BattlefieldPreviewTestTags {
    const val SCREEN: String = "battlefield-preview"
}

private val ControlPadding = 8.dp

@Preview(name = "Battlefield preview", showBackground = true, widthDp = 891, heightDp = 411)
@Composable
private fun BattlefieldPreviewScreenPreview() {
    MageTheme {
        BattlefieldPreviewScreen(onExit = {})
    }
}
