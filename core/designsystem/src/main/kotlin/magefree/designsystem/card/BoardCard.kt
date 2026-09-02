package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography

/*
 * The Board tier: a card as it appears on a battlefield or in a stack pile.
 *
 * This is the smallest of the three card tiers and the one every permanent is drawn with, so it shows
 * only what has to be readable without inspecting: the name, power and toughness, the counters on the
 * card, its keyword badges, whether it is tapped, what is attached to it, and what the game is
 * currently signalling about it.
 *
 * It computes nothing about the game. Every value is state the server already decided and sent.
 */

/** One counter on a card's face. The kind is an open set upstream, so it stays a string. */
data class BoardCounter(
    val name: String,
    val count: Int,
)

/**
 * A permanent attached to this one — an Aura, an Equipment, a Fortification.
 *
 * It carries its own [tapped] state because it is its own permanent and can be tapped independently
 * of what it is attached to: improvise and convoke tap artifacts and creatures to help pay a cost, and
 * an equipped Sword tapped that way is still equipping. A stack that drew every attachment upright
 * would be misreporting the board.
 */
data class BoardAttachment(
    val name: String,
    val manaCost: String? = null,
    val tapped: Boolean = false,
)

/**
 * A keyword badge on a permanent.
 *
 * These mirror the icons the **server** computes from a permanent's game-aware abilities, so a
 * creature granted flying until end of turn carries the badge exactly as a printed flier does. The
 * client never reads rules text to decide what belongs here.
 *
 * [Unknown] exists because a newer server can send an icon this build has not heard of; it renders as
 * a neutral badge rather than vanishing, so the player still sees that *something* is there.
 */
enum class BoardBadge(
    val shortLabel: String,
) {
    Flying("FLY"),
    Defender("DEF"),
    Deathtouch("DTH"),
    Lifelink("LL"),
    DoubleStrike("DS"),
    FirstStrike("FS"),
    Trample("TR"),
    Hexproof("HEX"),
    Indestructible("IND"),
    Vigilance("VIG"),
    Reach("RCH"),
    Infect("INF"),
    Crew("CRW"),
    ClassLevel("CLS"),
    HasTargets("TGT"),
    CostX("X"),
    HasRestrictions("!"),
    Commander("CMD"),
    Ringbearer("RNG"),
    Unknown("?"),
}

/**
 * What the game is signalling about a card right now.
 *
 * Declaration order is the fallback precedence used when the current [BoardFocus] does not single one
 * out. It runs from the most immediate to the least: being targeted is about to happen, combat is
 * already assigned, and being playable is an affordance rather than an event.
 */
enum class BoardCardSignal {
    /** Something on the stack is targeting this. */
    Targeted,

    /** This is dangerous to the player: lethal on board, an unanswered threat. */
    Threat,

    /** This creature is attacking. */
    Attacking,

    /** This creature is blocking. */
    Blocking,

    /** A cost being assembled will consume this. */
    PendingCost,

    /** This can be played, cast or activated right now. */
    Playable,
    ;

    /** The information colour this signal is drawn in. */
    val color: Color
        get() =
            when (this) {
                Targeted -> BoardSignal.targeting
                Threat -> BoardSignal.threat
                Attacking -> BoardSignal.attacking
                Blocking -> BoardSignal.blocking
                PendingCost -> BoardSignal.pendingCost
                Playable -> BoardSignal.playable
            }
}

/**
 * What the board is currently about, which decides which signal a card emphasises.
 *
 * A card can carry several signals at once, and which of them matters is not a property of the card —
 * it is a property of the moment. While a spell is on the stack the player is reading targets; during
 * a combat declaration they are reading attackers and blockers; the same creature is both, and
 * emphasising the wrong one every time is what makes a board unreadable.
 *
 * The board derives this from state it already has: the outstanding prompt and whether the stack is
 * empty. That derivation lives with the board, not here, so this stays free of game types.
 */
enum class BoardFocus {
    /** A spell or ability is on the stack, or a target is being chosen. */
    Targeting,

    /** Attackers or blockers are being declared, or combat is resolving. */
    Combat,

