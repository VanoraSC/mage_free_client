package magefree.feature.game.table

import androidx.compose.runtime.Composable
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.art.cardBackRequest
import magefree.cards.art.emblemArtRequest
import magefree.cards.art.faceDownArtRequest
import magefree.cards.art.tokenArtRequest
import magefree.designsystem.card.BoardAttachment
import magefree.designsystem.card.BoardBadge
import magefree.designsystem.card.BoardCardSignal
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardPreviewAttachment
import magefree.designsystem.card.CardPreviewState
import magefree.feature.game.board.PromptControlsUi
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
 * @property abilities the server's **game-aware** rules text — a creature granted flying until end of
 *   turn has it here, and the printing does not. Carried so that inspecting a permanent can show what
 *   it can do *now*, which is the whole difference between reading a board and reading a card.
 */
data class TablePermanent(
    val id: String,
    val role: PermanentRole,
    val state: BoardCardState,
    val art: CardArtRequest? = null,
    val carriesAttachment: Boolean = false,
    /**
     * Whether this is a token.
     *
     * The board draws identical tokens as a pile, and only tokens: the server says so with
     * `GameCard.isToken`, and it is the only thing that can — a token and a card look identical.
     */
    val isToken: Boolean = false,
    val abilities: List<String> = emptyList(),
    val attached: List<TableAttachment> = emptyList(),
)

/**
 * One permanent attached to another, as everything but the Board tier needs it.
 *
 * [BoardCardState.attachments] carries what the *card* draws — a name, a cost, whether it is turned.
 * This carries what everything else needs: the printing to draw its face from, and the server's own
 * text for it, so that reading the host reads what is on the host too.
 *
 * @property id the server's object id, which is what a press on the attachment's band names.
 */
data class TableAttachment(
    val id: String,
    val card: CardDisplay,
    val art: CardArtRequest? = null,
    val abilities: List<String> = emptyList(),
)

/**
 * Every copy of one land a player controls, tapped and untapped together, drawn as one stack.
 *
 * **Tap state does not split the stack; it decides which half of it a card sits in.** That is the
 * difference between this and a plain grouping, and it is what makes the board readable: four Plains
 * are one thing on the battlefield whether two of them are tapped or none are, and drawing them as two
 * unrelated piles that drift apart as the turn goes on says otherwise. One stack with an untapped side
 * and a tapped side keeps the count in one place and gives a tapping card somewhere to travel *to*.
 *
 * **Everything else is still strict.** A stack promises *these are interchangeable — read one and you
 * have read them all*, so any other difference keeps a land out: counters, badges, combat, and the
 * printing. An attachment keeps it out absolutely.
 *
 * **Playability is the exception, and it is one for the same reason tap state is.** What makes an
 * untapped Swamp playable is that you can still tap it for mana, so a land's playability is a
 * restatement of which half it is in — and treating it as a difference split every pile the moment one
 * of its lands was used. Each half is drawn from its own copies, so nothing borrows the other side's
 * mark.
 *
 * @property untapped the upright copies, in the server's own order.
 * @property tapped the turned copies, in the server's own order.
 */
