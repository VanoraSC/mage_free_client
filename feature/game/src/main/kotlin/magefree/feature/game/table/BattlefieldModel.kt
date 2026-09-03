package magefree.feature.game.table

import magefree.designsystem.card.BoardAttachment
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CardDisplay
import magefree.network.game.CardIconType
import magefree.network.game.CardType
import magefree.network.game.GameCard
import magefree.network.game.GameCardIcon
import magefree.network.game.GamePermanent
import magefree.network.game.GameState

/*
 * The battlefield as the new board draws it, built from the server's own snapshot.
 *
 * **This is a second model over the same state, deliberately.** The old board's `BoardUi` carries
 * counters and combat but no card icons, no attachments and no type classification, and §11's rule is
 * that old code is not edited to accommodate new code — the value of keeping the old board is that a
 * defect on the new one can be checked against it in one tap, and that value evaporates the moment the
 * two share a model this work is changing.
 *
 * Everything here is a rearrangement of what the server sent. Nothing is derived about the game: the
 * types are the current ones after continuous effects, the icons are what upstream computed from
 * game-aware abilities, and the combat assignment is the server's own.
 */

/** Where a permanent sits on its side of the board. §7.4's three buckets. */
enum class PermanentRole {
    /** The things that attack and block. They go in front, nearest the middle. */
    Creature,

    /** Everything else that is not a land — artifacts, enchantments, planeswalkers, battles. */
    Other,

    /** The most numerous and least individually interesting permanents. To the side, at the back. */
    Land,
}

/**
 * One permanent as the board will draw it.
 *
 * @property id the server's own object id, which is what the animation host tracks identity by.
 * @property role which bucket it belongs to.
 * @property state everything the Board card tier needs to draw it.
 */
data class TablePermanent(
    val id: String,
    val role: PermanentRole,
    val state: BoardCardState,
)

/** One player's half of the board. */
data class BattlefieldSide(
    val playerId: String,
    val playerName: String,
    val isViewer: Boolean,
    val permanents: List<TablePermanent>,
) {
    /** The permanents in [role], in the server's own order. */
    fun inRole(role: PermanentRole): List<TablePermanent> = permanents.filter { it.role == role }

    /** True when nothing occupies [role] — the layout draws no region at all for it. */
    fun isEmpty(role: PermanentRole): Boolean = permanents.none { it.role == role }
}

/**
 * Both halves of the board.
 *
 * @property viewer the seat being played, or `null` for a spectator, who has no side of their own.
 * @property opponents every other seat, in the server's order.
 */
data class BattlefieldModel(
    val viewer: BattlefieldSide?,
    val opponents: List<BattlefieldSide>,
)

/** The battlefield in [state], arranged. */
fun battlefieldModel(state: GameState): BattlefieldModel {
    val combat = CombatAssignment.of(state)
    val playable = state.playable.map { it.objectId }.toSet()

    // Attachments are folded onto their hosts, so a host has to be findable from anywhere on the
    // board: upstream permits an Aura you control on a creature they control, which means the host
    // can sit on the other side from the attachment.
    val everyPermanent = state.players.flatMap { it.battlefield }.associateBy { it.card.id }

    val sides =
        state.players.map { player ->
            BattlefieldSide(
                playerId = player.playerId,
                playerName = player.name,
                isViewer = player.isViewer,
                permanents =
                    player.battlefield
                        .filterNot { it.isAttachedToPermanent }
                        .map { permanent ->
                            TablePermanent(
                                id = permanent.card.id,
                                role = roleOf(permanent.card),
                                state =
                                    boardCardState(
                                        permanent = permanent,
                                        attachments = attachmentsOf(permanent, everyPermanent),
                                        combat = combat,
                                        playable = playable,
                                    ),
                            )
                        },
            )
        }

    return BattlefieldModel(
        viewer = sides.firstOrNull { it.isViewer },
        opponents = sides.filterNot { it.isViewer },
    )
}

/**
 * Which bucket [card] belongs in.
 *
 * **Creature is checked first, and that is the interesting part.** A permanent can be both — an
 * animated Mutavault, a Dryad Arbor, a land that Kenrith's Transformation turned into an Elk. The
 * server reports current types after continuous effects, so a land that is a creature right now is a
 * thing that attacks and blocks right now, and belongs where the player is looking for those.
 * Checking land first would file an attacking manland at the back of the board with the Plains.
 */
private fun roleOf(card: GameCard): PermanentRole =
    when {
        card.isCreature || CardType.Creature in card.cardTypes -> PermanentRole.Creature
        CardType.Land in card.cardTypes -> PermanentRole.Land
        else -> PermanentRole.Other
    }

/**
 * What is attached to [permanent], as the Board tier draws it.
 *
 * Resolved against every permanent on the board rather than against its controller's own, because
 * [GamePermanent.attachedControllerDiffers] exists precisely for the case where they are not the same
 * player. An id that resolves to nothing is dropped rather than drawn blank: it means the snapshot
 * referenced something it did not also send, and inventing a card face for it would be worse than the
 * missing one.
 */
