package magefree.feature.game.table

import magefree.designsystem.card.CardPreviewAction
import magefree.designsystem.card.CardPreviewState
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.PlayableObject

/*
 * A planeswalker's abilities, as the buttons on its raised card.
 *
 * **Every loyalty ability is a button, and the server says which of them work.** Pete: *"grey them out so
 * they're always present, even if you can't use them."* Which abilities the card has is its rules text;
 * which may be activated right now is `canPlayObjects` — per object, each playable ability's id and
 * `ability.toString()` clipped to fifty characters, the bridge's `PlayableObject.abilityIds` and
 * `abilityNames`. An ability the server did not list is drawn greyed. Nothing here decides whether an
 * ability can be activated: Liliana, Death's Majesty's −3 is absent from the list while no creature card
 * is in the graveyard, because its `TargetCardInYourGraveyard` has nothing to choose.
 *
 * **What a loyalty ability's line looks like is upstream's.** `AbilityImpl.getRule` writes the costs, then
 * `": "`, then the effect. A `LoyaltyAbility` has one cost: a `PayLoyaltyCost`, whose text is
 * `Integer.toString(amount)` with a `+` put in front of a positive amount — `+1`, `0`, `-3`, with an ASCII
 * hyphen — or a `PayVariableLoyaltyCost`, whose text is `-X`. So a line starting with one of those and a
 * colon is a loyalty ability, and a static line that only mentions `+1/+1` is not.
 */

/**
 * One of a planeswalker's abilities, ready to be a button.
 *
 * @property abilityId the server's id for it when it can be activated right now — what the answer to its
 *   ability question names — and `null` when it cannot, which draws the button greyed.
 * @property label what the button says: the permanent's own rules line for it where there is one.
 * @property rule that rules line, or `null` where none matched, so the panel does not also list as text
 *   what it is already showing as a button.
 */
data class AbilityButton(
    val abilityId: String?,
    val label: String,
    val rule: String?,
) {
    /** Whether a press on it activates anything. */
    val canActivate: Boolean get() = abilityId != null
}

/**
 * The buttons for [card]'s abilities: every loyalty ability, in the card's own order, then any ability the
 * server is offering that is not one of them.
 *
 * **Only for a planeswalker.** That is what was asked for, and it is the permanent whose abilities are the
 * whole of what it does; every other permanent keeps its single Play button.
 *
 * **An offered ability finds its line through upstream's clip.** The name is cut at fifty characters with
 * `...` (`PlayableObjectStats.load`), so the clipped text is matched as the start of one of the
 * permanent's rules lines. One that matches no loyalty line — an ability something else granted — is still
 * a button, after the loyalty ones, named by its own line or its clipped name, or numbered when an older
 * bridge sent no name.
 *
 * @param activatable whether a press on this planeswalker would be a Play right now — the question its
 *   abilities are the answers to. Outside one, every ability is drawn and none can be pressed.
 */
internal fun abilityButtons(
    card: GameCard,
    playable: List<PlayableObject>,
    activatable: Boolean,
): List<AbilityButton> {
    if (CardType.Planeswalker !in card.cardTypes) return emptyList()
    val rules = card.rules.map { it.trim() }.filter { it.isNotEmpty() }
    val loyalty = rules.filter { LOYALTY_RULE.containsMatchIn(it) }
    val offered = if (activatable) playable.firstOrNull { it.objectId == card.id } else null

    val idForLine = mutableMapOf<String, String>()
    val others = mutableListOf<AbilityButton>()
    offered?.abilityIds?.forEachIndexed { index, abilityId ->
        val name =
            offered.abilityNames
                .getOrNull(index)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val line = name?.removeSuffix(CLIPPED_SUFFIX)?.let { start -> rules.firstOrNull { it.startsWith(start) && it !in idForLine } }
        if (line != null && line in loyalty) {
            idForLine[line] = abilityId
        } else {
            others += AbilityButton(abilityId = abilityId, label = line ?: name ?: "$UNNAMED_ABILITY_LABEL ${index + 1}", rule = line)
        }
    }
    return loyalty.map { line -> AbilityButton(abilityId = idForLine[line], label = line, rule = line) } + others
}

/**
 * This preview with [buttons] as its abilities' buttons.
 *
 * **They replace its single action only when one of them can be pressed** — then that action was a Play,
 * and the buttons are the answer to which ability it meant. A planeswalker none of whose abilities can be
 * pressed keeps whatever action it has, which is how one that is the target of a spell can still be picked.
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
        action = if (buttons.any { it.canActivate }) null else action,
        abilityActions =
            buttons.map { button ->
                CardPreviewAction(label = button.label, enabled = button.canActivate, onAct = { button.abilityId?.let(onPress) })
            },
    )
}

/** A loyalty ability's rules line: `PayLoyaltyCost`'s `+1`, `0` or `-3`, or `PayVariableLoyaltyCost`'s `-X`, then `": "`. */
private val LOYALTY_RULE = Regex("""^(?:[+-]?\d+|-X): """)

/** What upstream appends to an ability name it has clipped. */
private const val CLIPPED_SUFFIX = "..."

/** What a button for an ability the server named nothing says, before its number. */
private const val UNNAMED_ABILITY_LABEL = "Ability"
