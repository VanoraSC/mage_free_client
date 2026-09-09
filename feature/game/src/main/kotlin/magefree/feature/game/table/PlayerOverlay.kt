package magefree.feature.game.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.board.BoardZone
import magefree.designsystem.board.ZoneIcon
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardState

/*
 * One player, opened.
 *
 * **One window rather than five.** A seat's numbers, its counters, its designations, its command zone
 * and the cards in each of its piles are all answers to the same question — *how is this player
 * doing* — and a player asking it is usually about to ask two or three of them in a row. Five separate
 * surfaces meant opening and closing five times; this is one, with the status down the left and a
 * column per pile beside it.
 *
 * It replaces the rail's stack of pile tiles, which cost four card-heights of a column that is only a
 * card wide and left the piles too small to read anyway. The rail now says how many are in each zone,
 * and this says what they are.
 *
 * §7.15: *"Expanded, it is the list. Every counter named with its count, monarch and initiative, the
 * designations, and the contents of `PlayerView.commandList` — emblems, dungeons, commanders and
 * planes. This is where a player answers 'what is actually acting on this game right now', and it is
 * the reason the section exists: none of it is on the battlefield, and all of it decides games."*
 *
 * **Opening it is a look, not a decision.** So it floats over the board, never displaces it, and closes
 * the way every other floating surface does — the same scrim-and-press the card preview uses, because
 * a player should not have to learn two ways to put something down.
 */

/**
 * Everything about one player, and every card in their piles.
 *
 * @param vitals the seat, from [tableVitals].
 * @param onDismiss called on a press outside the panel.
 * @param modifier the [Modifier] for the overlay.
 * @param zones that seat's piles, from [tableZones]. Empty draws the status alone, which is what a
 *   board with no cards anywhere yet looks like.
 * @param artFor resolves each card's art from the printing the server named.
 * @param onInspect called with a card's id when it is tapped — which opens the card preview, the same
 *   surface every other card on the board opens.
 */
@Composable
fun PlayerOverlay(
    vitals: TableVitals,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    zones: List<TableZonePile> = emptyList(),
    artFor: TableArtResolver? = null,
    onInspect: ((String) -> Unit)? = null,
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
                    ).testTag(PlayerOverlayTestTags.SCRIM),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth(PANEL_WIDTH_SHARE)
                    .fillMaxHeight(PANEL_HEIGHT_SHARE)
                    .background(BoardSurface.floating, PanelShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(PanelPadding)
                    .testTag(PlayerOverlayTestTags.PANEL),
            horizontalArrangement = Arrangement.spacedBy(PanelPadding),
        ) {
            StatusColumn(vitals = vitals, modifier = Modifier.width(StatusWidth).fillMaxHeight())

            // A column per pile, in the rail's own order, so the two agree about which zone is which.
            zones.forEach { zone ->
                ZoneColumn(
                    zone = zone,
                    artFor = artFor,
                    onInspect = onInspect,
                    modifier = Modifier.width(ZoneColumnWidth).fillMaxHeight(),
                )
            }
        }
    }
}

/** The numbers, the counters, the designations and the command zone — everything that is not a card. */
@Composable
private fun StatusColumn(
    vitals: TableVitals,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).testTag(PlayerOverlayTestTags.STATUS),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        Text(text = vitals.name, style = BoardTypography.promptBody, color = BoardSurface.onSurface)

        Line(label = "Life", value = "${vitals.life}")
        Line(label = BoardZone.Library.label, value = "${vitals.libraryCount}")
        Line(label = BoardZone.Hand.label, value = "${vitals.handCount}")
        Line(label = BoardZone.Graveyard.label, value = "${vitals.graveyardCount}")
        Line(label = BoardZone.Exile.label, value = "${vitals.exileCount}")
        if (vitals.showsWins) Line(label = "Games won", value = "${vitals.wins} of ${vitals.winsNeeded}")

        // Named, which is the whole reason the expanded view exists: the collapsed chip is a colour
        // and a number, and a colour is a way to tell two chips apart rather than a code.
        if (vitals.counters.isNotEmpty()) {
            Section(title = "Counters")
            vitals.counters.forEach { counter ->
                Line(
                    label = counter.name,
                    value = "${counter.count}",
                    tag = PlayerOverlayTestTags.counter(counter.name),
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
                Line(label = designation, value = "", tag = PlayerOverlayTestTags.designation(designation))
            }
        }

        if (vitals.commandObjects.isNotEmpty()) {
            Section(title = "In the command zone")
            vitals.commandObjects.forEach { name ->
                Line(label = name, value = "", tag = PlayerOverlayTestTags.command(name))
            }
        }
    }
}

