package magefree.app.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val renderer = rememberCardArtRenderer()
    return remember(renderer) { { request, display -> renderer.slotFor(request = request, display = display) } }
}
