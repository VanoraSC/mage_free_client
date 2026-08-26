package magefree.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import magefree.cards.CardCatalog
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.model.Card
import magefree.cards.model.CardId
import magefree.designsystem.card.CardDisplay

/** The distinct surface the inspection screen renders. */
enum class CardInspectionPhase {
    /** The card is being loaded from the catalog. */
    Loading,

    /** The card was loaded — the full-bleed detail. */
    Loaded,

    /** No card with the requested id exists in the catalog. */
    NotFound,

    /**
     * The catalog read failed — distinct from [NotFound], which means the catalog answered and the card
     * genuinely isn't there. A failure is environmental (typically no room for the ~14 MB asset copy on
     * first use), so it surfaces with a retry rather than crashing the process.
     */
    Error,
}

/**
 * Immutable UI state for the full-bleed card inspection view. Holds the design-system [display] fields,
 * the currently shown [face] (front/back), and the [artRequest] for that face (null -> placeholder).
 * DFC/MDFC cards can flip faces ([canFlip]); everything else is a single face.
 */
data class CardInspectionUiState(
    val phase: CardInspectionPhase = CardInspectionPhase.Loading,
    val display: CardDisplay? = null,
    val face: CardArtFace = CardArtFace.FRONT,
    val artRequest: CardArtRequest? = null,
    val canFlip: Boolean = false,
    val setRarityLabel: String? = null,
    val statsLabel: String? = null,
    val faceName: String? = null,
    /** Set only in [CardInspectionPhase.Error]; the human-readable reason the catalog read failed. */
    val errorMessage: String? = null,
) {
    /** The label for the flip control, reflecting which face would be shown next. */
    val flipLabel: String
        get() = if (face == CardArtFace.FRONT) "Flip to back" else "Flip to front"
}

/**
 * MVVM ViewModel for the read-only, full-bleed card inspection view. [load] fetches a
 * single oracle [Card] from the offline [CardCatalog] by id and maps it into [CardInspectionUiState];
 * [flipFace] toggles the shown face for a double-faced card, recomputing the art request for that face
 * (front/back via [CardArtFace]). Text always comes from the bundled catalog, so it is readable even
 * when art is uncached/offline (the screen renders the design-system placeholder on an art miss).
 *
 * Read-only — no in-game modifications or abilities, and no deck-add (deck building).
 */

class CardInspectionViewModel
    constructor(
        private val catalog: CardCatalog,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CardInspectionUiState())
        val uiState: StateFlow<CardInspectionUiState> = _uiState.asStateFlow()

        private var loaded: Card? = null

        /** The last id [load] was called with, so [retry] can re-run it. */
        private var lastRequested: CardId? = null

        /**
         * Load the card with [id]; a re-load resets the shown face to the front.
         *
         * This is a bare `launch` and `viewModelScope`'s `SupervisorJob` carries no
         * `CoroutineExceptionHandler`, so an unguarded catalog failure would reach the default handler
         * and crash the process. Fail soft instead.
         */
        fun load(id: CardId) {
            lastRequested = id
            _uiState.value = CardInspectionUiState(phase = CardInspectionPhase.Loading)
            viewModelScope.launch {
                val card =
                    try {
                        catalog.card(id)
                    } catch (error: Exception) {
                        loaded = null
                        _uiState.value =
                            CardInspectionUiState(phase = CardInspectionPhase.Error, errorMessage = message(CATALOG_ERROR, error))
                        return@launch
                    }
                loaded = card
                _uiState.value =
                    if (card == null) {
                        CardInspectionUiState(phase = CardInspectionPhase.NotFound)
                    } else {
                        card.toInspectionState(CardArtFace.FRONT)
                    }
            }
        }

        /** Re-run the last [load] after a catalog failure. */
        fun retry() {
            lastRequested?.let { load(it) }
        }

        /** Flip a double-faced card between its front and back faces. A no-op when [CardInspectionUiState.canFlip] is false. */
        fun flipFace() {
            val card = loaded ?: return
            if (!card.artFlippable) return
            _uiState.update { current ->
                val nextFace = if (current.face == CardArtFace.FRONT) CardArtFace.BACK else CardArtFace.FRONT
                card.toInspectionState(nextFace)
            }
        }

        private fun Card.toInspectionState(face: CardArtFace): CardInspectionUiState =
            CardInspectionUiState(
                phase = CardInspectionPhase.Loaded,
                display = toCardDisplay(),
                face = face,
                artRequest = printings.firstOrNull()?.let { CardArtRequest.of(it, face, CardArtSize.LARGE) },
                canFlip = artFlippable,
                setRarityLabel = setRarityLabel(),
                statsLabel = statsLabel(),
                faceName = if (face == CardArtFace.BACK) faces.otherFaceName else null,
            )

        private companion object {
            const val CATALOG_ERROR = "Couldn't read the card catalog"

            /** "<what went wrong>: <detail>", or just the headline when there's no useful detail. */
            fun message(
                headline: String,
                error: Throwable,
            ): String = error.message?.takeIf { it.isNotBlank() }?.let { "$headline: $it" } ?: headline

            /** Only transforming/modal double-faced cards have a distinct back-face image to flip to. */
            private val Card.artFlippable: Boolean
                get() = faces.doubleFaced || faces.modalDoubleFaced

            /** "SET · Rare" style label from the first printing, or null when unprinted. */
            private fun Card.setRarityLabel(): String? {
                val printing = printings.firstOrNull() ?: return null
                val rarity =
                    printing.rarity.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                return "${printing.setCode} · $rarity"
            }

            /** Power/toughness or loyalty or defense, whichever the card carries. */
            private fun Card.statsLabel(): String? =
                when {
                    power != null && toughness != null && typeLine.hasCardType("Creature") -> "$power/$toughness"
                    loyalty != null && typeLine.hasCardType("Planeswalker") -> "Loyalty $loyalty"
                    defense != null && typeLine.hasCardType("Battle") -> "Defense $defense"
                    else -> null
                }
        }
    }
