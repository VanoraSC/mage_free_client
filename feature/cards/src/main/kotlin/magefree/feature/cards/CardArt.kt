package magefree.feature.cards

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardImageLoader
import magefree.designsystem.card.CardArtPlaceholder
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import org.koin.compose.koinInject

/*
 * The bridge between the Coil-backed [CardImageLoader] and the design system's abstract
 * [CardArtSlot]. The design system carries no image dependency; this is where card-browse supplies the real,
 * disk-cached loader — and where an art miss (uncached + offline) degrades to the design-system
 * [CardArtPlaceholder] rather than a crash or blank.
 */

/**
 * Renders a card's art into a slot region. A [CardArtRenderer] is the injectable seam the screens use:
 * production backs it with Coil ([CoilCardArtRenderer]); previews back it with a stub so no network
 * image is ever loaded in a preview or test.
 */
interface CardArtRenderer {
    @Composable
    fun Render(
        request: CardArtRequest?,
        display: CardDisplay,
        modifier: Modifier,
    )
}

/** Convenience: a [CardArtSlot] (design-system art slot) for a given request/display. */
fun CardArtRenderer.slotFor(
    request: CardArtRequest?,
    display: CardDisplay,
): CardArtSlot = { modifier -> Render(request = request, display = display, modifier = modifier) }

/**
 * A [CardArtRenderer] that always shows the design-system placeholder — used by previews and as the
 * degraded renderer, never loading a network image.
 */
object PlaceholderCardArtRenderer : CardArtRenderer {
    @Composable
    override fun Render(
        request: CardArtRequest?,
        display: CardDisplay,
        modifier: Modifier,
    ) {
        CardArtPlaceholder(card = display, modifier = modifier)
    }
}

/**
 * The production renderer: binds the policy-correct Coil [ImageLoader] + its cache-keyed
 * [ImageRequest] to Coil's [SubcomposeAsyncImage]. On loading or an error (a cache miss with no
 * network), it renders the design-system [CardArtPlaceholder] — the graceful offline degradation the
 * requires. A null [request] (a card with no printing) shows the placeholder directly.
 */
class CoilCardArtRenderer(
    private val imageLoader: ImageLoader,
    private val requestBuilder: (CardArtRequest) -> ImageRequest,
    private val contentScale: ContentScale = ContentScale.Crop,
) : CardArtRenderer {
    @Composable
    override fun Render(
        request: CardArtRequest?,
        display: CardDisplay,
        modifier: Modifier,
    ) {
        if (request == null) {
            CardArtPlaceholder(card = display, modifier = modifier)
            return
        }
        SubcomposeAsyncImage(
            model = requestBuilder(request),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
            loading = { CardArtPlaceholder(card = display, modifier = Modifier.fillMaxSize()) },
            error = { CardArtPlaceholder(card = display, modifier = Modifier.fillMaxSize()) },
        )
    }
}

/**
 * Remembers a [CoilCardArtRenderer] bound to the current policy-correct Coil loader. Re-derives when
 * the [CardImageLoader]'s exposed loader changes (e.g. a cache-policy downgrade swaps the instance).
 *
 * a Composable is not an injection site, so Hilt needed a declared `@EntryPoint`
 * interface plus `EntryPointAccessors.fromApplication(...)` to reach the app-wide [CardImageLoader]
 * singleton. Koin resolves from a Composable directly, so the interface is gone and
 * this is a one-line read of the same singleton — the only conversion here whose *shape*
 * changed rather than just its annotations.
 */
@Composable
fun rememberCardArtRenderer(contentScale: ContentScale = ContentScale.Crop): CardArtRenderer {
    val loader: CardImageLoader = koinInject()
    val imageLoader by loader.imageLoader.collectAsStateWithLifecycle()
    return remember(imageLoader, contentScale) {
        CoilCardArtRenderer(imageLoader = imageLoader, requestBuilder = loader::buildRequest, contentScale = contentScale)
    }
}