data class TableLandStack(
    val untapped: List<TablePermanent>,
    val tapped: List<TablePermanent>,
) {
    /** What the stack is drawn as. Any member would do, which is the point of it being a stack. */
    val representative: TablePermanent get() = (untapped + tapped).first()

    /** How many there are in total, across both halves. */
    val count: Int get() = untapped.size + tapped.size

    /**
     * The permanent tapping this stack refers to.
     *
     * The topmost untapped copy — the one drawn lowest and furthest right, which is the one a player
     * would reach for. Which of them it actually is does not matter: they are identical by
     * construction, and asking the player to pick between four Plains would be inventing a choice
     * rather than offering one. `null` when every copy is already tapped.
     */
    val tapActionId: String? get() = untapped.lastOrNull()?.id

    /** Any member, for a board that is only being looked at rather than played. */
    val inspectId: String get() = representative.id
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
     * This side's lands, gathered into stacks.
     *
     * **Only lands stack.** §7.4's reasoning is that piling buys space, and the space is in the lands:
     * a board of ten Plains collapses and a board of ten differently-developed creatures does not,
     * because those ten differ. Applying it to creatures would be correct and would almost never fire,
     * so it is not done here — and the one place it could fire, a row of identical tokens, is worth
     * doing deliberately rather than as a side effect.
     */
    fun landStacks(): List<TableLandStack> {
        // Insertion-ordered, so the stacks appear where the server first mentioned them rather than in
        // whatever order a hash produced — a land that reorders itself between snapshots is a land
        // that appears to have moved.
        val grouped = LinkedHashMap<LandStackKey, MutableList<TablePermanent>>()
        val alone = mutableListOf<TableLandStack>()
        inRole(PermanentRole.Land).forEach { permanent ->
            val key = permanent.landStackKey()
            if (key == null) {
                alone += permanent.asOwnStack()
            } else {
                grouped.getOrPut(key) { mutableListOf() } += permanent
            }
        }
        return grouped.values.map { members ->
            TableLandStack(
                untapped = members.filterNot { it.state.tapped },
                tapped = members.filter { it.state.tapped },
            )
        } + alone
    }

    /**
     * What [role]'s row actually draws: single permanents, and piles of identical tokens.
     *
     * **Only tokens pile.** A board that made twelve Zombie tokens is a board with one thing on it
     * twelve times over, and drawing twelve cards spends the row's whole width saying so — which is
     * what shrank every card on the table to its floor. Real cards never pile: two Grizzly Bears are
     * two cards a player owns and may want to tell apart, and upstream marks the difference itself
     * (`GameCard.isToken`), so nothing is being guessed.
     *
     * **A tapped token is its own pile, not the turned half of one.** This is where tokens differ
     * from lands, deliberately. A land's two halves are the same permanent in two states and the
     * count is what matters, so one stack with a leaning side reads correctly. A creature's tap state
     * is *what it is doing* — it attacked, it crewed, it was tapped down — and that is a different
     * thing from an untapped copy standing ready, not the same thing lying over. So tap state is part
     * of the key here, and each pile is uniformly upright or uniformly turned.
     */
    fun entriesIn(role: PermanentRole): List<RowEntry> {
        val row = inRole(role)
        val members = LinkedHashMap<TokenPileKey, MutableList<TablePermanent>>()
        row.forEach { permanent ->
            permanent.tokenPileKey()?.let { key -> members.getOrPut(key) { mutableListOf() } += permanent }
        }

        // Each pile appears where its *first* member did, so a pile that gains a token does not jump
        // to the end of the row — the same rule the land stacks follow, for the same reason.
        //
        // **A lone token is a card, not a pile of one.** A pile is wider than a card and reads as a
        // count; one of something is neither, and drawing it as a stack would spend the extra width
        // saying nothing.
        val drawn = mutableSetOf<TokenPileKey>()
        return row.mapNotNull { permanent ->
            val key = permanent.tokenPileKey()
            val group = key?.let(members::getValue)
            when {
                group == null || group.size == 1 -> RowEntry.Single(permanent)
                drawn.add(key) -> RowEntry.Pile(group.toList())
                else -> null
            }
        }
    }

    /** True when nothing occupies [role] — the layout draws no region at all for it. */
    fun isEmpty(role: PermanentRole): Boolean = permanents.none { it.role == role }
}

/** One thing a battlefield row draws. */
sealed interface RowEntry {
    /** The permanents this entry stands for, in the server's own order. Never empty. */
    val permanents: List<TablePermanent>

    /**
     * How much of the row this occupies, in card widths.
     *
     * A pile is wider than a card — the tally sits down its left edge, and a turned card leans past
     * the upright one's side — so a row that budgeted a card per entry would put its last pile off
     * the edge.
     */
    fun widthInCards(): Float = if (this is Pile) stackWidthInCards(halves()) else 1f

