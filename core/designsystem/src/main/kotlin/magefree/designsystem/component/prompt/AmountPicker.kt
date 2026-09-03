package magefree.designsystem.component.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import magefree.designsystem.board.BoardSurface
import magefree.designsystem.board.BoardTypography
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * A number, picked between two bounds.
 *
 * The board needs one because some questions genuinely are numbers — announcing X, saying how many
 * times to repeat something. Those arrive as their own prompt with the server's own `min` and `max`,
 * and the bounds are not decoration: a value outside them is refused and the question asked again, so
 * a control that could produce one would be a control that wastes the player's turn.
 *
 * Deliberately a stepper rather than a free text field. The ranges are small — X is bounded by the
 * mana actually available — and a stepper cannot produce a value the server will reject, which a
 * keyboard can.
 */

/** Test tags for [AmountPicker]. */
object AmountPickerTestTags {
    const val PICKER: String = "amount-picker"
    const val VALUE: String = "amount-picker-value"
    const val LESS: String = "amount-picker-less"
    const val MORE: String = "amount-picker-more"
}

/**
 * Picks a number in [range], showing the bounds so the player can see what is on offer.
 *
 * @param value the current choice. Clamped into [range] on the way out, never silently on the way in —
 *   a caller holding a value outside the bounds has a bug worth seeing.
 * @param range the server's own `min..max`.
 * @param onValueChange invoked with the new value; already clamped.
 * @param modifier the [Modifier] for the row.
 */
@Composable
fun AmountPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(BoardSurface.zone, MageShapes.medium)
                .padding(horizontal = Spacing.small, vertical = Spacing.extraSmall)
                .testTag(AmountPickerTestTags.PICKER),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MageSecondaryButton(
            text = "−",
            onClick = { onValueChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
            modifier = Modifier.testTag(AmountPickerTestTags.LESS),
        )

        Box(modifier = Modifier.widthIn(min = ValueWidth), contentAlignment = Alignment.Center) {
            Text(
                text = value.toString(),
                style = BoardTypography.vitals,
                color = BoardSurface.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(AmountPickerTestTags.VALUE),
            )
        }

        MageSecondaryButton(
            text = "+",
            onClick = { onValueChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
            modifier = Modifier.testTag(AmountPickerTestTags.MORE),
        )

        // The bounds, shown rather than merely enforced: "why is + greyed out" should not be a question.
        Text(
            text = "${range.first}–${range.last}",
            style = BoardTypography.annotation,
            color = BoardSurface.onSurfaceMuted,
        )
    }
}

private val ValueWidth = 32.dp
