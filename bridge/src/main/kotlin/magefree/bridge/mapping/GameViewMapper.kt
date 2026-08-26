package magefree.bridge.mapping

import mage.constants.CardType
import mage.constants.PhaseStep
import mage.constants.TurnPhase
import mage.view.AbilityView
import mage.view.CardView
import mage.view.CardsView
import mage.view.CombatGroupView
import mage.view.GameView
import mage.view.PermanentView
import mage.view.PlayerView
import mage.view.StackAbilityView
import magefree.protocol.CardTypeCode
import magefree.protocol.GameCardView
import magefree.protocol.GameCombatGroupView
import magefree.protocol.GameCounterView
import magefree.protocol.GameManaPoolView
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GameStateView
import magefree.protocol.GameZoneView
import magefree.protocol.PhaseStepCode
import magefree.protocol.TurnPhaseCode
import java.util.UUID

/**
 * The `mage.view.GameView` → app-schema [GameStateView] projection — the single place the
 * bridge reads XMage's in-game view types, and the sibling of [TableMapper] for the game side. Pure and
 * deterministic: it reads getters and builds protocol data classes, nothing more.
 *
 * **The snapshot is the whole contract.** Every upstream game callback carries a complete `GameView`, so
 * this maps the view *once* and each event/prompt carries the result. Nothing here reconciles deltas and
 * nothing here decides legality — [GameStateView.playable] is the server's own `canPlayObjects`, mapped
 * straight through.
 *
 * **Reachability (verification standard 2) — the fields the app gates on:**
 * - *whose turn is it* — `GameView.getActivePlayerId()`/`getActivePlayerName()`, plus
 *   `PlayerView.isActive()` per player.
 * - *does the viewer have priority* — `GameView.getMyPlayer()?.hasPriority()`. `GameView` exposes
 *   `getPriorityPlayerName()` but **no** priority player *id*, so the viewer's own `PlayerView` flag is
 *   the only id-safe source; that is what [GameStateView.viewerHasPriority] carries.
 * - *what can the viewer play* — `GameView.getCanPlayObjects()`. The server sets it **only** on the view
 *   it builds for the player who currently holds priority (`GameSessionPlayer.prepareGameView`); for
 *   anyone else, and for every spectator, it is `null` and [GameStateView.playable] is empty.
 * - *is there an outstanding prompt* — not a `GameView` field at all: it is the **callback method**. A
 *   `GAME_SELECT`/`GAME_TARGET`/… push becomes a `GamePrompted`; `GAME_INIT`/`GAME_UPDATE` never do.
 *
 * **Never throws** (the 0006/0026-F5 invariant). The composed text getters (`getTypeText`,
 * `getManaCostStr`) walk several collections upstream populates lazily, so they are read through [text],
 * which turns any failure into a `null` field rather than losing the whole snapshot. [CallbackMapper]'s
 * `mapGuarded` remains the outer backstop.
 */
public object GameViewMapper {
    /** Maps [view] to its app-schema snapshot. */
    public fun map(view: GameView): GameStateView {
        val viewer: PlayerView? = view.myPlayer
        return GameStateView(
            turn = view.turn,
            phase = phaseOf(view.phase),
            step = stepOf(view.step),
            activePlayerId = view.activePlayerId?.toString(),
            activePlayerName = view.activePlayerName.orNullIfBlank(),
            priorityPlayerName = view.priorityPlayerName.orNullIfBlank(),
            viewerPlayerId = viewer?.playerId?.toString(),
            viewerHasPriority = viewer?.hasPriority() ?: false,
            players = view.players.orEmpty().map(::mapPlayer),
            hand = mapCards(view.myHand),
            stack = mapCards(view.stack),
            exile =
                view.exile.orEmpty().map { zone ->
                    GameZoneView(name = zone.name.orEmpty(), cards = mapCards(zone), zoneId = zone.id?.toString())
                },
            revealed =
                view.revealed.orEmpty().map { revealed ->
                    GameZoneView(name = revealed.name.orEmpty(), cards = mapCards(revealed.cards))
                },
            combat = view.combat.orEmpty().map(::mapCombatGroup),
            playable = mapPlayable(view),
            specialActionsAvailable = view.special,
            priorityTimeSeconds = view.priorityTime,
            bufferTimeSeconds = view.bufferTime,
        )
    }

