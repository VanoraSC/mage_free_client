package magefree.feature.decks.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import magefree.decks.DeckRepository
import magefree.decks.io.DeckIO
import magefree.decks.io.DeckImportResult
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary

/** The distinct surface the library screen renders. */
enum class LibraryPhase {
    /** The library flow has not emitted yet. */
    Loading,

    /** The library has at least one deck — the list. */
    Content,

    /** The library is empty — the "create your first deck" prompt. */
    Empty,

    /** The library flow itself failed — an error with retry rather than an indefinite spinner. */
    Error,
}

/**
 * Immutable UI state for the deck library. The [decks] come straight from [DeckRepository.observeLibrary]
 * (favorites first, then most recent), fully offline; [lastImport] carries the issues of the most recent
 * import so the screen can surface unresolved/ambiguous lines.
 *
 * [errorMessage] carries a failed *operation* (an import whose catalog read failed, say) without
 * discarding the list that is already on screen; [LibraryPhase.Error] is for the list itself failing.
 */
data class LibraryUiState(
    val decks: List<DeckSummary> = emptyList(),
    val phase: LibraryPhase = LibraryPhase.Loading,
    val lastImport: DeckImportResult? = null,
    val errorMessage: String? = null,
)

/**
 * MVVM ViewModel for the deck library. Every operation is a local [DeckRepository]
 * read/write — **no network, ever**. It observes the reactive library list and hoists create / rename /
 * duplicate / delete / favorite / import. Newly created or imported decks are announced via [openDeck]
 * so the route can open the builder on them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel
    constructor(
        private val repository: DeckRepository,
        private val deckIO: DeckIO,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LibraryUiState())
        val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

        private val openDeckChannel = Channel<DeckId>(Channel.BUFFERED)

        /** One-shot deck ids to open in the builder (a create/import just produced them). */
        val openDeck: Flow<DeckId> = openDeckChannel.receiveAsFlow()

        /** Bumped by [retry]; re-subscribes the library flow after a failure. */
        private val libraryRetries = MutableStateFlow(0)

        init {
            libraryRetries
                .flatMapLatest {
                    // Fold the failure into the value so it can be rendered. Without the catch the
                    // throw escapes through launchIn to viewModelScope, whose SupervisorJob has no
                    // CoroutineExceptionHandler — i.e. a process crash, and a dead pipeline.
                    repository
                        .observeLibrary()
                        .map { decks -> Result.success(decks) }
                        .catch { error -> emit(Result.failure(error)) }
                }.onEach { result ->
                    _uiState.value =
                        result.fold(
                            onSuccess = { decks ->
                                _uiState.value.copy(
                                    decks = decks,
                                    phase = if (decks.isEmpty()) LibraryPhase.Empty else LibraryPhase.Content,
                                    errorMessage = null,
                                )
                            },
                            onFailure = { error ->
                                _uiState.value.copy(phase = LibraryPhase.Error, errorMessage = message(LIBRARY_ERROR, error))
                            },
                        )
                }.launchIn(viewModelScope)
        }

        /** Re-subscribe the deck library after a failure. */
        fun retry() {
            libraryRetries.update { it + 1 }
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
         * Import [text] (the format is auto-detected) into the library as a new deck, then request the
         * builder open on it. The parse issues are retained in [LibraryUiState.lastImport] for the screen.
         */
        fun importDeck(text: String) {
            viewModelScope.launch {
                // Import resolves every line against the bundled catalog. That read can fail for
                // environmental reasons (no room for the ~14 MB copy on first use), and this is a bare
                // launch: unguarded, the throw crashes the process. Fail soft — report it and leave the
                // library, and the next import, working.
                val result =
                    try {
                        deckIO.import(text)
                    } catch (error: Exception) {
                        _uiState.value = _uiState.value.copy(errorMessage = message(IMPORT_ERROR, error))
                        return@launch
                    }
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
                _uiState.value = _uiState.value.copy(lastImport = result, errorMessage = null)
                openDeckChannel.send(created.id)
            }
        }

        /** Dismiss the retained import summary once the screen has shown it. */
        fun clearImportResult() {
            _uiState.value = _uiState.value.copy(lastImport = null)
        }

        /** Dismiss a surfaced failure once the screen has shown it. */
        fun clearError() {
            _uiState.value = _uiState.value.copy(errorMessage = null)
        }

        private companion object {
            const val DEFAULT_NEW_NAME = "New deck"
            const val DEFAULT_IMPORT_NAME = "Imported deck"
            const val LIBRARY_ERROR = "Couldn't load your decks"
            const val IMPORT_ERROR = "Couldn't import that deck"

            /** "<what went wrong>: <detail>", or just the headline when there's no useful detail. */
            fun message(
                headline: String,
                error: Throwable,
            ): String = error.message?.takeIf { it.isNotBlank() }?.let { "$headline: $it" } ?: headline
        }
    }
