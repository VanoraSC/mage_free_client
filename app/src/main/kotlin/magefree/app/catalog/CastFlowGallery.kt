package magefree.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing
import magefree.feature.game.cast.CastPrompt
import magefree.feature.game.cast.CastPromptEvent
import magefree.network.game.AbilityChoice
import magefree.network.game.GamePrompt
import magefree.network.game.PromptOptions

/*
 * A cast, one question at a time.
 *
 * The point of seeing this is the **shape of the conversation**, which no single screenshot shows: a
 * cast is five or six separate questions, each answered as it is given, and the thing worth judging is
 * whether that reads as one act or as a chain of dialogs.
 *
 * The script is the sequence upstream actually asks, in upstream's order (see
 * `docs/upstream-cast-sequence.md` §1) — optional cost, then X, then targets, then one prompt per mana
 * source. Note that X comes **before** targets: that is the engine's order, not a choice made here.
 *
 * Watch the way out. It is there on the mana prompts and on the optional target, gone on X, and gone
 * on a target the server marked required — and where it is gone the surface says why, because a
 * missing button with no explanation is indistinguishable from a broken one.
 */

/** The scripted cast, driven by hand. */
@Composable
fun CastFlowGallery(modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<CastPromptEvent?>(null) }
    val prompt = CastScript[step]

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            MageSecondaryButton(
                text = if (step == CastScript.lastIndex) "Start again" else "Next question",
                onClick = {
                    step = if (step == CastScript.lastIndex) 0 else step + 1
                    last = null
                },
            )
            MageSecondaryButton(text = "Back", onClick = { if (step > 0) step-- }, enabled = step > 0)
        }

        Text(
            text = "${step + 1} of ${CastScript.size} — ${CastCaptions[step]}",
            style = MaterialTheme.typography.labelMedium,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(BoardSurface.ground, MageShapes.medium)
                    .padding(Spacing.small),
        ) {
            CastPrompt(prompt = prompt, onEvent = { last = it })
        }

        // What the surface reported, so a press can be seen to mean what it says rather than taken on
        // trust — the answer index in particular, which is the one that would silently pay a cost the
        // player declined.
        Text(
            text = last?.let { "Reported: $it" } ?: "Nothing pressed yet",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/** The prompts of one cast, in the order the engine asks them. */
private val CastScript: List<GamePrompt> =
    listOf(
        GamePrompt.Ask(
            message = "Pay the kicker cost of {2}{R}?",
            options =
                PromptOptions(
                    text =
                        mapOf(
                            PromptOptions.LEFT_BUTTON_TEXT to "Kick it",
                            PromptOptions.RIGHT_BUTTON_TEXT to "Just the spell",
                        ),
                ),
        ),
        GamePrompt.GetAmount(message = "Announce the value for {X}", min = 0, max = 5),
        GamePrompt.Target(message = "Choose up to two target creatures", isRequired = false),
        GamePrompt.Target(message = "Choose target creature", isRequired = true),
        GamePrompt.PlayMana(
            message = "Pay {3}{R}",
            options = PromptOptions(text = mapOf(PromptOptions.SPECIAL_BUTTON to "Convoke")),
        ),
        GamePrompt.ChooseAbility(
            message = "Choose a mana ability",
            choices =
                listOf(
                    AbilityChoice(abilityId = "sacred-foundry-red", text = "{T}: Add {R}"),
                    AbilityChoice(abilityId = "sacred-foundry-white", text = "{T}: Add {W}"),
                ),
        ),
        GamePrompt.PlayMana(message = "Pay {2}{R}"),
    )

/** What each step is there to show. */
private val CastCaptions: List<String> =
    listOf(
        "an optional cost, in the server's own wording — neither answer leaves the cast",
        "X, which cannot be taken back once announced. No way out is offered, and it says so",
        "optional targets — declining means \"I have chosen enough\", so the way out reads Done",
        "a required target, as a free cast produces. Nothing to press but the board",
        "paying, with convoke offered because the server offered it. Cancel is available again",
        "a dual land against a coloured cost — the server asked because the choice is real, and every " +
            "option it listed is offered",
        "the next mana prompt — one per source, each showing what is left to pay",
    )

/** The cast flow as a catalog section, titled to match the design system's own. */
@Composable
internal fun CastFlowSection() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        MageSectionHeader(text = "Cast flow")
        HorizontalDivider()
        CastFlowGallery()
    }
}