    /**
     * How tall this is, in card **widths** — the unit `StackShape` measures in.
     *
     * **An upright pile is exactly a card tall**, which it was not while a pile was a fan: the
     * stagger and the room kept below for a turned half came out of every card on the board. A turned
     * one is genuinely taller, because a square on its corner is √2 across.
     */
    fun heightInCards(): Float = if (this is Pile) stackHeightInCards(halves()) else cardHeightInCards()

    /** A permanent on its own: every real card, and any token nothing matches. */
    data class Single(
        val permanent: TablePermanent,
    ) : RowEntry {
        override val permanents: List<TablePermanent> get() = listOf(permanent)
    }

    /**
     * Identical tokens, drawn as one pile.
     *
     * Uniformly upright or uniformly turned — see [BattlefieldSide.entriesIn] — so [asStack] hands
     * the pile renderer one populated half and one empty one.
     */
    data class Pile(
        val members: List<TablePermanent>,
    ) : RowEntry {
        override val permanents: List<TablePermanent> get() = members

        /** The pile in the shape the stack renderer takes, which is the lands' own. */
        fun asStack(): TableLandStack =
            TableLandStack(
                untapped = members.filterNot { it.state.tapped },
                tapped = members.filter { it.state.tapped },
            )

        /**
         * The one half this pile has, and the only one it can ever have.
         *
         * A land stack reserves both because a land taps without leaving it. A token that taps leaves
         * for a pile of its own — tap state is part of the key — so this pile reserving the other
         * half would be reserving room nothing can arrive in, in both directions: a pile of upright
         * tokens claimed the leaning half's overhang and the drop below it, and a pile of tapped ones
         * hung a title bar below every card beside it.
         */
        internal fun halves(): StackHalves = StackHalves.of(asStack())
    }
}

/**
 * What makes two tokens the same pile, or `null` for a permanent that may never pile at all.
 *
 * `null` for every non-token, and for a token carrying an attachment — an Aura is on *that* Zombie,
 * so "read one and you have read them all" stops being true, exactly as it does for lands.
 *
 * The whole drawing state is the key, **tap state included**, plus the printing. Nothing is dropped
 * the way a land drops playability: a creature's tap state is what it is doing.
 */
internal data class TokenPileKey(
    val state: BoardCardState,
    val art: CardArtRequest?,
)

private fun TablePermanent.tokenPileKey(): TokenPileKey? =
    if (!isToken || carriesAttachment) null else TokenPileKey(state = state, art = art)

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
private data class LandStackKey(
    val state: BoardCardState,
    val art: CardArtRequest?,
)

/**
 * The key with **tap state removed**, since that is what the stack has two halves for.
 *
 * **And playability with it, because for a land that is the same fact.** An untapped Swamp is in
 * `canPlayObjects` — you can tap it for mana — and a tapped one is not, so leaving the signal in the
 * key split every land the moment one of them was used: four upright Swamps in one stack and five
 * leaning ones in a second stack beside it, which is precisely the pile the stack exists to avoid.
 * Found on a real board.
 *
 * Only [BoardCardSignal.Playable] is dropped. The others — being targeted, being a threat, being in
 * combat — are facts about *that* permanent rather than about which half it is in, and a stack that
 * hid them would be claiming "read one and you have read them all" when it is not true.
 *
 * `null` for a permanent that may never stack: see the class doc above.
 */
private fun TablePermanent.landStackKey(): LandStackKey? =
    if (carriesAttachment) {
        null
    } else {
        LandStackKey(
            state = state.copy(tapped = false, signals = state.signals - BoardCardSignal.Playable),
            art = art,
        )
    }

