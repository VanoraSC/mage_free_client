package magefree.bridge.mapping

import mage.constants.PhaseStep
import mage.constants.TurnPhase
import mage.view.CardView
import mage.view.CardsView
import mage.view.CombatGroupView
import mage.view.GameView
import mage.view.PermanentView
import mage.view.PlayerView
import magefree.protocol.GameCardView
import magefree.protocol.GameCombatGroupView
import magefree.protocol.GameManaPoolView
import magefree.protocol.GamePermanentView
import magefree.protocol.GamePlayableObject
import magefree.protocol.GamePlayerView
import magefree.protocol.GameStateView
import magefree.protocol.GameZoneView
import magefree.protocol.PhaseStepCode
import magefree.protocol.TurnPhaseCode

/**
 * The `mage.view.GameView` → app-schema [GameStateView] projection (story 0051) — the single place the
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

    /** Maps one `PlayerView` — the seat, its counts, and the two flags the app gates turn/priority on. */
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
        )

    /** Maps one battlefield `PermanentView`: the card plus the battlefield-only state. */
    private fun mapPermanent(permanent: PermanentView): GamePermanentView =
        GamePermanentView(
            card = mapCard(permanent),
            tapped = permanent.isTapped,
            flipped = permanent.isFlipped,
            phasedIn = permanent.isPhasedIn,
            summoningSickness = permanent.hasSummoningSickness(),
            damage = permanent.damage,
            attachedTo = permanent.attachedTo?.toString(),
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
     * (`(setCode, collectorNumber)`, the pair story 0030's catalog resolves art by) and to render a
     * text-only card.
     */
    public fun mapCard(card: CardView): GameCardView =
        GameCardView(
            id = card.id?.toString().orEmpty(),
            name = card.name.orEmpty(),
            setCode = card.expansionSetCode.orNullIfBlank(),
            collectorNumber = card.cardNumber.orNullIfBlank(),
            manaCost = text { card.manaCostStr }.orNullIfBlank(),
            typeLine = text { card.typeText }?.trim().orNullIfBlank(),
            power = card.power.orNullIfBlank(),
            toughness = card.toughness.orNullIfBlank(),
            rules = card.rules.orEmpty().filterNotNull(),
            faceDown = card.isFaceDown,
        )

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
