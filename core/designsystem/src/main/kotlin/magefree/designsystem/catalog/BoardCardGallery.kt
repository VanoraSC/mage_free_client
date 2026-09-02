package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.card.BoardAttachment
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCard
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.BoardFocus
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CounterPalette
import magefree.designsystem.card.rememberCounterPalette
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The Board card tier, rendered on the ground it is actually drawn on.
 *
 * Board cards are small, and the whole question about them is whether they stay readable at that size.
 * Showing them on the board's own grey rather than on the catalog's surface is the point: a signal
 * that reads against a light card-list background may not read against the battlefield.
 *
 * The row that matters most is the focus row. It is the same card carrying the same signals under each
 * board focus, which is the only way to see that emphasis follows what is happening rather than what
 * the card is.
 *
 * One [CounterPalette] spans the whole gallery, so a counter kind keeps its colour from row to row
 * exactly as it would across a game.
 */

/** Every Board-tier state: resting, tapped, countered, badged, attached, and under each focus. */
@Composable
internal fun BoardCardGallery(
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
) {
    val palette = rememberCounterPalette()
    val art = artFor?.invoke(BEARS.name)
    val forestArt = artFor?.invoke(FOREST.name)
    val attachmentArt: (BoardAttachment) -> CardArtSlot? = { attachment -> artFor?.invoke(attachment.name) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        GalleryCaption("Resting, tapped, counters, and a land with no stats")
        BoardRow {
            LabelledCard(
                label = "resting",
                state = BoardCardState(card = BEARS, power = "2", toughness = "2"),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "tapped",
                state = BoardCardState(card = BEARS, power = "2", toughness = "2", tapped = true),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "one counter",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "4",
                        toughness = "4",
                        counters = listOf(BoardCounter("+1/+1", 2)),
                    ),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "many kinds",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "2",
                        toughness = "2",
                        counters =
                            listOf(
                                BoardCounter("+1/+1", 3),
                                BoardCounter("poison", 1),
                                BoardCounter("energy", 12),
                            ),
                    ),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "no stats",
                state = BoardCardState(card = FOREST),
                art = forestArt,
                palette = palette,
            )
        }

        GalleryCaption("Keyword badges — placeholder art, sized for several along the bottom edge")
        BoardRow {
            LabelledCard(
                label = "flying",
                state = BoardCardState(card = BEARS, power = "2", toughness = "2", badges = listOf(BoardBadge.Flying)),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "three",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "2",
                        toughness = "2",
                        badges = listOf(BoardBadge.Flying, BoardBadge.Trample, BoardBadge.Vigilance),
                    ),
                art = art,
                palette = palette,
            )
            LabelledCard(
                label = "badges + counters",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "5",
                        toughness = "5",
                        counters = listOf(BoardCounter("+1/+1", 3)),
                        badges =
                            listOf(
                                BoardBadge.Flying,
                                BoardBadge.Deathtouch,
                                BoardBadge.Lifelink,
                                BoardBadge.Trample,
                            ),
                    ),
                art = art,
                palette = palette,
            )
        }

        GalleryCaption("Attachments stack up and right, each showing its own name and cost")
        BoardRow {
            LabelledCard(
                label = "one aura",
                state = BoardCardState(card = BEARS, power = "2", toughness = "2", attachments = listOf(PACIFISM)),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
            LabelledCard(
                label = "two auras",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "3",
                        toughness = "3",
                        attachments = listOf(PACIFISM, HOLY_STRENGTH),
                    ),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
            LabelledCard(
                label = "host tapped",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "2",
                        toughness = "2",
                        tapped = true,
                        attachments = listOf(PACIFISM),
                    ),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
        }

        GalleryCaption(
            "An attachment has its own tap state — improvise taps an Equipment that is still equipping. " +
                "Turned, it can only show its right edge, so it steps sideways",
        )
        BoardRow {
            LabelledCard(
                label = "equipment tapped",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "4",
                        toughness = "2",
                        attachments = listOf(BONESPLITTER_TAPPED),
                    ),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
            LabelledCard(
                label = "equipment untapped",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "4",
                        toughness = "2",
                        attachments = listOf(BONESPLITTER),
                    ),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
            LabelledCard(
                label = "one of each",
                state =
                    BoardCardState(
                        card = BEARS,
                        power = "4",
                        toughness = "2",
                        attachments = listOf(PACIFISM, BONESPLITTER_TAPPED),
                    ),
                art = art,
                attachmentArt = attachmentArt,
                palette = palette,
            )
        }

        GalleryCaption("The same card and the same three signals under each focus — the border follows the moment")
        BoardRow {
            BoardFocus.entries.forEach { focus ->
                LabelledCard(
                    label = focus.name.lowercase(),
                    state =
                        BoardCardState(
                            card = BEARS,
                            power = "2",
                            toughness = "2",
                            signals =
                                setOf(
                                    BoardCardSignal.Attacking,
                                    BoardCardSignal.Targeted,
                                    BoardCardSignal.Playable,
                                ),
                        ),
                    focus = focus,
                    art = art,
                    palette = palette,
                )
            }
        }

        GalleryCaption("One signal each, at full emphasis, so every colour can be judged on its own")
        BoardRow {
            BoardCardSignal.entries.forEach { signal ->
                LabelledCard(
                    label = signal.name.lowercase(),
                    state = BoardCardState(card = BEARS, power = "2", toughness = "2", signals = setOf(signal)),
                    focus = focusPromoting(signal),
                    art = art,
                    palette = palette,
                )
            }
        }
    }
}

/** The focus that promotes [signal], so a single-signal card can be shown at full emphasis. */
private fun focusPromoting(signal: BoardCardSignal): BoardFocus = BoardFocus.entries.first { signal in it.focalSignals }

/** A strip of the board's own ground, so the cards are judged against what they sit on. */
@Composable
private fun BoardRow(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(BoardSurface.zone, MageShapes.medium)
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        content()
    }
}

/** One card with its state named beneath it, since a border colour alone does not say what it means. */
@Composable
private fun LabelledCard(
    label: String,
    state: BoardCardState,
    palette: CounterPalette,
    focus: BoardFocus = BoardFocus.Quiet,
    art: CardArtSlot? = null,
    attachmentArt: (BoardAttachment) -> CardArtSlot? = { null },
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BoardCard(
            state = state,
            width = GalleryCardWidth,
            focus = focus,
            counterPalette = palette,
            art = art,
            attachmentArt = attachmentArt,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = BoardSurface.onSurfaceMuted)
    }
}

/** A caption in the surrounding app theme, so the board's colours never explain themselves. */
@Composable
private fun GalleryCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Board size, near the small end of what the battlefield will derive, so legibility is judged honestly. */
private val GalleryCardWidth = 68.dp

private val BEARS = CardDisplay(name = "Grizzly Bears", manaCost = "1G", typeLine = "Creature — Bear")
private val FOREST = CardDisplay(name = "Forest", typeLine = "Basic Land — Forest")
private val PACIFISM = BoardAttachment(name = "Pacifism", manaCost = "1W")
private val HOLY_STRENGTH = BoardAttachment(name = "Holy Strength", manaCost = "W")
private val BONESPLITTER = BoardAttachment(name = "Bonesplitter", manaCost = "1")
private val BONESPLITTER_TAPPED = BONESPLITTER.copy(tapped = true)
