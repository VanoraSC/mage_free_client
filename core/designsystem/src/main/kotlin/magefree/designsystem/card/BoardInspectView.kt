package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The board inspect view: what a player gets when they zoom a permanent.
 *
 * **This exists so the Board tier is allowed to be terse.** At board size a counter is a coloured
 * circle with a number, an attachment is a card behind a card, a keyword is a two-letter square. That
 * compression is only honest if the detail is one gesture away — otherwise it is information loss, and
 * a counter kind whose colour the player does not recognise is a fact the board has hidden from them.
 * This is where they ask, so the two halves are designed together.
 *
 * **The panel explains the board's shorthand in the board's own terms.** A counter row draws the same
 * circle the card draws, next to the counter's name; a badge row draws the same square next to the
 * keyword's name. That is what turns the colour queue from an arbitrary allocation into something a
 * player learns — and it is why the view takes the live [CounterPalette] rather than choosing colours
 * of its own, which would teach a shorthand the board does not use.
 *
 * It renders a presentation model and computes nothing. It is also **not** a change to [FullCardView],
 * which serves inspection from the deck builder and the card browser and has no idea what is attached
 * to a permanent; the old path is left alone.
 */

/**
 * A keyword badge with what the server said about it.
 *
 * @property badge which keyword, as the board draws it.
 * @property detail the server's own hint, shown when it says more than the badge's name does. Two
 *   cases make this load-bearing: upstream sends **shroud** under the hexproof icon, distinguished by
 *   its hint alone, and a restrictions badge carries the actual reasons the permanent cannot act.
 */
data class InspectBadge(
    val badge: BoardBadge,
    val detail: String = "",
)

/**
 * Everything the inspect view shows about one permanent.
 *
 * @property card the card itself — name, cost, type line, as the server sent them.
 * @property counters every counter on it, by kind and count.
 * @property badges its keyword badges, with the server's hints.
 * @property attachments what is attached to it, each with its own tap state and controller.
 * @property modifications what is currently modifying it, in the server's own game-aware rules text.
 *   A granted ability is already in that text, so nothing here re-derives one.
 */
data class BoardInspectState(
    val card: CardDisplay,
    val power: String? = null,
    val toughness: String? = null,
    val tapped: Boolean = false,
    val counters: List<BoardCounter> = emptyList(),
    val badges: List<InspectBadge> = emptyList(),
    val attachments: List<BoardAttachment> = emptyList(),
    val modifications: List<String> = emptyList(),
) {
    /** The same permanent as the Board tier draws it — the card in the zoom *is* the card on the board. */
    fun asBoardCard(): BoardCardState =
        BoardCardState(
            card = card,
            power = power,
            toughness = toughness,
            counters = counters,
            badges = badges.map { it.badge },
            attachments = attachments,
            tapped = tapped,
        )
}

/** Test tags for the inspect view. */
object BoardInspectTestTags {
    const val VIEW: String = "board-inspect"
    const val CARD: String = "board-inspect-card"
    const val PANEL: String = "board-inspect-panel"
    const val MODIFICATIONS: String = "board-inspect-modifications"

    /** The row for one attachment, by its name. */
    fun attachment(name: String): String = "board-inspect-attachment-$name"

    /** The marker on an attachment somebody else controls. */
    fun foreignAttachment(name: String): String = "board-inspect-foreign-$name"

    /** The row for one counter kind, by its name. */
    fun counter(name: String): String = "board-inspect-counter-$name"

    /** The row for one badge, by the keyword's enum name. */
    fun badge(badge: BoardBadge): String = "board-inspect-badge-${badge.name}"
}

/**
 * The zoomed permanent with its detail panel beside it.
 *
 * **Beside, never over.** A panel covering the zoomed card is a zoom that hides the thing it was opened
 * to show. Landscape makes this the natural arrangement rather than a compromise, which is why the
 * card is sized to whatever is left once the panel has its width.
 *
 * Dismissal is deliberately absent: the gesture that opens this view belongs to the board, and so does
 * the one that closes it.
 *
 * @param state the permanent to show.
 * @param modifier the [Modifier] for the whole view.
 * @param counterPalette the board's live palette, so the panel names the colours the board is using.
 *   Passing a fresh one would teach a shorthand nothing else uses.
 * @param art the zoomed card's art.
 * @param attachmentArt art for each attachment, so an Aura is recognisable behind its host.
 */
