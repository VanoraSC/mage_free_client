package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography

/*
 * A player's vitals, expanded.
 *
 * §7.15: *"Expanded, it is the list. Every counter named with its count, monarch and initiative, the
 * designations, and the contents of `PlayerView.commandList` — emblems, dungeons, commanders and
 * planes. This is where a player answers 'what is actually acting on this game right now', and it is
 * the reason the section exists: none of it is on the battlefield, and all of it decides games."*
 *
 * **Expanding is a look, not a decision.** So it floats over the board, never displaces it, and closes
 * the way every other floating surface does — the same scrim-and-press the card preview uses, because
 * a player should not have to learn two ways to put something down.
 *
 * **This is also the Effects zone** (§4.3). Emblems belong to a player rather than to a pile of cards,
 * so they live here and not in the zone browser, which is for zones you look *through*. One home.
 */

/**
 * Everything the collapsed strip left out.
 *
 * @param vitals the player, from [tableVitals].
 * @param onDismiss called on a press outside the panel.
 * @param modifier the [Modifier] for the overlay.
 */
@Composable
fun VitalsOverlay(
    vitals: TableVitals,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A sibling behind the panel rather than a wrapper around it, for the reason the card preview
        // learned: wrapped, the scrim's `clickable` merges the whole panel into one accessibility node
        // and every press inside it dismisses.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ).testTag(VitalsOverlayTestTags.SCRIM),
        )

        Column(
            modifier =
                Modifier
                    .widthIn(max = PanelWidth)
                    .background(BoardSurface.floating, PanelShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(PanelPadding)
                    .verticalScroll(rememberScrollState())
                    .testTag(VitalsOverlayTestTags.PANEL),
            verticalArrangement = Arrangement.spacedBy(RowGap),
        ) {
            Text(
                text = vitals.name,
                style = BoardTypography.promptBody,
                color = BoardSurface.onSurface,
            )

            Line(label = "Life", value = "${vitals.life}")
            Line(label = "Library", value = "${vitals.libraryCount}")
            Line(label = "Hand", value = "${vitals.handCount}")
            Line(label = "Graveyard", value = "${vitals.graveyardCount}")
            Line(label = "Exile", value = "${vitals.exileCount}")
            if (vitals.showsWins) Line(label = "Games won", value = "${vitals.wins} of ${vitals.winsNeeded}")

            // Named, which is the whole reason the expanded view exists: the collapsed chip is a
            // colour and a number, and a colour is a way to tell two chips apart rather than a code.
            if (vitals.counters.isNotEmpty()) {
                Section(title = "Counters")
                vitals.counters.forEach { counter ->
                    Line(
                        label = counter.name,
                        value = "${counter.count}",
                        tag = VitalsOverlayTestTags.counter(counter.name),
                    )
                }
            }

            val designations =
                buildList {
                    if (vitals.isMonarch) add("Monarch")
                    if (vitals.hasInitiative) add("Initiative")
                    addAll(vitals.designations)
                }
            if (designations.isNotEmpty()) {
                Section(title = "Designations")
                designations.forEach { designation ->
                    Line(label = designation, value = "", tag = VitalsOverlayTestTags.designation(designation))
                }
            }

            if (vitals.commandObjects.isNotEmpty()) {
                Section(title = "In the command zone")
                vitals.commandObjects.forEach { name ->
                    Line(label = name, value = "", tag = VitalsOverlayTestTags.command(name))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = BoardSurface.onSurfaceMuted)
    Text(text = title, style = BoardTypography.counter, color = BoardSurface.onSurfaceMuted)
}

@Composable
private fun Line(
    label: String,
    value: String,
    tag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().let { base -> tag?.let { base.testTag(it) } ?: base },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = BoardTypography.promptBody, color = BoardSurface.onSurface)
        if (value.isNotEmpty()) {
            Text(text = value, style = BoardTypography.promptBody, color = BoardSurface.onSurfaceMuted)
        }
    }
}

/** Test tags for the overlay's parts. */
object VitalsOverlayTestTags {
    const val SCRIM: String = "vitals-overlay-scrim"
    const val PANEL: String = "vitals-overlay-panel"

    fun counter(name: String): String = "vitals-overlay-counter-$name"

    fun designation(name: String): String = "vitals-overlay-designation-$name"

    fun command(name: String): String = "vitals-overlay-command-$name"
}

private val PanelWidth = 320.dp
private val PanelShape = RoundedCornerShape(6.dp)
private val PanelPadding = 12.dp
private val RowGap = 6.dp
private val ScrimColor = Color.Black.copy(alpha = 0.72f)