private fun TablePermanent.asOwnStack(): TableLandStack =
    if (state.tapped) {
        TableLandStack(untapped = emptyList(), tapped = listOf(this))
    } else {
        TableLandStack(untapped = listOf(this), tapped = emptyList())
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
) {
    /**
     * A permanent by its server id, from either side, including the ones folded onto a host.
     *
     * Which side a card is on is not something a player asks when they tap it — they tap a card and
     * expect to read it — so lookup crosses both, and it reaches attachments too because an Aura drawn
     * on somebody else's creature is still a card somebody may want to read.
     */
    fun permanentById(id: String): TablePermanent? =
        (listOfNotNull(viewer) + opponents)
            .flatMap { it.permanents }
            .firstOrNull { it.id == id }

    /**
     * An attached permanent by its id, from either side.
     *
     * Attachments are folded onto their hosts and so are not in anybody's `permanents` list, but they
     * are still cards on the board with their own faces and their own text. A press on the band that
     * an attachment stack exists to expose has to be able to find one.
     */
    fun attachmentById(id: String): TableAttachment? =
        (listOfNotNull(viewer) + opponents)
            .flatMap { side -> side.permanents.flatMap { it.attached } }
            .firstOrNull { it.id == id }
}

/**
 * A permanent as the inspect overlay shows it.
 *
 * The same overlay a card in hand opens, from the same gesture, because reading a card is one thing
 * wherever the card is. What differs is only what there is to read: a permanent's abilities are the
 * server's game-aware text, so a creature that can currently fly says so.
 *
 * @param oracleText the **printed** text, which the wire does not carry. Supplied by whoever is
 *   showing the preview, from the device's own card database.
 */
fun permanentPreview(
    permanent: TablePermanent,
    oracleText: String? = null,
): CardPreviewState =
    CardPreviewState(
        card = permanent.state.card,
        power = permanent.state.power,
        toughness = permanent.state.toughness,
        abilities = permanent.abilities,
        oracleText = oracleText,
        // What is on the permanent is part of reading the permanent. Pacifism is the reason the Craw
        // Wurm is not attacking, and at board size the Aura is a name band behind its host — so this
        // is the only place its text can be read at all.
        attachments =
            permanent.attached.map { attachment ->
                CardPreviewAttachment(
                    name = attachment.card.name,
                    manaCost = attachment.card.manaCost,
                    rules = attachment.abilities,
                )
            },
        // No action. A permanent's abilities are activated through the server's own prompt (§7.6), and
        // a button here would be this client deciding what may be done, which is the one thing the
        // cast flow refuses to do anywhere else.
        action = null,
    )

/**
 * An attached permanent as the inspect overlay shows it.
 *
 * The same overlay as its host's, because it is a card in play like any other — it is only drawn
 * smaller. It carries no attachments of its own: nothing in Magic attaches to an Aura.
 */
fun attachmentPreview(
    attachment: TableAttachment,
    oracleText: String? = null,
): CardPreviewState =
    CardPreviewState(
        card = attachment.card,
        abilities = attachment.abilities,
        oracleText = oracleText,
        action = null,
    )

/**
 * What the outstanding prompt says about the cards on the board.
 *
 * **The board has to draw the question, not just the game.** Without this, a prompt whose candidates
 * are permanents — "separate all permanents target player controls into two piles", "sacrifice a
 * creature", anything with a target on the battlefield — draws a board that looks exactly like a board
 * with nothing pending. The player has no way to see which cards answer it, and no way to see that a
 * pick they made landed, because the only feedback was the card detail closing.
 *
 * **Two channels, because the two facts are different in kind.** A card that can answer the question
 * is drawn with the board's own `Playable` green — the colour that already means *you can act on
 * this*, and a player who has learned it once should not have to learn a second one for the same
 * idea. A card the player has *chosen* is tinted the same green across its whole face, because what
 * is being assembled is a set and a border cannot say "in the set" while also saying "eligible".
 *
 * Both come straight from the prompt: [pickable] is the server's own candidate list, [picked] its own
 * `chosenTargets`. Nothing is inferred.
 *
 * @property pickable ids the outstanding prompt can be answered with — the server's candidates **and**
 *   what it already holds. The union, deliberately: `Target.keepValidPossibleTargets` drops chosen ids
 *   from `possibleTargets` ("keep only valid and *not selected* targets"), but upstream still answers a
 *   chosen id — `HumanPlayer.choose` removes it before it ever consults `possibleTargets` — so a chosen
 *   card is still a card that can be pressed, to take the choice back.
 * @property picked ids already sent as part of the answer.
 */
data class PromptPicks(
    val pickable: Set<String> = emptySet(),
    val picked: Set<String> = emptySet(),
)