    /**
     * Maps one `PlayerView` — the seat, its counts, and the two flags the app gates turn/priority on.
     *
     * **Per-player state that decides games without being on the battlefield.** `PlayerView`'s
     * constructor fills all four on every snapshot, for every seat, because all of it is public:
     * - `counters` from `player.getCountersAsCopy().values()` into `CounterView{name, count}`. Ten
     *   poison counters is a loss. `mage.counters.Counters` extends `HashMap`, so the order that
     *   arrives is hash order and carries no meaning — consumers look counters up by name.
     * - `monarch` / `initiative` from `player.getId().equals(game.getMonarchId() / getInitiativeId())`.
     * - `designationNames` from `player.getDesignations()`. That list holds City's Blessing and
     *   nothing else in practice: the only production caller of `Player.addDesignation` in XMage is
     *   `AscendAbility`, while the Monarch, Initiative and Speed designations are registered on the
     *   game state instead. The two flags above are the only way to read those.
     */
    private fun mapPlayer(player: PlayerView): GamePlayerView =
        GamePlayerView(
            playerId = player.playerId?.toString().orEmpty(),
            name = player.name.orEmpty(),
            life = player.life,
            libraryCount = player.libraryCount,
            handCount = player.handCount,
            graveyardCount = player.graveyard?.size ?: 0,
            exileCount = player.exile?.size ?: 0,
            wins = player.wins,
            winsNeeded = player.winsNeeded,
            viewer = player.controlled,
            active = player.isActive,
            hasPriority = player.hasPriority(),
            human = player.isHuman,
            hasLeft = player.hasLeft(),
            manaPool =
                player.manaPool?.let { pool ->
                    GameManaPoolView(
                        white = pool.white,
                        blue = pool.blue,
                        black = pool.black,
                        red = pool.red,
                        green = pool.green,
                        colorless = pool.colorless,
                    )
                } ?: GameManaPoolView(),
            battlefield =
                player.battlefield
                    .orEmpty()
                    .values
                    .map(::mapPermanent),
            counters =
                player.counters.orEmpty().filterNotNull().map { counter ->
                    GameCounterView(name = counter.name.orEmpty(), count = counter.count)
                },
            monarch = player.isMonarch,
            initiative = player.isInitiative,
            designationNames = player.designationNames.orEmpty().filterNotNull(),
        )

    /**
     * Maps one battlefield `PermanentView`: the card plus the battlefield-only state.
     *
     * **Attachments both ways.** `attachedTo` alone forces every host to scan every
     * battlefield for anything pointing at it; upstream already computed the reverse list once per
     * snapshot, so [GamePermanentView.attachments] carries it rather than the client rederiving it.
     *
     * **The two flags are read off accessors whose names do not match their fields**, which is why
     * `PermanentView` was read rather than guessed at:
     * - `attachedToPermanent` ← `isAttachedToPermanent()`.
     * - `attachedControllerDiffers` ← **`isAttachedToDifferentlyControlledPermanent()`**.
     *
     * **Reachability (verification standard 2/5).** All three are written unconditionally by
     * `PermanentView`'s constructor for every battlefield permanent: `attachments` from
     * `new ArrayList<>(permanent.getAttachments())` — so upstream never sends it null, though the
     * `orEmpty()` still covers a sparse view — and both flags from a real `game.getPermanent(attachedTo)`
     * lookup. That lookup is also why `attachedControllerDiffers` is **always false when the host is a
     * player**: upstream computes it inside the "the host resolved to a permanent" branch. It answers
     * "is the host a permanent someone else controls", not "is the host someone else's".
     */
    private fun mapPermanent(permanent: PermanentView): GamePermanentView =
        GamePermanentView(
            card = mapCard(permanent),
            tapped = permanent.isTapped,
            flipped = permanent.isFlipped,
            phasedIn = permanent.isPhasedIn,
            summoningSickness = permanent.hasSummoningSickness(),
            damage = permanent.damage,
            attachedTo = permanent.attachedTo?.toString(),
            attachments =
                permanent.attachments
                    .orEmpty()
                    .filterNotNull()
                    .map(UUID::toString),
            attachedToPermanent = permanent.isAttachedToPermanent,
            attachedControllerDiffers = permanent.isAttachedToDifferentlyControlledPermanent,
            controlledByViewer = permanent.isControlled,
        )

