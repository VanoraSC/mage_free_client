package magefree.bridge.mapping

import mage.interfaces.callback.ClientCallback
import mage.interfaces.callback.ClientCallbackMethod
import mage.view.AbilityPickerView
import mage.view.ChatMessage
import mage.view.GameClientMessage
import mage.view.GameView
import mage.view.TableClientMessage
import magefree.bridge.mapping.game.GameErrorMapper
import magefree.bridge.mapping.game.GameInformedMapper
import magefree.bridge.mapping.game.GameOverMapper
import magefree.bridge.mapping.game.GamePromptMapper
import magefree.bridge.mapping.game.GameStartedMapper
import magefree.bridge.mapping.game.GameStateUpdatedMapper
import magefree.bridge.mapping.game.WatchingGameMapper
import magefree.bridge.mapping.table.ConstructMapper
import magefree.bridge.mapping.table.JoinedTableMapper
import magefree.bridge.mapping.table.MatchStartingMapper
import magefree.bridge.mapping.table.SideboardMapper
import magefree.protocol.ServerMessage
import org.slf4j.LoggerFactory

/**
 * The generic server→client push mapper: turns a raw [ClientCallback] into an app-schema
 * [ServerMessage], or `null` when no mapper is registered for the callback's method. This — together
 * with the per-feature mappers it dispatches to (e.g. [ChatMessageMapper]) — is the **single coupling
 * surface** where `mage.view.*` payloads are read; nothing outside `magefree.bridge.mapping` sees them.
 *
 * [map] is the only place a callback's compressed payload is decompressed and cast. Downstream epics
 * register more `when` cases (lobby, deck, game). Mapping **never throws** — so a new upstream push
 * cannot crash the session:
 * - an **unmapped** method returns `null` (the relay logs and drops it);
 * - a **known** method whose payload fails to decompress or is not the expected `mage.view.*` shape
 *   (upstream drift) is logged and returns `null` too, rather than letting the throw propagate and
 *   evict the [magefree.bridge.session.LiveSession].
 */
public object CallbackMapper {
    private val logger = LoggerFactory.getLogger(CallbackMapper::class.java)

