package magefree.cards

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import magefree.cards.internal.SqliteCardCatalog
import java.io.File

/** Shared helpers for the Robolectric-backed catalog tests. */
internal object CatalogTestSupport {
    private val driver = AndroidSQLiteDriver()

    /** Copy a bundled test-classpath resource (e.g. the fixture DB) to a temp file and open it. */
    fun openResourceDatabase(resource: String): SQLiteConnection {
        val url =
            requireNotNull(CatalogTestSupport::class.java.classLoader!!.getResource(resource)) {
                "missing test resource: $resource"
            }
        val tmp = File.createTempFile("catalog-", ".sqlite").apply { deleteOnExit() }
        url.openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        return driver.open(tmp.path)
    }

    fun catalog(
        db: SQLiteConnection,
        dispatcher: CoroutineDispatcher,
    ): CardCatalog = SqliteCardCatalog(databaseProvider = { db }, ioDispatcher = dispatcher)
}
