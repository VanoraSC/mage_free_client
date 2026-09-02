package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.component.prompt.Prompt
import magefree.designsystem.component.prompt.PromptAction
import magefree.designsystem.component.prompt.PromptEmphasis
import magefree.designsystem.component.prompt.PromptState
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * The Prompt, over a stand-in board.
 *
 * The grey block behind each one is doing real work: the board-interactive state's whole promise is
 * that it leaves the board visible, and that cannot be judged against a blank page. The last example
 * is deliberately hostile — a headline and progress line longer than anything the server would send —
 * because a size budget is only worth having if it holds against the case that would break it.
 */

/** Every Prompt state, each over a stand-in battlefield. */
@Composable
internal fun PromptGallery(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        GalleryNote("Idle — quiet, but never empty: the phase and whose priority it is")
        OverBoard {
            Prompt(
                state = PromptState.Idle(turn = "Your turn", phase = "Main phase 1", priority = "Your priority"),
                onAction = {},
            )
        }

        GalleryNote("Asking — the server's own question, answered here, in the server's own wording")
        OverBoard {
            Prompt(
                state =
                    PromptState.Asking(
                        question = "Do you want to mulligan?",
                        detail = "You have drawn seven cards.",
                        actions =
                            listOf(
                                PromptAction("Mulligan", PromptEmphasis.Primary),
                                PromptAction("Keep"),
                            ),
                    ),
                onAction = {},
            )
        }

        GalleryNote("Board-interactive — the board is the thing being used, so the Prompt gets out of the way")
        OverBoard {
            Prompt(
                state =
                    PromptState.BoardInteractive(
                        headline = "Choose targets",
                        progress = "2 of 3 targets",
                        actions =
                            listOf(
                                PromptAction("Done", PromptEmphasis.Primary),
                                PromptAction("Cancel", PromptEmphasis.Cancel),
                            ),
                    ),
                onAction = {},
            )
        }

        GalleryNote("The same state with content chosen to break its size budget — it must still not grow")
        OverBoard {
            Prompt(
                state =
                    PromptState.BoardInteractive(
                        headline =
                            "Choose up to three target creatures an opponent controls that entered " +
                                "the battlefield this turn",
                        progress = "2 of 3 targets chosen, 1 remaining, 4 legal candidates",
                        actions =
                            listOf(
                                PromptAction("Confirm the selection", PromptEmphasis.Primary),
                                PromptAction("Cancel this choice", PromptEmphasis.Cancel),
                            ),
                    ),
                onAction = {},
            )
        }
    }
}

/**
 * A stand-in battlefield with the Prompt anchored over its bottom edge, which is where the board puts
 * it: one component, one position, reachable in landscape.
 */
@Composable
private fun OverBoard(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(StandInBoardHeight)
                .background(BoardSurface.ground, MageShapes.medium)
                .padding(Spacing.small),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // A block of "battlefield" the Prompt is supposed to leave alone.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(StandInZoneHeight)
                    .background(BoardSurface.zone, MageShapes.medium)
                    .align(Alignment.TopCenter),
        )
        content()
    }
}

/** A caption in the surrounding app theme, so the board's own colours never explain themselves. */
@Composable
private fun GalleryNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val StandInBoardHeight = 132.dp
private val StandInZoneHeight = 64.dp
