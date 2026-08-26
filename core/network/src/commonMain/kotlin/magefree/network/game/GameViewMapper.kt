package magefree.network.game

import magefree.protocol.AskPrompt
import magefree.protocol.CardTypeCode
import magefree.protocol.ChooseAbilityPrompt
import magefree.protocol.ChooseChoicePrompt
import magefree.protocol.ChoosePilePrompt
import magefree.protocol.GameAbilityChoice
import magefree.protocol.GameCardView
import magefree.protocol.GameChoiceOption
import magefree.protocol.GameCombatGroupView
import magefree.protocol.GameManaPoolView
import magefree.protocol.GameMultiAmountEntry
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GamePromptOptions
import magefree.protocol.GameStateView
import magefree.protocol.GameZoneView
import magefree.protocol.GetAmountPrompt
import magefree.protocol.GetMultiAmountPrompt
import magefree.protocol.ManaTypeCode
import magefree.protocol.PhaseStepCode
import magefree.protocol.PlayManaPrompt
import magefree.protocol.PlayXManaPrompt
import magefree.protocol.PlayerActionCode
import magefree.protocol.SelectPrompt
import magefree.protocol.TargetPrompt
import magefree.protocol.TurnPhaseCode
import magefree.protocol.UnknownGamePrompt
import magefree.protocol.GamePrompt as GamePromptMessage

/**
 * The single boundary where the wire game shapes become the app-schema ones (and the
 * app-schema enums become wire codes for the reply path). It is `internal` and total: every `when` here
 * is exhaustive over a closed `:protocol` set, so a future additive change upstream is a **compile**
 * error at this one file rather than a silent runtime hole further out.
 *
 * It holds no state and touches no client, so it is unit-testable on its own — and because
 * [GameEventFold] delegates all shape conversion here, the fold stays a pure state machine.
 */
internal object GameViewMapper {
    /**
     * Apply a wire snapshot to [state], **replacing** every field the snapshot owns. Nothing is merged:
     * the server sends the whole `GameView` each time, so a value missing from this snapshot is genuinely
     * absent rather than "unchanged" (see the file header of [GameState]).
     *
     * The fields *not* owned by a snapshot — [GameState.lastMessage], [GameState.lastError],
     * [GameState.result], [GameState.isWatching] — are left alone here and moved only by the event that
     * produces them, in [GameEventFold].
     *
     * [GameState.prompt] is the **one narrow exception**: [resyncPrompt], when non-null, is
     * this session's last-cached outstanding question (`GameStateSnapshot.prompt`, itself only ever set
     * from a real `GamePrompted` the bridge relayed — see `GameStateCache`'s own KDoc for why re-serving
     * it is correct rather than a guess). A resync **restores** a prompt that would otherwise be missing;
     * it never **clears** one a live push already set — [resyncPrompt] being `null` leaves [state]'s
     * existing [GameState.prompt] alone, it does not null it out.
     */
    fun apply(
        state: GameState,
        view: GameStateView,
        resyncPrompt: GamePromptMessage? = null,
    ): GameState =
        state.copy(
            turn = view.turn,
            phase = view.phase.toPhase(),
            step = view.step.toStep(),
            activePlayerId = view.activePlayerId,
            activePlayerName = view.activePlayerName,
            priorityPlayerName = view.priorityPlayerName,
            viewerPlayerId = view.viewerPlayerId,
            viewerHasPriority = view.viewerHasPriority,
            players = view.players.map { it.toPlayer() },
            hand = view.hand.map { it.toCard() },
            stack = view.stack.map { it.toCard() },
            exile = view.exile.map { it.toZone() },
            revealed = view.revealed.map { it.toZone() },
            combat = view.combat.map { it.toCombatGroup() },
            playable = view.playable.map { it.toPlayable() },
            specialActionsAvailable = view.specialActionsAvailable,
            priorityTimeSeconds = view.priorityTimeSeconds,
            bufferTimeSeconds = view.bufferTimeSeconds,
            hasSnapshot = true,
            prompt = resyncPrompt?.let { toPrompt(it) } ?: state.prompt,
        )

