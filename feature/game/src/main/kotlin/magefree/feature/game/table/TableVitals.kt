package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.art.emblemArtRequest
import magefree.designsystem.card.CardDisplay
import magefree.network.game.CommandObjectKind
import magefree.network.game.GameCommandObject
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.ManaPool

/*
 * What decides a game without being on the battlefield.
 *
 * §7.15's list, and its argument for showing it: *"most of this is zero most of the time, so it earns
 * its room by asking for almost none until there is something to say."*
 *
 * **Nothing here is derived.** Life, the zone counts, the counters, the designations and the command
 * objects are all the server's own values. The single judgement this file makes is what to put first,
 * which is a question about attention rather than about rules.
 */

/**
 * One counter on a player, ready to draw.
 *
 * @property name the server's own name for it — `Poison`, `Energy`, `Experience`, or anything a new
 *   set introduces. Never matched against except for [isPoison], and that one match is documented
 *   where it is made.
 * @property count how many.
 * @property isPoison whether this is the one counter that ends a game on its own.
 * @property isNearLethal whether the player is close enough to losing that the board should say so.
 */
data class TablePlayerCounter(
    val name: String,
    val count: Int,
    val isPoison: Boolean = false,
    val isNearLethal: Boolean = false,
)

/**
 * One colour of mana floating in a player's pool.
 *
 * **The colour is the whole point, and a total loses it.** The pool was drawn as one amber chip with
 * the total in it, which said "three mana" where the game said "three black" — and mid-cast that
 * difference is the whole question of whether the spell in hand can be paid for. [symbol] is the mana
 * symbol in `SymbolText`'s own notation, so the board draws the game's own picture of the colour
 * rather than picking a swatch to stand for it.
 *
 * @property symbol the mana symbol, e.g. `{B}`.
 * @property count how many of that colour are floating.
 */
data class TableManaPoolEntry(
    val symbol: String,
    val count: Int,
)

/**
 * One object in a player's command zone — an emblem, a commander, a dungeon or a plane — as a card.
 *
 * **Drawn and read like any card**, because that is what a player does with one: an emblem is a
 * picture and some text that acts on the game from outside every zone, and pressing it is asking what
 * the text says.
 *
 * @property id the server's own id — what a press names, and what the detail is looked up by.
 * @property card the face: the object's name, its kind as the type line, and its rules as the text.
 * @property art the image to draw it from, or `null` where there is none — see [artRequest].
 * @property rules the server's own text for it, in its own order, blank lines dropped.
 */
data class TableCommandObject(
    val id: String,
    val card: CardDisplay,
    val art: CardArtRequest? = null,
    val rules: List<String> = emptyList(),
) {
    /** The illustration alone, for the Board tier. */
    val boardArt: CardArtRequest? get() = art?.copy(size = CardArtSize.ART_CROP)

    /** Full resolution, for the detail. */
    val fullArt: CardArtRequest? get() = art?.copy(size = CardArtSize.LARGE)
}

private fun GameCommandObject.toTable(): TableCommandObject =
    TableCommandObject(
        id = id,
        card =
            CardDisplay(
                name = name,
                typeLine = kind.label,
                oracleText = rules.joinToString("\n").takeIf { it.isNotBlank() },
            ),
        art = artRequest(),
        rules = rules.mapNotNull { it.trim().ifBlank { null } },
    )

/**
 * The image for a command object.
 *
 * - **A commander, and an emblem made of a card** (`EmblemOfCard`), name a real printing, and are drawn
 *   from it.
 * - **Any other emblem** names only the set upstream chose for its image, and is looked up in upstream's
 *   own table — see [emblemArtRequest]. One upstream has no image for keeps the placeholder.
 * - **A dungeon or a plane** names no printing and is in no emblem table, so it keeps the placeholder.
 */
internal fun GameCommandObject.artRequest(): CardArtRequest? {
    val set = setCode?.takeIf { it.isNotBlank() } ?: return null
    collectorNumber?.takeIf { it.isNotBlank() }?.let { number ->
        return CardArtRequest(setCode = set, collectorNumber = number)
    }
    return if (kind == CommandObjectKind.Emblem) emblemArtRequest(setCode = set, name = name, imageNumber = imageNumber) else null
}

/** What the type line says a command object is. Nothing, for a kind this build does not know. */
private val CommandObjectKind.label: String?
    get() =
        when (this) {
            CommandObjectKind.Emblem -> "Emblem"
            CommandObjectKind.Commander -> "Commander"
            CommandObjectKind.Dungeon -> "Dungeon"
            CommandObjectKind.Plane -> "Plane"
            CommandObjectKind.Unknown -> null
        }

