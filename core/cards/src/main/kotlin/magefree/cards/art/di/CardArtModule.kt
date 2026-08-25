package magefree.cards.art.di

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import magefree.cards.CardCatalog
import magefree.cards.art.ArtDownloadManager
import magefree.cards.art.CardArtCachePolicyRepository
import magefree.cards.art.CardArtUserAgent
import magefree.cards.art.CardImageLoader
import magefree.cards.art.CatalogPrefetchTargetSource
import magefree.cards.art.ScryfallImageSource
import magefree.cards.art.XMageImageSource
import magefree.cards.art.androidAppVersion
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The card-art preferences (cache policy) live in their own Preferences DataStore file. */
private val Context.cardArtDataStore by preferencesDataStore(name = "card_art_prefs")

/** Long-lived scope for the loader's policy observer and the bulk pre-download. */
private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Koin provisioning for the card-art loader/cache (story 0031; was Hilt's `CardArtModule`).
 * Everything is app-wide `single`: one [XMageImageSource], one [CardImageLoader] (which owns Coil's
 * memory+disk cache), one [ArtDownloadManager]. `:app` gets a working art loader just by depending
 * on `:core:cards`.
 *
 * The cache-policy DataStore is built inside the [CardArtCachePolicyRepository] binding rather than
 * exposed as a `DataStore<Preferences>` binding of its own, so it does not collide with
 * `:core:network`'s Preferences DataStore. That collision would now be a **runtime** override rather
 * than a compile-time duplicate-binding error, which is exactly why it stays private here.
 */
val cardArtModule =
    module {
        single<XMageImageSource> { ScryfallImageSource() }

        single { CardArtCachePolicyRepository(androidContext().cardArtDataStore) }

        single {
            val context = androidContext()
            CardImageLoader(
                context = context,
                source = get(),
                policyRepository = get(),
                appScope = appScope,
                ioDispatcher = Dispatchers.IO,
                // Story 0082: the `User-Agent` is built here, from the version the platform reports,
                // rather than inside the loader — that one string was the whole reason the art
                // pipeline needed a `PackageManager`. Scryfall answers HTTP 400 without it (0056).
                userAgent = CardArtUserAgent.value(androidAppVersion(context)),
                // Logcat is still the only place a systemic art failure is visible; there is no
                // in-app diagnostic surface for it yet.
                logWarning = { message -> Log.w(CardImageLoader.LOG_TAG, message) },
            )
        }

        single {
            ArtDownloadManager(
                targetSource = CatalogPrefetchTargetSource(get<CardCatalog>()),
                warmer = get<CardImageLoader>(),
                appScope = appScope,
            )
        }
    }