    /** Maps one `CombatGroupView` — attackers/blockers as ids into the battlefields already mapped. */
    private fun mapCombatGroup(group: CombatGroupView): GameCombatGroupView =
        GameCombatGroupView(
            defenderId = group.defenderId?.toString(),
            defenderName = group.defenderName.orNullIfBlank(),
            blocked = group.isBlocked,
            attackerIds =
                group.attackers
                    .orEmpty()
                    .keys
                    .map { it.toString() },
            blockerIds =
                group.blockers
                    .orEmpty()
                    .keys
                    .map { it.toString() },
        )

    /**
     * The server's own playability answer: `canPlayObjects` → one [GamePlayableObject] per object, with
     * the ids of the abilities that make it playable. A `null` list (the view was not built for the
     * player holding priority) maps to **empty** — "nothing for you to do", never a guess.
     */
    private fun mapPlayable(view: GameView): List<GamePlayableObject> =
        view.canPlayObjects
            ?.objects
            .orEmpty()
            .map { (objectId, stats) ->
                GamePlayableObject(
                    objectId = objectId?.toString().orEmpty(),
                    abilityIds = stats?.playableAbilityIds.orEmpty().map { it.toString() },
                )
            }

    /** Maps a whole `CardsView` (an ordered id→card map) to the app-schema list, order preserved. */
    public fun mapCards(cards: CardsView?): List<GameCardView> = cards.orEmpty().values.map(::mapCard)

