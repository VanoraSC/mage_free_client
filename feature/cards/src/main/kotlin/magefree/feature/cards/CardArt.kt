package magefree.feature.cards

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardImageLoader
import magefree.designsystem.card.CardArtPlaceholder
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay

/*
 * The bridge between story 0031's Coil-backed [CardImageLoader] and the design system's abstract
 * [CardArtSlot]. The design system carries no image dependency; this is where 0032 supplies the real,
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
 * The production renderer: binds 0031's policy-correct Coil [ImageLoader] + its cache-keyed
 * [ImageRequest] to Coil's [SubcomposeAsyncImage]. On loading or an error (a cache miss with no
 * network), it renders the design-system [CardArtPlaceholder] — the graceful offline degradation the
 * story requires. A null [request] (a card with no printing) shows the placeholder directly.
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

/** Hilt entry point exposing the app-wide [CardImageLoader] singleton (story 0031) to a Composable. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CardArtLoaderEntryPoint {
    fun cardImageLoader(): CardImageLoader
}

/**
 * Remembers a [CoilCardArtRenderer] bound to the current policy-correct Coil loader. Re-derives when
 * the [CardImageLoader]'s exposed loader changes (e.g. a cache-policy downgrade swaps the instance).
 */
@Composable
fun rememberCardArtRenderer(contentScale: ContentScale = ContentScale.Crop): CardArtRenderer {
    val context = LocalContext.current
    val loader =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, CardArtLoaderEntryPoint::class.java)
                .cardImageLoader()
        }
    val imageLoader by loader.imageLoader.collectAsStateWithLifecycle()
    return remember(imageLoader, contentScale) {
        CoilCardArtRenderer(imageLoader = imageLoader, requestBuilder = loader::buildRequest, contentScale = contentScale)
    }
}
