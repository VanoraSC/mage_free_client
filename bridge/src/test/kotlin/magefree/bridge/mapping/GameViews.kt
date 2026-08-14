package magefree.bridge.mapping

import mage.constants.CardType
import mage.constants.PhaseStep
import mage.constants.SubType
import mage.constants.SuperType
import mage.constants.TurnPhase
import mage.counters.Counter
import mage.players.PlayableObjectStats
import mage.players.PlayableObjectsList
import mage.util.SubTypes
import mage.view.CardView
import mage.view.CardsView
import mage.view.CombatGroupView
import mage.view.CounterView
import mage.view.ExileView
import mage.view.GameView
import mage.view.ManaPoolView
import mage.view.PermanentView
import mage.view.PlayerView
import mage.view.RevealedView
import java.util.UUID

/**
 * Builders for **crafted** `mage.view.*` game views, for the hermetic mapper tests.
 *
 * XMage's in-game view types have no usable public constructors outside a running game: `GameView` needs
 * a `GameState` + `Game`, `PlayerView` a `Player`, `ManaPoolView` a `ManaPool` — all of which need a
 * concrete game type (`Mage.Game.TwoPlayerDuel`) and the server's card database, neither of which is on
 * the bridge's classpath. The alternative to building them here is to have *no* hermetic coverage of
 * [GameViewMapper] at all and rely solely on the live IT, which is exactly the "green tests that prove
 * nothing" trap the verification standards exist to avoid.
 *
 * So each view is allocated through the JDK's **serialization constructor** (`ReflectionFactory`, the
 * mechanism `ObjectInputStream` itself uses — these classes are all `Serializable`, so this is the same
 * shape of object the remoting layer hands the bridge in production) and its fields are set reflectively.
 * The *getters under test are the real ones*: nothing here is a stub or a double of `GameView`.
 *
 * Because the serialization constructor skips field initialisers, an unset collection field is `null` —
 * which is itself useful: it is precisely the "upstream sent a sparse view" case the mapper must survive.
 */
internal object GameViews {
    private val reflectionFactory = sun.reflect.ReflectionFactory.getReflectionFactory()
    private val objectConstructor = Any::class.java.getDeclaredConstructor()

    /** Allocates [type] without running its constructor, exactly as deserialization does. */
    private fun <T : Any> allocate(type: Class<T>): T {
        val constructor = reflectionFactory.newConstructorForSerialization(type, objectConstructor)
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance() as T
    }