private fun attachmentsOf(
    permanent: GamePermanent,
    everyPermanent: Map<String, GamePermanent>,
): List<BoardAttachment> =
    permanent.attachments.mapNotNull { id ->
        val attached = everyPermanent[id] ?: return@mapNotNull null
        BoardAttachment(
            name = attached.card.name,
            manaCost = attached.card.manaCost,
            tapped = attached.isTapped,
            controlledByOther = attached.attachedControllerDiffers,
        )
    }

/** One permanent's full drawing state. */
private fun boardCardState(
    permanent: GamePermanent,
    attachments: List<BoardAttachment>,
    combat: CombatAssignment,
    playable: Set<String>,
): BoardCardState {
    val card = permanent.card
    return BoardCardState(
        card =
            CardDisplay(
                name = card.name,
                manaCost = card.manaCost,
                typeLine = card.typeLine,
                oracleText = card.rules.joinToString("\n").takeIf { it.isNotBlank() },
            ),
        power = card.power,
        toughness = card.toughness,
        counters = card.counters.map { BoardCounter(name = it.name, count = it.count) },
        badges = card.icons.mapNotNull(::badgeOf),
        attachments = attachments,
        tapped = permanent.isTapped,
        signals = signalsOf(permanent, combat, playable),
    )
}

/**
 * Which signals apply to [permanent] right now.
 *
 * All three are the server's own answers: combat assignment comes from `GameState.combat`, and
 * playability from `GameState.playable`, which is the list upstream computes of what this player may
 * act on. None of it is inferred from the permanent.
 */
private fun signalsOf(
    permanent: GamePermanent,
    combat: CombatAssignment,
    playable: Set<String>,
): Set<BoardCardSignal> =
    buildSet {
        val id = permanent.card.id
        if (id in combat.attackerIds) add(BoardCardSignal.Attacking)
        if (id in combat.blockerIds) add(BoardCardSignal.Blocking)
        if (id in playable) add(BoardCardSignal.Playable)
    }

/**
 * The badge for one of the server's icons, or `null` for one that is not a badge on a permanent.
 *
 * **The hint is read in exactly one place.** Upstream sends shroud and hexproof under the same
 * [CardIconType.AbilityHexproof], distinguished only by a hint of `"Shroud"` — see
 * `CardIconImpl.ABILITY_SHROUD`. Everything else maps by type alone, and an icon this build has never
 * heard of becomes [BoardBadge.Unknown] rather than vanishing, so the player still sees that the
 * server marked *something*.
 *
 * [CardIconType.PlayableCount] returns `null` on purpose: it is a count of playable copies in a pile,
 * which is a hand and zone-browser concern rather than a property of a permanent. So do upstream's two
 * inner-client values, which are not expected from a server at all.
 */
private fun badgeOf(icon: GameCardIcon): BoardBadge? =
    when (icon.type) {
        CardIconType.AbilityFlying -> BoardBadge.Flying
        CardIconType.AbilityDefender -> BoardBadge.Defender
        CardIconType.AbilityDeathtouch -> BoardBadge.Deathtouch
        CardIconType.AbilityLifelink -> BoardBadge.Lifelink
        CardIconType.AbilityDoubleStrike -> BoardBadge.DoubleStrike
        CardIconType.AbilityFirstStrike -> BoardBadge.FirstStrike
        CardIconType.AbilityCrew -> BoardBadge.Crew
        CardIconType.AbilityTrample -> BoardBadge.Trample
        CardIconType.AbilityHexproof -> if (icon.isShroud()) BoardBadge.Shroud else BoardBadge.Hexproof
        CardIconType.AbilityInfect -> BoardBadge.Infect
        CardIconType.AbilityIndestructible -> BoardBadge.Indestructible
        CardIconType.AbilityVigilance -> BoardBadge.Vigilance
        CardIconType.AbilityClassLevel -> BoardBadge.ClassLevel
        CardIconType.AbilityReach -> BoardBadge.Reach
        CardIconType.FaceDown -> BoardBadge.FaceDown
        CardIconType.OtherCostX -> BoardBadge.CostX
        CardIconType.HasRestrictions -> BoardBadge.HasRestrictions
        CardIconType.HasTargets -> BoardBadge.HasTargets
        CardIconType.RingBearer -> BoardBadge.Ringbearer
        CardIconType.Commander -> BoardBadge.Commander
        CardIconType.PlayableCount, CardIconType.SystemCombined, CardIconType.SystemDebug -> null
        CardIconType.Unknown -> BoardBadge.Unknown
    }

/** Upstream's own hint text for the shroud case, from `CardIconImpl.ABILITY_SHROUD`. */
private fun GameCardIcon.isShroud(): Boolean = hint.trim().equals("Shroud", ignoreCase = true)

/**
 * Who is attacking and who is blocking, indexed once per snapshot.
 *
 * `CombatGroup` is per-attacker upstream, so "is this permanent blocking" is a question about every
 * group rather than a field on one. Answering it once beats answering it per permanent per frame.
 */
private class CombatAssignment(
    val attackerIds: Set<String>,
    val blockerIds: Set<String>,
) {
    companion object {
        val Empty = CombatAssignment(emptySet(), emptySet())

        fun of(state: GameState): CombatAssignment {
            if (state.combat.isEmpty()) return Empty
            return CombatAssignment(
                attackerIds = state.combat.flatMap { it.attackerIds }.toSet(),
                blockerIds = state.combat.flatMap { it.blockerIds }.toSet(),
            )
        }
    }
}
