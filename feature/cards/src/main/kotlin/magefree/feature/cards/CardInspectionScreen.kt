package magefree.feature.cards

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.FullCardView
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.ErrorState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/**
 * Stateless full-bleed card inspection surface. Wraps the design system's [FullCardView] (large art +
 * name / mana / type / oracle) and overlays a **flip** control for double-faced cards, which toggles
 * the shown face (front/back) via [onFlip]. Art comes through the injected [CardArtRenderer] so an
 * uncached/offline face degrades to the design-system placeholder while the card text stays readable.
 *
 * Read-only: no in-game modifications or abilities; the deck-add action is deck building.
 */
@Composable
fun CardInspectionScreen(
    uiState: CardInspectionUiState,
    artRenderer: CardArtRenderer,
    onClose: () -> Unit,
    onFlip: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState.phase) {
        CardInspectionPhase.Loading ->
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LoadingState(message = "Loading card")
            }
        CardInspectionPhase.NotFound ->
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EmptyState(title = "Card not found", message = "This card is not in the catalog.")
            }
        CardInspectionPhase.Error ->
            Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                ErrorState(
                    message = uiState.errorMessage ?: "Couldn't read the card catalog",
                    onRetry = onRetry,
                )
            }
        CardInspectionPhase.Loaded -> {
            val display = uiState.display ?: return
            Box(modifier = modifier.fillMaxSize()) {
                FullCardView(
                    card = displayForFace(display, uiState),
                    onClose = onClose,
                    art = artRenderer.slotFor(uiState.artRequest, display),
                    modifications = detailLines(uiState),
                )
                if (uiState.canFlip) {
                    ExtendedFloatingActionButton(
                        onClick = onFlip,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.medium),
                        icon = { Icon(imageVector = Icons.Filled.Refresh, contentDescription = null) },
                        text = { Text(uiState.flipLabel) },
                    )
                }
            }
        }
    }
}

/**
 * When flipped to the back face of a DFC, surface the back-face name in the title (the catalog models
 * per-face art + name; full per-face oracle text is not modeled, so the front oracle stays shown).
 */
private fun displayForFace(
    display: CardDisplay,
    uiState: CardInspectionUiState,
): CardDisplay = if (uiState.face == CardArtFace.BACK && uiState.faceName != null) display.copy(name = uiState.faceName) else display

/** The set/rarity + P/T/loyalty lines, shown via [FullCardView]'s detail section (empty ones drop out). */
private fun detailLines(uiState: CardInspectionUiState): List<String> =
    listOfNotNull(
        uiState.setRarityLabel?.let { "Set: $it" },
        uiState.statsLabel,
    )

// ---- Previews ---------------------------------------------------------------------------------

@Composable
private fun previewScreen(uiState: CardInspectionUiState) {
    MageTheme {
        CardInspectionScreen(
            uiState = uiState,
            artRenderer = PlaceholderCardArtRenderer,
            onClose = {},
            onFlip = {},
            onRetry = {},
        )
    }
}

private val loadedSingleFace =
    CardInspectionUiState(
        phase = CardInspectionPhase.Loaded,
        display =
            CardDisplay(
                name = "Lightning Bolt",
                manaCost = "{R}",
                typeLine = "Instant",
                oracleText = "Lightning Bolt deals 3 damage to any target.",
            ),
        artRequest = CardArtRequest("2ed", "161"),
        setRarityLabel = "2ED · Common",
    )

private val loadedDoubleFace =
    CardInspectionUiState(
        phase = CardInspectionPhase.Loaded,
        display =
            CardDisplay(
                name = "Delver of Secrets",
                manaCost = "{U}",
                typeLine = "Creature — Human Wizard",
                oracleText =
                    "At the beginning of your upkeep, look at the top card of your library. You may reveal that " +
                        "card. If an instant or sorcery card is revealed this way, transform Delver of Secrets.",
            ),
        artRequest = CardArtRequest("isd", "51"),
        canFlip = true,
        setRarityLabel = "ISD · Common",
        statsLabel = "1/1",
    )

@Preview(name = "Inspection - single face (light)", showBackground = true, heightDp = 780)
@Preview(name = "Inspection - single face (dark)", showBackground = true, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InspectionSingleFacePreview() = previewScreen(loadedSingleFace)

@Preview(name = "Inspection - double-faced", showBackground = true, heightDp = 780)
@Composable
private fun InspectionDoubleFacePreview() = previewScreen(loadedDoubleFace)

@Preview(name = "Inspection - not found", showBackground = true, heightDp = 780)
@Composable
private fun InspectionNotFoundPreview() = previewScreen(CardInspectionUiState(phase = CardInspectionPhase.NotFound))

@Preview(name = "Inspection - catalog error", showBackground = true, heightDp = 780)
@Composable
private fun InspectionErrorPreview() =
    previewScreen(CardInspectionUiState(phase = CardInspectionPhase.Error, errorMessage = "Couldn't read the card catalog"))
