package magefree.feature.game.table

import androidx.compose.runtime.Composable
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.designsystem.card.BoardAttachment
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CardArtSlot
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
 * Draws a permanent's art, given the printing the server named and what the card is.
 *
 * The same shape as the design system's `CardArtSlot` seam and for the same reason: `:feature:game`
 * lays the board out and something further out loads the images. It takes a *request*, not a name,
 * because the server names the printing — a snapshot carries `setCode` and `collectorNumber`, so a
 * board never has to guess which Forest it is looking at.
 */
typealias TableArtResolver = @Composable (CardArtRequest?, CardDisplay) -> CardArtSlot?

/**
 * One permanent as the board will draw it.
 *
 * @property id the server's own object id, which is what the animation host tracks identity by.
 * @property role which bucket it belongs to.
 * @property state everything the Board card tier needs to draw it.
 * @property art the printing the server named, or `null` for a card it did not — a face-down
 *   permanent, or a token, which has no printing to name.
 * @property carriesAttachment whether the *server* said something is attached to this, which is not
 *   quite the same as [state] having attachments to draw: a snapshot can name an attachment it did
 *   not also send. It is carried separately because it decides whether this may stack, and there the
 *   answer has to come from what the server said rather than from what we managed to resolve — a
 *   partial snapshot must not quietly merge two enchanted permanents into one.
 */
data class TablePermanent(
    val id: String,
    val role: PermanentRole,
    val state: BoardCardState,
    val art: CardArtRequest? = null,
    val carriesAttachment: Boolean = false,
)

/**
 * Permanents that are identical in every respect, drawn as one stack.
 *
 * **A stack says "these are interchangeable — read one, you have read them all."** That promise is the
 * whole value of it, and it is what makes the grouping strict rather than convenient: anything that
 * makes one member different from another keeps it out. Same card, same printing, same tap state, same
 * counters, same badges, same combat assignment, same playability.
 *
 * @property members every permanent in the stack, in the server's own order. Never empty.
 */
data class TablePile(
    val members: List<TablePermanent>,
) {
    /** What the stack is drawn as. Any member would do, which is the point of it being a stack. */
    val representative: TablePermanent get() = members.first()

    /** How many there are. The stack draws at most [PILE_FAN_LIMIT] of them and counts the rest. */
    val count: Int get() = members.size

    /**
     * The permanent an action on this stack refers to.
     *
     * Any of them, because they are identical by construction. Four Plains are four Plains: tapping
     * "the third one" is not a distinction the game makes, and asking the player to make it would be
     * inventing a choice rather than offering one.
     */
    val actionId: String get() = members.first().id
}

/** One player's half of the board. */
data class BattlefieldSide(
    val playerId: String,
    val playerName: String,
    val isViewer: Boolean,
    val permanents: List<TablePermanent>,
) {
    /** The permanents in [role], in the server's own order. */
    fun inRole(role: PermanentRole): List<TablePermanent> = permanents.filter { it.role == role }

    /**
     * The permanents in [role] as the stacks the board draws.
     *
     * **Only lands stack, for now.** §7.4's reasoning is that piling buys space, and the space is in
     * the lands: a board of ten Plains collapses and a board of ten differently-developed creatures
     * does not, because those ten differ. Applying it to creatures would be correct and would almost
     * never fire, so it is not done here — and the one place it could fire, a row of identical tokens,
     * is worth doing deliberately rather than as a side effect.
     */
    fun pilesIn(role: PermanentRole): List<TablePile> {
        val members = inRole(role)
        if (role != PermanentRole.Land) return members.map { TablePile(listOf(it)) }

        // Insertion-ordered, so the stacks appear where the server first mentioned them rather than in
        // whatever order a hash produced — a land that reorders itself between snapshots is a land
        // that appears to have moved.
        val piles = LinkedHashMap<PileKey, MutableList<TablePermanent>>()
        val alone = mutableListOf<TablePile>()
        members.forEach { permanent ->
            val key = permanent.pileKey()
            if (key == null) {
                alone += TablePile(listOf(permanent))
            } else {
                piles.getOrPut(key) { mutableListOf() } += permanent
            }
        }
        return piles.values.map { TablePile(it) } + alone
    }

    /** True when nothing occupies [role] — the layout draws no region at all for it. */
    fun isEmpty(role: PermanentRole): Boolean = permanents.none { it.role == role }
}

/**
 * What makes two permanents the same stack, or `null` for one that may never stack at all.
 *
 * `null` is the **attachment** case, and it is absolute rather than another field in the key. An
 * attachment attaches to one specific instance: the Aura is on *that* Grizzly Bears, not on the group.
 * Two identically-enchanted permanents still do not stack, because each carries its own attachment and
 * "read one and you have read them all" stops being true.
 *
 * Everything else is a field, and the field is the whole drawing state — tap, counters, badges,
 * combat, playability, power and toughness — plus the printing, because two Forests with different art
 * are visibly two different things however identical the game considers them.
 */
private data class PileKey(
    val state: BoardCardState,
    val art: CardArtRequest?,
)

private fun TablePermanent.pileKey(): PileKey? = if (carriesAttachment) null else PileKey(state = state, art = art)

/** How many faces a stack shows before it starts counting instead. */
const val PILE_FAN_LIMIT: Int = 3

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
                                art = artRequestOf(permanent.card),
                                carriesAttachment = permanent.attachments.isNotEmpty(),
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
 * The printing the server named, or `null` when it named none.
 *
 * **A face-down permanent gets no request.** Its face is not information the viewer is entitled to,
 * and the server may still be sending what the card is; drawing its art would show a card the game
 * says is hidden. A token has no printing to name either, and falls back to the placeholder.
 *
 * [GameCard.transformed] is what says a permanent is *currently* showing its back face, and a
 * double-faced card's two faces share one printing — so which face is up is entirely a matter of which
 * [CardArtFace] is asked for.
 */
private fun artRequestOf(card: GameCard): CardArtRequest? {
    if (card.isFaceDown) return null
    val set = card.setCode?.takeIf { it.isNotBlank() } ?: return null
    val number = card.collectorNumber?.takeIf { it.isNotBlank() } ?: return null
    return CardArtRequest(
        setCode = set,
        collectorNumber = number,
        face = if (card.transformed) CardArtFace.BACK else CardArtFace.FRONT,
        size = CardArtSize.SMALL,
    )
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
