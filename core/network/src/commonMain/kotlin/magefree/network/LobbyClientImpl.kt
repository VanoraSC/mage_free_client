package magefree.network

import magefree.model.GameType
import magefree.model.LobbyTable
import magefree.model.RoomUser
import magefree.network.mapper.LobbyMapper.toModel
import magefree.protocol.GameTypeList
import magefree.protocol.GetGameTypes
import magefree.protocol.GetRoomUsers
import magefree.protocol.GetTables
import magefree.protocol.RoomUserList
import magefree.protocol.ServerMessage
import magefree.protocol.TableList
import kotlin.uuid.Uuid

/**
 * The production [LobbyClient]: each call mints a `requestId`, sends the matching lobby `GetX` over the
 * live session via [BridgeClient.request], awaits the correlated `XList` reply, and maps its wire
 * summaries into `:core:model` at [LobbyMapper][magefree.network.mapper.LobbyMapper]. The `:protocol`
 * request/reply types live only here and in the mapper — they never cross the [LobbyClient] seam.
 *
 * It holds no session of its own: it rides the same authenticated socket [BridgeClient] owns. When
 * there is no live session (or the reply times out / the socket drops) `request` throws; the
 * `LobbyRepository` catches that and surfaces it as error state.
 *
 * @param newRequestId how a correlation id is minted (a random UUID in production; overridable so a
 *   test can assert the exact id sent).
 */
class LobbyClientImpl
    constructor(
        private val bridgeClient: BridgeClient,
    ) : LobbyClient {
        private val newRequestId: () -> String = { Uuid.random().toString() }

        override suspend fun tables(): List<LobbyTable> {
            val id = newRequestId()
            val reply = bridgeClient.request<ServerMessage>(GetTables(id), id)
            return (reply as? TableList ?: unexpected("TableList", reply)).tables.map { it.toModel() }
        }

        override suspend fun roomUsers(): List<RoomUser> {
            val id = newRequestId()
            val reply = bridgeClient.request<ServerMessage>(GetRoomUsers(id), id)
            return (reply as? RoomUserList ?: unexpected("RoomUserList", reply)).users.map { it.toModel() }
        }

        override suspend fun gameTypes(): List<GameType> {
            val id = newRequestId()
            val reply = bridgeClient.request<ServerMessage>(GetGameTypes(id), id)
            return (reply as? GameTypeList ?: unexpected("GameTypeList", reply)).gameTypes.map { it.toModel() }
        }

        private fun unexpected(
            expected: String,
            actual: ServerMessage,
        ): Nothing = throw IllegalStateException("lobby: expected $expected, got ${actual::class.simpleName}")
    }
