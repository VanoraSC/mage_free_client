package magefree.bridge.mapping

import mage.view.GameTypeView
import magefree.protocol.GameTypeSummary

/**
 * Maps an upstream [mage.view.GameTypeView] to the app-schema [GameTypeSummary]. Part of the **single
 * coupling surface**: `mage.view.*` is read only here (and its sibling mappers). Pure and
 * deterministic.
 *
 * **Testability.** `GameTypeView`'s only constructor takes a `mage.game.match.MatchType`, which is not
 * on the bridge classpath (only `mage-common` is), so the raw view cannot be constructed in a hermetic
 * test. The mapping logic therefore lives in [build], a pure function over the extracted getter values
 * asserted directly by `GameTypeMapperTest`; [map] is the thin getter-forwarding shim, exercised
 * end-to-end by the live `LobbyRelayIT` (the reference server offers a non-empty game-type list, so the
 * shim's getter wiring *is* covered live — unlike tables).
 *
 * Field mapping (see [build]):
 * - `name` ← [GameTypeView.getName]; `minPlayers` ← [GameTypeView.getMinPlayers]; `maxPlayers` ← [GameTypeView.getMaxPlayers].
 */
public object GameTypeMapper {
    /** Maps [view] to its app-schema summary by extracting the browse-relevant getters into [build]. */
    public fun map(view: GameTypeView): GameTypeSummary =
        build(
            name = view.name ?: "",
            minPlayers = view.minPlayers,
            maxPlayers = view.maxPlayers,
        )

    /** The pure mapping logic over already-extracted values (unit-testable without a [GameTypeView]). */
    public fun build(
        name: String,
        minPlayers: Int,
        maxPlayers: Int,
    ): GameTypeSummary =
        GameTypeSummary(
            name = name,
            minPlayers = minPlayers,
            maxPlayers = maxPlayers,
        )
}
