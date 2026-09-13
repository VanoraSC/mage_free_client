package magefree.feature.game.board

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import magefree.designsystem.theme.Corner
import magefree.designsystem.theme.Spacing
import magefree.feature.cards.CardArtRenderer
import magefree.network.game.TriggerAutoOrder
import kotlin.math.roundToInt

/**
 * The player's simultaneous triggers, laid out the way the stack will read, dragged into order and put on
 * the stack with one press. `TriggerOrder.kt` is how one press answers the server's one-at-a-time questions.
 *
 * **Left resolves first**, the stack region's own reading order: the object about to happen is nearest the
 * left, where reading starts.
 *
 * **Equivalent triggers are one card with a count** — nine life-gain triggers from three cards are three
 * things to order, not nine.
 *
 * **Held, then dragged.** The row scrolls once the triggers outgrow it, and a plain drag is that scroll, so a
 * group is picked up by holding it a moment and then carried to its place.
 *
 * The arrangement is view state until the press: nothing is sent while the player is still moving things.
 * It is keyed on the triggers the question offers, so a new question never inherits an old arrangement —
 * the ViewModel hands a later question over already laid out as the player left it.
 */
@Composable
internal fun TriggerOrderPanel(
    controls: PromptControlsUi.TriggerOrder,
    artRenderer: CardArtRenderer,
    onAction: (BoardAction) -> Unit,
) {
    if (controls.isPlacing) {
        Note(text = PLACING_TRIGGERS_NOTE)
        return
    }

    val offered = controls.triggerGroups.map { it.abilityIds }
    var order by remember(offered) { mutableStateOf(controls.triggerGroups) }
    // The auto-order rules set from here, so each group says which one it has.
    var pinned by remember(offered) { mutableStateOf(emptyMap<String, TriggerAutoOrder>()) }
    var dragging by remember(offered) { mutableStateOf<String?>(null) }
    var dragOffset by remember(offered) { mutableFloatStateOf(0f) }
    // One place along the row: a card and the gap after it.
    val slotPx = with(LocalDensity.current) { (CandidateCardWidth + Spacing.small).toPx() }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(TriggerOrderTestTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EndLabel(text = RESOLVES_FIRST_LABEL)
            EndLabel(text = RESOLVES_LAST_LABEL)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            order.forEach { group ->
                key(group.id) {
                    val carried = dragging == group.id
                    Column(
                        modifier =
                            Modifier
                                .width(CandidateCardWidth)
                                // The carried card is drawn over its neighbours and follows the finger;
                                // its place in the row changes each time it crosses half of one.
                                .zIndex(if (carried) 1f else 0f)
                                .graphicsLayer { translationX = if (carried) dragOffset else 0f }
                                .testTag(TriggerOrderTestTags.group(group.id))
                                .pointerInput(group.id, slotPx) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragging = group.id
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            dragging = null
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragging = null
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.x
                                            val from = order.indexOfFirst { it.id == group.id }
                                            val to = (from + (dragOffset / slotPx).roundToInt()).coerceIn(0, order.lastIndex)
                                            if (from >= 0 && to != from) {
                                                order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                dragOffset -= (to - from) * slotPx
                                            }
                                        },
                                    )
                                },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            BoardCardFace(card = group.card, artRenderer = artRenderer, modifier = Modifier.padding(Spacing.extraSmall))
                            if (group.abilityIds.size > 1) {
                                Surface(
                                    shape = RoundedCornerShape(Corner.small),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.extraSmall),
                                ) {
                                    Text(
                                        text = "×${group.abilityIds.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier =
                                            Modifier
                                                .padding(
                                                    horizontal = Spacing.extraSmall,
                                                ).testTag(TriggerOrderTestTags.count(group.id)),
                                    )
                                }
                            }
                        }
                        Text(
                            text = group.card.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // A standing rule needs the rule text upstream keys it by; without one there is nothing
                        // the server could remember it as.
                        group.ruleText?.let { rule ->
                            AlwaysButton(
                                label = ALWAYS_RESOLVE_FIRST_LABEL,
                                isSet = pinned[group.id] == TriggerAutoOrder.ResolveFirst,
                                tag = TriggerOrderTestTags.alwaysFirst(group.id),
                            ) {
                                onAction(BoardAction.AlwaysOrderTrigger(ruleText = rule, order = TriggerAutoOrder.ResolveFirst))
                                pinned = pinned + (group.id to TriggerAutoOrder.ResolveFirst)
                                order = listOf(group) + order.filterNot { it.id == group.id }
                            }
                            AlwaysButton(
                                label = ALWAYS_RESOLVE_LAST_LABEL,
                                isSet = pinned[group.id] == TriggerAutoOrder.ResolveLast,
                                tag = TriggerOrderTestTags.alwaysLast(group.id),
                            ) {
                                onAction(BoardAction.AlwaysOrderTrigger(ruleText = rule, order = TriggerAutoOrder.ResolveLast))
                                pinned = pinned + (group.id to TriggerAutoOrder.ResolveLast)
                                order = order.filterNot { it.id == group.id } + group
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onAction(BoardAction.OrderTriggers(resolveOrder = order.flatMap { it.abilityIds })) },
            modifier = Modifier.testTag(TriggerOrderTestTags.CONFIRM),
        ) {
            Text(text = PUT_ON_STACK_LABEL, maxLines = 1)
        }
    }
}

@Composable
private fun EndLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** One standing rule under a group; a check says it is the one set. */
@Composable
private fun AlwaysButton(
    label: String,
    isSet: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.extraSmall, vertical = 0.dp),
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            text = if (isSet) "✓ $label" else label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/** Test tags for the trigger-ordering panel; a group is named by its first trigger's id. */
object TriggerOrderTestTags {
    const val PANEL: String = "trigger-order"
    const val CONFIRM: String = "trigger-order-confirm"

    fun group(id: String): String = "trigger-group-$id"

    fun count(id: String): String = "trigger-group-count-$id"

    fun alwaysFirst(id: String): String = "trigger-always-first-$id"

    fun alwaysLast(id: String): String = "trigger-always-last-$id"
}
