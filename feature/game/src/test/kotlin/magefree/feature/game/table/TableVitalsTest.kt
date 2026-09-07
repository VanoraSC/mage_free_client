package magefree.feature.game.table

import magefree.network.game.GameCommandObject
import magefree.network.game.GameCounter
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.ManaPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a seat is on, from the snapshot.
 *
 * Most of this is a field copy. The parts worth asserting are the two decisions: **which counters are
 * worth room** — a board that reserved a chip for energy in every game would spend the space on
 * nothing in almost all of them — and **when poison stops being a number and starts being a warning**,
 * because ten is a loss and the difference between "you have poison" and "you are two from losing" is
 * the entire value of showing it.
 */
class TableVitalsTest {
    @Test
    fun `a counter at zero takes no room`() {
        val seat = seatWith(counters = listOf(GameCounter("energy", 0), GameCounter("experience", 2)))

        assertEquals(listOf("experience"), seat.counters.map { it.name })
    }

    @Test
    fun `poison appears the moment it is not zero`() {
        // Not behind a tap, and not once it is dangerous: a number that can end the game is worth its
        // chip from the first one.
        val seat = seatWith(counters = listOf(GameCounter("poison", 1)))

        val poison = seat.counters.single()
        assertTrue(poison.isPoison)
        assertEquals(1, poison.count)
        assertFalse("one poison is not a warning", poison.isNearLethal)
    }

    @Test
    fun `poison is called out once it is close to lethal`() {
        // Ten is a loss (CR 104.3c). Eight is two away — close enough that a player needs to know
        // without doing arithmetic.
        assertFalse(poisonAt(7).isNearLethal)
        assertTrue(poisonAt(8).isNearLethal)
        assertTrue(poisonAt(10).isNearLethal)
    }

    @Test
    fun `poison leads, because it is the only counter that ends a game by itself`() {
        val seat =
            seatWith(
                counters = listOf(GameCounter("energy", 3), GameCounter("poison", 2), GameCounter("experience", 1)),
            )

        assertEquals("poison", seat.counters.first().name)
    }

    @Test
    fun `designations and command objects come through by name`() {
        val seat =
            seatWith(
                isMonarch = true,
                hasInitiative = true,
                designationNames = listOf("City's Blessing"),
                commandList = listOf(GameCommandObject(id = "e1", name = "Emblem — Elspeth")),
            )

        assertTrue(seat.isMonarch)
        assertTrue(seat.hasInitiative)
        assertEquals(listOf("City's Blessing"), seat.designations)
        assertEquals(listOf("Emblem — Elspeth"), seat.commandObjects)
    }

    @Test
    fun `an empty library is its own state, not just a count`() {
        assertTrue(seatWith(libraryCount = 0).isDecking)
        assertFalse(seatWith(libraryCount = 1).isDecking)
    }

    @Test
    fun `the match score is only shown when the match is more than one game`() {
        assertFalse(seatWith(winsNeeded = 1).showsWins)
        assertTrue(seatWith(winsNeeded = 2).showsWins)
    }

    @Test
    fun `a spectator sees both seats`() {
        val state =
            GameState(
                gameId = "g",
                isWatching = true,
                players = listOf(GamePlayer(playerId = "a", name = "A"), GamePlayer(playerId = "b", name = "B")),
            )

        assertEquals(listOf("a", "b"), tableVitals(state).map { it.playerId })
        assertEquals(listOf(false, false), tableVitals(state).map { it.isViewer })
    }

    @Test
    fun `the floating mana is the whole pool, not one colour of it`() {
        val seat = seatWith(manaPool = ManaPool(white = 1, green = 2))

        assertEquals(3, seat.floatingMana)
    }
}

private fun poisonAt(count: Int) = seatWith(counters = listOf(GameCounter("poison", count))).counters.single()

private fun seatWith(
    counters: List<GameCounter> = emptyList(),
    libraryCount: Int = 30,
    winsNeeded: Int = 1,
    isMonarch: Boolean = false,
    hasInitiative: Boolean = false,
    designationNames: List<String> = emptyList(),
    commandList: List<GameCommandObject> = emptyList(),
    manaPool: ManaPool = ManaPool(),
): TableVitals =
    tableVitals(
        GameState(
            gameId = "g",
            viewerPlayerId = "me",
            players =
                listOf(
                    GamePlayer(
                        playerId = "me",
                        name = "Me",
                        isViewer = true,
                        life = 20,
                        libraryCount = libraryCount,
                        winsNeeded = winsNeeded,
                        counters = counters,
                        isMonarch = isMonarch,
                        hasInitiative = hasInitiative,
                        designationNames = designationNames,
                        commandList = commandList,
                        manaPool = manaPool,
                    ),
                ),
        ),
    ).single()
