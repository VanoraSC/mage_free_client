package magefree.cards.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import magefree.cards.bundle.BundledFiles
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * Opens the bundled card catalog SQLite as a read-only database.
 *
 * The catalog ships as a bundled file (`cards.sqlite`). A bundled file cannot be opened directly by
 * SQLite — on Android it lives inside the APK zip — so on first use it is copied once into
 * [BundledFiles.writableDirectory] and opened from there. The copied file name embeds
 * [ASSET_VERSION] so a bundle refresh (new XMage version / schema) lands under a new name and stale
 * copies are removed — no migration logic, since the catalog is immutable per version.
 *
 * **Local preparation.** The freshly copied file is prepared **once**, before it is published under
 * its final name, to add the case-insensitive name index the deck path's exact-name lookup needs
 * (`card(name COLLATE NOCASE)` — the generator only ships a BINARY `idx_card_name`, which a NOCASE
 * comparison cannot use). The shipped asset itself is never modified; only this private copy is. The
 * copy's name embeds [LOCAL_REVISION] as well as [ASSET_VERSION], so changing what preparation does
 * re-copies once rather than leaving an already-installed copy unprepared.
 *
 * **Story 0082 took `Context` out of this object.** Where the bytes come from and where they may be
 * written are now [BundledFiles]' business; the copy-once, prepare-once, version-stamped logic here
 * is platform-independent and is expressed in okio so it can move to a common source set unchanged.
 */
internal object CardCatalogDatabase {
    const val ASSET_NAME = "cards.sqlite"

    /** Bump whenever `assets/cards.sqlite` is regenerated (schema id + pinned XMage ref). */
    const val ASSET_VERSION = "1-e0fe4b6f6a"

    /**
     * Bump whenever [prepare] changes, so existing copies are re-laid rather than left stale.
     *
     * Bumped to 3 by story 0082: [prepare] now creates the index through `androidx.sqlite` rather
     * than `SQLiteDatabase`. An already-installed device holds a copy laid down by the old path, and
     * leaving it would be invisible until an exact-name lookup got slow — so the copy is re-laid
     * once on first launch after the upgrade.
     */
    const val LOCAL_REVISION = 3

    /** Case-insensitive name index added locally to serve [magefree.cards.CardCatalog.cardsByName]. */
    const val NAME_NOCASE_INDEX = "idx_card_name_nocase"

    /** Subdirectory of [BundledFiles.writableDirectory] holding the private copy. */
    private const val COPY_DIR = "card-catalog"

    /**
     * The driver. `AndroidSQLiteDriver` is the platform's own SQLite — the same engine the pre-port
     * `SQLiteDatabase` used — so this changes the API in front of SQLite, not SQLite itself, and the
     * APK gains no native library. A JVM/desktop target supplies `BundledSQLiteDriver` instead; the
     * query code in [SqliteCardCatalog] is identical either way.
     */
    private val driver = AndroidSQLiteDriver()

    private val fileSystem: FileSystem get() = FileSystem.SYSTEM

    fun open(files: BundledFiles): SQLiteConnection = driver.open(preparedFile(files).toString())

    /**
     * The prepared private copy, laid down on first call. Exposed (rather than kept inside [open])
     * so a test can reach the same file through a different SQLite API — see
     * `CardCatalogExactNameTest.queryPlan`, which needs `EXPLAIN QUERY PLAN` and cannot get it from
     * the driver.
     */
    fun preparedFile(files: BundledFiles): Path {
        val dir = files.writableDirectory / COPY_DIR
        fileSystem.createDirectories(dir)
        val target = dir / "cards-$ASSET_VERSION-r$LOCAL_REVISION.sqlite"
        if (fileSystem.exists(target) && (fileSystem.metadataOrNull(target)?.size ?: 0L) > 0L) return target

        // Drop any older bundle versions (and any leftover temp/journal files) before laying down
        // the new one.
        fileSystem
            .list(dir)
            .filter { it.name.startsWith("cards-") && it.name != target.name }
            .forEach { fileSystem.delete(it) }

        val tmp = dir / "${target.name}.tmp"
        files.openBundled(ASSET_NAME).use { source ->
            fileSystem.sink(tmp).buffer().use { sink -> sink.writeAll(source) }
        }
        prepare(tmp)
        publish(tmp, target)
        return target
    }

    /**
     * Publish the prepared temp file under its final name.
     *
     * The copy-then-delete fallback is carried over from the pre-okio version, which checked
     * `File.renameTo`'s boolean and copied when it failed. `atomicMove` throws instead of returning
     * false, and this runs on **every user's first launch** — so a rename that fails for an
     * environmental reason must still end with a usable catalog rather than an exception on the way
     * to the card browser.
     */
    private fun publish(
        tmp: Path,
        target: Path,
    ) {
        try {
            fileSystem.atomicMove(tmp, target)
        } catch (_: IOException) {
            fileSystem.copy(tmp, target)
            fileSystem.delete(tmp)
        }
    }

    /**
     * One-time local preparation of the private copy: add the NOCASE name index. Done here, on a
     * not-yet-published temp file, so no reader ever sees an unprepared copy.
     *
     * **Story 0082 lost a guardrail here and it is worth stating rather than discovering.**
     * `SQLiteDriver.open(fileName)` takes no flags, so `OPEN_READONLY` — which every post-preparation
     * open used to pass — cannot be expressed. Nothing writes to the catalog (it is an immutable
     * bundled asset and [SqliteCardCatalog] only ever issues `SELECT`s), so behaviour is unchanged;
     * what is gone is SQLite refusing a write if one were ever added by mistake.
     */
    private fun prepare(file: Path) {
        driver.open(file.toString()).use { connection ->
            connection.execSQL("CREATE INDEX IF NOT EXISTS $NAME_NOCASE_INDEX ON card(name COLLATE NOCASE)")
        }
    }
}