    /** Sets the (possibly inherited, possibly final) field [name] on this object. */
    private fun Any.set(
        name: String,
        value: Any?,
    ) {
        var type: Class<*>? = javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(this, value)
                return
            }
            type = type.superclass
        }
        error("no field '$name' on ${javaClass.name}")
    }

    /**
     * A `CardView` as the server would send one: identifiable printing, type line, rules, P/T, and the
     * counters on the object.
     *
     * [counters] is built through upstream's **own** `CounterView(Counter)` constructor rather than
     * reflectively, because that constructor is real production code (`name` and `count` come off
     * `mage.counters.Counter`) and is on the bridge's classpath.
     *
     * Note that `CardView.isCreature()` is not a settable field: upstream computes it as
     * `cardTypes.contains(CREATURE)`, so [cardTypes] is what drives it here exactly as it does in a
     * running game.
     */
    fun card(
        id: UUID = UUID.randomUUID(),
        name: String = "Forest",
        setCode: String? = "M21",
        collectorNumber: String? = "272",
        manaCost: List<String> = emptyList(),
        cardTypes: List<CardType> = listOf(CardType.LAND),
        superTypes: List<SuperType> = listOf(SuperType.BASIC),
        subTypes: List<SubType> = listOf(SubType.FOREST),
        power: String = "",
        toughness: String = "",
        rules: List<String> = listOf("({T}: Add {G}.)"),
        faceDown: Boolean = false,
        counters: List<Pair<String, Int>> = emptyList(),
    ): CardView =
        allocate(CardView::class.java).apply {
            set("counters", counters.map { (counterName, count) -> CounterView(Counter(counterName, count)) })
            set("id", id)
            set("expansionSetCode", setCode)
            set("cardNumber", collectorNumber)
            set("name", name)
            set("rules", rules)
            set("power", power)
            set("toughness", toughness)
            set("cardTypes", cardTypes)
            set("superTypes", superTypes)
            set("subTypes", SubTypes(*subTypes.toTypedArray()))
            set("manaCostLeftStr", manaCost)
            set("manaCostRightStr", emptyList<String>())
            set("faceDown", faceDown)
        }

    /** A `PermanentView` (a `CardView` plus battlefield state). */
    fun permanent(
        card: CardView = card(),
        tapped: Boolean = false,
        flipped: Boolean = false,
        phasedIn: Boolean = true,
        summoningSickness: Boolean = false,
        damage: Int = 0,
        attachedTo: UUID? = null,
        controlled: Boolean = true,
    ): PermanentView =
        allocate(PermanentView::class.java).apply {
            copyCardFieldsFrom(card)
            set("tapped", tapped)
            set("flipped", flipped)
            set("phasedIn", phasedIn)
            set("summoningSickness", summoningSickness)
            set("damage", damage)
            set("attachedTo", attachedTo)
            set("controlled", controlled)
        }

    /** Copies the `CardView` half of a permanent from an already-built [card]. */
    private fun PermanentView.copyCardFieldsFrom(card: CardView) {
        set("id", card.id)
        set("expansionSetCode", card.expansionSetCode)
        set("cardNumber", card.cardNumber)
        set("name", card.name)
        set("rules", card.rules)
        set("power", card.power)
        set("toughness", card.toughness)
        set("cardTypes", card.cardTypes)
        set("superTypes", card.superTypes)
        set("subTypes", card.subTypes)
        set("counters", card.counters)
        set("manaCostLeftStr", card.manaCostSymbols)
        set("manaCostRightStr", emptyList<String>())
        set("faceDown", card.isFaceDown)
    }

    /** A `ManaPoolView` with the given floating mana. */
    fun manaPool(
        white: Int = 0,
        blue: Int = 0,
        black: Int = 0,
        red: Int = 0,
        green: Int = 0,
        colorless: Int = 0,
    ): ManaPoolView =
        allocate(ManaPoolView::class.java).apply {
            set("white", white)
            set("blue", blue)
            set("black", black)
            set("red", red)
            set("green", green)
            set("colorless", colorless)
        }

    /** A `PlayerView` — the seat, its counts, and the active/priority flags the app gates on. */
    fun player(
        playerId: UUID = UUID.randomUUID(),
        name: String = "alice",
        life: Int = 20,
        libraryCount: Int = 53,
        handCount: Int = 7,
        graveyard: CardsView = CardsView(),
        exile: CardsView = CardsView(),
        wins: Int = 0,
        winsNeeded: Int = 1,
        controlled: Boolean = true,
        active: Boolean = true,
        hasPriority: Boolean = true,
        human: Boolean = true,
        hasLeft: Boolean = false,
        manaPool: ManaPoolView = manaPool(),
        battlefield: List<PermanentView> = emptyList(),
    ): PlayerView =
        allocate(PlayerView::class.java).apply {
            set("playerId", playerId)
            set("name", name)
            set("life", life)
            set("libraryCount", libraryCount)
            set("handCount", handCount)
            set("graveyard", graveyard)
            set("exile", exile)
            set("wins", wins)
            set("winsNeeded", winsNeeded)
            set("controlled", controlled)
            set("isActive", active)
            set("hasPriority", hasPriority)
            set("isHuman", human)
            set("hasLeft", hasLeft)
            set("manaPool", manaPool)
            set("battlefield", battlefield.associateByTo(LinkedHashMap()) { it.id })
        }

    /** A `CombatGroupView` — who is attacking whom, and what is blocking. */
    fun combatGroup(
        defenderId: UUID? = UUID.randomUUID(),
        defenderName: String = "Computer",
        blocked: Boolean = true,
        attackers: List<PermanentView> = emptyList(),
        blockers: List<PermanentView> = emptyList(),
    ): CombatGroupView =
        allocate(CombatGroupView::class.java).apply {
            set("defenderId", defenderId)
            set("defenderName", defenderName)
            set("isBlocked", blocked)
            set("attackers", cardsView(attackers))
            set("blockers", cardsView(blockers))
        }

    /**
     * A named exile zone holding [cards].
     *
     * `ExileView` extends `CardsView` extends `LinkedHashMap`, and the serialization constructor skips
     * `HashMap`'s too — leaving `loadFactor` at `0.0f`. `putMapEntries` then pre-sizes to
     * `ceil(size / 0.0) = Infinity` → `MAXIMUM_CAPACITY`, and the first insert tries to allocate a
     * 2^30-entry table. Restoring the default load factor before inserting is what keeps that a map
     * rather than an `OutOfMemoryError`.
     */
    fun exileZone(
        name: String = "Exile",
        id: UUID = UUID.randomUUID(),
        cards: List<CardView> = emptyList(),
    ): ExileView =
        allocate(ExileView::class.java).apply {
            set("loadFactor", DEFAULT_LOAD_FACTOR)
            set("name", name)
            set("id", id)
            putAll(cards.associateBy { it.id })
        }

    /** `HashMap.DEFAULT_LOAD_FACTOR`, which the serialization constructor does not set. */
    private const val DEFAULT_LOAD_FACTOR = 0.75f

    /** A named revealed set holding [cards]. */
    fun revealed(
        name: String = "Revealed",
        cards: List<CardView> = emptyList(),
    ): RevealedView =
        allocate(RevealedView::class.java).apply {
            set("name", name)
            set("cards", cardsView(cards))
        }

    /** A `CardsView` (upstream's ordered id→card map) over [cards]. */
    fun cardsView(cards: List<CardView>): CardsView = CardsView().apply { putAll(cards.associateBy { it.id }) }

    /**
     * The server's `canPlayObjects` for [playable]: object id → the ids of the abilities that make it
     * playable. Built through `PlayableObjectStats`' own record type, so `getPlayableAbilityIds()` — the
     * getter the mapper reads — is exercised for real.
     */
    fun playableObjects(playable: Map<UUID, List<UUID>>): PlayableObjectsList =
        PlayableObjectsList().apply {
            playable.forEach { (objectId, abilityIds) ->
                objects[objectId] =
                    PlayableObjectStats().apply {
                        set("basicCastAbilities", abilityIds.map(::playableRecord).toMutableList())
                    }
            }
        }

    /** One `mage.players.PlayableObjectRecord` (package-private upstream) for [abilityId]. */
    private fun playableRecord(abilityId: UUID): Any {
        val type = Class.forName("mage.players.PlayableObjectRecord")
        val constructor = type.getDeclaredConstructor(UUID::class.java, String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(abilityId, "ability $abilityId")
    }

    /**
     * A whole `GameView`. Unset fields stay `null` (the serialization constructor skips initialisers),
     * which is the sparse-view case the mapper must survive; pass what a test asserts on.
     */
    fun game(
        turn: Int = 3,
        phase: TurnPhase? = TurnPhase.PRECOMBAT_MAIN,
        step: PhaseStep? = PhaseStep.PRECOMBAT_MAIN,
        activePlayerId: UUID? = null,
        activePlayerName: String = "alice",
        priorityPlayerName: String = "alice",
        myPlayerId: UUID? = null,
        players: List<PlayerView> = emptyList(),
        hand: List<CardView> = emptyList(),
        stack: List<CardView> = emptyList(),
        exiles: List<ExileView> = emptyList(),
        revealed: List<RevealedView> = emptyList(),
        combat: List<CombatGroupView> = emptyList(),
        canPlayObjects: PlayableObjectsList? = null,
        special: Boolean = false,
        priorityTime: Int = 1200,
        bufferTime: Int = 5,
    ): GameView =
        allocate(GameView::class.java).apply {
            set("turn", turn)
            set("phase", phase)
            set("step", step)
            set("activePlayerId", activePlayerId)
            set("activePlayerName", activePlayerName)
            set("priorityPlayerName", priorityPlayerName)
            set("myPlayerId", myPlayerId)
            set("players", players)
            set("myHand", cardsView(hand))
            set("stack", cardsView(stack))
            set("exiles", exiles)
            set("revealed", revealed)
            set("combat", combat)
            set("canPlayObjects", canPlayObjects)
            set("special", special)
            set("priorityTime", priorityTime)
            set("bufferTime", bufferTime)
        }
}
