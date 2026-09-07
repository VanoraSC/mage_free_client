package magefree.feature.game.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import magefree.designsystem.card.CARD_ASPECT_RATIO
import magefree.designsystem.theme.Corner
import magefree.feature.cards.CardArtRenderer

/*
 * The card face the **prompt surfaces** draw, and the words the projection uses for combat.
 *
 * **What used to be here has gone.** This file held the portrait board's own permanent, hand and
 * stack cards, drawn tap-free over the design system's card vocabulary because that board was a
 * read-only surface. 0112 retired that board: the battlefield, the hand and the piles are all drawn
 * by `feature/game/table` now, from `BoardCard` — the Board tier, which has a black border, a title
 * bar and a lean rather than a quarter turn.
 *
 * What is left is what the *controls* still need. [BoardCardFace] draws a candidate the prompt
 * carried with it — a card the server offered that is not on the board at all — and the marks below
 * are the words [BoardUi] uses to state combat, shared with the tests so both agree on the wording.
 */

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

/** The battlefield marks, shared with the tests so both agree on the wording. */
internal const val TAPPED_MARK: String = "Tapped"

internal const val SUMMONING_SICK_MARK: String = "Summoning sick"

internal const val ATTACKING_MARK: String = "Attacking"

internal const val BLOCKING_MARK: String = "Blocking"

/** What an attacker says about the creatures blocking it — the other half of a legible combat. */
internal const val BLOCKED_BY_MARK: String = "Blocked by"

internal const val DAMAGE_MARK: String = "Damage"