    /** The app-schema face of a wire prompt. Total over the closed set. */
    fun toPrompt(prompt: GamePromptMessage): GamePrompt =
        when (prompt) {
            is SelectPrompt -> GamePrompt.Select(message = prompt.message, options = prompt.options.toOptions())
            is TargetPrompt ->
                GamePrompt.Target(
                    message = prompt.message,
                    cards = prompt.cards.map { it.toCard() },
                    targetIds = prompt.targetIds,
                    isRequired = prompt.required,
                    options = prompt.options.toOptions(),
                )

            is AskPrompt -> GamePrompt.Ask(message = prompt.message, options = prompt.options.toOptions())
            is ChooseAbilityPrompt ->
                GamePrompt.ChooseAbility(message = prompt.message, choices = prompt.choices.map { it.toAbilityChoice() })

            is ChoosePilePrompt ->
                GamePrompt.ChoosePile(
                    message = prompt.message,
                    pile1 = prompt.pile1.map { it.toCard() },
                    pile2 = prompt.pile2.map { it.toCard() },
                )

            is ChooseChoicePrompt ->
                GamePrompt.ChooseChoice(
                    message = prompt.message,
                    choices = prompt.choices.map { it.toChoiceOption() },
                    subMessage = prompt.subMessage,
                    isRequired = prompt.required,
                    specialText = prompt.specialText,
                )

            is PlayManaPrompt -> GamePrompt.PlayMana(message = prompt.message, options = prompt.options.toOptions())
            is PlayXManaPrompt -> GamePrompt.PlayXMana(message = prompt.message, options = prompt.options.toOptions())
            is GetAmountPrompt ->
                GamePrompt.GetAmount(
                    message = prompt.message,
                    min = prompt.min,
                    max = prompt.max,
                    options = prompt.options.toOptions(),
                )

            is GetMultiAmountPrompt ->
                GamePrompt.GetMultiAmount(
                    message = prompt.message,
                    entries = prompt.entries.map { it.toMultiAmountEntry() },
                    min = prompt.min,
                    max = prompt.max,
                    options = prompt.options.toOptions(),
                )

            // Deliberately last and deliberately opaque: an unrecognised prompt has no known answer, so
            // it carries only its discriminator (see [GamePrompt.Unrecognised]).
            is UnknownGamePrompt -> GamePrompt.Unrecognised(type = prompt.type)
        }

    /** The wire code for an app-schema [ManaType] (the reply path for [GameClient.unlockMana]). */
    fun toCode(manaType: ManaType): ManaTypeCode =
        when (manaType) {
            ManaType.White -> ManaTypeCode.WHITE
            ManaType.Blue -> ManaTypeCode.BLUE
            ManaType.Black -> ManaTypeCode.BLACK
            ManaType.Red -> ManaTypeCode.RED
            ManaType.Green -> ManaTypeCode.GREEN
            ManaType.Colorless -> ManaTypeCode.COLORLESS
            ManaType.Generic -> ManaTypeCode.GENERIC
        }

    /** The wire player action for an app-schema [PassPriorityScope]. */
    fun toCode(scope: PassPriorityScope): PlayerActionCode =
        when (scope) {
            PassPriorityScope.UntilMyNextTurn -> PlayerActionCode.PASS_PRIORITY_UNTIL_MY_NEXT_TURN
            PassPriorityScope.UntilTurnEndStep -> PlayerActionCode.PASS_PRIORITY_UNTIL_TURN_END_STEP
            PassPriorityScope.UntilNextMainPhase -> PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_MAIN_PHASE
            PassPriorityScope.UntilNextTurn -> PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN
            PassPriorityScope.UntilNextTurnSkipStack -> PlayerActionCode.PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK
            PassPriorityScope.UntilStackResolved -> PlayerActionCode.PASS_PRIORITY_UNTIL_STACK_RESOLVED
            PassPriorityScope.UntilEndStepBeforeMyNextTurn -> PlayerActionCode.PASS_PRIORITY_UNTIL_END_STEP_BEFORE_MY_NEXT_TURN
            PassPriorityScope.CancelAll -> PlayerActionCode.PASS_PRIORITY_CANCEL_ALL_ACTIONS
        }

    // --- payload projections -------------------------------------------------------------------------

    private fun GamePlayerView.toPlayer(): GamePlayer =
        GamePlayer(
            playerId = playerId,
            name = name,
            life = life,
            libraryCount = libraryCount,
            handCount = handCount,
            graveyardCount = graveyardCount,
            exileCount = exileCount,
            wins = wins,
            winsNeeded = winsNeeded,
            isViewer = viewer,
            isActive = active,
            hasPriority = hasPriority,
            isHuman = human,
            hasLeft = hasLeft,
            manaPool = manaPool.toManaPool(),
            battlefield = battlefield.map { it.toPermanent() },
            counters = counters.map { GameCounter(name = it.name, count = it.count) },
            isMonarch = monarch,
            hasInitiative = initiative,
            designationNames = designationNames,
        )

    private fun GameCardView.toCard(): GameCard =
        GameCard(
            id = id,
            name = name,
            setCode = setCode,
            collectorNumber = collectorNumber,
            manaCost = manaCost,
            typeLine = typeLine,
            power = power,
            toughness = toughness,
            rules = rules,
            isFaceDown = faceDown,
            // Written on the wire by the bridge's own read of `mage.view.CardView`:
            // `getCardTypes()`, `isCreature()` and `getCounters()` respectively. Carried through
            // unchanged — the server owns the predicate, this is only a shape change.
            cardTypes = cardTypes.map { it.toCardType() },
            isCreature = creature,
            counters = counters.map { GameCounter(name = it.name, count = it.count) },
            alternateName = alternateName,
            transformed = transformed,
            // upstream's own `CardView.getTargets()`, already de-duplicated and stably
            // ordered by the server (`addTargets` uses a LinkedHashSet for exactly that). Carried
            // through unchanged: these are ids into this same snapshot, and resolving one to the
            // object it names belongs to whatever draws the arrow.
            targets = targets,
        )