    /** A cost is being assembled and the player is choosing what pays it. */
    PendingCost,

    /** The player has priority with nothing pending: what they can do is what matters. */
    Playable,

    /** Nothing is pending. Only danger is worth raising a voice about. */
    Quiet,
    ;

    /** The signals this focus promotes, in order, when a card carries more than one. */
    val focalSignals: List<BoardCardSignal>
        get() =
            when (this) {
                Targeting -> listOf(BoardCardSignal.Targeted)
                Combat -> listOf(BoardCardSignal.Attacking, BoardCardSignal.Blocking)
                PendingCost -> listOf(BoardCardSignal.PendingCost)
                Playable -> listOf(BoardCardSignal.Playable)
                Quiet -> listOf(BoardCardSignal.Threat)
            }
}

/**
 * The signal that claims the card's strong border: the one the current [focus] is about, or null when
 * the card carries nothing relevant to what is happening.
 */
fun focalSignal(
    signals: Set<BoardCardSignal>,
    focus: BoardFocus,
): BoardCardSignal? = focus.focalSignals.firstOrNull { it in signals }

/**
 * The signal that gets the thin muted edge: the most immediate one that is not already carrying the
 * strong border. It is what stops a creature from looking un-attacked while a spell resolves — the
 * fact is still true, it is just no longer what the player is being asked about.
 */
fun secondarySignal(
    signals: Set<BoardCardSignal>,
    focus: BoardFocus,
): BoardCardSignal? {
    val focal = focalSignal(signals, focus)
    return BoardCardSignal.entries.firstOrNull { it in signals && it != focal }
}

/**
 * A permanent's board-tier state. Everything here is server-supplied.
 *
 * @param card the display fields shared by all three tiers.
 * @param power the current power, already resolved by the server; null for a non-creature.
 * @param toughness the current toughness; null for a non-creature.
 * @param counters the counters on the card, in the order the server sent them.
 * @param badges the keyword badges the server computed for this permanent.
 * @param attachments what is attached to this permanent, in the order the server sent them.
 * @param tapped whether the permanent is tapped, which rotates it a quarter turn.
 * @param signals everything the game is signalling about this card at once.
 */
data class BoardCardState(
    val card: CardDisplay,
    val power: String? = null,
    val toughness: String? = null,
    val counters: List<BoardCounter> = emptyList(),
    val badges: List<BoardBadge> = emptyList(),
    val attachments: List<BoardAttachment> = emptyList(),
    val tapped: Boolean = false,
    val signals: Set<BoardCardSignal> = emptySet(),
)

/**
 * A card at Board size, with whatever is attached to it stacked behind.
 *
 * **Tapping rotates the card rather than badging it** — the universal Magic idiom, and cheaper to read
 * than a symbol. Rotation is not only paint: a tapped permanent occupies a landscape footprint where
 * an untapped one occupies a portrait one, and this component owns that, so a caller never has to know
 * a tapped card is a different shape.
 *
 * **Attachments stack up and to the right**, each exposing a band tall enough for its own name and
 * mana cost. That is the whole reason for the vertical offset: an Aura the player cannot name is an
 * Aura they have to tap to identify. The cost is real — a creature carrying two Auras is taller and
 * slightly wider than a bare one — and the battlefield's sizing has to accommodate it.
 *
 * @param state everything the game says about this card.
 * @param width the card's width when untapped; the footprint derives from it.
 * @param focus what the board is currently about, which decides which signal is emphasised.
 * @param counterPalette the colour allocator, so a counter kind keeps its colour for the whole game.
 * @param modifier the [Modifier] for the whole assembly.
 * @param art the card-art slot for the permanent itself. This is the **whole card face**, not a crop
 *   of its art box: a real card already prints its name and mana cost where a player looks for them.
 *   The built-in placeholder is used when none is supplied.
 * @param attachmentArt resolves the art for each attached card, which is what makes the stack readable
 *   — the exposed band of a real card face is where its name and cost are printed.
 * @param onTap invoked when the card is tapped; null makes the card non-interactive.
 */
