package magefree.decks.di

import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineDispatcher
import magefree.cards.CardCatalog
import magefree.cards.bundle.BundledFiles
import magefree.decks.DeckRepository
import magefree.decks.internal.DefaultDeckLegality
import magefree.decks.internal.FormatBundleLoader
import magefree.decks.internal.RoomDeckRepository
import magefree.decks.internal.db.DeckDatabase
import magefree.decks.io.DeckIO
import magefree.decks.io.internal.DefaultDeckIO
import magefree.decks.legality.DeckLegality
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Koin provisioning for `:core:decks`, in common (story 0083).
 *
 * Exposes only the public [DeckRepository] (fully-offline deck library, Room-backed) and
 * [DeckLegality] (offline checker over the bundled `formats.json` + the `:core:cards` [CardCatalog]);
 * the Room database/DAO stay internal to the module and never enter the graph. `:app` gets both
 * working just by depending on this module — no network involved.
 *
 * **The two platform-shaped inputs are parameters.** Room's builder entry point is genuinely
 * platform-specific — Android's takes a `Context` alongside the database file path, a JVM host's
 * takes the path alone — and `Dispatchers.IO` does not exist in a common source set. Everything
 * between them is identical on every target and lives here. The public `deckModule` each platform
 * publishes is a call to this with those two supplied; `androidMain`'s is the one `:app` uses.
 *
 * `internal`, because [DeckDatabase] is: the builder type in the signature would otherwise leak a
 * type the module deliberately does not export. [databaseBuilder] is a `Scope.() ->` rather than a
 * plain value because on Android it needs `androidContext()`, which is only reachable while Koin is
 * resolving a definition.
 */
internal fun deckDefinitions(
    databaseBuilder: Scope.() -> RoomDatabase.Builder<DeckDatabase>,
    ioDispatcher: CoroutineDispatcher,
): Module =
    module {
        single<DeckRepository> {
            val database =
                databaseBuilder()
                    .setQueryCoroutineContext(ioDispatcher)
                    .build()
            RoomDeckRepository(dao = database.deckDao(), ioDispatcher = ioDispatcher)
        }

        single<DeckLegality> {
            val files = get<BundledFiles>()
            DefaultDeckLegality(
                bundleProvider = { FormatBundleLoader.load(files) },
                catalog = get(),
                ioDispatcher = ioDispatcher,
            )
        }

        single<DeckIO> { DefaultDeckIO(catalog = get(), ioDispatcher = ioDispatcher) }
    }
