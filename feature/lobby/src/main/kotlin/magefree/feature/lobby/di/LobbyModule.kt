package magefree.feature.lobby.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import magefree.model.ConnectionState
import magefree.network.ConnectionRepository

/**
 * Feature-local Hilt provisioning for the lobby graph.
 *
 * Story 0028's `LobbyRepository` takes a `StateFlow<ConnectionState>`. Because `StateFlow` is
 * declared covariant (`interface StateFlow<out T>`), Kotlin compiles that `@Inject` constructor
 * parameter to the wildcard Java key `StateFlow<? extends ConnectionState>`, while
 * `NetworkModule.provideConnectionState` supplies the invariant `StateFlow<ConnectionState>`. The two
 * never met until this story wired `LobbyRepository` into the app's Hilt graph (via
 * [magefree.feature.lobby.LobbyViewModel]), so the mismatch surfaces here as a Dagger missing-binding.
 *
 * This provider closes the gap **without modifying `:core:network`**: it re-exposes the same
 * single-source-of-truth [ConnectionRepository.connectionState] under the covariant
 * (`StateFlow<out ConnectionState>`) key the repository actually asks for. The idiomatic long-term
 * fix is a `@JvmSuppressWildcards` on the upstream provider / injection site in `:core:network`.
 */
@Module
@InstallIn(SingletonComponent::class)
object LobbyModule {
    @Provides
    fun provideCovariantConnectionState(connectionRepository: ConnectionRepository): StateFlow<@JvmWildcard ConnectionState> =
        connectionRepository.connectionState
}