@Composable
fun BoardCard(
    state: BoardCardState,
    width: Dp,
    modifier: Modifier = Modifier,
    focus: BoardFocus = BoardFocus.Quiet,
    counterPalette: CounterPalette = rememberCounterPalette(),
    art: CardArtSlot? = null,
    attachmentArt: (BoardAttachment) -> CardArtSlot? = { null },
    onTap: (() -> Unit)? = null,
) {
    val cardHeight = width / CARD_ASPECT_RATIO
    val hostWidth = if (state.tapped) cardHeight else width
    val hostHeight = if (state.tapped) width else cardHeight

    // An untapped attachment slides upward behind the host and shows its top band. A tapped one is
    // turned a quarter turn, so the band it can show is its right edge — it slides sideways instead,
    // and it is longer than the host is wide, so it sticks out on both sides exactly as a sideways
    // card under an upright one does on a table.
    val upright = state.attachments.filter { !it.tapped }
    val turned = state.attachments.filter { it.tapped }

    val upStack = AttachmentBandHeight * upright.size
    val uprightReach = AttachmentInset * upright.size
    val hostTop = upStack

    // Extents measured with the host's left edge at zero, then shifted so nothing is laid out at a
    // negative coordinate. A turned attachment reaching left past the host is what forces this.
    val turnedLeftMost = if (turned.isEmpty()) 0.dp else hostWidth + AttachmentBandHeight - cardHeight
    val leftOverhang = maxOf(0.dp, -turnedLeftMost)
    val rightMost = maxOf(hostWidth + uprightReach, hostWidth + AttachmentBandHeight * turned.size)

    Box(
        modifier = modifier.size(width = leftOverhang + rightMost, height = hostTop + hostHeight),
    ) {
        // Turned attachments are furthest back: the host and anything upright sits over them.
        turned.forEachIndexed { index, attachment ->
            TurnedAttachedCard(
                attachment = attachment,
                width = width,
                cardHeight = cardHeight,
                art = attachmentArt(attachment),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = leftOverhang + hostWidth + AttachmentBandHeight * (index + 1) - cardHeight,
                            y = hostTop + (hostHeight - width) / 2,
                        ),
            )
        }

        // Drawn back to front: the first sits highest and furthest right, and each subsequent one
        // covers the body of the one behind it, leaving only its top band showing.
        upright.forEachIndexed { index, attachment ->
            UprightAttachedCard(
                attachment = attachment,
                width = width,
                cardHeight = cardHeight,
                art = attachmentArt(attachment),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = leftOverhang + AttachmentInset * (upright.size - index),
                            y = AttachmentBandHeight * index,
                        ),
            )
        }

        HostCard(
            state = state,
            width = width,
            cardHeight = cardHeight,
            focus = focus,
            counterPalette = counterPalette,
            art = art,
            onTap = onTap,
            modifier = Modifier.align(Alignment.TopStart).offset(x = leftOverhang, y = hostTop),
        )
    }
}