    private fun GamePermanentView.toPermanent(): GamePermanent =
        GamePermanent(
            card = card.toCard(),
            isTapped = tapped,
            isFlipped = flipped,
            isPhasedIn = phasedIn,
            hasSummoningSickness = summoningSickness,
            damage = damage,
            attachedTo = attachedTo,
            // both directions, because only both together describe the relationship --
            // and the bridge already read them off one `PermanentView`, so they agree by construction.
            attachments = attachments,
            isAttachedToPermanent = attachedToPermanent,
            attachedControllerDiffers = attachedControllerDiffers,
            isControlledByViewer = controlledByViewer,
        )

    private fun GameZoneView.toZone(): GameZone = GameZone(name = name, cards = cards.map { it.toCard() }, zoneId = zoneId)

    private fun GameCombatGroupView.toCombatGroup(): CombatGroup =
        CombatGroup(
            defenderId = defenderId,
            defenderName = defenderName,
            isBlocked = blocked,
            attackerIds = attackerIds,
            blockerIds = blockerIds,
        )

    private fun GameManaPoolView.toManaPool(): ManaPool =
        ManaPool(white = white, blue = blue, black = black, red = red, green = green, colorless = colorless)

    private fun GamePlayableObject.toPlayable(): PlayableObject = PlayableObject(objectId = objectId, abilityIds = abilityIds)

    private fun GameAbilityChoice.toAbilityChoice(): AbilityChoice = AbilityChoice(abilityId = abilityId, text = text)

    private fun GameChoiceOption.toChoiceOption(): ChoiceOption = ChoiceOption(key = key, label = label)

    private fun GameMultiAmountEntry.toMultiAmountEntry(): MultiAmountEntry =
        MultiAmountEntry(message = message, min = min, max = max, defaultValue = defaultValue)

    private fun GamePromptOptions.toOptions(): PromptOptions = PromptOptions(text = text, ids = ids)

    /** The app-schema face of a wire card type. Total over the closed `:protocol` set. */
    private fun CardTypeCode.toCardType(): CardType =
        when (this) {
            CardTypeCode.ARTIFACT -> CardType.Artifact
            CardTypeCode.BATTLE -> CardType.Battle
            CardTypeCode.CONSPIRACY -> CardType.Conspiracy
            CardTypeCode.CREATURE -> CardType.Creature
            CardTypeCode.DUNGEON -> CardType.Dungeon
            CardTypeCode.ENCHANTMENT -> CardType.Enchantment
            CardTypeCode.INSTANT -> CardType.Instant
            CardTypeCode.KINDRED -> CardType.Kindred
            CardTypeCode.LAND -> CardType.Land
            CardTypeCode.PHENOMENON -> CardType.Phenomenon
            CardTypeCode.PLANE -> CardType.Plane
            CardTypeCode.PLANESWALKER -> CardType.Planeswalker
            CardTypeCode.SCHEME -> CardType.Scheme
            CardTypeCode.SORCERY -> CardType.Sorcery
            CardTypeCode.VANGUARD -> CardType.Vanguard
            CardTypeCode.UNKNOWN -> CardType.Unknown
        }

    private fun TurnPhaseCode.toPhase(): TurnPhase =
        when (this) {
            TurnPhaseCode.BEGINNING -> TurnPhase.Beginning
            TurnPhaseCode.PRECOMBAT_MAIN -> TurnPhase.PrecombatMain
            TurnPhaseCode.COMBAT -> TurnPhase.Combat
            TurnPhaseCode.POSTCOMBAT_MAIN -> TurnPhase.PostcombatMain
            TurnPhaseCode.END -> TurnPhase.End
            TurnPhaseCode.UNKNOWN -> TurnPhase.Unknown
        }

    private fun PhaseStepCode.toStep(): PhaseStep =
        when (this) {
            PhaseStepCode.UNTAP -> PhaseStep.Untap
            PhaseStepCode.UPKEEP -> PhaseStep.Upkeep
            PhaseStepCode.DRAW -> PhaseStep.Draw
            PhaseStepCode.PRECOMBAT_MAIN -> PhaseStep.PrecombatMain
            PhaseStepCode.BEGIN_COMBAT -> PhaseStep.BeginCombat
            PhaseStepCode.DECLARE_ATTACKERS -> PhaseStep.DeclareAttackers
            PhaseStepCode.DECLARE_BLOCKERS -> PhaseStep.DeclareBlockers
            PhaseStepCode.FIRST_COMBAT_DAMAGE -> PhaseStep.FirstCombatDamage
            PhaseStepCode.COMBAT_DAMAGE -> PhaseStep.CombatDamage
            PhaseStepCode.END_COMBAT -> PhaseStep.EndCombat
            PhaseStepCode.POSTCOMBAT_MAIN -> PhaseStep.PostcombatMain
            PhaseStepCode.END_TURN -> PhaseStep.EndTurn
            PhaseStepCode.CLEANUP -> PhaseStep.Cleanup
            PhaseStepCode.UNKNOWN -> PhaseStep.Unknown
        }
}
