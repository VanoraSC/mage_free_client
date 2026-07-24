package magefree.designsystem.card

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import magefree.designsystem.theme.Sizing

/*
 * The card inspection interaction pattern — the touch equivalent of "hover to preview".
 *
 * Hover doesn't exist on a phone, so every card surface offers two gestures through one modifier:
 *   - a TAP that opens the full inspection view — the guaranteed floor, always available; and
 *   - an optional LONG-PRESS that peeks — a pure accelerator, never the only path to a card.
 *
 * The modifier is built on [combinedClickable], so both gestures are exposed to accessibility services
 * (each with its own action label) and the surface enforces the >= 48dp minimum touch target. It is
 * stateless: the caller owns what "open" and "peek" mean (e.g. showing a [FullCardView] or a transient
 * overlay); this only wires the gestures.
 */

/**
 * Makes any card surface inspectable: tap opens the full view, long-press peeks.
 *
 * Tap is the guaranteed path and is always wired; long-press is an optional accelerator that is only
 * active when [onLongPressPeek] is supplied. Both are announced to accessibility services with the
 * given labels, and the surface is padded up to the [Sizing.minTouchTarget] accessibility minimum.
 *
 * @param onTap invoked on tap — opens the full inspection view (the guaranteed path).
 * @param onLongPressPeek invoked on long-press to peek; when null, no long-press action is offered.
 * @param enabled when false, both gestures are disabled.
 * @param tapLabel accessibility label for the tap action (e.g. "Open card"); null uses the default.
 * @param peekLabel accessibility label for the long-press action (e.g. "Peek at card").
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.cardInspectable(
    onTap: () -> Unit,
    onLongPressPeek: (() -> Unit)? = null,
    enabled: Boolean = true,
    tapLabel: String? = null,
    peekLabel: String? = null,
): Modifier =
    this
        .defaultMinSize(minWidth = Sizing.minTouchTarget, minHeight = Sizing.minTouchTarget)
        .combinedClickable(
            enabled = enabled,
            onClickLabel = tapLabel,
            onLongClickLabel = peekLabel.takeIf { onLongPressPeek != null },
            onLongClick = onLongPressPeek,
            onClick = onTap,
        )
