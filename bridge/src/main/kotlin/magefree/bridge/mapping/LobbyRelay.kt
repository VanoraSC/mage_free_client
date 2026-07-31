package magefree.bridge.mapping

import mage.remote.SessionImpl
import magefree.protocol.GameTypeSummary
import magefree.protocol.RoomUserSummary
import magefree.protocol.TableSummary
import java.util.UUID

/**
 * The lobby-side fetch+map boundary. `SessionImpl.getTables`/`getRoomUsers`/`getGameTypes` **return**
 * `mage.view.*` collections, so their call sites — not just the per-view mappers — must live inside
 * `magefree.bridge.mapping` to honour the rule that upstream view shapes are read only here
 * (`docs/architecture.md`; the same reason `CallbackRelay` lives here). This object performs the
 * blocking upstream reads and maps each element through the pure [TableMapper]/[RoomUserMapper]/
 * [GameTypeMapper], returning app-schema summaries the session layer can relay without ever seeing a
 * `mage.view.*` type.
 *
 * These are **blocking** JBoss-remoting calls; callers (e.g. `XMageSession`) invoke them on
 * `Dispatchers.IO`. This layer does not catch [mage.remote.MageRemoteException]; the caller decides how
 * a transport failure maps to a reply (the coordinator falls back to an empty list).
 */
public object LobbyRelay {
    /** Fetches the open tables in [roomId] and maps them to app-schema summaries. */
    public fun tables(
        session: SessionImpl,
        roomId: UUID,
    ): List<TableSummary> = session.getTables(roomId).map { TableMapper.map(it) }

    /** Fetches the users in [roomId] and maps them (flattening each room-users snapshot) to summaries. */
    public fun roomUsers(
        session: SessionImpl,
        roomId: UUID,
    ): List<RoomUserSummary> = session.getRoomUsers(roomId).flatMap { RoomUserMapper.map(it) }

    /** Fetches and maps the server's available game types. */
    public fun gameTypes(session: SessionImpl): List<GameTypeSummary> = session.gameTypes.map { GameTypeMapper.map(it) }
}
