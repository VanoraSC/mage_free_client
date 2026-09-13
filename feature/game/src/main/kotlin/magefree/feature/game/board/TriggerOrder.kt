package magefree.feature.game.board

import magefree.network.game.GameCard

/*
 * Ordering the player's simultaneous triggers — the arrangement they make, and the questions it answers.
 *
 * **The server asks one question at a time, and asks it backwards.** `HumanPlayer.chooseTriggeredAbility`
 * sends *"Pick triggered ability (goes to the stack first)"* while more than one trigger is left, so N
 * triggers are N−1 questions, and the one picked goes on the stack *first* — to the bottom, resolving
 * *last*. Putting a trigger on the stack also runs its own targets and costs at once
 * (`docs/upstream-trigger-ordering.md` §1.3), so the ordering questions arrive separated by whatever the
 * chosen trigger asks, and a trigger that fires because of an earlier one joins the same round.
 *
 * **So the arrangement is held here and spent one question at a time.** The player lays their triggers out
 * the way the stack will read — the first one resolving first — and confirms once. Each ordering question
 * that follows is answered with the lowest trigger in that arrangement the question offers, whatever came
 * in between. A question offering a trigger the arrangement does not hold is not answered: that trigger
 * fired after the player arranged, and where it goes is theirs to say.
 */

/**
 * Equivalent triggers, drawn and moved as one.
 *
 * **Equivalent is the same ability from a card of the same name, aimed at the same things**: two Soul
 * Wardens' triggers are one group, and a Soul Warden's and a Soul's Attendant's are two, because they are
 * different abilities however alike they read. Targets count because a trigger can carry them before it is
 * on the stack — "destroy that creature" already names one — and two of those are not interchangeable.
 * Upstream's own silent ordering of identical triggers compares the same three things. Nothing inside a
 * group is ordered.
 *
 * @property abilityIds every trigger in the group, in the order the server listed them — the ids an
 *   ordering question is answered with.
 * @property card what the group draws: its first trigger.
 * @property ruleText the rule as upstream keys an auto-order rule by — see [autoOrderRule] — or `null` when
 *   the trigger carries no source name to write into it.
 */
data class TriggerGroupUi(
    val abilityIds: List<String>,
    val card: CardUi,
    val ruleText: String?,
) {
    /** How the group is told apart from the others in one question, for keys and test tags. */
    val id: String get() = abilityIds.first()
}

/** The triggers an ordering question carried, grouped — see [TriggerGroupUi] — in the order each group first appears. */
internal fun triggerGroups(cards: List<GameCard>): List<TriggerGroupUi> =
    cards
        .groupBy { card -> Triple(card.name, card.rules, card.targets) }
        .values
        .map { members ->
            TriggerGroupUi(
                abilityIds = members.map { it.id },
                card = members.first().toCardUi(),
                ruleText = members.first().autoOrderRule(),
            )
        }

/**
 * The trigger to put on the stack next, or `null` when [resolveOrder] cannot answer this question.
 *
 * @param resolveOrder every trigger the player arranged, **the first to resolve first** — the order a
 *   stack is read in.
 * @param offered the triggers the question offers.
 * @return the lowest of [offered] in [resolveOrder]: the server asks which goes onto the stack first, and
 *   first on resolves last. `null` when [offered] holds a trigger [resolveOrder] does not.
 */
internal fun nextTriggerToStack(
    resolveOrder: List<String>,
    offered: Collection<String>,
): String? {
    if (offered.isEmpty() || !resolveOrder.containsAll(offered)) return null
    return resolveOrder.lastOrNull { it in offered }
}

/**
 * [groups] with the ones already in [resolveOrder] kept in that order, and any it does not hold ahead of
 * them — a trigger that fired after the player arranged is the one to look at, so it is where reading starts.
 */
internal fun arrangedBy(
    groups: List<TriggerGroupUi>,
    resolveOrder: List<String>,
): List<TriggerGroupUi> {
    if (resolveOrder.isEmpty()) return groups
    val (known, fresh) = groups.partition { group -> group.abilityIds.any { it in resolveOrder } }
    return fresh + known.sortedBy { group -> group.abilityIds.minOf { id -> resolveOrder.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE } }
}

/**
 * The rule text upstream remembers an auto-order rule by: `Ability.getRule(sourceName)`, which is the
 * ability's rule with the source's name in place of `{this}`.
 *
 * An ordering question's card is upstream's `AbilityView`, whose rule is `ability.getRule()` — the same
 * text with `{this}` still in it — and whose name the bridge takes from its source card. So the key is
 * rebuilt exactly as the server builds it. `null` without a name: upstream throws on a rule still carrying
 * `{this}`.
 */
internal fun GameCard.autoOrderRule(): String? {
    val rule = rules.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (name.isBlank()) return null
    return rule.replace("{this}", name)
}
