package magefree.designsystem.board

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.R

/*
 * The four zones a count is kept of, as pictures.
 *
 * **Upstream's own icons, on purpose.** They are the ones its player panel draws beside each count, so
 * anybody who has played on the desktop client already knows what a tombstone and an X mean here. They
 * are MIT and redistributed — see `docs/third-party-notices.md`.
 *
 * The Mana font this app ships covers mana symbols, card types, keyword abilities and counters, and a
 * graveyard is none of those. Upstream draws these as bitmaps for the same reason.
 */

/** A zone that has a card count worth showing. */
enum class BoardZone(
    @DrawableRes val icon: Int,
    /** What a screen reader says, and what the expanded view titles the column. */
    val label: String,
) {
    Hand(R.drawable.zone_hand, "Hand"),
    Library(R.drawable.zone_library, "Library"),
    Graveyard(R.drawable.zone_graveyard, "Graveyard"),
    Exile(R.drawable.zone_exile, "Exile"),
}

/**
 * One zone's icon.
 *
 * Drawn at its own colours rather than tinted: they are shaded pictures rather than flat symbols, and
 * a tint flattens them into blobs at this size.
 */
@Composable
fun ZoneIcon(
    zone: BoardZone,
    modifier: Modifier = Modifier,
    size: Dp = DefaultZoneIconSize,
) {
    Image(
        painter = painterResource(zone.icon),
        contentDescription = zone.label,
        modifier = modifier.size(size),
    )
}

/** Big enough to tell four pictures apart on a line of text, small enough to sit on one. */
val DefaultZoneIconSize: Dp = 14.dp
