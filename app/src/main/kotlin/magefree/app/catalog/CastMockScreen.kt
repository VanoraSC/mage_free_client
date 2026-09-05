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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.app.game.ImmersiveSystemUi
import magefree.designsystem.card.CardPreview
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.table.BattlefieldLayout
import magefree.feature.game.table.TableArtResolver
import magefree.feature.game.table.TableHandCard
import magefree.feature.game.table.battlefieldModel
import magefree.feature.game.table.handCards
import magefree.feature.game.table.handPreviewState

/*
 * Inspecting a card, and playing it — mocked.
 *
 * A separate screen from the battlefield preview on purpose. That one exists to judge the *layout*,
 * and layout is judged by looking at a still board; this one exists to judge the *interaction*, which
 * can only be judged by doing it. Mixing them would mean every layout check started by dismissing an
 * overlay somebody opened by accident.
 *
 * **Mocked means the action is reported, not taken.** Play and Cast say what they would submit. The
 * cast flow that would actually submit it exists (0102, 0103) and is driven by the server's own
 * prompts, so wiring the two together belongs to the story that has a live session to wire them to —
 * and until then a Cast button that appeared to work would be lying about what the server had been
 * told.
 */

/**
 * The cast-and-inspect mock.
 *
 * @param onExit leaves the mock; also bound to system back, so there is one exit path.
 * @param modifier the [Modifier] for the surface.
 * @param artFor resolves art from the printing each fixture names.
 * @param oracleFor resolves a card's printed text from the device's own catalog. Null in a preview,
 *   where there is no Koin graph to resolve it from, and the panel simply omits the oracle section.
 */
@Composable
fun CastMockScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: TableArtResolver? = null,
    oracleFor: (suspend (String) -> String?)? = null,
) {
    LandscapeOnly()
    ImmersiveSystemUi()
    BackHandler(onBack = onExit)

    val state = remember { castMockBoard() }
    val hand = remember(state) { handCards(state) }
    var inspecting by remember { mutableStateOf<TableHandCard?>(null) }
    var oracle by remember { mutableStateOf<String?>(null) }
    var reported by remember { mutableStateOf<String?>(null) }

    // Looked up when a card is opened rather than for the whole hand: the catalog is a database, and
    // the board only ever needs the one card somebody is reading.
    LaunchedEffect(inspecting, oracleFor) {
        oracle = inspecting?.let { card -> oracleFor?.invoke(card.card.name) }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().testTag(CastMockTestTags.SCREEN)) {
            BattlefieldLayout(
                model = battlefieldModel(state),
                hand = hand,
                artFor = artFor,
                // Both gestures open the card. §7.1 gives tap to *act* and long press to *inspect*,
                // and in a hand the two are the same first move: you look at the card, and the action
                // is offered on the card you are looking at. That keeps a tap from casting something
                // by accident, which is the one mistake a board must not make easy.
                onPlayFromHand = { id -> inspecting = hand.firstOrNull { it.id == id } },
                onInspect = { id -> inspecting = hand.firstOrNull { it.id == id } ?: inspecting },
                modifier = Modifier.fillMaxSize(),
            )

            inspecting?.let { card ->
                CardPreview(
                    state =
                        handPreviewState(
                            card = card,
                            oracleText = oracle,
                            onAct = { id ->
                                reported = "${card.actionLabel} $id"
                                inspecting = null
                            },
                        ),
                    onDismiss = { inspecting = null },
                    // Full resolution, not the hand's downsampled image: this is the Full tier, and
                    // the card is three quarters of the screen tall with printed text on it.
                    art = artFor?.invoke(card.fullArt, card.card),
                )
            }

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
                Text(
                    text = reported ?: "Tap or hold a card to read it; drag one up to play it.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(CastMockTestTags.REPORT),
                )
            }
        }
    }
}

/** Test tags for the mock, whose parts carry no distinctive text of their own. */
object CastMockTestTags {
    const val SCREEN: String = "cast-mock"
    const val REPORT: String = "cast-mock-report"
}

private val ControlPadding = 8.dp

@Preview(name = "Cast mock", showBackground = true, widthDp = 891, heightDp = 411)
@Composable
private fun CastMockScreenPreview() {
    MageTheme {
        CastMockScreen(onExit = {})
    }
}
