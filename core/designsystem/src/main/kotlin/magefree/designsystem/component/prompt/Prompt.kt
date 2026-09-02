package magefree.designsystem.component.prompt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardDuration
import magefree.designsystem.board.BoardEasing
import magefree.designsystem.board.BoardSignal
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.board.LocalMotionScale

/*
 * The Prompt: one component, one position, three states.
 *
 * It is where a player learns what the game wants, so it is always the same thing in the same place.
 * What changes is how loudly it speaks:
 *
 * - **Idle** — whose priority it is and what phase we are in. Low contrast, because nothing is being
 *   asked; but never empty, because those are the two facts a player checks constantly, and an empty
 *   Idle turns every phase question into a hunt somewhere else on screen.
 * - **Asking** — the server wants a decision that can be answered here. High contrast, and it states
 *   the question in the server's own words.
 * - **Board-interactive** — the decision needs the board touched, so the Prompt gets out of the way:
 *   a headline, the progress through the choice, and the controls that end it. **It never covers the
 *   board**, which is the requirement the whole component is designed around rather than a property
 *   it happens to have.
 *
 * It renders a presentation model and holds no game types. Turning a server prompt into one of these
 * is the board's job, and it is a substantial one — which is exactly why it does not belong here.
 */

/** How much weight an action carries, expressed by style rather than by colour alone. */
enum class PromptEmphasis {
    /** The action the player most likely wants. One per prompt, at most. */
    Primary,

    /** An ordinary alternative. */
    Secondary,

    /** Backing out. Offered only when the server would actually accept it. */
    Cancel,
}

/**
 * One thing the player can do from the Prompt.
 *
 * @param label what the button says. Where the server supplied its own wording — "Mulligan", "Keep",
 *   "Done" — that is what belongs here, because the server's phrasing is part of the question.
 */
data class PromptAction(
    val label: String,
    val emphasis: PromptEmphasis = PromptEmphasis.Secondary,
)

/**
 * What the Prompt is saying right now.
 *
 * There is no queue and no backlog: upstream blocks the game on the answer, so at most one question
 * is outstanding per seat and a new one replaces the old.
 */
sealed interface PromptState {
    /**
     * Nothing is being asked. Carries the two facts a player checks constantly.
     *
     * @param phase where in the turn we are.
     * @param priority whose priority it is, or null when that is not meaningful yet.
     */
    data class Idle(
        val phase: String,
        val priority: String? = null,
    ) : PromptState

    /**
     * The server wants a decision that can be answered from here.
     *
     * @param question the server's own words.
     * @param detail extra server-supplied text below the question, when it sent any.
     * @param actions how it can be answered. A Cancel appears here only when declining is real.
     */
    data class Asking(
        val question: String,
        val detail: String? = null,
        val actions: List<PromptAction> = emptyList(),
    ) : PromptState

    /**
     * The decision needs the board touched — choosing targets, declaring attackers or blockers.
     *
     * @param headline what is being chosen, kept short because this state has a size budget.
     * @param progress how far through the choice the player is, e.g. "2 of 3 targets".
     * @param actions what ends the choice — typically Confirm, and Cancel when declining is real.
     */
    data class BoardInteractive(
        val headline: String,
        val progress: String? = null,
        val actions: List<PromptAction> = emptyList(),
    ) : PromptState
}

/**
 * The Prompt.
 *
 * @param state what the game is currently saying.
 * @param onAction invoked with the action the player chose.
 * @param modifier the [Modifier] for the Prompt. The caller places it; the Prompt owns how much room
 *   it takes within that placement, which is what keeps the board-interactive promise enforceable.
 */