    /**
     * Maps one `CardView` to its app-schema form. Deliberately thin — enough to identify the printing
     * (`(setCode, collectorNumber)`, the pair the catalog resolves art by), to render a
     * text-only card, and to say what the object **currently is**.
     *
     * **Reachability (verification standard 2/5) — what writes the 0058 fields.** Each is a getter on
     * the same `CardView` the rest of this function reads, and upstream populates all three in the
     * `CardView` constructors themselves (verified against `mage-common-1.4.60`):
     * - [GameCardView.cardTypes] ← `CardView.getCardTypes()`, recomputed per snapshot from the live
     *   game object, so continuous effects are already in it (an Earthbent land carries `CREATURE`).
     * - [GameCardView.creature] ← `CardView.isCreature()`, which upstream defines as
     *   `cardTypes.contains(CREATURE)`. Read rather than reimplemented: the predicate stays the
     *   server's.
     * - [GameCardView.counters] ← `CardView.getCounters()`, built from `Card.getCounters(game)` /
     *   `Permanent.getCounters(game)` into `CounterView{name, count}`. Upstream allocates the list only
     *   when the object actually has counters, so **null is the ordinary case** and means "none".
     *
     * `power`/`toughness` stay strings on purpose — `*` is a real value — and are carried exactly as
     * sent, including the `"0"` a noncreature permanent reports. Deciding whether to *show* them is the
     * board's job, not the mapper's.
     *
     * **Ability naming.** Two distinct upstream view types wrap a non-card ability object,
     * both read directly, and **both** hardcode a generic `name`:
     * - `AbilityView` (`mage.view.AbilityView`) — a `CardsView` built from a `Collection<Ability>`,
     *   which is how the ordering-simultaneous-triggers prompt (`GAME_TARGET`/`PICK_ABILITY`) sends its
     *   candidates. Its constructor sets `name = "Ability"` unconditionally for the ordinary
     *   battlefield/stack/hand case (`setName(...)` is called only for the emblem/dungeon/plane special
     *   cases, neither of which this prompt produces).
     * - `StackAbilityView` (`mage.view.StackAbilityView`) — a **separate, sibling** class (does *not*
     *   extend `AbilityView`), used for the ordinary game stack (`GameView.stack`, i.e. every triggered
     *   or activated ability sitting on the stack during normal play, not just the ordering prompt).
     *   Also sets `name = "Ability"` unconditionally — confirmed by reading the constructor directly,
     *   not assumed from the symptom looking similar.
     *
     * Both expose the real identifying name via their own `getSourceCard()` — a nested `CardView` for
     * the source permanent/card — which this function otherwise never reads. [displayName] resolves
     * whichever one applies; `rules` (the ability's actual text, already correct upstream) is untouched.
     *
     * **No art (Soul Warden's own triggered ability was the reported
     * example).** Same root cause, different field: neither `AbilityView` nor `StackAbilityView` ever
     * sets `expansionSetCode`/`cardNumber` on *itself* — only the nested `sourceCard` carries the real
     * printing identity, which [setCode]/[collectorNumber] now read instead. Without this, the app's
     * card-art loader receives a blank set/number pair, resolves to a request with no valid art, and
     * the object renders with no image at all — a distinct symptom from the naming bug (this one stays
     * broken even after the name displays correctly).
     *
     * **`transformed`/`alternateName` are two separate upstream signals, read straight —
     * do not derive one from the other.** Traced directly against upstream's `CardView`/`PermanentView`
     * constructors (pinned ref `e0fe4b6f6a`), the same way the real desktop client (`CardPanel.java`)
     * reads them, rather than reverse-engineered from the symptom:
     *
     * - [CardView.isTransformed] is the live "is this permanent currently showing its back face" fact.
     *   `CardView`'s own constructor sets it — `if (permanent.isTransformed()) transformed = true` — for
     *   any `Permanent`, and `PermanentView` inherits that unchanged via its `super(permanent, game,
     *   ...)` call (its own `this.transformed = permanent.isTransformed()` is commented out at the
     *   pinned ref, but only because `super()` already did it — not because upstream never computes
     *   this). It is `false` for anything that is not a permanent (hand, library, stack, exile), exactly
     *   as it should be: only a permanent transforms. This is the one signal [mapCard] uses to pick a
     *   currently-showing face, mirroring `CardPanel.isTransformed()`'s own reads directly.
     * - [CardView.getAlternateName] means something different: "does this object have another face at
     *   all, and what is it called" — a **catalog fact**, set unconditionally on *any* transformable,
     *   flip, meld, or modal-double-faced object regardless of current state (`CardView`'s own
     *   constructor: `card instanceof PermanentCard && card.isTransformable()` → `alternateName =
     *   getOtherFace().getName()`, no transform check at all). It is never a signal for "which face is
     *   showing," and threading it through unfiltered — matching upstream's own field exactly — is what
     *   the client's flip-button/peek feature needs: whether a flip control should exist,
     *   and the other face's name.
     *
     * Three live bugs (a transformed permanent frozen on its
     * front art; a hand card showing its other face's art; an untransformed permanent showing its back
     * face's art with no flip button) were all one mistake: treating `alternateName != null` as "is
     * this currently transformed," when upstream never uses it that way anywhere, including in its own
     * client. [CardView.isTransformed] already answers that question directly and needs no rederiving.
     *
     * **`targets` is one flat id list, read straight — no per-kind branching.** Upstream
     * builds it in `CardView.addTargets(Targets, Effects, Ability, Game)`, whose own comment is *"need
     * only unique targets for arrow drawing"*: it takes `target.getTargets()` for every `Target` that
     * `isChosen(game)`, adds every effect's `TargetPointer.getTargets(game, source)` (so a mode that
     * declares no target of its own still resolves through the pointer), and de-duplicates through a
     * `LinkedHashSet` — *"use linked, so it will use stable sort order"*. The order upstream sends is
     * therefore meaningful and is preserved here.
     *
     * **Reachability (verification standard 2) — what populates it.** Two call sites, and both are
     * stack objects: `CardView`'s own constructor for a `Spell` (per selected mode, when that mode has
     * targets), and `StackAbilityView.updateTargets` for an ability. `CardsView` also calls it when
     * wrapping abilities for the ordering prompt. Nothing else does — so `targets` is **null** on a
     * hand card, a battlefield permanent, or an untargeted spell, which is why `orEmpty()` is
     * load-bearing here exactly as it is for `counters`.
     *
     * The ids are resolved upstream through `game.getObject(uuid)`, so a target that is itself a spell
     * on the stack (a counterspell's target) arrives the same way a permanent does. Joining an id back
     * to the object it names is the renderer's job — every candidate is already in the same snapshot.
     */
    public fun mapCard(card: CardView): GameCardView =
        GameCardView(
            id = card.id?.toString().orEmpty(),
            name = displayName(card),
            setCode = setCode(card),
            collectorNumber = collectorNumber(card),
            manaCost = text { card.manaCostStr }.orNullIfBlank(),
            typeLine = text { card.typeText }?.trim().orNullIfBlank(),
            power = card.power.orNullIfBlank(),
            toughness = card.toughness.orNullIfBlank(),
            rules = card.rules.orEmpty().filterNotNull(),
            faceDown = card.isFaceDown,
            cardTypes =
                card.cardTypes
                    .orEmpty()
                    .filterNotNull()
                    .map(::typeOf),
            // The server's own predicate, asked only once it has actually sent the list it reads:
            // `isCreature()` dereferences `cardTypes`, and a sparse view can leave it null (the same
            // null `getTypeText()` chokes on). "The server did not say" is never "yes".
            creature = card.cardTypes != null && card.isCreature,
            counters =
                card.counters.orEmpty().filterNotNull().map { counter ->
                    GameCounterView(name = counter.name.orEmpty(), count = counter.count)
                },
            // upstream's own field for "does this have another face, and what's it called" —
            // a catalog fact, unconditional, never a signal for which face is currently up. See mapCard's
            // KDoc.
            alternateName = card.alternateName,
            // upstream's own field for "which face is currently up" — see mapCard's KDoc.
            transformed = card.isTransformed,
            // what this object is pointing at — see mapCard's KDoc. `orEmpty()` is
            // load-bearing: upstream allocates the list only inside `addTargets`, so null is the
            // ordinary case for everything that is not a targeting stack object.
            targets =
                card.targets
                    .orEmpty()
                    .filterNotNull()
                    .map(UUID::toString),
        )

