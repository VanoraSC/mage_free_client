package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.text.ManaFontGlyph
import magefree.designsystem.text.SymbolText

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
 *
 * [controlledByOther] is upstream's `attachedControllerDiffers` — your Aura on their creature, or
 * theirs on yours. It is real board state and easily missed, so it is carried here; the Board tier
 * does not draw it, because at that size there is nowhere to say it without saying it badly. The
 * inspect view is where it is shown.
 */
data class BoardAttachment(
    val name: String,
    val manaCost: String? = null,
    val tapped: Boolean = false,
    val controlledByOther: Boolean = false,
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
 *
 * **[glyph] is the picture, [shortLabel] is what happens without one.** The shipped Mana font draws
 * every keyword upstream marks, so a flier is a feather rather than the letters `FLY`. Four of these
 * have no usable picture — a restriction, a target and an unrecognised icon are not keywords at all,
 * and the level banner is unreadable at this size — so those keep their short form. That is the same
 * rule the mana symbols follow: what cannot be drawn is still said.
 */
enum class BoardBadge(
    val shortLabel: String,
    val label: String,
    val glyph: Char? = null,
) {
    Flying("FLY", "Flying", Char(0xE952)),
    Defender("DEF", "Defender", Char(0xE94C)),
    Deathtouch("DTH", "Deathtouch", Char(0xE94B)),
    Lifelink("LL", "Lifelink", Char(0xEA4B)),
    DoubleStrike("DS", "Double strike", Char(0xE94D)),
    FirstStrike("FS", "First strike", Char(0xE950)),
    Trample("TR", "Trample", Char(0xE964)),

    /**
     * Upstream sends **shroud** under this icon too, told apart only by the server's own hint — so the
     * two are separate entries here, and whoever reads the hint picks between them. They are worth
     * telling apart: hexproof stops *their* spells, shroud stops yours as well.
     */
    Hexproof("HEX", "Hexproof", Char(0xE954)),
    Shroud("SHR", "Shroud", Char(0xEA88)),
    Indestructible("IND", "Indestructible", Char(0xE95A)),
    Vigilance("VIG", "Vigilance", Char(0xE968)),
    Reach("RCH", "Reach", Char(0xE960)),
    Infect("INF", "Infect", Char(0xEA73)),
    Crew("CRW", "Crew", Char(0xE947)),

    /**
     * No glyph, though the font has one. `ms-level` is a wide banner with the word *LEVEL* set into
     * it in microtype; scaled into a 13dp square it is an illegible smudge, and a badge nobody can
     * read is worse than three letters they can.
     */
    ClassLevel("CLS", "Class level"),
    FaceDown("FD", "Face down", Char(0xE9D6)),

    /** No glyph: this is a property of the game state, not a keyword any font has a picture of. */
    HasTargets("TGT", "Has targets"),
    CostX("X", "Announced X", Char(0xE615)),

    /** No glyph, for the same reason. The reasons themselves are in the server's hint. */
    HasRestrictions("!", "Restricted"),
    Commander("CMD", "Commander", Char(0xE9C6)),
    Ringbearer("RNG", "Ring-bearer", Char(0xE9DF)),

    /** No glyph by definition: the server named something this build does not know. */
    Unknown("?", "Unrecognised"),
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
 * The largest card width whose whole **assembly** fits in [maxWidth] × [maxHeight].
 *
 * A card with attachments is bigger than the card: upright attachments stack *above* the host so
 * their name bands show, and turned ones reach out to the right. A caller that sizes the card to the
 * space available gets an assembly that overflows by exactly the stack it forgot about — which
 * presents as the attachments' name plates being cut off at the top, since the stack grows upward.
 *
 * The geometry lives here rather than at the call site because the constants do. Callers that hand a
 * [BoardCard] a width worked out from their own arithmetic will drift from it the next time an
 * attachment's band height changes.
 *
 * Returns at least [Dp.Hairline]; a space too small for any card is the caller's problem to notice,
 * not this function's to hide.
 */
fun boardCardWidthFitting(
    state: BoardCardState,
    maxWidth: Dp,
    maxHeight: Dp,
): Dp {
    val upright = state.attachments.count { !it.tapped }
    val turned = state.attachments.count { it.tapped }

    // Every dimension of the assembly is a multiple of the card's own width, because the steps are
    // fractions of the card rather than fixed distances. So each constraint is `width <= budget /
    // factor` and the answer is the smallest of them — no searching, no iteration.
    val ratio = BOARD_CARD_ASPECT_RATIO
    val band = ATTACHMENT_BAND_FRACTION / CARD_ASPECT_RATIO // a band, in units of card width
    val inset = ATTACHMENT_INSET_FRACTION
    val host = if (state.tapped) 1f else 1f / ratio // the host's own height, in units of card width
    val hostSpan = if (state.tapped) 1f / ratio else 1f // the host's own width, likewise

    // Height. The host sits under the upright stack — and the upright attachments are never rotated,
    // so they stay a whole card tall and can reach *below* a tapped host, which is only a card's
    // width tall. Solving only the first constraint is what puts the stack outside the assembly.
    // A turned one hangs below the host too, being a card's width tall where the host is less.
    val byHostHeight = maxHeight / (band * upright + host)
    val byUprightDepth =
        if (upright == 0) byHostHeight else maxHeight / (band * (upright - 1) + 1f / ratio)
    val byTurnedDepth =
        if (turned == 0) byHostHeight else maxHeight / (band * upright + maxOf(0f, (host - 1f) / 2f) + 1f)

    // Width. The host plus the stack's sideways drift, and separately the turned reach, which is
    // whichever is longer: the card laid on its side, or the host with a band showing past it.
    val byHostWidth = maxWidth / (hostSpan + inset * upright)
    val byTurnedReach =
        if (turned == 0) byHostWidth else maxWidth / (maxOf(1f / ratio, hostSpan + band) + band * (turned - 1))

    return minOf(byHostHeight, byUprightDepth, byTurnedDepth, byHostWidth, byTurnedReach)
        .coerceAtLeast(Dp.Hairline)
}

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
    val cardHeight = width / BOARD_CARD_ASPECT_RATIO
    val hostWidth = if (state.tapped) cardHeight else width
    val hostHeight = if (state.tapped) width else cardHeight

    // An untapped attachment slides upward behind the host and shows its top band. A tapped one is
    // turned a quarter turn, so the band it can show is its right edge — it slides sideways instead,
    // and it is longer than the host is wide, so it sticks out on both sides exactly as a sideways
    // card under an upright one does on a table.
    val upright = state.attachments.filter { !it.tapped }
    val turned = state.attachments.filter { it.tapped }

    val band = attachmentBandHeight(width)
    val inset = attachmentInset(width)

    val upStack = band * upright.size
    val uprightReach = inset * upright.size
    val hostTop = upStack

    // A turned attachment lies behind the host and reaches past its right edge, and what shows there
    // is its name and mana cost: a quarter turn moves that band from the card's top edge to its right
    // one. So the reach is defined by what must stay uncovered — the host's own width plus a band —
    // rather than by how far the card happens to stick out.
    //
    // **Which way it sticks out is not a constant of the design.** A whole card is taller than it is
    // wide, so a turned one laid flush with the host overhung it on its own; the Board tier's card is
    // cut below its art and is *wider* than tall, so a turned one is shorter than the host is wide and
    // has to be pushed out to show anything. Hence [maxOf]: the card's own length where that is more
    // than a band, a band where it is not.
    //
    // Several of them step sideways rather than downward for the same reason. A stack always steps
    // perpendicular to the band it has to expose, so that no card covers another's name: upright
    // cards have a band across the top and step up, turned cards have one down the right and step
    // right. The same rule, rotated with the card.
    val turnedReach = if (turned.isEmpty()) 0.dp else maxOf(cardHeight, hostWidth + band) + band * (turned.size - 1)

    val assemblyWidth = maxOf(hostWidth + uprightReach, turnedReach)

    // An upright attachment is a whole card tall wherever the host is, so the stack can reach below
    // the host rather than only above it — which happens exactly when the host is **tapped**, because
    // a tapped host is only a card's *width* tall while the Auras behind it are not rotated and stay
    // full height. Declaring only `hostTop + hostHeight` there under-measures the assembly, and the
    // attachments spill out of it: the first thing a parent clips is the name band the stack exists
    // to expose.
    val uprightDepth = if (upright.isEmpty()) 0.dp else band * (upright.size - 1) + cardHeight

    // A turned card is a card's *width* tall, and the Board tier's host is less than that — so a
    // turned attachment now hangs below an untapped host instead of only reaching past its side. It is
    // centred on the host where the host is the taller of the two, and hung from its top edge where it
    // is not, so the overhang is always downward and never clipped off the top of the assembly.
    val turnedTop = hostTop + maxOf(0.dp, (hostHeight - width) / 2)
    val turnedDepth = if (turned.isEmpty()) 0.dp else turnedTop + width
    val assemblyHeight = maxOf(hostTop + hostHeight, uprightDepth, turnedDepth)

    Box(modifier = modifier.size(width = assemblyWidth, height = assemblyHeight)) {
        // Drawn furthest-out first, so the nearest turned card ends up on top and each one behind it
        // shows a full-height band of its right edge.
        turned.indices.reversed().forEach { index ->
            TurnedAttachedCard(
                attachment = turned[index],
                width = width,
                cardHeight = cardHeight,
                art = attachmentArt(turned[index]),
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            // Right edges land at the reach, one band apart, so each card behind
                            // shows exactly one band of the one in front of it.
                            x = turnedReach - band * (turned.size - 1 - index) - cardHeight,
                            y = turnedTop,
                        ),
            )
        }

        // Upright attachments stack the other way: up and slightly right, each leaving its top band —
        // the part of a real card face that carries the name and cost — uncovered.
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
                            x = inset * (upright.size - index),
                            y = band * index,
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
            modifier = Modifier.align(Alignment.TopStart).offset(x = 0.dp, y = hostTop),
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
                    // requiredSize for the same reason a turned attachment needs it: when tapped, this
                    // sits in a landscape box shorter than the card, and a plain size would be clamped
                    // to it — cropping the card before the rotation could turn it.
                    .requiredSize(width = width, height = cardHeight)
                    .graphicsLayer { rotationZ = if (state.tapped) TAPPED_ROTATION_DEGREES else 0f }
                    .clip(BoardCardShape)
                    .background(BoardSurface.card)
                    .border(width = borderWidth, color = borderColor, shape = BoardCardShape)
                    .let { base -> if (onTap != null) base.cardInspectable(onTap = onTap) else base }
                    .testTag(BoardCardTestTags.CARD),
        ) {
            // **The card face, cut below its art box.** A real card carries its name and mana cost in
            // the places a player looks for them, so overlaying our own would be redundant and worse —
            // an overlay covers the art it is printed on. What the bottom of the card carries is rules
            // text, and at battlefield size that is a grey smudge; dropping it buys height for every
            // card on the board.
            //
            // Drawn at its **full** height inside a box only [BOARD_CARD_CROP] of that, top-aligned,
            // so the image keeps its own proportions and the crop takes the bottom. Scaling it into the
            // shorter box instead would squash every card on the board.
            // `requiredHeight`, not `height`: a plain height is clamped by the parent's constraints, so
            // the box came out the *cropped* height and the renderer's centre-crop then took the top
            // and the bottom in equal measure — a card with no title bar and no type line. Required
            // ignores the clamp, the image fills a box of its own proportions, and the parent's clip
            // takes the bottom and only the bottom.
            Box(modifier = Modifier.fillMaxWidth().requiredHeight(width / CARD_ASPECT_RATIO).align(Alignment.TopStart)) {
                CardArtRegion(card = state.card, art = art, modifier = Modifier.fillMaxSize())
            }

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
 * One counter: a filled chip carrying its kind's symbol, where the font has one, and its count.
 *
 * The ring alternates black and white around the outline, which is what lets the same counter read on
 * dark art, light art and the card frame alike without anyone choosing a colour per background. The
 * digit and the symbol flip between black and white by the fill's lightness, so a queue-allocated
 * colour can never produce an unreadable chip.
 *
 * **The symbol is added to the colour, not swapped for it.** [CounterPalette] answers *which kind is
 * this, compared to that one* for every kind including the hundreds the font has never heard of; the
 * glyph answers *which kind is this* outright, for the two dozen it has. A counter with no glyph is
 * the chip without its leading symbol — the same shape, the same colour, the same count.
 */
@Composable
internal fun CounterCircle(
    counter: BoardCounter,
    palette: CounterPalette,
) {
    val fill = palette.colorFor(counter.name)
    val ink = counterDigitColor(fill)
    val glyph = counterGlyph(counter.name)
    Row(
        modifier =
            Modifier
                .height(CounterCircleSize)
                .defaultMinSize(minWidth = CounterCircleSize)
                .drawBehind {
                    val radius = size.height / 2f
                    val corner = CornerRadius(radius, radius)
                    drawRoundRect(color = fill, cornerRadius = corner)
                    // A white outline, then a dashed black one on top of it: the gaps in the black
                    // show the white through, giving one alternating edge rather than two.
                    val strokePx = RING_STROKE_DP.dp.toPx()
                    val inset = strokePx / 2f
                    val body = Size(size.width - strokePx, size.height - strokePx)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(inset, inset),
                        size = body,
                        cornerRadius = corner,
                        style = Stroke(width = strokePx),
                    )
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(inset, inset),
                        size = body,
                        cornerRadius = corner,
                        style =
                            Stroke(
                                width = strokePx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(RING_DASH_PX, RING_DASH_PX)),
                            ),
                    )
                }.padding(horizontal = CounterChipPadding),
        horizontalArrangement = Arrangement.spacedBy(CounterChipPadding, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            ManaFontGlyph(
                glyph = glyph,
                color = ink,
                fill = COUNTER_GLYPH_FILL,
                modifier =
                    Modifier
                        .size(CounterGlyphSize)
                        .semantics { contentDescription = counter.name },
            )
        }
        Text(
            text = counterCountLabel(counter.count),
            style = BoardTypography.counter,
            color = ink,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A keyword badge: the keyword's own symbol on a small plate.
 *
 * The plate stays because the badge sits on card art, which can be any colour — an unbacked glyph
 * disappears against half the cards in a set. What changed is what is on it: the shipped font draws
 * the actual keyword, so a flier is a feather instead of the letters `FLY`.
 *
 * **The keyword is still said, not just drawn.** The glyph carries the full name as its content
 * description, so a badge reads as "Flying" to anything that reads rather than looks — the same
 * promise the mana symbols make about the server's sentences.
 */
@Composable
internal fun BadgeSquare(badge: BoardBadge) {
    Box(
        modifier =
            Modifier
                .size(BadgeSize)
                .background(BoardSurface.zone.copy(alpha = BAND_OPACITY), BadgeShape)
                .border(width = 1.dp, color = BoardSurface.onSurfaceMuted, shape = BadgeShape),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = badge.glyph
        if (glyph != null) {
            ManaFontGlyph(
                glyph = glyph,
                color = BoardSurface.onSurface,
                fill = BADGE_GLYPH_FILL,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = badge.label },
            )
        } else {
            // Nothing to draw, so the short form stands — a restriction and a target are game state
            // rather than keywords, and no font has a picture of them.
            Text(
                text = badge.shortLabel,
                style = BoardTypography.counter,
                color = BoardSurface.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
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
        bandHeight = attachmentBandHeight(width),
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
            bandHeight = attachmentBandHeight(width),
            // requiredSize, not size: the card is taller than this landscape box, and a plain size
            // would be clamped by the box's constraints — squashing the card to a square before the
            // rotation ever happened, which cropped the art. The card keeps its own dimensions and
            // the rotation is what makes it fit.
            modifier =
                Modifier
                    .requiredSize(width = width, height = cardHeight)
                    .graphicsLayer { rotationZ = TAPPED_ROTATION_DEGREES },
        )
    }
}

/** The face of an attached card, upright in its own coordinates. */
@Composable
private fun AttachedCardFace(
    attachment: BoardAttachment,
    art: CardArtSlot?,
    bandHeight: Dp,
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
                        .height(bandHeight)
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
                    SymbolText(
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

/** The gap between a counter's symbol and its count, and the chip's own inset from its outline. */
private val CounterChipPadding = 1.dp

/** The symbol's box inside the chip: the chip's height less its outline and inset on both sides. */
private val CounterGlyphSize = 11.dp

/** Room around the glyph, so it does not sit on the plate's outline. */
private const val BADGE_GLYPH_FILL = 0.74f

/** The counter's chip is tighter than a badge's plate, and its symbol takes more of the space. */
private const val COUNTER_GLYPH_FILL = 0.92f

/*
 * The stack's two steps are **fractions of the card**, not fixed distances.
 *
 * They started as 15.dp and 4.dp, which are right for a card on a battlefield and wrong everywhere
 * else: the same 15.dp that exposes a whole name plate on a 68.dp card shows a sliver of one on the
 * 250.dp card the inspect view draws, and a 4.dp sideways step that reads clearly at board size is
 * invisible beside a card four times as wide. A stack whose offsets do not grow with the card stops
 * being a stack and becomes a pile of edges.
 *
 * The fractions below are calibrated so that at the board's own 68.dp they still produce 15.dp and
 * 4.dp — the tier that was designed and eyeballed against them renders as it did.
 */

/** The band as a fraction of a **whole** card's height: 15dp of a 68dp card's 95dp height. */
private const val ATTACHMENT_BAND_FRACTION = 0.158f

/** The sideways step as a fraction of the card's **width**: 4dp of 68dp. */
private const val ATTACHMENT_INSET_FRACTION = 0.059f

/**
 * Tall enough for an attached card's name and mana cost — the reason the stack offsets vertically.
 *
 * Measured against the **whole** card the printing came from, not the [BOARD_CARD_CROP] slice this
 * tier draws. The name plate is a fixed part of a card face, so cropping the card below its art must
 * not shrink the strip that exposes it: the band is the same 15dp on a 68dp card either way.
 */
private fun attachmentBandHeight(width: Dp): Dp = width / CARD_ASPECT_RATIO * ATTACHMENT_BAND_FRACTION

/** The sideways step that makes the stack read as separate cards rather than one block. */
private fun attachmentInset(width: Dp): Dp = width * ATTACHMENT_INSET_FRACTION
