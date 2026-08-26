package magefree.decks.internal

import kotlinx.serialization.json.Json
import magefree.cards.bundle.BundledFiles
import magefree.decks.legality.LegalityBundle
import okio.buffer
import okio.use

/**
 * Loads the bundled format-legality asset (`formats.json`), parsed once. The asset is generated
 * offline from XMage (see `tools/card-catalog-generator`); loading it needs no network.
 *
 * The bytes arrive through [BundledFiles] — the same boundary `:core:cards` reads its 14 MB
 * `cards.sqlite` through. `formats.json` and the card catalog differ only in size, so
 * they share one mechanism rather than each carrying its own.
 */
internal object FormatBundleLoader {
    const val ASSET_NAME = "formats.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(files: BundledFiles): LegalityBundle {
        val text = files.openBundled(ASSET_NAME).buffer().use { it.readUtf8() }
        return json.decodeFromString(LegalityBundle.serializer(), text)
    }
}
