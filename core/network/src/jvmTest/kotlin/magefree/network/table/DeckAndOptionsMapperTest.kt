package magefree.network.table

import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.model.SkillLevel
import magefree.protocol.RangeCode
import magefree.protocol.SeatPlayerTypeCode
import magefree.protocol.SkillLevelCode
import magefree.protocol.TableDetail
import magefree.protocol.TableSeatSummary
import magefree.protocol.TableStateCode
import magefree.protocol.TableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hermetic coverage of the table client's mapper boundary: a 0033 domain [Deck] projects onto the wire
 * `DeckList` (reusing 0033's own `toDeckList`) and the app-schema [CreateTableOptions] projects onto the
 * wire `CreateTableOptions`, both losslessly and with the enums mapped one-to-one.
 */
class DeckAndOptionsMapperTest {
    @Test
    fun aDomainDeckMapsOntoTheWireDeckListPreservingEveryField() {
        val deck =
            Deck(
                id = DeckId("d-1"),
                name = "Mono Blue",
                author = "pete",
                main = listOf(DeckEntry(cardName = "Island", setCode = "XLN", collectorNumber = "265", quantity = 20)),
                sideboard = listOf(DeckEntry(cardName = "Negate", setCode = "XLN", collectorNumber = "070", quantity = 3)),
            )

        val wire = deck.toProtocolDeckList()

        assertEquals("Mono Blue", wire.name)
        assertEquals("pete", wire.author)
        assertEquals(1, wire.cards.size)
        assertEquals("Island", wire.cards.single().cardName)
        assertEquals("XLN", wire.cards.single().setCode)
        assertEquals("265", wire.cards.single().collectorNumber)
        assertEquals(20, wire.cards.single().amount)
        assertEquals("Negate", wire.sideboard.single().cardName)
        assertEquals(3, wire.sideboard.single().amount)
    }

    @Test
    fun createOptionsMapOntoTheWireOptionsWithEnumsAlignedOneToOne() {
        val options =
            CreateTableOptions(
                name = "Pete's Table",
                gameType = "Two Player Duel",
                deckType = "Constructed - Standard",
                players = listOf(SeatPlayerType.Human, SeatPlayerType.ComputerMonteCarlo),
                rated = true,
                winsNeeded = 2,
                skillLevel = SkillLevel.Serious,
                range = RangeOfInfluence.One,
                password = "secret",
            )

        val wire = options.toProtocol()

        assertEquals("Pete's Table", wire.name)
        assertEquals("Two Player Duel", wire.gameType)
        assertEquals("Constructed - Standard", wire.deckType)
        assertEquals(listOf(SeatPlayerTypeCode.HUMAN, SeatPlayerTypeCode.COMPUTER_MONTE_CARLO), wire.players)
        assertEquals(true, wire.rated)
        assertEquals(2, wire.winsNeeded)
        assertEquals(SkillLevelCode.SERIOUS, wire.skillLevel)
        assertEquals(RangeCode.ONE, wire.range)
        assertEquals("secret", wire.password)
    }

    @Test
    fun aTableDetailCarriesTheActiveGameIdThroughToTheAppSchema() {
        // the wire's activeGameId — present on every GetTable reply once a match has
        // produced a game, not only on the one-shot MatchStarting push — must survive this boundary.
        val summary =
            TableSummary(
                tableId = "t-1",
                name = "Duel Night",
                controllerName = "pete",
                gameType = "Two Player Duel",
                deckType = "Constructed",
                state = TableStateCode.DUELING,
                seatsFilled = 2,
                seatsTotal = 2,
                isTournament = false,
                isRated = false,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevelCode.CASUAL,
                createdAtEpochMs = 0L,
            )
        val wire =
            TableDetail(
                table = summary,
                seats = listOf(TableSeatSummary(index = 0, playerName = "pete", playerType = SeatPlayerTypeCode.HUMAN, occupied = true)),
                activeGameId = "g-9",
            )

        assertEquals("g-9", wire.toDetails().activeGameId)
    }

    @Test
    fun aTableDetailWithNoGameYetMapsToANullActiveGameId() {
        val summary =
            TableSummary(
                tableId = "t-1",
                name = "Duel Night",
                controllerName = "pete",
                gameType = "Two Player Duel",
                deckType = "Constructed",
                state = TableStateCode.WAITING,
                seatsFilled = 1,
                seatsTotal = 2,
                isTournament = false,
                isRated = false,
                isPassworded = false,
                isLimited = false,
                skillLevel = SkillLevelCode.CASUAL,
                createdAtEpochMs = 0L,
            )
        val wire = TableDetail(table = summary)

        assertNull(wire.toDetails().activeGameId)
    }
}
