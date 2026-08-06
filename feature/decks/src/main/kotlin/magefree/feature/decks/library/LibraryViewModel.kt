package magefree.feature.decks.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import magefree.decks.DeckRepository
import magefree.decks.io.DeckIO
import magefree.decks.io.DeckImportResult
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import javax.inject.Inject

/** The distinct surface the library screen renders. */
enum class LibraryPhase {
    /** The library flow has not emitted yet. */
    Loading,

    /** The library has at least one deck — the list. */
    Content,

    /** The library is empty — the "create your first deck" prompt. */
    Empty,
}

/**
 * Immutable UI state for the deck library. The [decks] come straight from [DeckRepository.observeLibrary]
 * (favorites first, then most recent), fully offline; [lastImport] carries the issues of the most recent
 * import so the screen can surface unresolved/ambiguous lines.
 */
data class LibraryUiState(
    val decks: List<DeckSummary> = emptyList(),
    val phase: LibraryPhase = LibraryPhase.Loading,
    val lastImport: DeckImportResult? = null,
)

/**
 * MVVM ViewModel for the deck library (story 0035). Every operation is a local [DeckRepository]
 * read/write — **no network, ever**. It observes the reactive library list and hoists create / rename /
 * duplicate / delete / favorite / import. Newly created or imported decks are announced via [openDeck]
 * so the route can open the builder on them.
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val repository: DeckRepository,
        private val deckIO: DeckIO,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LibraryUiState())
        val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        private val openDeckChannel = Channel<DeckId>(Channel.BUFFERED)

        /** One-shot deck ids to open in the builder (a create/import just produced them). */
        val openDeck: Flow<DeckId> = openDeckChannel.receiveAsFlow()

        init {
            repository
                .observeLibrary()
                .onEach { decks ->
                    _uiState.value =
                        _uiState.value.copy(
                            decks = decks,
                            phase = if (decks.isEmpty()) LibraryPhase.Empty else LibraryPhase.Content,
                        )
                }.launchIn(viewModelScope)
        }

        /** Create a new empty deck and request the builder open on it. */
        fun createDeck(
            name: String,
            format: DeckFormat? = null,
        ) {
            viewModelScope.launch {
                val deck = repository.create(name = name.trim().ifBlank { DEFAULT_NEW_NAME }, format = format)
                openDeckChannel.send(deck.id)
            }
        }

        fun renameDeck(
            id: DeckId,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch { repository.rename(id, trimmed) }
        }

        fun duplicateDeck(id: DeckId) {
            viewModelScope.launch { repository.duplicate(id) }
        }

        fun deleteDeck(id: DeckId) {
            viewModelScope.launch { repository.delete(id) }
        }

        fun setFavorite(
            id: DeckId,
            favorite: Boolean,
        ) {
            viewModelScope.launch { repository.setFavorite(id, favorite) }
        }

        /**
         * Import [text] (0034 auto-detects the format) into the library as a new deck, then request the
         * builder open on it. The parse issues are retained in [LibraryUiState.lastImport] for the screen.
         */
        fun importDeck(text: String) {
            viewModelScope.launch {
                val result = deckIO.import(text)
                val imported = result.deck
                val created =
                    repository.create(
                        name = imported.name.trim().ifBlank { DEFAULT_IMPORT_NAME },
                        format = null,
                    )
                repository.save(
                    created.copy(
                        author = imported.author,
                        main = imported.main,
                        sideboard = imported.sideboard,
                    ),
                )
                _uiState.value = _uiState.value.copy(lastImport = result)
                openDeckChannel.send(created.id)
            }
        }

        /** Dismiss the retained import summary once the screen has shown it. */
        fun clearImportResult() {
            _uiState.value = _uiState.value.copy(lastImport = null)
        }

        private companion object {
            const val DEFAULT_NEW_NAME = "New deck"
            const val DEFAULT_IMPORT_NAME = "Imported deck"
        }
    }
