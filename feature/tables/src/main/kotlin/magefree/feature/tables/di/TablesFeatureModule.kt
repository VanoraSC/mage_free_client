package magefree.feature.tables.di

import magefree.feature.tables.host.HostTableViewModel
import magefree.feature.tables.join.JoinTableViewModel
import magefree.feature.tables.room.TableRoomViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring for `:feature:tables` (was Hilt's `TablesFeatureModule`, which was empty).
 *
 * It now declares the feature's three ViewModels, because Koin has no equivalent of Hilt's
 * `@HiltViewModel` — every ViewModel is an explicit binding. Their dependencies still come from
 * elsewhere in the graph: [magefree.network.table.TableClient] from `:core:network`'s
 * `networkModule` (story 0037), and [magefree.decks.DeckRepository] /
 * [magefree.decks.legality.DeckLegality] from `:core:decks`' `deckModule` (story 0033).
 */
val tablesFeatureModule =
    module {
        viewModel { HostTableViewModel(tableClient = get(), deckRepository = get(), deckLegality = get()) }
        viewModel { JoinTableViewModel(tableClient = get(), deckRepository = get(), deckLegality = get()) }
        viewModel { TableRoomViewModel(tableClient = get(), deckRepository = get()) }
    }