    /** The nested source `CardView` for an `AbilityView`/`StackAbilityView`, `null` for anything else. */
    private fun sourceCardOf(card: CardView): CardView? =
        when (card) {
            is AbilityView -> card.sourceCard
            is StackAbilityView -> card.sourceCard
            else -> null
        }

    /**
     * The name to display for [card] — see [mapCard]'s KDoc. For an ordinary `CardView` this is just
     * `card.name`. For an `AbilityView`/`StackAbilityView`, `card.name` is upstream's literal
     * placeholder `"Ability"`; the real name is on [sourceCardOf], itself falling back to the
     * placeholder only if that is somehow absent too (defensive — every ability upstream sends has a
     * resolvable source object).
     */
    private fun displayName(card: CardView): String = sourceCardOf(card)?.name.orNullIfBlank() ?: card.name.orEmpty()

    /** The set code to resolve art by — see [mapCard]'s KDoc on the "No art" defect. */
    private fun setCode(card: CardView): String? = (sourceCardOf(card) ?: card).expansionSetCode.orNullIfBlank()

    /** The collector number to resolve art by — see [mapCard]'s KDoc on the "No art" defect. */
    private fun collectorNumber(card: CardView): String? = (sourceCardOf(card) ?: card).cardNumber.orNullIfBlank()

