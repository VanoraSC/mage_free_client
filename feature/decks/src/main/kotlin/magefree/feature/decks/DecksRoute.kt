package magefree.feature.decks

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.cards.model.CardId
import magefree.decks.model.DeckId
import magefree.feature.cards.CardInspectionScreen
import magefree.feature.cards.CardInspectionViewModel
import magefree.feature.cards.CardSearchViewModel
import magefree.feature.cards.rememberCardArtRenderer
import magefree.feature.decks.builder.AddCardsScreen
import magefree.feature.decks.builder.BuilderScreen
import magefree.feature.decks.builder.BuilderViewModel
import magefree.feature.decks.builder.DeckArtSheet
import magefree.feature.decks.library.LibraryScreen
import magefree.feature.decks.library.LibraryViewModel

/**
 * The `:feature:decks` entry route: the deck **library**, with the touch-first **builder**, its
 * card search/add surface, full-bleed inspection, and the deck-scoped art sheet layered on top as
 * in-feature UI. Keeping library ↔ builder ↔ add ↔ inspect navigation inside the feature keeps the
 * `:app` graph to a single destination (mirroring `:feature:cards`). Every deck operation is served
 * offline; only art fetch/prefetch touches the network. [onBrowseCards] hands off to the app's
 * read-only catalog browser so card browse stays reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecksRoute(
    onBrowseCards: () -> Unit,
    modifier: Modifier = Modifier,
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    builderViewModel: BuilderViewModel = hiltViewModel(),
    searchViewModel: CardSearchViewModel = hiltViewModel(),
    inspectionViewModel: CardInspectionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val builderState by builderViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val inspectionState by inspectionViewModel.uiState.collectAsStateWithLifecycle()

    var openDeck by remember { mutableStateOf<DeckId?>(null) }
    var addingCards by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf<CardId?>(null) }
    var showArtSheet by remember { mutableStateOf(false) }

    val thumbnailRenderer = rememberCardArtRenderer(contentScale = ContentScale.Crop)
    val fullArtRenderer = rememberCardArtRenderer(contentScale = ContentScale.Fit)

    // A create/import produced a new deck: open the builder on it.
    LaunchedEffect(Unit) {
        libraryViewModel.openDeck.collect { id ->
            openDeck = id
            builderViewModel.load(id)
        }
    }

    // A share/export payload: hand it to the platform share sheet (user-initiated).
    LaunchedEffect(Unit) {
        builderViewModel.shareEvents.collect { content ->
            val send =
                Intent(Intent.ACTION_SEND).apply {
                    type = content.mimeType
                    putExtra(Intent.EXTRA_TITLE, content.fileName)
                    putExtra(Intent.EXTRA_SUBJECT, content.fileName)
                    putExtra(Intent.EXTRA_TEXT, content.content)
                }
            val chooser = Intent.createChooser(send, "Share deck").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(chooser)
        }
    }

    when {
        openDeck == null ->
            LibraryScreen(
                uiState = libraryState,
                onOpenDeck = { id ->
                    openDeck = id
                    builderViewModel.load(id)
                },
                onCreateDeck = libraryViewModel::createDeck,
                onRenameDeck = libraryViewModel::renameDeck,
                onDuplicateDeck = libraryViewModel::duplicateDeck,
                onDeleteDeck = libraryViewModel::deleteDeck,
                onToggleFavorite = libraryViewModel::setFavorite,
                onImportDeck = libraryViewModel::importDeck,
                onBrowseCards = onBrowseCards,
                onRetry = libraryViewModel::retry,
                modifier = modifier,
            )
        else -> {
            BackHandler(enabled = !addingCards && inspecting == null) { openDeck = null }
            BuilderScreen(
                uiState = builderState,
                onBack = { openDeck = null },
                onAddCards = { addingCards = true },
                onInspectCard = { inspecting = it },
                onChangeQuantity = builderViewModel::changeQuantity,
                onRemove = builderViewModel::removeRow,
                onFormatSelected = builderViewModel::setFormat,
                onShare = { builderViewModel.shareDeck() },
                onOpenArtSettings = { showArtSheet = true },
                onRetry = builderViewModel::retry,
                modifier = modifier,
            )
        }
    }

    if (addingCards) {
        BackHandler(enabled = inspecting == null) { addingCards = false }
        AddCardsScreen(
            uiState = searchState,
            artRenderer = thumbnailRenderer,
            onBack = { addingCards = false },
            onQueryChange = searchViewModel::onQueryChange,
            onClearQuery = searchViewModel::clearQuery,
            onColorToggle = searchViewModel::toggleColor,
            onCardTypeSelected = searchViewModel::setCardType,
            onManaValueSelected = searchViewModel::setManaValue,
            onRaritySelected = searchViewModel::setRarity,
            onResetFilters = searchViewModel::resetFilters,
            onRetry = searchViewModel::retry,
            onAdd = builderViewModel::addCard,
            onInspect = { inspecting = it },
        )
    }

    inspecting?.let { cardId ->
        LaunchedEffect(cardId) { inspectionViewModel.load(cardId) }
        BackHandler { inspecting = null }
        CardInspectionScreen(
            uiState = inspectionState,
            artRenderer = fullArtRenderer,
            onClose = { inspecting = null },
            onFlip = inspectionViewModel::flipFace,
            onRetry = inspectionViewModel::retry,
        )
    }

    if (showArtSheet) {
        ModalBottomSheet(
            onDismissRequest = { showArtSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            DeckArtSheet(
                policy = builderState.policy,
                prefetch = builderState.prefetch,
                onPolicyChange = builderViewModel::setPolicy,
                onDownloadDeckArt = builderViewModel::downloadDeckArt,
                onCancelDownload = builderViewModel::cancelDownload,
            )
        }
    }
}
