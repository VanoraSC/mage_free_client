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
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.CardPreview
import magefree.designsystem.card.CardPreviewState
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.phase.PhaseBarState
import magefree.designsystem.component.phase.StepIds
import magefree.designsystem.component.phase.standardTurnSteps
import magefree.designsystem.theme.MageTheme
import magefree.feature.game.table.BattlefieldLayout
import magefree.feature.game.table.GraveyardOverlay
import magefree.feature.game.table.LandStackHalf
import magefree.feature.game.table.TableArtResolver
import magefree.feature.game.table.TableCard
import magefree.feature.game.table.TablePermanent
import magefree.feature.game.table.TableVitals
import magefree.feature.game.table.VitalsOverlay
import magefree.feature.game.table.battlefieldModel
import magefree.feature.game.table.handCards
import magefree.feature.game.table.permanentPreview
import magefree.feature.game.table.tableCardPreview
import magefree.feature.game.table.tableGraveyards
import magefree.feature.game.table.tableVitals

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
    var expandedSeat by remember { mutableStateOf<TableVitals?>(null) }
    var openGraveyard by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<ReadingCard?>(null) }
    // The stops are the one part of the phase bar a player changes, so the preview keeps them live.
    var stops by remember { mutableStateOf(setOf(StepIds.PRECOMBAT_MAIN, StepIds.POSTCOMBAT_MAIN)) }
    val board = catalogBoard(step)

    // The stacking rule is about a *transition* — a card turning a quarter and travelling into the
    // other half of its stack — and a transition cannot be posed. So one board is played rather than
    // stepped through: tapping its Plains taps a Plains, and the animation is the thing being judged.
    val state = if (board.tappable) tappedPlainsBoard(tappedPlains) else board.state
    val model = battlefieldModel(state)
    val graveyards = tableGraveyards(state)

    Surface(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().testTag(BattlefieldPreviewTestTags.SCREEN)) {
            BattlefieldLayout(
                model = model,
                hand = handCards(state),
                onPlayFromHand = { id -> inspected = "played $id" },
                vitals = tableVitals(state),
                onExpandVitals = { seat -> expandedSeat = seat },
                graveyards = graveyards,
                onOpenGraveyard = { playerId -> openGraveyard = playerId },
                phases = PhaseBarState(steps = standardTurnSteps(stops), currentStepId = StepIds.PRECOMBAT_MAIN),
                onToggleStop = { step -> stops = if (step.id in stops) stops - step.id else stops + step.id },
                artFor = artFor,
                // §7.1: a tap on a card *is* the way to read it, wherever the card is. On the
                // battlefield there is nothing else a tap could mean until the board is wired to a
                // session that has actions to offer.
                onInspect = { id ->
                    inspected = id
                    reading = model.permanentById(id)?.let(::readingOf)
                },
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

            expandedSeat?.let { seat ->
                VitalsOverlay(vitals = seat, onDismiss = { expandedSeat = null })
            }

            // The graveyard, and the card being read out of it. Two floating layers rather than one:
            // closing the card puts you back in the zone you opened it from, which is what a player
            // flicking through a graveyard expects and what a single layer cannot do.
            openGraveyard?.let { playerId ->
                graveyards.firstOrNull { it.playerId == playerId }?.let { zone ->
                    GraveyardOverlay(
                        graveyard = zone,
                        onDismiss = { openGraveyard = null },
                        artFor = artFor,
                        onInspect = { id ->
                            reading = zone.cards.firstOrNull { it.id == id }?.let(::readingOf)
                        },
                    )
                }
            }

            reading?.let { card ->
                CardPreview(
                    state = card.state,
                    onDismiss = { reading = null },
                    art = artFor?.invoke(card.art, card.state.card),
                )
            }

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

/**
 * A card the player is reading, and the printing to draw it from.
 *
 * The preview is the same surface whether the card came off the battlefield or out of a graveyard, so
 * what the screen holds is the preview's own state and an art request — not "the permanent" or "the
 * card", which would make the caller ask which one it had.
 */
private data class ReadingCard(
    val state: CardPreviewState,
    val art: CardArtRequest?,
)

/** Full resolution, because this is the Full tier and the card fills three quarters of the screen. */
private fun readingOf(permanent: TablePermanent): ReadingCard =
    ReadingCard(state = permanentPreview(permanent), art = permanent.art?.copy(size = CardArtSize.LARGE))

private fun readingOf(card: TableCard): ReadingCard = ReadingCard(state = tableCardPreview(card), art = card.fullArt)

private val ControlPadding = 8.dp

@Preview(name = "Battlefield preview", showBackground = true, widthDp = 891, heightDp = 411)
@Composable
private fun BattlefieldPreviewScreenPreview() {
    MageTheme {
        BattlefieldPreviewScreen(onExit = {})
    }
}
