package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.network.game.CommandObjectKind
import magefree.network.game.GameCommandObject
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pressing something in a command zone.
 *
 * An emblem is text acting on the game from outside every zone, and a player presses one to read it —
 * which it could not be before, because the command zone was a list of names that resolved to nothing.
 */
class RaisedCommandObjectTest {
    @Test
    fun `pressing an emblem opens its detail, with its rules and its image at full size`() {
        val raised = raise("e1", stateWith(liliana()))

        assertEquals("Emblem Liliana", raised?.state?.card?.name)
        assertEquals(listOf(LILIANA_RULES), raised?.state?.abilities)
        assertEquals(CardArtRequest(setCode = "temn", collectorNumber = "9", size = CardArtSize.LARGE), raised?.art)
    }

    @Test
    fun `an opponent's emblem opens too`() {
        // Every seat's command zone is on the wire, and what an opponent's emblem does is exactly what a
        // player needs to read.
        val state =
            GameState(
                gameId = "g",
                viewerPlayerId = "me",
                players =
                    listOf(
                        GamePlayer(playerId = "me", name = "Me", isViewer = true),
                        GamePlayer(playerId = "them", name = "Them", commandList = listOf(liliana())),
                    ),
            )

        assertEquals("Emblem Liliana", raise("e1", state)?.state?.card?.name)
    }

    @Test
    fun `an id that is nothing on the board still raises nothing`() {
        assertNull(raise("nowhere", stateWith(liliana())))
    }

    private fun raise(
        objectId: String,
        state: GameState,
    ) = raisedCard(
        objectId = objectId,
        snapshot = state,
        model = battlefieldModel(state),
        stack = tableStack(state),
        candidates = emptyList(),
        actionLabel = null,
        onAct = {},
    )

    private fun stateWith(vararg command: GameCommandObject) =
        GameState(
            gameId = "g",
            viewerPlayerId = "me",
            players = listOf(GamePlayer(playerId = "me", name = "Me", isViewer = true, commandList = command.toList())),
        )

    private fun liliana() =
        GameCommandObject(
            id = "e1",
            name = "Emblem Liliana",
            kind = CommandObjectKind.Emblem,
            setCode = "EMN",
            rules = listOf(LILIANA_RULES),
        )
}

private const val LILIANA_RULES =
    "At the beginning of your end step, create X 2/2 black Zombie creature tokens, where X is two plus the number of Zombies you control."
