package magefree.feature.game.board

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.theme.Corner
import magefree.designsystem.theme.Elevation
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer

/*
 * The board's card surfaces (story 0055).
 *
 * These reuse the design system's **card vocabulary** — `CardDisplay`, [CARD_ASPECT_RATIO], the
 * `CardArtSlot` contract and [CardArtPlaceholder] — and 0032's Coil-backed [CardArtRenderer], so real
 * card art is on the board from day one and an art miss degrades to the same placeholder every other
 * card surface in the app degrades to.
 *
 * **Why not 0014's `CardTile` itself:** `CardTile` is a *button* by construction — it takes a required
 * `onTap`, wires `Modifier.cardInspectable`, and announces `Role.Button`. A read-only board must offer
 * no way to act, and a tile that looks tappable and does nothing is precisely the dead affordance this
 * project has been bitten by before. So the board draws its own tap-free surfaces over the same
 * vocabulary; when story 0056 gives a card something to do, `CardTile`'s tap model is what it adopts.
 */

/** Width of a permanent on a battlefield band. */
internal val PermanentWidth: Dp = 76.dp

/** Width of a card in the expanded hand. */
internal val HandCardWidth: Dp = 96.dp

/** Width of an entry in the stack strip. */
internal val StackCardWidth: Dp = 44.dp

/**
 * A card face: its art (or the design-system placeholder while loading, on an art miss, or when the
 * server named no printing) at card aspect ratio, with no interaction of any kind.
 */
@Composable
internal fun BoardCardFace(
    card: CardUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(CARD_ASPECT_RATIO)
                .clip(RoundedCornerShape(Corner.small)),
    ) {
        if (card.isFaceDown) {
            // Never fetch art for a card the viewer is not entitled to identify.
            FaceDownBack(modifier = Modifier.fillMaxSize())
        } else {
            artRenderer.Render(request = card.art, display = card.display, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The stand-in for a face-down card: a plain back, never the card's own art. */
@Composable
private fun FaceDownBack(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = FACE_DOWN_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The highlight a card carries for the **outstanding prompt** (story 0057) — the server's own answer to
 * "may this be picked right now", never a computed one.
 *
 * Distinct from [PermanentUi.isOfferedByServer], which is the standing `canPlayObjects` mark: this says
 * *the question on screen accepts this card*, and it is what makes the card tappable.
 */
internal enum class CardPickState {
    /** Not part of the outstanding question. Still tappable to *inspect* (§11.1) — never to act. */
    None,

    /** The server offered this object as an answer to the outstanding prompt. */
    Pickable,

    /** The server says this object is already chosen for the outstanding prompt. */
    Chosen,
}

/** The border a [CardPickState] draws, or null for none. Selection wins, because it is the player's. */
@Composable
private fun pickBorder(
    pick: CardPickState,
    isSelected: Boolean,
): BorderStroke? =
    when {
        isSelected -> BorderStroke(SELECTED_BORDER, MaterialTheme.colorScheme.secondary)
        pick == CardPickState.Pickable -> BorderStroke(PICKABLE_BORDER, MaterialTheme.colorScheme.primary)
        pick == CardPickState.Chosen -> BorderStroke(PICKABLE_BORDER, MaterialTheme.colorScheme.tertiary)
        else -> null
    }

/**
 * One permanent on a battlefield: its face, rotated when tapped, with the battlefield-only marks the
 * server reports — summoning sickness, marked damage, attacking/blocking, and (only while the viewer
 * holds priority) the "the server offered this" mark.
 *
 * @param onTap raises the card and opens its detail (§5.1 / §11.1). Inspecting is not acting: the
 *   *action* lives on the detail's own button, and only when the server offered this object.
 */
@Composable
internal fun PermanentCard(
    permanent: PermanentUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
    pick: CardPickState = CardPickState.None,
    isSelected: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.width(PermanentWidth).let { if (onTap != null) it.clickable(onClick = onTap) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(Corner.small),
            tonalElevation = if (permanent.isOfferedByServer) Elevation.level3 else Elevation.level1,
            border = pickBorder(pick, isSelected),
            color =
                if (permanent.isOfferedByServer) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoardCardFace(
                card = permanent.card,
                artRenderer = artRenderer,
                // A tapped permanent is turned sideways, exactly as it would be on a table.
                modifier = Modifier.padding(Spacing.extraSmall).rotate(if (permanent.isTapped) TAPPED_ROTATION else 0f),
            )
        }
        Text(
            text = permanent.card.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val marks =
            buildList {
                permanent.card.powerToughness?.let { add(it) }
                if (permanent.isTapped) add(TAPPED_MARK)
                if (permanent.hasSummoningSickness) add(SUMMONING_SICK_MARK)
                if (permanent.isAttacking) add(ATTACKING_MARK)
                if (permanent.isBlocking) add(BLOCKING_MARK)
                if (permanent.damage > 0) add("$DAMAGE_MARK ${permanent.damage}")
            }
        if (marks.isNotEmpty()) {
            Text(
                text = marks.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One card in the expanded hand: its face, its name, and its cost when the server sent one. */
@Composable
internal fun HandCard(
    handCard: HandCardUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
    pick: CardPickState = CardPickState.None,
    isSelected: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.width(HandCardWidth).let { if (onTap != null) it.clickable(onClick = onTap) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(Corner.small),
            tonalElevation = if (handCard.isOfferedByServer) Elevation.level3 else Elevation.level1,
            border = pickBorder(pick, isSelected),
            color =
                if (handCard.isOfferedByServer) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoardCardFace(card = handCard.card, artRenderer = artRenderer, modifier = Modifier.padding(Spacing.extraSmall))
        }
        Text(
            text = handCard.card.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // `manaCost` is null for lands upstream, so this row is simply absent for them.
        handCard.card.display.manaCost?.let { cost ->
            Text(
                text = cost,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** One object on the stack: a small face and its name. */
@Composable
internal fun StackCard(
    entry: StackEntryUi,
    artRenderer: CardArtRenderer,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardCardFace(card = entry.card, artRenderer = artRenderer, modifier = Modifier.width(StackCardWidth))
        Text(
            text = entry.card.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.small),
        )
    }
}

/** A slim card edge, used by the collapsed hand's peek strip — a count of backs, never identities. */
@Composable
internal fun HandPeekEdge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .width(HAND_EDGE_WIDTH)
                .clip(RoundedCornerShape(topStart = Corner.small, topEnd = Corner.small))
                .background(MaterialTheme.colorScheme.secondaryContainer),
    )
}

private val HAND_EDGE_WIDTH: Dp = 22.dp

/** Border widths for the two "the server offered this for the outstanding prompt" marks. */
private val PICKABLE_BORDER: Dp = 2.dp

private val SELECTED_BORDER: Dp = 3.dp

/** How far a tapped permanent turns. */
private const val TAPPED_ROTATION = 90f

/** The battlefield marks, shared with the tests so both agree on the wording. */
internal const val TAPPED_MARK: String = "Tapped"

internal const val SUMMONING_SICK_MARK: String = "Summoning sick"

internal const val ATTACKING_MARK: String = "Attacking"

internal const val BLOCKING_MARK: String = "Blocking"

internal const val DAMAGE_MARK: String = "Damage"
