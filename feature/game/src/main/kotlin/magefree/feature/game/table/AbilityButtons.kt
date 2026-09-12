package magefree.feature.game.table

import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewState
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.PlayableObject

/*
 * A planeswalker's abilities, as the buttons on its raised card.
 *
 * **Which abilities may be pressed is the server's answer, already on the wire.** `canPlayObjects` names,
 * per object, each playable ability's id and `ability.toString()` clipped to fifty characters — the
 * bridge's `PlayableObject.abilityIds` and `abilityNames`. Nothing here decides whether an ability can be
 * activated; it draws the ones the server listed.
 */

/**
 * One offered ability, ready to be a button.
 *
 * @property abilityId the server's id for it — what the answer to its ability question names.
 * @property label what the button says: the permanent's own rules line for it where one matches.
 * @property rule that rules line, or `null` where none matched, so the panel does not also list as text
 *   what it is already showing as a button.
 */
data class AbilityButton(
    val abilityId: String,
    val label: String,
    val rule: String?,
)

/**
 * The buttons for [card]'s abilities the server is offering, in the server's order.
 *
 * **Only for a planeswalker.** That is what was asked for, and it is the permanent whose abilities are
 * the whole of what it does; every other permanent keeps its single Play button.
 *
 * **The label is the full rules line where one can be found.** Upstream clips the name at fifty
 * characters with `...` (`PlayableObjectStats.load`), so the clipped text is matched as the start of one
 * of the permanent's own rules lines. An ability whose name matches none keeps the clipped name, and one
 * with no name at all — an older bridge sends none — is numbered.
 */
internal fun abilityButtons(
    card: GameCard,
    playable: List<PlayableObject>,
): List<AbilityButton> {
    if (CardType.Planeswalker !in card.cardTypes) return emptyList()
    val offered = playable.firstOrNull { it.objectId == card.id } ?: return emptyList()
    val rules = card.rules.map { it.trim() }.filter { it.isNotEmpty() }
    return offered.abilityIds.mapIndexed { index, abilityId ->
        val name =
            offered.abilityNames
                .getOrNull(index)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val rule = name?.removeSuffix(CLIPPED_SUFFIX)?.let { start -> rules.firstOrNull { it.startsWith(start) } }
        AbilityButton(abilityId = abilityId, label = rule ?: name ?: "$UNNAMED_ABILITY_LABEL ${index + 1}", rule = rule)
    }
}

/**
 * This preview with [buttons] as its abilities' buttons, in place of its single action.
 *
 * A rules line that became a button is taken out of the text above it: the same sentence twice, once to
 * read and once to press, is the panel saying one thing two ways.
 */
internal fun CardPreviewState.withAbilityButtons(
    buttons: List<AbilityButton>,
    onPress: (String) -> Unit,
): CardPreviewState {
    if (buttons.isEmpty()) return this
    val shown = buttons.mapNotNull { it.rule }.toSet()
    return copy(
        abilities = abilities.filterNot { it.trim() in shown },
        action = null,
        abilityActions = buttons.map { button -> CardPreviewAction(label = button.label, onAct = { onPress(button.abilityId) }) },
    )
}

/** What upstream appends to an ability name it has clipped. */
private const val CLIPPED_SUFFIX = "..."

/** What a button for an ability the server named nothing says, before its number. */
private const val UNNAMED_ABILITY_LABEL = "Ability"
