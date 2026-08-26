package magefree.designsystem.component.prompt

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.theme.Corner
import magefree.designsystem.theme.Elevation
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/*
 * The decision / prompt surface — the reusable "decisions come to the player" primitive.
 *
 * [DecisionPrompt] is a stateless, bottom-anchored surface that surfaces a required choice where the
 * thumb naturally rests: a title, an optional message, and a set of full-width choice actions. The
 * primary choice is given clear (filled) emphasis and receives default focus; the title/message are
 * merged into a single accessibility announcement (marked as a heading) so the prompt reads as one
 * unit, while each choice remains an individually actionable, >= 48dp target. Emphasis is expressed
 * by button style (not color alone). Targeting / choices UI renders into this primitive.
 *
 * Placement: the surface fills its width and is meant to sit at the bottom of the screen (e.g. a
 * Scaffold bottom slot, or aligned to the bottom of a Box) — see the preview.
 */

/** The relative emphasis of a [DecisionPromptChoice], mapped to a button style (never color-only). */
enum class DecisionEmphasis {
    /** The dominant, recommended choice — filled button, granted default focus. */
    Primary,

    /** A supporting choice — tonal button. */
    Secondary,

    /** A low-emphasis / dismissive choice — text button. */
    Tertiary,
}

/**
 * A single choice presented by a [DecisionPrompt].
 *
 * @param label the visible action label.
 * @param contentDescription an optional accessibility override; when null the [label] is announced.
 * @param emphasis the relative emphasis (default [DecisionEmphasis.Secondary]).
 */
data class DecisionPromptChoice(
    val label: String,
    val contentDescription: String? = null,
    val emphasis: DecisionEmphasis = DecisionEmphasis.Secondary,
) {
    /** The name announced to accessibility services: the explicit description, else the label. */
    val accessibleLabel: String
        get() = contentDescription ?: label
}

/**
 * Builds the merged accessibility description announced for the prompt header: the [title] followed
 * by the [message] when present. Pure logic, unit-tested.
 */
internal fun buildPromptDescription(
    title: String,
    message: String?,
): String = if (message.isNullOrBlank()) title else "$title. $message"

/**
 * The bottom-anchored decision prompt.
 *
 * @param title the prompt's question / headline.
 * @param choices the available choices, rendered top-to-bottom in the given order and styled by their
 *   [DecisionEmphasis]; the first [DecisionEmphasis.Primary] choice receives default focus.
 * @param onChoice invoked with the chosen [DecisionPromptChoice] when a choice is tapped.
 * @param modifier the [Modifier] for the surface.
 * @param message an optional supporting message beneath the [title].
 */
@Composable
fun DecisionPrompt(
    title: String,
    choices: List<DecisionPromptChoice>,
    onChoice: (DecisionPromptChoice) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val primaryIndex = choices.indexOfFirst { it.emphasis == DecisionEmphasis.Primary }
    val focusRequester = remember { FocusRequester() }
    if (primaryIndex >= 0) {
        LaunchedEffect(primaryIndex) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Corner.extraLarge, topEnd = Corner.extraLarge),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.level3,
        shadowElevation = Elevation.level3,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Column(
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        heading()
                        contentDescription = buildPromptDescription(title, message)
                    },
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                choices.forEachIndexed { index, choice ->
                    val choiceModifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (index == primaryIndex) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                            ).semantics(mergeDescendants = true) {
                                contentDescription = choice.accessibleLabel
                            }
                    DecisionChoiceButton(
                        choice = choice,
                        onClick = { onChoice(choice) },
                        modifier = choiceModifier,
                    )
                }
            }
        }
    }
}

/** Renders one choice with the button style matching its [DecisionEmphasis]. */
@Composable
private fun DecisionChoiceButton(
    choice: DecisionPromptChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (choice.emphasis) {
        DecisionEmphasis.Primary ->
            MagePrimaryButton(text = choice.label, onClick = onClick, modifier = modifier)
        DecisionEmphasis.Secondary ->
            MageSecondaryButton(text = choice.label, onClick = onClick, modifier = modifier)
        DecisionEmphasis.Tertiary ->
            MageTextButton(text = choice.label, onClick = onClick, modifier = modifier)
    }
}

private val PreviewChoices =
    listOf(
        DecisionPromptChoice(label = "Cast it", emphasis = DecisionEmphasis.Primary),
        DecisionPromptChoice(label = "Save for later", emphasis = DecisionEmphasis.Secondary),
        DecisionPromptChoice(
            label = "Pass",
            contentDescription = "Pass priority",
            emphasis = DecisionEmphasis.Tertiary,
        ),
    )

@Preview(name = "DecisionPrompt - light", showBackground = true, heightDp = 480)
@Preview(
    name = "DecisionPrompt - dark",
    showBackground = true,
    heightDp = 480,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DecisionPromptPreview() {
    MageTheme {
        // Demonstrates the intended placement: anchored to the bottom (thumb reach).
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            DecisionPrompt(
                title = "You may cast Lightning Bolt",
                message = "Deal 3 damage to any target, or hold priority.",
                choices = PreviewChoices,
                onChoice = {},
            )
        }
    }
}

@Preview(name = "DecisionPrompt - title only", showBackground = true, heightDp = 320)
@Composable
private fun DecisionPromptTitleOnlyPreview() {
    MageTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            DecisionPrompt(
                title = "Choose a target",
                choices =
                    listOf(
                        DecisionPromptChoice(label = "Opposing creature", emphasis = DecisionEmphasis.Primary),
                        DecisionPromptChoice(label = "Your creature", emphasis = DecisionEmphasis.Secondary),
                    ),
                onChoice = {},
            )
        }
    }
}
