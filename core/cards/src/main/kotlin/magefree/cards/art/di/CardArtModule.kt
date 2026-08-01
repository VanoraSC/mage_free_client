package magefree.cards.art.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.cards.CardCatalog
import magefree.cards.art.ArtDownloadManager
import magefree.cards.art.CardArtCachePolicyRepository
import magefree.cards.art.CardImageLoader
import magefree.cards.art.CatalogPrefetchTargetSource
import magefree.cards.art.ScryfallImageSource
import magefree.cards.art.XMageImageSource
import javax.inject.Singleton

/**
 * Hilt provisioning for the card-art loader/cache (story 0031). Everything is app-wide `@Singleton`:
 * one [XMageImageSource], one [CardImageLoader] (which owns Coil's memory+disk cache), one
 * [ArtDownloadManager]. `:app` gets a working art loader just by depending on `:core:cards`.
 *
 * The cache-policy DataStore is built inside [provideCachePolicyRepository] rather than exposed as a
 * `DataStore<Preferences>` binding, so it does not collide with `:core:network`'s own Preferences
 * DataStore in the singleton graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object CardArtModule {
    /** The card-art preferences (cache policy) live in their own Preferences DataStore file. */
    private val Context.cardArtDataStore by preferencesDataStore(name = "card_art_prefs")

    /** Long-lived scope for the loader's policy observer and the bulk pre-download. */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideImageSource(): XMageImageSource = ScryfallImageSource()

    @Provides
    @Singleton
    fun provideCachePolicyRepository(
        @ApplicationContext context: Context,
    ): CardArtCachePolicyRepository = CardArtCachePolicyRepository(context.cardArtDataStore)

    @Provides
    @Singleton
    fun provideCardImageLoader(
        @ApplicationContext context: Context,
        source: XMageImageSource,
        policyRepository: CardArtCachePolicyRepository,
    ): CardImageLoader =
        CardImageLoader(
            context = context,
            source = source,
            policyRepository = policyRepository,
            appScope = appScope,
            ioDispatcher = Dispatchers.IO,
        )

    @Provides
    @Singleton
    fun provideArtDownloadManager(
        catalog: CardCatalog,
        loader: CardImageLoader,
    ): ArtDownloadManager =
        ArtDownloadManager(
            targetSource = CatalogPrefetchTargetSource(catalog),
            warmer = loader,
            appScope = appScope,
        )
}