    /**
     * Dispatches [callback] by its [ClientCallbackMethod]:
     * - [ClientCallbackMethod.CHATMESSAGE] → [ChatMessageMapper] over the decompressed [ChatMessage].
     * - the table-lifecycle methods → their per-callback table mapper over the decompressed
     *   [TableClientMessage]: `JOINED_TABLE` → [JoinedTableMapper] (a `TableUpdated`),
     *   `CONSTRUCT` → [ConstructMapper] (a `ConstructPrompt`), `SIDEBOARD` → [SideboardMapper] (a
     *   `SideboardPrompt`), and `START_GAME` → [MatchStartingMapper] (a `MatchStarting`, the the game layer
     *   boundary).
     * - the in-game methods → their per-callback game mapper. For every one of these the
     *   callback's **object id is the game id** (upstream fires them with `game.getId()`), and the
     *   payload type differs per method — a bare `GameView`, a [GameClientMessage], an
     *   [AbilityPickerView], a `String`, or a [TableClientMessage].
     * - anything else → `null` (the caller logs "unmapped callback: <method>" and drops it). That
     *   deliberately includes **`GAME_REDRAW_GUI`**, a `CLIENT_SIDE_EVENT` whose desktop handler is a
     *   bare repaint of already-held state — see `magefree.bridge.mapping.game`'s file header — and
     *   every `DRAFT_*`/`TOURNAMENT_*` method.
     *
     * The decompress + cast for a known method is guarded: a malformed payload is logged and mapped to
     * `null`, never thrown.
     */
    public fun map(callback: ClientCallback): ServerMessage? =
        when (callback.method) {
            ClientCallbackMethod.CHATMESSAGE ->
                mapGuarded(callback) { ChatMessageMapper.map(it.chatMessage()) }
            ClientCallbackMethod.JOINED_TABLE ->
                mapGuarded(callback) { JoinedTableMapper.map(it.tableClientMessage()) }
            ClientCallbackMethod.CONSTRUCT ->
                mapGuarded(callback) { ConstructMapper.map(it.tableClientMessage()) }
            ClientCallbackMethod.SIDEBOARD ->
                mapGuarded(callback) { SideboardMapper.map(it.tableClientMessage()) }
            ClientCallbackMethod.START_GAME ->
                mapGuarded(callback) { MatchStartingMapper.map(it.tableClientMessage()) }

            // In-game lifecycle.
            ClientCallbackMethod.GAME_INIT ->
                mapGuarded(callback) { GameStartedMapper.map(it.objectId, it.gameView()) }
            ClientCallbackMethod.GAME_UPDATE ->
                mapGuarded(callback) { GameStateUpdatedMapper.map(it.objectId, it.gameView()) }
            ClientCallbackMethod.GAME_UPDATE_AND_INFORM ->
                mapGuarded(callback) { GameInformedMapper.map(it.objectId, it.gameClientMessage(), personal = false) }
            ClientCallbackMethod.GAME_INFORM_PERSONAL ->
                mapGuarded(callback) { GameInformedMapper.map(it.objectId, it.gameClientMessage(), personal = true) }
            ClientCallbackMethod.GAME_ERROR ->
                mapGuarded(callback) { GameErrorMapper.map(it.objectId, it.errorMessage()) }
            ClientCallbackMethod.GAME_OVER ->
                mapGuarded(callback) { GameOverMapper.map(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.WATCHGAME ->
                mapGuarded(callback) { WatchingGameMapper.map(it.objectId, it.tableClientMessage()) }

            // In-game prompts — the closed, typed question set.
            ClientCallbackMethod.GAME_SELECT ->
                mapGuarded(callback) { GamePromptMapper.select(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_TARGET ->
                mapGuarded(callback) { GamePromptMapper.target(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_ASK ->
                mapGuarded(callback) { GamePromptMapper.ask(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_CHOOSE_ABILITY ->
                mapGuarded(callback) { GamePromptMapper.chooseAbility(it.objectId, it.abilityPickerView()) }
            ClientCallbackMethod.GAME_CHOOSE_PILE ->
                mapGuarded(callback) { GamePromptMapper.choosePile(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_CHOOSE_CHOICE ->
                mapGuarded(callback) { GamePromptMapper.chooseChoice(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_PLAY_MANA ->
                mapGuarded(callback) { GamePromptMapper.playMana(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_PLAY_XMANA ->
                mapGuarded(callback) { GamePromptMapper.playXMana(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_GET_AMOUNT ->
                mapGuarded(callback) { GamePromptMapper.getAmount(it.objectId, it.gameClientMessage()) }
            ClientCallbackMethod.GAME_GET_MULTI_AMOUNT ->
                mapGuarded(callback) { GamePromptMapper.getMultiAmount(it.objectId, it.gameClientMessage()) }

            else -> null
        }

    /** Runs [transform], turning any malformed-payload failure into a logged `null` (never a throw). */
    private inline fun mapGuarded(
        callback: ClientCallback,
        transform: (ClientCallback) -> ServerMessage?,
    ): ServerMessage? =
        try {
            transform(callback)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.warn("Dropping malformed {} callback payload: {}", callback.method, failure.toString())
            null
        }

    /** Decompresses the payload and returns it as a [ChatMessage] (throws if the shape drifted). */
    private fun ClientCallback.chatMessage(): ChatMessage {
        // The payload is compressed until decompressed; getData() returns the mage.view.* object after.
        decompressData()
        return data as ChatMessage
    }

    /** Decompresses the payload and returns it as a [TableClientMessage] (throws if the shape drifted). */
    private fun ClientCallback.tableClientMessage(): TableClientMessage {
        decompressData()
        return data as TableClientMessage
    }

    /** Decompresses the payload and returns it as a [GameView] (`GAME_INIT`/`GAME_UPDATE` send it bare). */
    private fun ClientCallback.gameView(): GameView {
        decompressData()
        return data as GameView
    }

    /** Decompresses the payload and returns it as a [GameClientMessage] (every other game callback). */
    private fun ClientCallback.gameClientMessage(): GameClientMessage {
        decompressData()
        return data as GameClientMessage
    }

    /** Decompresses the payload and returns it as an [AbilityPickerView] (`GAME_CHOOSE_ABILITY` only). */
    private fun ClientCallback.abilityPickerView(): AbilityPickerView {
        decompressData()
        return data as AbilityPickerView
    }

    /** Decompresses the payload and returns it as the `GAME_ERROR` message string. */
    private fun ClientCallback.errorMessage(): String? {
        decompressData()
        return data as String?
    }
}
