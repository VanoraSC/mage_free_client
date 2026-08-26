package magefree.app.di

import magefree.app.connection.ConnectionStatusSource
import magefree.app.connection.ConnectionStatusSourceImpl
import magefree.app.connection.ConnectionStatusViewModel
import magefree.app.connection.SessionViewModel
import magefree.app.core.DefaultDispatcherProvider
import magefree.app.core.DispatcherProvider
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the connection-status surface (was Hilt's `ConnectionModule`).
 *
 * Binds [ConnectionStatusSource] to the real,
 * repository-backed [ConnectionStatusSourceImpl] — the single seam swap that makes the shell's
 * status bar reflect the live bridge session. The `ConnectionStatusViewModel` and
 * `ConnectionStatusBar` are untouched; `StubConnectionStatusSource` remains for previews/tests.
 * [DispatcherProvider] is also bound here so ViewModels/sources inject dispatchers rather than
 * hard-coding `Dispatchers.*`.
 */
val connectionModule =
    module {
        single<ConnectionStatusSource> { ConnectionStatusSourceImpl(repository = get(), dispatchers = get()) }

        single<DispatcherProvider> { DefaultDispatcherProvider() }

        viewModel { ConnectionStatusViewModel(source = get(), dispatchers = get()) }
        viewModel { SessionViewModel(connectionRepository = get()) }
    }