@Composable
fun BoardInspectView(
    state: BoardInspectState,
    modifier: Modifier = Modifier,
    counterPalette: CounterPalette = rememberCounterPalette(),
    art: CardArtSlot? = null,
    attachmentArt: (BoardAttachment) -> CardArtSlot? = { null },
) {
    BoxWithConstraints(modifier = modifier.testTag(BoardInspectTestTags.VIEW)) {
        // The panel takes its width first and the card takes what is left, bounded by the height so a
        // tall card cannot push the panel off screen. Sizing the card first would let a long attachment
        // stack decide how much room the detail gets, which is backwards.
        val available = maxWidth - PanelWidth - Spacing.medium

        // Sized against the whole **assembly**, not the card. A permanent with two Auras on it is
        // taller than its own card, because the upright attachments stack above the host to show
        // their name bands — so sizing the card to the space available overflows upward by exactly
        // that stack, and the first thing to disappear is the name plate the stack exists to reveal.
        val cardWidth =
            boardCardWidthFitting(
                state = state.asBoardCard(),
                maxWidth = available,
                maxHeight = maxHeight * CARD_HEIGHT_SHARE,
            )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(
                modifier = Modifier.width(available).fillMaxHeight().testTag(BoardInspectTestTags.CARD),
                contentAlignment = Alignment.Center,
            ) {
                BoardCard(
                    state = state.asBoardCard(),
                    width = cardWidth.coerceAtLeast(MinimumCardWidth),
                    counterPalette = counterPalette,
                    art = art,
                    attachmentArt = attachmentArt,
                )
            }

            DetailPanel(
                state = state,
                counterPalette = counterPalette,
                modifier = Modifier.width(PanelWidth).fillMaxHeight().testTag(BoardInspectTestTags.PANEL),
            )
        }
    }
}

/** The panel: the board's marks, said in words. */
@Composable
private fun DetailPanel(
    state: BoardInspectState,
    counterPalette: CounterPalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(BoardSurface.zone, MageShapes.medium)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        if (state.attachments.isNotEmpty()) {
            PanelSection(title = "Attached") {
                state.attachments.forEach { attachment -> AttachmentRow(attachment) }
            }
        }

        if (state.counters.isNotEmpty()) {
            PanelSection(title = "Counters") {
                state.counters.forEach { counter -> CounterRow(counter, counterPalette) }
            }
        }

        if (state.badges.isNotEmpty()) {
            PanelSection(title = "Abilities") {
                state.badges.forEach { badge -> BadgeRow(badge) }
            }
        }

        if (state.modifications.isNotEmpty()) {
            PanelSection(title = "Currently") {
                Column(
                    modifier = Modifier.testTag(BoardInspectTestTags.MODIFICATIONS),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    state.modifications.forEach { line ->
                        Text(text = line, style = BoardTypography.annotation, color = BoardSurface.onSurface)
                    }
                }
            }
        }
    }
}

/** A titled group in the panel. */
@Composable
private fun PanelSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(text = title, style = BoardTypography.annotation, color = BoardSurface.onSurfaceMuted)
        content()
    }
}

/**
 * One attached permanent.
 *
 * The controller line is the reason this row is not just a name: **your Aura on their creature is real
 * board state and easily missed.** Upstream reports it as `attachedControllerDiffers`, and a panel that
 * listed attachments plainly would be dropping the one fact about them the player cannot work out by
 * looking.
 */
@Composable
private fun AttachmentRow(attachment: BoardAttachment) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(BoardInspectTestTags.attachment(attachment.name)),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = attachment.name, style = BoardTypography.cardName, color = BoardSurface.onSurface)
            attachment.manaCost?.let { cost ->
                Text(text = cost, style = BoardTypography.cardStats, color = BoardSurface.onSurfaceMuted)
            }
        }
        if (attachment.tapped) {
            Text(text = "Tapped", style = BoardTypography.annotation, color = BoardSurface.onSurfaceMuted)
        }
        if (attachment.controlledByOther) {
            Text(
                text = FOREIGN_ATTACHMENT_LABEL,
                style = BoardTypography.annotation,
                color = BoardSurface.onSurface,
                modifier = Modifier.testTag(BoardInspectTestTags.foreignAttachment(attachment.name)),
            )
        }
    }
}

/**
 * One counter kind: the board's own circle, then what it is called.
 *
 * The circle is [CounterCircle] itself rather than a lookalike, so the panel cannot drift from the
 * board it is explaining. An unrecognised kind is named here exactly like a known one — the palette
 * allocates it a colour on sight, and a counter the client has never heard of is still a counter the
 * player needs to be able to read.
 */
@Composable
private fun CounterRow(
    counter: BoardCounter,
    palette: CounterPalette,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(BoardInspectTestTags.counter(counter.name)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CounterCircle(counter = counter, palette = palette)
        Text(text = counter.name, style = BoardTypography.cardName, color = BoardSurface.onSurface)
        Text(text = "×${counter.count}", style = BoardTypography.cardStats, color = BoardSurface.onSurfaceMuted)
    }
}

/** One keyword: the board's own square, the keyword's name, and whatever more the server said. */
@Composable
private fun BadgeRow(badge: InspectBadge) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(BoardInspectTestTags.badge(badge.badge)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeSquare(badge = badge.badge)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
            Text(text = badge.badge.label, style = BoardTypography.cardName, color = BoardSurface.onSurface)
            if (badge.detail.isNotBlank() && badge.detail != badge.badge.label) {
                Text(text = badge.detail, style = BoardTypography.annotation, color = BoardSurface.onSurfaceMuted)
            }
        }
    }
}

/** What an attachment somebody else controls is called, in words a player can act on. */
internal const val FOREIGN_ATTACHMENT_LABEL: String = "Controlled by someone else"

/** The panel's width. Fixed, because the card is what should grow with the window. */
private val PanelWidth: Dp = 260.dp

/** How much of the view's height the zoomed card is allowed to claim. */
private const val CARD_HEIGHT_SHARE = 0.92f

/** Below this the zoom stops being a zoom, so the card keeps it even in a cramped window. */
private val MinimumCardWidth: Dp = 120.dp
