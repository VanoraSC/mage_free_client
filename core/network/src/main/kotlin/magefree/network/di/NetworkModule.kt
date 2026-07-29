package magefree.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.network.BridgeClient
import magefree.network.ktor.KtorBridgeClient
import javax.inject.Singleton

/**
 * Hilt provisioning for `:core:network`'s session/persistence layer.
 *
 * - Binds the production [BridgeClient] to the real Ktor WebSocket implementation; tests construct a
 *   [magefree.network.fake.FakeBridgeClient] directly and never touch Hilt.
 * - Supplies the injected IO dispatcher + application scope the [magefree.network.ConnectionRepository]
 *   needs (no hard-coded `Dispatchers.*`, per `AGENTS.md`).
 * - Provides the single Preferences [DataStore] backing [magefree.network.ServerRepository].
 *
 * `ConnectionRepository` / `ServerRepository` are `@Inject`-constructed `@Singleton`s, so they need
 * no explicit `@Provides` here.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /** The user's saved server list lives in this Preferences DataStore file. */
    private val Context.serverDataStore by preferencesDataStore(name = "server_targets")

    @Provides
    @Singleton
    fun provideBridgeClient(): BridgeClient = KtorBridgeClient()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideServerDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.serverDataStore
}
