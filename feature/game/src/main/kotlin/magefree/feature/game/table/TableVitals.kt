package magefree.feature.game.table

import magefree.network.game.GamePlayer
import magefree.network.game.GameState

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
 * One player's vitals.
 *
 * @property counters every **non-zero** counter, poison first. A counter at zero is not shown, for the
 *   same reason an empty region holds no height: a board that reserved a chip for energy in every game
 *   would spend the space on nothing in almost all of them.
 * @property commandObjects emblems, dungeons, commanders and planes — the things acting on the game
 *   from outside every zone a player can look through.
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
    val floatingMana: Int,
    val wins: Int,
    val winsNeeded: Int,
    val isActive: Boolean,
    val hasPriority: Boolean,
    val isMonarch: Boolean,
    val hasInitiative: Boolean,
    val designations: List<String>,
    val commandObjects: List<String>,
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
        floatingMana = manaPool.total,
        wins = wins,
        winsNeeded = winsNeeded,
        isActive = isActive,
        hasPriority = hasPriority,
        isMonarch = isMonarch,
        hasInitiative = hasInitiative,
        designations = designationNames,
        commandObjects = commandList.map { it.name },
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
