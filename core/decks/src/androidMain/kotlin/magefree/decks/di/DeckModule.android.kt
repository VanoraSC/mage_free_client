package magefree.decks.di

import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import magefree.decks.internal.db.DeckDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module

/**
 * The Android edge of [deckDefinitions]: where the deck database file lives, which
 * SQLite implementation opens it, and which dispatcher blocking IO runs on. This is the `deckModule`
 * `:app` lists in `appModules`.
 *
 * **The path is `getDatabasePath(NAME)`, and that is the whole of the data-safety guarantee.** The pre-port
 * builder took the bare name `decks.db` and let Room resolve it to `/data/data/<pkg>/databases/`.
 * Room's multiplatform builder takes an absolute path instead, so resolving the same name through
 * the same API the old one used internally is what makes an upgrade over an existing deck library
 * open the file the user already has rather than create an empty one beside it.
 *
 * [AndroidSQLiteDriver] is the platform's own SQLite — the same engine the pre-port `SQLiteDatabase`
 * open helper used, so the APK gains no native library and query behaviour is unchanged. It is the
 * driver `:core:cards` passes for the card catalog; one SQLite driver for the repo.
 */
val deckModule: Module =
    deckDefinitions(
        databaseBuilder = {
            val context = androidContext()
            Room
                .databaseBuilder<DeckDatabase>(
                    context = context,
                    name = context.getDatabasePath(DeckDatabase.NAME).absolutePath,
                ).setDriver(AndroidSQLiteDriver())
        },
        ioDispatcher = Dispatchers.IO,
    )