/** The permanent itself: art, name, counters, badges, stats, and the signal border. */
@Composable
private fun HostCard(
    state: BoardCardState,
    width: Dp,
    cardHeight: Dp,
    focus: BoardFocus,
    counterPalette: CounterPalette,
    art: CardArtSlot?,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val focal = focalSignal(state.signals, focus)
    val secondary = secondarySignal(state.signals, focus)

    // One channel, two weights. The focal signal is what the player is being asked about; a secondary
    // one is still true and still worth seeing, but must not compete for the same attention.
    val borderWidth = if (focal != null) FocalBorderWidth else SecondaryBorderWidth
    val borderColor =
        focal?.color
            ?: secondary?.color?.copy(alpha = SECONDARY_BORDER_ALPHA)
            ?: BoardSurface.zoneRaised

    Box(
        modifier = modifier.size(width = if (state.tapped) cardHeight else width, height = if (state.tapped) width else cardHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = width, height = cardHeight)
                    .graphicsLayer { rotationZ = if (state.tapped) TAPPED_ROTATION_DEGREES else 0f }
                    .clip(BoardCardShape)
                    .background(BoardSurface.card)
                    .border(width = borderWidth, color = borderColor, shape = BoardCardShape)
                    .let { base -> if (onTap != null) base.cardInspectable(onTap = onTap) else base }
                    .testTag(BoardCardTestTags.CARD),
        ) {
            // The whole card face, not a crop of its art box. A real card already carries its name and
            // mana cost in the places a player looks for them, so overlaying our own is redundant —
            // and an overlay is strictly worse, because it covers the art it is printed on. Power and
            // toughness are different: they change during a game, and the printed pair goes stale.
            CardArtRegion(card = state.card, art = art, modifier = Modifier.fillMaxSize())

            if (state.counters.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(BoardCardPadding)
                            .testTag(BoardCardTestTags.COUNTERS),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    state.counters.forEach { counter ->
                        CounterCircle(counter = counter, palette = counterPalette)
                    }
                }
            }

            if (state.badges.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(BoardCardPadding)
                            .testTag(BoardCardTestTags.BADGES),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    state.badges.forEach { badge -> BadgeSquare(badge) }
                }
            }

            boardStatsLabel(state.power, state.toughness)?.let { stats ->
                Text(
                    text = stats,
                    style = BoardTypography.cardStats,
                    color = BoardSurface.onSurface,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BoardCardPadding)
                            .background(BoardSurface.zone.copy(alpha = BAND_OPACITY), BoardCardShape)
                            .padding(horizontal = BoardCardPadding)
                            .testTag(BoardCardTestTags.STATS),
                )
            }
        }
    }
}

/**
 * One counter: a filled circle carrying only its count.
 *
 * The ring alternates black and white around the circumference, which is what lets the same counter
 * read on dark art, light art and the card frame alike without anyone choosing a colour per
 * background. The digit flips between black and white by the fill's lightness, so a queue-allocated
 * colour can never produce an unreadable number.
 */
