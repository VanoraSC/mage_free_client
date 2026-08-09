package magefree.cards.internal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens the bundled card catalog SQLite as a read-only database.
 *
 * The catalog ships as an APK **asset** (`assets/cards.sqlite`). An asset lives inside the APK zip
 * and cannot be opened directly by SQLite, so on first use it is copied once into the app's private
 * files dir and opened from there read-only. The copied file name embeds [ASSET_VERSION] so a bundle
 * refresh (new XMage version / schema) lands under a new name and stale copies are removed — no
 * migration logic, since the catalog is immutable per version.
 *
 * The asset is read via [android.content.res.AssetManager.open] (streaming), which transparently
 * inflates it, so the APK is free to keep it compressed (~5.5 MB vs ~14 MB). The >1 MB compressed
 * asset caveat only applies to `openFd`/mmap access, which this copy path never uses.
 *
 * **Local preparation.** The freshly copied file is opened read-write **once**, before it is published
 * under its final name, to add the case-insensitive name index the deck path's exact-name lookup needs
 * (`card(name COLLATE NOCASE)` — the generator only ships a BINARY `idx_card_name`, which a NOCASE
 * comparison cannot use). The shipped asset itself is never modified; only this private copy is. The
 * copy's name embeds [LOCAL_REVISION] as well as [ASSET_VERSION], so changing what preparation does
 * re-copies once rather than leaving an already-installed copy unprepared.
 */
internal object CardCatalogDatabase {
    const val ASSET_NAME = "cards.sqlite"

    /** Bump whenever `assets/cards.sqlite` is regenerated (schema id + pinned XMage ref). */
    const val ASSET_VERSION = "1-e0fe4b6f6a"

    /** Bump whenever [prepare] changes, so existing copies are re-laid rather than left stale. */
    const val LOCAL_REVISION = 2

    /** Case-insensitive name index added locally to serve [magefree.cards.CardCatalog.cardsByName]. */
    const val NAME_NOCASE_INDEX = "idx_card_name_nocase"

    fun open(context: Context): SQLiteDatabase {
        val file = copyIfNeeded(context)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun copyIfNeeded(context: Context): File {
        val dir = File(context.filesDir, "card-catalog").apply { mkdirs() }
        val target = File(dir, "cards-$ASSET_VERSION-r$LOCAL_REVISION.sqlite")
        if (target.exists() && target.length() > 0L) return target

        // Drop any older bundle versions (and any leftover temp/journal files) before laying down the
        // new one.
        dir
            .listFiles { f -> f.name.startsWith("cards-") && f.name != target.name }
            ?.forEach { it.delete() }

        val tmp = File(dir, "${target.name}.tmp")
        context.assets.open(ASSET_NAME).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        prepare(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    /**
     * One-time local preparation of the private copy: add the NOCASE name index. Read-write is used
     * only here, on a not-yet-published temp file; every subsequent open is read-only.
     */
    private fun prepare(file: File) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE INDEX IF NOT EXISTS $NAME_NOCASE_INDEX ON card(name COLLATE NOCASE)")
        }
    }
}
