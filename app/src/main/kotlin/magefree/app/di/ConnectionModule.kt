package magefree.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import magefree.app.connection.ConnectionStatusSource
import magefree.app.connection.ConnectionStatusSourceImpl
import magefree.app.core.DefaultDispatcherProvider
import magefree.app.core.DispatcherProvider
import javax.inject.Singleton

/**
 * Hilt bindings for the connection-status surface.
 *
 * Story 0017 (EPIC-04) flips [ConnectionStatusSource] from story 0010's stub to the real,
 * repository-backed [ConnectionStatusSourceImpl] — the single seam swap that makes the shell's status
 * bar reflect the live bridge session. The `ConnectionStatusViewModel` and `ConnectionStatusBar` are
 * untouched; `StubConnectionStatusSource` remains for previews/tests. [DispatcherProvider] is also
 * bound here so ViewModels/sources inject dispatchers rather than hard-coding `Dispatchers.*`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    @Singleton
    abstract fun bindConnectionStatusSource(impl: ConnectionStatusSourceImpl): ConnectionStatusSource

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
