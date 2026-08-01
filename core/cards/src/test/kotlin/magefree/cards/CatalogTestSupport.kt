package magefree.cards

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import magefree.cards.internal.SqliteCardCatalog
import java.io.File

/** Shared helpers for the Robolectric-backed catalog tests. */
internal object CatalogTestSupport {
    /** Copy a bundled test-classpath resource (e.g. the fixture DB) to a temp file and open it. */
    fun openResourceDatabase(resource: String): SQLiteDatabase {
        val url =
            requireNotNull(CatalogTestSupport::class.java.classLoader!!.getResource(resource)) {
                "missing test resource: $resource"
            }
        val tmp = File.createTempFile("catalog-", ".sqlite").apply { deleteOnExit() }
        url.openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    fun catalog(
        db: SQLiteDatabase,
        dispatcher: CoroutineDispatcher,
    ): CardCatalog = SqliteCardCatalog(databaseProvider = { db }, ioDispatcher = dispatcher)
}
