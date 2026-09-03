package magefree.feature.game.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import magefree.designsystem.component.prompt.AmountPicker
import magefree.designsystem.component.prompt.Prompt
import magefree.designsystem.component.prompt.PromptAction
import magefree.designsystem.component.prompt.PromptEmphasis
import magefree.designsystem.component.prompt.PromptState
import magefree.designsystem.theme.Spacing
import magefree.network.game.GamePrompt

/*
 * A cast's questions, put to the player one at a time.
 *
 * The server asks a sequence and each answer is submitted as it is given, so this renders whichever
 * question is outstanding rather than assembling anything. What it changes is the **form**: a mana
 * prompt is the board asking to be tapped, not a list of land names in a dialog, and 0097's Prompt is
 * where the words go.
 *
 * The one rule it exists to keep is that a way out appears only where the server accepts one. That
 * decision is made in [castPromptModel] and tested there; this file draws the result.
 */

/** What the player did with the cast prompt. */
sealed interface CastPromptEvent {
    /** Decline the prompt — abandon a payment, or finish choosing optional targets. */
    data object Exit : CastPromptEvent

    /** Use a special mana action: convoke, improvise, delve. */
    data object Special : CastPromptEvent

    /**
     * Answer a yes/no.
     *
     * @property affirmative index 0 of the model's answers — the contract [CastPromptModel.answers]
     *   documents, resolved here so no caller has to remember which button was which.
     */
    data class Answer(
        val affirmative: Boolean,
    ) : CastPromptEvent

    /** Announce a number. */
    data class Amount(
        val value: Int,
    ) : CastPromptEvent
}

/** Test tags for the cast surface. */
object CastPromptTestTags {
    const val SURFACE: String = "cast-prompt"
    const val AMOUNT: String = "cast-prompt-amount"
}

/**
 * Renders the outstanding [prompt] of a cast.
 *
 * @param prompt the server's question, straight from `GameState.prompt`.
 * @param onEvent what the player did.
 * @param modifier the [Modifier] for the surface.
 */
@Composable
fun CastPrompt(
    prompt: GamePrompt,
    onEvent: (CastPromptEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = castPromptModel(prompt)
    val exit = model.exit

    // Seeded to the bottom of the range and re-seeded when the range changes, which is what a new
    // question means. Announcing the minimum is the least committal answer available, and there is no
    // way out of this prompt to fall back on.
    var amount by remember(model.amount) { mutableIntStateOf(model.amount?.first ?: 0) }

    val actions =
        buildList {
            model.answers.forEachIndexed { index, label ->
                add(PromptAction(label = label, emphasis = if (index == 0) PromptEmphasis.Primary else PromptEmphasis.Secondary))
            }
            model.special?.let { add(PromptAction(label = it, emphasis = PromptEmphasis.Secondary)) }
            model.amount?.let { add(PromptAction(label = ANNOUNCE, emphasis = PromptEmphasis.Primary)) }
            if (exit is CastExit.Offered) add(PromptAction(label = exit.label, emphasis = PromptEmphasis.Cancel))
        }

    // Why there is no way out is shown, not swallowed. A missing button with no explanation is
    // indistinguishable from a broken one, and this is the moment a player is most likely to look for
    // the one that is not there.
    val note = (exit as? CastExit.NotAccepted)?.because

    Column(
        modifier = modifier.fillMaxWidth().testTag(CastPromptTestTags.SURFACE),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Prompt(
            state =
                if (model.choosesOnBoard) {
                    // The board carries the choosing; the Prompt only says what is being chosen and
                    // how far through it is.
                    PromptState.BoardInteractive(headline = model.headline, progress = note, actions = actions)
                } else {
                    PromptState.Asking(question = model.headline, detail = note, actions = actions)
                },
            onAction = { action ->
                when {
                    exit is CastExit.Offered && action.label == exit.label -> onEvent(CastPromptEvent.Exit)
                    action.label == model.special -> onEvent(CastPromptEvent.Special)
                    action.label == ANNOUNCE -> onEvent(CastPromptEvent.Amount(amount))
                    else -> onEvent(CastPromptEvent.Answer(affirmative = action.label == model.answers.firstOrNull()))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        model.amount?.let { range ->
            AmountPicker(
                value = amount,
                range = range,
                onValueChange = { amount = it },
                modifier = Modifier.testTag(CastPromptTestTags.AMOUNT),
            )
        }
    }
}

/** The label on the button that commits a number. */
internal const val ANNOUNCE: String = "Announce"