/**
 * One player's vitals.
 *
 * @property counters every **non-zero** counter, poison first. A counter at zero is not shown, for the
 *   same reason an empty region holds no height: a board that reserved a chip for energy in every game
 *   would spend the space on nothing in almost all of them.
 * @property commandObjects emblems, dungeons, commanders and planes — the things acting on the game
 *   from outside every zone a player can look through — in the server's order, each as a card.
 */
data class TableVitals(
    val playerId: String,
    val name: String,
    val isViewer: Boolean,
    val life: Int,
    val libraryCount: Int,
    val handCount: Int,
    val graveyardCount: Int,
    val exileCount: Int,
    val floatingMana: List<TableManaPoolEntry>,
    val wins: Int,
    val winsNeeded: Int,
    val isActive: Boolean,
    val hasPriority: Boolean,
    val isMonarch: Boolean,
    val hasInitiative: Boolean,
    val designations: List<String>,
    val commandObjects: List<TableCommandObject>,
    val counters: List<TablePlayerCounter>,
) {
    /** True when the match is more than one game, which is the only time the score means anything. */
    val showsWins: Boolean get() = winsNeeded > 1

    /** True when this player is one loss away from being out of cards. */
    val isDecking: Boolean get() = libraryCount == 0
}

/** Every seat's vitals, in the server's order, with the viewer's own marked. */
fun tableVitals(state: GameState): List<TableVitals> = state.players.map(GamePlayer::toVitals)

private fun GamePlayer.toVitals(): TableVitals =
    TableVitals(
        playerId = playerId,
        name = name,
        isViewer = isViewer,
        life = life,
        libraryCount = libraryCount,
        handCount = handCount,
        graveyardCount = graveyardCount,
        exileCount = exileCount,
        floatingMana = manaPool.entries(),
        wins = wins,
        winsNeeded = winsNeeded,
        isActive = isActive,
        hasPriority = hasPriority,
        isMonarch = isMonarch,
        hasInitiative = hasInitiative,
        designations = designationNames,
        commandObjects = commandList.map { it.toTable() },
        counters = playerCounters(),
    )

/**
 * The counters worth drawing, poison first.
 *
 * **Poison leads because it is the only one that ends a game by itself.** Everything else is ordered as
 * the server sent it — which upstream warns is hash order, since `mage.counters.Counters` extends
 * `HashMap`, so there is no meaning in it to preserve and no reason to impose one either.
 */
private fun GamePlayer.playerCounters(): List<TablePlayerCounter> =
    counters
        .filter { it.count > 0 }
        .map { counter ->
            val poison = counter.name.equals(POISON_COUNTER, ignoreCase = true)
            TablePlayerCounter(
                name = counter.name,
                count = counter.count,
                isPoison = poison,
                isNearLethal = poison && counter.count >= NEAR_LETHAL_POISON,
            )
        }.sortedByDescending { it.isPoison }

/**
 * Upstream's own name for the counter, from `mage.counters.CounterType.POISON`.
 *
 * The one place a counter's name is matched against anything. It is worth the exception: poison is the
 * only counter whose *number* decides a game, so it is the only one the board can say anything true
 * about beyond how many there are.
 */
private const val POISON_COUNTER = "poison"

/**
 * Where the board starts calling poison out rather than merely counting it.
 *
 * Ten is a loss (CR 104.3c) in every format this client plays, so eight is two away — close enough
 * that a player needs to know without doing arithmetic, and far enough that it does not cry wolf. This
 * is presenting a rule the game already fixed, not predicting one.
 */
private const val NEAR_LETHAL_POISON = 8

/**
 * The pool, one entry per colour that has something in it, in the game's own WUBRG order.
 *
 * Colourless last because it is the one a player is least often waiting on, and generic is not in a
 * pool at all — it is a way of paying, not a thing that floats.
 */
private fun ManaPool.entries(): List<TableManaPoolEntry> =
    buildList {
        if (white > 0) add(TableManaPoolEntry(symbol = "{W}", count = white))
        if (blue > 0) add(TableManaPoolEntry(symbol = "{U}", count = blue))
        if (black > 0) add(TableManaPoolEntry(symbol = "{B}", count = black))
        if (red > 0) add(TableManaPoolEntry(symbol = "{R}", count = red))
        if (green > 0) add(TableManaPoolEntry(symbol = "{G}", count = green))
        if (colorless > 0) add(TableManaPoolEntry(symbol = "{C}", count = colorless))
    }