@Composable
fun Prompt(
    state: PromptState,
    onAction: (PromptAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = LocalMotionScale.current
    val duration = scale.scale(BoardDuration.ZONE_MOVE)

    AnimatedContent(
        targetState = state,
        modifier = modifier.testTag(PromptTestTags.PROMPT),
        transitionSpec = {
            // A prompt changing is a game event, so the change is shown rather than swapped in. It
            // crossfades on the zone-move duration: the Prompt is a thing arriving, not a value
            // updating in place.
            fadeIn(tween(durationMillis = duration, easing = BoardEasing.enter)) togetherWith
                fadeOut(tween(durationMillis = duration, easing = BoardEasing.exit))
        },
        contentKey = { it::class },
        label = "prompt",
    ) { current ->
        when (current) {
            is PromptState.Idle -> IdlePrompt(current)
            is PromptState.Asking -> AskingPrompt(current, onAction)
            is PromptState.BoardInteractive -> BoardInteractivePrompt(current, onAction)
        }
    }
}

/** Quiet, and never empty: the phase and whose priority it is. */
@Composable
private fun IdlePrompt(state: PromptState.Idle) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PromptShape)
                .background(BoardSurface.zone.copy(alpha = IDLE_OPACITY))
                .padding(horizontal = PromptPadding, vertical = IdleVerticalPadding)
                .testTag(PromptTestTags.IDLE),
        horizontalArrangement = Arrangement.spacedBy(PromptPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.phase,
            style = BoardTypography.promptBody,
            color = BoardSurface.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.priority?.let { priority ->
            Text(
                text = priority,
                style = BoardTypography.promptBody,
                color = BoardSurface.onSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/** Loud, because a decision is wanted and it can be answered right here. */
@Composable
private fun AskingPrompt(
    state: PromptState.Asking,
    onAction: (PromptAction) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PromptShape)
                .background(BoardSurface.floating)
                .padding(PromptPadding)
                .testTag(PromptTestTags.ASKING),
        verticalArrangement = Arrangement.spacedBy(PromptPadding),
    ) {
        Text(
            text = state.question,
            style = BoardTypography.promptTitle,
            color = BoardSurface.onSurface,
        )
        state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(text = detail, style = BoardTypography.promptBody, color = BoardSurface.onSurfaceMuted)
        }
        PromptActions(actions = state.actions, onAction = onAction)
    }
}

/**
 * Small, because the board is the thing being used.
 *
 * The height cap is the component's promise. Everything inside is single-line and elides rather than
 * wrapping, so no combination of a long headline, a long progress line and several actions can push
 * this over a board it is supposed to be leaving alone.
 */
@Composable
private fun BoardInteractivePrompt(
    state: PromptState.BoardInteractive,
    onAction: (PromptAction) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = BoardInteractiveMaxHeight)
                .clip(PromptShape)
                .background(BoardSurface.floating)
                .padding(horizontal = PromptPadding, vertical = IdleVerticalPadding)
                .testTag(PromptTestTags.BOARD_INTERACTIVE),
        horizontalArrangement = Arrangement.spacedBy(PromptPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.headline,
                style = BoardTypography.promptBody,
                color = BoardSurface.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.progress?.let { progress ->
                Text(
                    text = progress,
                    style = BoardTypography.counter,
                    color = BoardSignal.targeting,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(PromptTestTags.PROGRESS),
                )
            }
        }
        state.actions.forEach { action ->
            PromptButton(action = action, onAction = onAction, compact = true)
        }
    }
}

/** The row of answers. */
@Composable
private fun PromptActions(
    actions: List<PromptAction>,
    onAction: (PromptAction) -> Unit,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PromptPadding),
    ) {
        actions.forEach { action ->
            PromptButton(action = action, onAction = onAction, modifier = Modifier.weight(1f))
        }
    }
}

/** One answer. Emphasis is carried by fill and border, never by colour alone. */
@Composable
private fun PromptButton(
    action: PromptAction,
    onAction: (PromptAction) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val background =
        when (action.emphasis) {
            PromptEmphasis.Primary -> BoardSignal.playable
            PromptEmphasis.Secondary -> BoardSurface.cardRaised
            PromptEmphasis.Cancel -> Color.Transparent
        }
    val content =
        when (action.emphasis) {
            PromptEmphasis.Primary -> BoardSurface.ground
            PromptEmphasis.Secondary -> BoardSurface.onSurface
            PromptEmphasis.Cancel -> BoardSurface.onSurfaceMuted
        }

    Box(
        modifier =
            modifier
                .clip(PromptShape)
                .background(background)
                .clickable { onAction(action) }
                .defaultMinSize(minHeight = if (compact) CompactActionHeight else ActionHeight)
                .padding(horizontal = PromptPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            style = BoardTypography.promptBody,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Test tags for the states and the parts that carry no distinctive text. */
object PromptTestTags {
    const val PROMPT: String = "prompt"
    const val IDLE: String = "prompt-idle"
    const val ASKING: String = "prompt-asking"
    const val BOARD_INTERACTIVE: String = "prompt-board-interactive"
    const val PROGRESS: String = "prompt-progress"
}

/**
 * The most vertical room the board-interactive state may take.
 *
 * This is the component's contract with the board, which is why it is a named constant rather than a
 * consequence of whatever the content happens to be. A board-interactive prompt is on screen exactly
 * when the player needs to see and touch the permanents it is asking about.
 */
val BoardInteractiveMaxHeight: Dp = 56.dp

/** How present the Idle state is: legible, but not competing with a board that is being read. */
private const val IDLE_OPACITY = 0.72f

private val PromptShape = RoundedCornerShape(6.dp)
private val PromptPadding = 8.dp
private val IdleVerticalPadding = 4.dp
private val ActionHeight = 40.dp
private val CompactActionHeight = 32.dp