/**
 * What this prompt says about the board, or nothing for a prompt the board already draws its own way.
 *
 * The gate is [PromptControlsUi.marksCandidatesOnBoard], which is the prompt's own answer: a priority
 * window's candidates are already `Playable` for its own reason, and a mana payment's are the cost
 * being assembled.
 */
fun PromptControlsUi?.boardPicks(): PromptPicks =
    if (this == null || !marksCandidatesOnBoard) {
        PromptPicks()
    } else {
        PromptPicks(pickable = pickableObjectIds + chosenObjectIds, picked = chosenObjectIds)
    }

/** The battlefield in [state], arranged, with [picks] marking what the outstanding question is about. */
fun battlefieldModel(
    state: GameState,
    picks: PromptPicks = PromptPicks(),
): BattlefieldModel {
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
                                        picks = picks,
                                    ),
                                art = artRequestOf(permanent.card),
                                carriesAttachment = permanent.attachments.isNotEmpty(),
                                isToken = permanent.card.isToken,
                                abilities = permanent.card.rules,
                                attached = attachedCardsOf(permanent, everyPermanent),
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
 * says is hidden.
 *
 * **A token is asked for by name**, because upstream leaves its collector number empty — see
 * [tokenArtRequest]. It still has a set, and that set is the one upstream chose for the token's
 * *image* rather than the set of the card that made it, which is what makes the lookup work.
 *
 * [GameCard.transformed] is what says a permanent is *currently* showing its back face, and a
 * double-faced card's two faces share one printing — so which face is up is entirely a matter of which
 * [CardArtFace] is asked for.
 */
internal fun artRequestOf(card: GameCard): CardArtRequest? {
    // **A face-down permanent has a picture, and which one says what it is.** Morph, manifest, cloak,
    // disguise and foretell are five different things a player must tell apart at a glance — what may
    // be turned up, for how much, and by whom — and Magic prints a distinct helper card for each.
    // Drawing all five as one blank was the board declining to say something the server had told it.
    // `GameCard.imageName` is upstream's own name for the kind; the printings are upstream's too.
    if (card.isFaceDown) {
        return card.imageName
            ?.let { faceDownArtRequest(it, CardArtSize.ART_CROP) }
            ?: cardBackRequest(CardArtSize.ART_CROP)
    }
    val set = card.setCode?.takeIf { it.isNotBlank() } ?: return null
    // **An emblem names no printing, but upstream's table names one for it.** What reaches here is an
    // emblem's trigger on the stack, whose source is the emblem itself: its name, the set upstream chose
    // for its image and no card number. Only an exact key in that table answers, so an ordinary card
    // with a missing number is not mistaken for one. It depends on nothing but the emblem, which is
    // what lets it outlive the planeswalker that made it.
    if (card.collectorNumber.isNullOrBlank()) {
        emblemArtRequest(setCode = set, name = card.name, imageNumber = card.imageNumber, size = CardArtSize.ART_CROP)
            ?.let { return it }
    }
    if (card.isToken) return tokenArtRequest(setCode = set, name = card.name, size = CardArtSize.ART_CROP)
    val number = card.collectorNumber?.takeIf { it.isNotBlank() } ?: return null
    return CardArtRequest(
        setCode = set,
        collectorNumber = number,
        face = if (card.transformed) CardArtFace.BACK else CardArtFace.FRONT,
        // The illustration on its own. Everything on a battlefield is drawn at the Board tier, which
        // shows the art and nothing else — so it asks for the art and nothing else, rather than being
        // handed a whole card and made to cut the frame off it.
        size = CardArtSize.ART_CROP,
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
            id = attached.card.id,
        )
    }

/** The same attachments, with the printings and text the card tier has no room for. */
private fun attachedCardsOf(
    permanent: GamePermanent,
    everyPermanent: Map<String, GamePermanent>,
): List<TableAttachment> =
    permanent.attachments.mapNotNull { id ->
        val attached = everyPermanent[id] ?: return@mapNotNull null
        TableAttachment(
            id = attached.card.id,
            card =
                CardDisplay(
                    name = attached.card.name,
                    manaCost = attached.card.manaCost,
                    typeLine = attached.card.typeLine,
                ),
            art = artRequestOf(attached.card),
            abilities = attached.card.rules,
        )
    }