    /**
     * Maps `CardType` to its app-schema code. Exhaustive on purpose: a card type added upstream becomes
     * a **compile** error here — the one place the two sets meet — rather than a permanent that quietly
     * stops looking like what it is.
     */
    private fun typeOf(type: CardType): CardTypeCode =
        when (type) {
            CardType.ARTIFACT -> CardTypeCode.ARTIFACT
            CardType.BATTLE -> CardTypeCode.BATTLE
            CardType.CONSPIRACY -> CardTypeCode.CONSPIRACY
            CardType.CREATURE -> CardTypeCode.CREATURE
            CardType.DUNGEON -> CardTypeCode.DUNGEON
            CardType.ENCHANTMENT -> CardTypeCode.ENCHANTMENT
            CardType.INSTANT -> CardTypeCode.INSTANT
            CardType.KINDRED -> CardTypeCode.KINDRED
            CardType.LAND -> CardTypeCode.LAND
            CardType.PHENOMENON -> CardTypeCode.PHENOMENON
            CardType.PLANE -> CardTypeCode.PLANE
            CardType.PLANESWALKER -> CardTypeCode.PLANESWALKER
            CardType.SCHEME -> CardTypeCode.SCHEME
            CardType.SORCERY -> CardTypeCode.SORCERY
            CardType.VANGUARD -> CardTypeCode.VANGUARD
        }

    /** Maps `TurnPhase` to its app-schema code; an absent/unknown phase becomes [TurnPhaseCode.UNKNOWN]. */
    private fun phaseOf(phase: TurnPhase?): TurnPhaseCode =
        when (phase) {
            TurnPhase.BEGINNING -> TurnPhaseCode.BEGINNING
            TurnPhase.PRECOMBAT_MAIN -> TurnPhaseCode.PRECOMBAT_MAIN
            TurnPhase.COMBAT -> TurnPhaseCode.COMBAT
            TurnPhase.POSTCOMBAT_MAIN -> TurnPhaseCode.POSTCOMBAT_MAIN
            TurnPhase.END -> TurnPhaseCode.END
            null -> TurnPhaseCode.UNKNOWN
        }

    /** Maps `PhaseStep` to its app-schema code; an absent/unknown step becomes [PhaseStepCode.UNKNOWN]. */
    private fun stepOf(step: PhaseStep?): PhaseStepCode =
        when (step) {
            PhaseStep.UNTAP -> PhaseStepCode.UNTAP
            PhaseStep.UPKEEP -> PhaseStepCode.UPKEEP
            PhaseStep.DRAW -> PhaseStepCode.DRAW
            PhaseStep.PRECOMBAT_MAIN -> PhaseStepCode.PRECOMBAT_MAIN
            PhaseStep.BEGIN_COMBAT -> PhaseStepCode.BEGIN_COMBAT
            PhaseStep.DECLARE_ATTACKERS -> PhaseStepCode.DECLARE_ATTACKERS
            PhaseStep.DECLARE_BLOCKERS -> PhaseStepCode.DECLARE_BLOCKERS
            PhaseStep.FIRST_COMBAT_DAMAGE -> PhaseStepCode.FIRST_COMBAT_DAMAGE
            PhaseStep.COMBAT_DAMAGE -> PhaseStepCode.COMBAT_DAMAGE
            PhaseStep.END_COMBAT -> PhaseStepCode.END_COMBAT
            PhaseStep.POSTCOMBAT_MAIN -> PhaseStepCode.POSTCOMBAT_MAIN
            PhaseStep.END_TURN -> PhaseStepCode.END_TURN
            PhaseStep.CLEANUP -> PhaseStepCode.CLEANUP
            null -> PhaseStepCode.UNKNOWN
        }

    /**
     * Reads a **composed** upstream text getter, yielding `null` instead of propagating a failure.
     *
     * `getTypeText()`/`getManaCostStr()` build their result from collections upstream fills in only for
     * some card kinds (abilities on the stack, emblems, face-down permanents), so a drifted or partly
     * populated view must cost one card field — not the entire snapshot the app is waiting for.
     */
    private inline fun text(read: () -> String?): String? =
        try {
            read()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    /** `null` for a null or blank upstream string — the app-schema form of "the server said nothing". */
    private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
