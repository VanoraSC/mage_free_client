package magefree.app.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import magefree.feature.cards.rememberCardArtRenderer
import magefree.feature.cards.slotFor
import magefree.feature.game.table.TableArtResolver

/**
 * Real card art for the battlefield preview.
 *
 * The board asks for a printing, not a name — the server names one on every card, and the fixtures do
 * the same — so this is a straight hand-off to the Coil-backed renderer that `:app` already owns. The
 * design system and `:feature:game` both stay free of an image dependency, which is the whole reason
 * the seam exists.
 *
 * It matters that the preview shows real art. Every rule the arrangement is judged on is about whether
 * a card stays readable at the size it was given, and a grey rectangle is the one background that makes
 * every size look fine.
 */
@Composable
fun rememberBattlefieldArtResolver(): TableArtResolver {
    // **Fill the width, anchored to the top — never centre-crop.** The Board tier draws a card cut
    // below its art box, and it gets there by letting the image overflow the face and clipping the
    // bottom. `ContentScale.Crop` is the wrong tool for that: it scales the image to *cover* the box
    // it is given, so in a box shorter than a card it takes the top and the bottom in equal measure
    // and the card loses its title bar — and if the box is not the height the layout intended, it
    // silently squashes instead. FillWidth preserves the card's proportions from its width alone,
    // which is the one dimension every tier agrees on, and top alignment makes the part that falls
    // off the bottom — the rules text nobody reads at board size.
    val renderer = rememberCardArtRenderer(contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter)
    return remember(renderer) { { request, display -> renderer.slotFor(request = request, display = display) } }
}
