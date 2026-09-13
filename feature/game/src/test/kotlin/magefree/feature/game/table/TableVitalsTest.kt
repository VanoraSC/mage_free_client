package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.network.game.CommandObjectKind
import magefree.network.game.GameCommandObject
import magefree.network.game.GameCounter
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.ManaPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(listOf("Emblem — Elspeth"), seat.commandObjects.map { it.card.name })
    }

    @Test
    fun `an emblem is drawn from upstream's own image for it, since it names no printing`() {
        // Liliana, the Last Hope's emblem as the server sends it: the set upstream chose for its image
        // and no card number. It drew as a placeholder.
        val emblem =
            seatWith(
                commandList =
                    listOf(
                        GameCommandObject(
                            id = "e1",
                            name = "Emblem Liliana",
                            kind = CommandObjectKind.Emblem,
                            setCode = "EMN",
                            rules = listOf("At the beginning of your end step, create X 2/2 black Zombie creature tokens.", " "),
                        ),
                    ),
            ).commandObjects.single()

        assertEquals(CardArtRequest(setCode = "temn", collectorNumber = "9"), emblem.art)
        assertEquals("Emblem", emblem.card.typeLine)
        assertEquals(listOf("At the beginning of your end step, create X 2/2 black Zombie creature tokens."), emblem.rules)
    }

    @Test
    fun `two emblems of one name in one set are drawn from their own images`() {
        val (first, second) =
            seatWith(
                commandList =
                    listOf(
                        GameCommandObject(
                            id = "c1",
                            name = "Emblem Chandra",
                            kind = CommandObjectKind.Emblem,
                            setCode = "CMM",
                            imageNumber = 1,
                        ),
                        GameCommandObject(
                            id = "c2",
                            name = "Emblem Chandra",
                            kind = CommandObjectKind.Emblem,
                            setCode = "CMM",
                            imageNumber = 2,
                        ),
                    ),
            ).commandObjects

        assertEquals("78", first.art?.collectorNumber)
        assertEquals("79", second.art?.collectorNumber)
    }

    @Test
    fun `a commander is drawn from its own printing, and a dungeon or an unknown emblem from nothing`() {
        val (commander, dungeon, karn) =
            seatWith(
                commandList =
                    listOf(
                        GameCommandObject(
                            id = "a",
                            name = "Atraxa, Praetors' Voice",
                            kind = CommandObjectKind.Commander,
                            setCode = "C16",
                            collectorNumber = "28",
                        ),
                        GameCommandObject(id = "d", name = "Tomb of Annihilation", kind = CommandObjectKind.Dungeon, setCode = "AFR"),
                        GameCommandObject(id = "k", name = "Emblem Karn", kind = CommandObjectKind.Emblem, setCode = "DMU"),
                    ),
            ).commandObjects

        assertEquals(CardArtRequest(setCode = "C16", collectorNumber = "28"), commander.art)
        assertNull("a dungeon names no printing", dungeon.art)
        assertNull("upstream has no image for this emblem either", karn.art)
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
    fun `the floating mana is one entry per colour, in the game's own symbols`() {
        // A total said "three mana" where the game said "one white and two green", and mid-cast that
        // difference is the whole question of whether the spell in hand can be paid for.
        val seat = seatWith(manaPool = ManaPool(white = 1, green = 2))

        assertEquals(
            listOf(TableManaPoolEntry("{W}", 1), TableManaPoolEntry("{G}", 2)),
            seat.floatingMana,
        )
    }

    @Test
    fun `a colour with nothing in the pool is not drawn at all`() {
        assertEquals(emptyList<TableManaPoolEntry>(), seatWith(manaPool = ManaPool()).floatingMana)
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
