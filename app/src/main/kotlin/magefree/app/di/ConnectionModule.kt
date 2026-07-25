package magefree.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import magefree.app.connection.ConnectionStatusSource
import magefree.app.connection.StubConnectionStatusSource
import magefree.app.core.DefaultDispatcherProvider
import magefree.app.core.DispatcherProvider
import javax.inject.Singleton

/**
 * Hilt bindings for the connection-status surface.
 *
 * Today [ConnectionStatusSource] resolves to the driveable [StubConnectionStatusSource]; EPIC-04
 * flips this single binding to the real session-backed source with no UI change. [DispatcherProvider]
 * is also bound here so ViewModels inject dispatchers rather than hard-coding `Dispatchers.*`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    @Singleton
    abstract fun bindConnectionStatusSource(impl: StubConnectionStatusSource): ConnectionStatusSource

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
