package magefree.decks.internal

import android.content.Context
import kotlinx.serialization.json.Json
import magefree.decks.legality.LegalityBundle

/**
 * Loads the bundled format-legality asset (`assets/formats.json`) off the APK. Read via
 * [android.content.res.AssetManager.open] (streaming, transparently inflated), parsed once. The asset
 * is generated offline from XMage (see `tools/card-catalog-generator`); loading it needs no network.
 */
internal object FormatBundleLoader {
    const val ASSET_NAME = "formats.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): LegalityBundle {
        val text =
            context.assets
                .open(ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
        return json.decodeFromString(LegalityBundle.serializer(), text)
    }
}