/**
 * One pile, top to bottom.
 *
 * **Down rather than across**, because a pile is a stack and reading one is scanning a list. Four
 * lists side by side is also the only arrangement in which a player can compare them, which is what
 * they are usually here to do — what died against what is exiled against what is left.
 *
 * The server's own order, newest last, which is the order everything that names the top of a pile
 * means.
 */
@Composable
private fun ZoneColumn(
    zone: TableZonePile,
    artFor: TableArtResolver?,
    onInspect: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag(PlayerOverlayTestTags.zone(zone.kind)),
        verticalArrangement = Arrangement.spacedBy(RowGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(RowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            zone.kind.icon?.let { ZoneIcon(zone = it) }
            Text(
                text = "${zone.kind.label} ${zone.count}",
                style = BoardTypography.counter,
                color = BoardSurface.onSurfaceMuted,
                maxLines = 1,
            )
        }

        if (zone.cards.isEmpty()) {
            Text(
                // **Counted but not seen is not the same as empty.** An opponent's hand of four is
                // four cards this player has not been shown, and a column that said "Empty" would be
                // reporting the wrong game.
                text = if (zone.hidden > 0) hiddenMessage(zone.hidden) else EMPTY_MESSAGE,
                style = BoardTypography.annotation,
                color = BoardSurface.onSurfaceMuted,
                modifier = Modifier.testTag(PlayerOverlayTestTags.empty(zone.kind)),
            )
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RowGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            zone.cards.forEach { card ->
                BoardCard(
                    state = BoardCardState(card = card.card, signals = setOfNotNull(card.signal), isSelected = card.isSelected),
                    width = ZoneCardWidth,
                    art = artFor?.invoke(card.boardArt, card.card),
                    onTap = onInspect?.let { inspect -> { inspect(card.id) } },
                    modifier = Modifier.testTag(PlayerOverlayTestTags.card(card.id)),
                )
            }

            // **A hand can be partly known, and both halves have to show.** An opponent whose hand you
            // have seen two cards of has two cards and a number, not one or the other — this branch
            // used to be unreachable because the count was only drawn for a pile with nothing in it.
            if (zone.hidden > 0) {
                Text(
                    text = hiddenMessage(zone.hidden),
                    style = BoardTypography.annotation,
                    color = BoardSurface.onSurfaceMuted,
                    modifier = Modifier.testTag(PlayerOverlayTestTags.hidden(zone.kind)),
                )
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
object PlayerOverlayTestTags {
    const val SCRIM: String = "player-overlay-scrim"
    const val PANEL: String = "player-overlay-panel"
    const val STATUS: String = "player-overlay-status"

    fun counter(name: String): String = "player-overlay-counter-$name"

    fun designation(name: String): String = "player-overlay-designation-$name"

    fun command(name: String): String = "player-overlay-command-$name"

    /** One pile's column. */
    fun zone(kind: TableZoneKind): String = "player-overlay-zone-${kind.name}"

    /** What a column says when its pile is empty. */
    fun empty(kind: TableZoneKind): String = "player-overlay-empty-${kind.name}"

    /** The "and N you have not been shown" line, beside whatever *is* known. */
    fun hidden(kind: TableZoneKind): String = "player-overlay-hidden-${kind.name}"

    /** One card in one of the columns, by its server object id. */
    fun card(cardId: String): String = "player-overlay-card-$cardId"
}

/** What an opened but empty pile says, since the column is there and has to say something. */
private const val EMPTY_MESSAGE = "Empty"

/** What a pile the server counted but did not send says instead. */
private fun hiddenMessage(count: Int): String = "$count hidden"

/** Dark enough to say the board is not the thing being touched, light enough to still read it. */
private val ScrimColor = Color.Black.copy(alpha = 0.72f)

private const val PANEL_WIDTH_SHARE = 0.9f
private const val PANEL_HEIGHT_SHARE = 0.9f
private val PanelShape = RoundedCornerShape(8.dp)
private val PanelPadding = 12.dp
private val RowGap = 4.dp

/** Wide enough for a counter's name and its count on one line. */
private val StatusWidth: Dp = 200.dp

/** A column is a card wide plus room for the scrollbar the cards sit under. */
private val ZoneColumnWidth: Dp = 132.dp

/** Board tier, at the size a pile is read at: bigger than the rail, smaller than the battlefield. */
private val ZoneCardWidth: Dp = 120.dp