/** One permanent's full drawing state. */
private fun boardCardState(
    permanent: GamePermanent,
    attachments: List<BoardAttachment>,
    combat: CombatAssignment,
    playable: Set<String>,
    picks: PromptPicks,
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
        power = card.shownPower,
        toughness = card.shownToughness,
        counters = card.counters.map { BoardCounter(name = it.name, count = it.count) },
        badges = card.icons.mapNotNull(::badgeOf),
        attachments = attachments,
        tapped = permanent.isTapped,
        signals = signalsOf(permanent, combat, playable, picks),
        isSelected = permanent.card.id in picks.picked,
        // The server's own answer, and drawn only on creatures: it is true of any permanent that
        // arrived this turn, but it is a *creature* that a player is about to try to attack with.
        hasSummoningSickness = permanent.hasSummoningSickness && card.isCreature,
        // A morph or a manifest is drawn as the whole helper card, with no name band — see
        // [BoardCardState.isFaceDown]. The server's own answer, never inferred from a blank name.
        isFaceDown = card.isFaceDown,
    )
}

/**
 * Which signals apply to [permanent] right now.
 *
 * All of them are the server's own answers: combat assignment comes from `GameState.combat`,
 * playability from `GameState.playable`, and the picks from the outstanding prompt's own candidate
 * and chosen lists. None of it is inferred from the permanent.
 *
 * **A candidate is `Playable`, and so is a card you could cast.** One colour, one meaning — *you can
 * act on this* — because a player who has learned the green border once should not have to learn a
 * second colour for the same idea depending on which question is outstanding. Being *chosen* is the
 * other channel entirely: [BoardCardState.isSelected], a tint over the whole face.
 */
private fun signalsOf(
    permanent: GamePermanent,
    combat: CombatAssignment,
    playable: Set<String>,
    picks: PromptPicks,
): Set<BoardCardSignal> =
    buildSet {
        val id = permanent.card.id
        if (id in combat.attackerIds) add(BoardCardSignal.Attacking)
        if (id in combat.blockerIds) add(BoardCardSignal.Blocking)
        if (id in playable || id in picks.pickable) add(BoardCardSignal.Playable)
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
internal fun badgeOf(icon: GameCardIcon): BoardBadge? =
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

/*
 * Power and toughness, shown only where they mean something.
 *
 * **A noncreature permanent's power and toughness are `"0"`, not absent.** Upstream fills both on
 * every object, so a Swamp arrives as a 0/0 and a Liliana's Mastery as a 0/0, and a board that carried
 * them through drew "0/0" on every land and every enchantment. `BoardUi` gated on this from the start
 * and wrote down why; the table tier did not, and the marks doubling in size is what made it
 * impossible to miss.
 *
 * **The predicate is the server's own**, `GameCard.isCreature` — upstream's `CardView.isCreature()`,
 * which is game state and not printing. An animated land *is* a creature and says so, and stops being
 * one when the effect ends. Reading the type line instead would put rules interpretation in the client
 * and would be wrong for exactly the cards that make this worth getting right.
 *
 * The strings stay strings and nothing parses them: `*` is a real power.
 */

/** This object's power, or `null` where a power is not a thing it has. */
internal val GameCard.shownPower: String? get() = if (showsStats) power else null

/** This object's toughness, on the same terms. */
internal val GameCard.shownToughness: String? get() = if (showsStats) toughness else null

private val GameCard.showsStats: Boolean
    get() = isCreature && !power.isNullOrBlank() && !toughness.isNullOrBlank()

/**
 * How tall a row of these is, in card widths — the tallest entry in it, or zero for an empty row.
 *
 * An empty row costs nothing, which is the board's own rule about regions that hold height.
 */
internal fun List<RowEntry>.heightInCards(): Float = maxOfOrNull { it.heightInCards() } ?: 0f
