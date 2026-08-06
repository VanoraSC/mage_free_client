package magefree.feature.decks.builder

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/** The tallest a curve bar can be; each column scales to the deck's busiest mana value. */
private val MaxBarHeight = 96.dp

/**
 * A compact mana-curve panel: one bar per mana-value bucket (0..5, then 6+), each scaled to the
 * deck's busiest column, with the copy count above and the mana value below. Non-land main spells
 * only. Stateless; the [curve] is computed by [BuilderViewModel].
 */
@Composable
fun ManaCurveView(
    curve: ManaCurve,
    modifier: Modifier = Modifier,
) {
    val max = curve.maxCount.coerceAtLeast(1)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.small)
                .semantics { contentDescription = curveDescription(curve) },
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalAlignment = Alignment.Bottom,
    ) {
        curve.buckets.forEach { bucket ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = bucket.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fraction = bucket.count.toFloat() / max
                Box(
                    modifier =
                        Modifier
                            .width(20.dp)
                            .height((MaxBarHeight.value * fraction).dp.coerceAtLeast(2.dp))
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                )
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun curveDescription(curve: ManaCurve): String {
    val parts = curve.buckets.joinToString(", ") { "${it.label} costs ${it.count}" }
    return "Mana curve of ${curve.total} spells: $parts"
}

@Preview(name = "Mana curve - light", showBackground = true, widthDp = 360)
@Preview(name = "Mana curve - dark", showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ManaCurvePreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ManaCurveView(
                curve =
                    ManaCurve(
                        buckets =
                            listOf(
                                ManaCurveBucket("0", 1),
                                ManaCurveBucket("1", 8),
                                ManaCurveBucket("2", 12),
                                ManaCurveBucket("3", 6),
                                ManaCurveBucket("4", 4),
                                ManaCurveBucket("5", 2),
                                ManaCurveBucket("6+", 3),
                            ),
                    ),
            )
        }
    }
}
