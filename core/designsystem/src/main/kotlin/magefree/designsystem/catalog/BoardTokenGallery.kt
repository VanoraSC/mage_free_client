package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardDuration
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.board.MotionScale
import magefree.designsystem.board.perceptualLightness
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The board tokens, rendered.
 *
 * The numbers hold the palette to its requirements; this is where it is judged. Two questions can
 * only be answered by looking: do the greys separate without any colour helping, and does every
 * signal still read once it is a small mark on a large ground rather than a swatch beside a label.
 *
 * Measured lightness is printed on each surface so the ramp can be read as a ramp rather than as six
 * unrelated greys, and the signals are shown at the size they are actually drawn at.
 */

/** The value ramp, the information colours over every ground, and the motion durations. */
@Composable
internal fun BoardTokenGallery(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        GalleryLabel("Value ramp — the only thing separating these is lightness")
        Column(
            modifier = Modifier.fillMaxWidth().background(BoardSurface.ground, MageShapes.medium).padding(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SurfaceRows.forEach { (name, color) -> SurfaceRow(name = name, color = color) }
        }

        GalleryLabel("Information colours, over every ground they are drawn on")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardSurface.signalGrounds.forEach { ground -> SignalRow(ground = ground) }
        }

        GalleryLabel("Durations, at full scale and reduced")
        Column(
            modifier = Modifier.fillMaxWidth().background(BoardSurface.zone, MageShapes.medium).padding(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DurationRows.forEach { (name, duration) -> DurationRow(name = name, durationMillis = duration) }
        }
    }
}

/** One surface of the ramp, filling the width so adjacent steps meet along a long edge. */
@Composable
private fun SurfaceRow(
    name: String,
    color: Color,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SurfaceRowHeight)
                .background(color)
                .padding(horizontal = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = name, style = BoardTypography.annotation, color = BoardSurface.onSurface)
        Text(
            text = "L* ${"%.1f".format(color.perceptualLightness())}",
            style = BoardTypography.annotation,
            color = BoardSurface.onSurfaceMuted,
        )
    }
}

/** Every signal as a small mark on one ground — the size and context they are read in. */
@Composable
private fun SignalRow(ground: Color) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SignalRowHeight)
                .background(ground)
                .padding(horizontal = Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        BoardSignal.all.forEach { signal ->
            Box(modifier = Modifier.size(SignalMarkSize).background(signal, CircleShape))
        }
        Text(
            text = "L* ${"%.1f".format(ground.perceptualLightness())}",
            style = BoardTypography.annotation,
            color = BoardSurface.onSurfaceMuted,
        )
    }
}

/** One duration, at full and reduced scale, so the reduce-motion path is visible as shortening. */
@Composable
private fun DurationRow(
    name: String,
    durationMillis: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = name,
            style = BoardTypography.annotation,
            color = BoardSurface.onSurface,
            modifier = Modifier.width(DurationLabelWidth),
        )
        Box(
            modifier =
                Modifier
                    .width((durationMillis / DURATION_MILLIS_PER_DP).dp)
                    .height(DurationBarHeight)
                    .background(BoardSignal.playable),
        )
        Box(
            modifier =
                Modifier
                    .width((MotionScale.Reduced.scale(durationMillis) / DURATION_MILLIS_PER_DP).dp)
                    .height(DurationBarHeight)
                    .background(BoardSignal.pendingCost),
        )
        Text(
            text = "$durationMillis → ${MotionScale.Reduced.scale(durationMillis)} ms",
            style = BoardTypography.annotation,
            color = BoardSurface.onSurfaceMuted,
        )
    }
}

/** A caption in the surrounding app theme, so the board's own colours are never used to explain themselves. */
@Composable
private fun GalleryLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val SurfaceRows =
    listOf(
        "ground" to BoardSurface.ground,
        "zone" to BoardSurface.zone,
        "zoneRaised" to BoardSurface.zoneRaised,
        "card" to BoardSurface.card,
        "cardRaised" to BoardSurface.cardRaised,
        "floating" to BoardSurface.floating,
    )

private val DurationRows =
    listOf(
        "zone move" to BoardDuration.ZONE_MOVE,
        "tap" to BoardDuration.TAP,
        "counter" to BoardDuration.COUNTER,
        "spotlight" to BoardDuration.SPOTLIGHT_HOLD,
    )

private val SurfaceRowHeight = 36.dp
private val SignalRowHeight = 32.dp
private val SignalMarkSize = 14.dp
private val DurationBarHeight = 8.dp
private val DurationLabelWidth = 72.dp

/** Milliseconds per dp in the duration bars, chosen so the longest hold fits a phone width. */
private const val DURATION_MILLIS_PER_DP = 3
