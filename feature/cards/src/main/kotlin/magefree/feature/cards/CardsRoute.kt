package magefree.feature.cards

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.cards.model.CardId

/**
 * The `:feature:cards` entry route: the card search/browse surface, with the full-bleed inspection
 * view and the art cache/download sheet layered on top as in-feature UI. Keeping search <-> inspect
 * navigation inside the feature keeps the `:app` nav graph to a single nested destination (mirroring
 * how `:feature:lobby` is wired). Back from inspection returns to the results; Back from results
 * returns to the caller ([onBack]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    searchViewModel: CardSearchViewModel = hiltViewModel(),
    inspectionViewModel: CardInspectionViewModel = hiltViewModel(),
    artSettingsViewModel: CardArtSettingsViewModel = hiltViewModel(),
) {
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val inspectionState by inspectionViewModel.uiState.collectAsStateWithLifecycle()
    val artSettingsState by artSettingsViewModel.uiState.collectAsStateWithLifecycle()

    var selectedCard by remember { mutableStateOf<CardId?>(null) }
    var showArtSettings by remember { mutableStateOf(false) }

    val thumbnailRenderer = rememberCardArtRenderer(contentScale = ContentScale.Crop)
    val fullArtRenderer = rememberCardArtRenderer(contentScale = ContentScale.Fit)

    CardSearchScreen(
        uiState = searchState,
        artRenderer = thumbnailRenderer,
        onBack = onBack,
        onQueryChange = searchViewModel::onQueryChange,
        onClearQuery = searchViewModel::clearQuery,
        onColorToggle = searchViewModel::toggleColor,
        onCardTypeSelected = searchViewModel::setCardType,
        onManaValueSelected = searchViewModel::setManaValue,
        onRaritySelected = searchViewModel::setRarity,
        onResetFilters = searchViewModel::resetFilters,
        onCardSelected = { selectedCard = it },
        onRetry = searchViewModel::retry,
        onOpenArtSettings = { showArtSettings = true },
        modifier = modifier,
    )

    selectedCard?.let { cardId ->
        LaunchedEffect(cardId) { inspectionViewModel.load(cardId) }
        BackHandler { selectedCard = null }
        CardInspectionScreen(
            uiState = inspectionState,
            artRenderer = fullArtRenderer,
            onClose = { selectedCard = null },
            onFlip = inspectionViewModel::flipFace,
            onRetry = inspectionViewModel::retry,
        )
    }

    if (showArtSettings) {
        ModalBottomSheet(
            onDismissRequest = { showArtSettings = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            CardArtSettingsSheet(
                uiState = artSettingsState,
                onPolicyChange = artSettingsViewModel::setPolicy,
                onDownloadAll = artSettingsViewModel::downloadAllArt,
                onCancelDownload = artSettingsViewModel::cancelDownload,
            )
        }
    }
}
