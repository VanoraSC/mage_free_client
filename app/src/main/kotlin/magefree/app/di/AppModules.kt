package magefree.app.di

import magefree.cards.art.di.cardArtModule
import magefree.cards.di.cardCatalogModule
import magefree.decks.di.deckModule
import magefree.feature.cards.di.cardsFeatureModule
import magefree.feature.connect.di.connectFeatureModule
import magefree.feature.decks.di.decksFeatureModule
import magefree.feature.game.di.boardModule
import magefree.feature.lobby.di.lobbyFeatureModule
import magefree.feature.tables.di.tablesFeatureModule
import magefree.network.di.networkModule
import org.koin.core.module.Module

/**
 * Every Koin module in the app, in one list (story 0081).
 *
 * **This list is the graph.** Hilt assembled the component at compile time by scanning
 * `@InstallIn` annotations across ten modules, so nothing had to enumerate them and nothing could
 * forget to. Koin has no such discovery: a module missing from this list is simply absent, and the
 * first symptom is a crash on whichever screen needed one of its bindings.
 *
 * Two things follow, and both are deliberate:
 *
 * - **It is a `val`, not a call site.** `MageApp` starts Koin with exactly this list, and
 *   `KoinGraphTest` verifies exactly this list. A test that assembled its own list would verify a
 *   graph the app does not run.
 * - **A new feature module must be added here.** There is no annotation that does it for you. The
 *   guard is `KoinGraphTest`, which resolves every declared binding — including the ViewModels that
 *   used to be found by `@HiltViewModel` alone.
 */
val appModules: List<Module> =
    listOf(
        // :core
        networkModule,
        cardCatalogModule,
        cardArtModule,
        deckModule,
        // :feature
        connectFeatureModule,
        lobbyFeatureModule,
        cardsFeatureModule,
        decksFeatureModule,
        tablesFeatureModule,
        boardModule,
        // :app
        connectionModule,
    )