@Composable
private fun CounterCircle(
    counter: BoardCounter,
    palette: CounterPalette,
) {
    val fill = palette.colorFor(counter.name)
    Box(
        modifier =
            Modifier
                .size(CounterCircleSize)
                .drawBehind {
                    val radius = size.minDimension / 2f
                    drawCircle(color = fill, radius = radius)
                    // A white ring, then a dashed black ring on top of it: the gaps in the black show
                    // the white through, giving one alternating outline rather than two rings.
                    val strokePx = RING_STROKE_DP.dp.toPx()
                    val inset = radius - strokePx / 2f
                    drawCircle(color = Color.White, radius = inset, style = Stroke(width = strokePx))
                    drawCircle(
                        color = Color.Black,
                        radius = inset,
                        style =
                            Stroke(
                                width = strokePx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(RING_DASH_PX, RING_DASH_PX)),
                            ),
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = counterCountLabel(counter.count),
            style = BoardTypography.counter,
            color = counterDigitColor(fill),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** A keyword badge. Placeholder art: a small square carrying the keyword's short form. */
@Composable
private fun BadgeSquare(badge: BoardBadge) {
    Box(
        modifier =
            Modifier
                .size(BadgeSize)
                .background(BoardSurface.zone.copy(alpha = BAND_OPACITY), BadgeShape)
                .border(width = 1.dp, color = BoardSurface.onSurfaceMuted, shape = BadgeShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge.shortLabel,
            style = BoardTypography.counter,
            color = BoardSurface.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One attached permanent, rendered as a whole card behind the host.
 *
 * It is a full-size card rather than a label because that is what it is — an Aura is a permanent, and
 * drawing it as a strip makes it read as an annotation on the creature instead of a card in play. Only
 * its top band is left uncovered, and on a real card face that band is exactly where the name and mana
 * cost are printed, so the stack becomes readable without anything being overlaid on it.
 *
 * With no art there is nothing in that band to read, so the name and cost are drawn instead. That is
 * the degraded path, not the intended one.
 */
@Composable
private fun UprightAttachedCard(
    attachment: BoardAttachment,
    width: Dp,
    cardHeight: Dp,
    art: CardArtSlot?,
    modifier: Modifier = Modifier,
) {
    AttachedCardFace(
        attachment = attachment,
        art = art,
        modifier = modifier.size(width = width, height = cardHeight),
    )
}

/**
 * A tapped attached permanent: the same card, turned a quarter turn.
 *
 * Turning it moves the band it can show from its top edge to its right edge, so the stack that would
 * step upward steps sideways instead. It is longer than the host is wide, so it reaches out past both
 * sides — which is what a sideways card slid under an upright one looks like, and why the assembly
 * measures itself around it rather than letting it overhang into a neighbour.
 */
@Composable
private fun TurnedAttachedCard(
    attachment: BoardAttachment,
    width: Dp,
    cardHeight: Dp,
    art: CardArtSlot?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(width = cardHeight, height = width),
        contentAlignment = Alignment.Center,
    ) {
        AttachedCardFace(
            attachment = attachment,
            art = art,
            modifier =
                Modifier
                    .size(width = width, height = cardHeight)
                    .graphicsLayer { rotationZ = TAPPED_ROTATION_DEGREES },
        )
    }
}

/** The face of an attached card, upright in its own coordinates. */
@Composable
private fun AttachedCardFace(
    attachment: BoardAttachment,
    art: CardArtSlot?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(BoardCardShape)
                .background(BoardSurface.card)
                .border(width = 1.dp, color = BoardSurface.zoneRaised, shape = BoardCardShape)
                .testTag(BoardCardTestTags.ATTACHMENT),
    ) {
        if (art != null) {
            art(Modifier.fillMaxSize())
        } else {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(AttachmentBandHeight)
                        .padding(horizontal = BoardCardPadding),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = attachment.name,
                    style = BoardTypography.cardName,
                    color = BoardSurface.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                attachment.manaCost?.takeIf { it.isNotBlank() }?.let { cost ->
                    Text(
                        text = cost,
                        style = BoardTypography.counter,
                        color = BoardSurface.onSurfaceMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A count that always fits the circle: three digits become a capped form rather than overflowing. */
internal fun counterCountLabel(count: Int): String = if (count > MAX_SHOWN_COUNT) "$MAX_SHOWN_COUNT+" else count.toString()

/**
 * The power/toughness label, or null when the card has neither. A card carrying only one of the two is
 * a server state this client does not get to interpret, so the missing half renders as a dash rather
 * than suppressing the whole label.
 */
internal fun boardStatsLabel(
    power: String?,
    toughness: String?,
): String? =
    if (power == null && toughness == null) {
        null
    } else {
        "${power ?: "-"}/${toughness ?: "-"}"
    }

/** Test tags for the parts of the card that carry no text of their own. */
object BoardCardTestTags {
    const val CARD: String = "board-card"
    const val STATS: String = "board-card-stats"
    const val COUNTERS: String = "board-card-counters"
    const val BADGES: String = "board-card-badges"
    const val ATTACHMENT: String = "board-card-attachment"
}

/** A tapped permanent is turned a quarter turn, as on a physical table. */
private const val TAPPED_ROTATION_DEGREES = 90f

/** How opaque the name band, badges and stats backing are over arbitrary art. */
private const val BAND_OPACITY = 0.82f

/** How much a secondary signal's colour is dimmed so it never competes with the focal one. */
private const val SECONDARY_BORDER_ALPHA = 0.45f

/** The largest count rendered in full; above it the circle shows a capped form. */
private const val MAX_SHOWN_COUNT = 99

private const val RING_STROKE_DP = 1
private const val RING_DASH_PX = 3f

private val BoardCardShape = RoundedCornerShape(3.dp)
private val BadgeShape = RoundedCornerShape(2.dp)
private val BoardCardPadding = 2.dp
private val FocalBorderWidth = 2.dp
private val SecondaryBorderWidth = 1.dp
private val CounterCircleSize = 15.dp
private val BadgeSize = 13.dp

/** Tall enough for an attached card's name and mana cost — the reason the stack offsets vertically. */
private val AttachmentBandHeight = 15.dp

/** The small sideways step that makes the stack read as separate cards rather than one block. */
private val AttachmentInset = 4.dp
