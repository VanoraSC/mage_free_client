package magefree.feature.decks.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import magefree.cards.art.ArtWarmer
import magefree.cards.art.CardArtFace
import magefree.cards.art.CardArtRequest
import magefree.cards.art.CardArtSize
import magefree.cards.art.PrefetchStatus
import magefree.cards.model.CardFaces
import magefree.cards.model.CardPrinting
import magefree.cards.model.Rarity
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckId
import magefree.decks.model.DeckZone
import magefree.feature.cards.toCardRow
import magefree.feature.decks.FakeCardCatalog
import magefree.feature.decks.builder.deckCardRow
import magefree.feature.decks.testCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * "Make this deck viewable offline" must warm **every size the deck surfaces display** (
 * defect A, deck-scoped half). SMALL and LARGE resolve to different URLs and therefore different Coil
 * cache entries, so warming only one leaves the other surface blank offline — which defeats the whole
 * point of the feature.
 *
 * The expected sizes are derived from the production mappings: the builder's rows come from
 * [deckCardRow] and the add-cards results grid from [toCardRow].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckArtDownloaderTest {
    private class RecordingWarmer : ArtWarmer {
        val warmed: MutableList<CardArtRequest> = Collections.synchronizedList(ArrayList())

        override suspend fun isCached(request: CardArtRequest): Boolean = false

        override suspend fun warm(request: CardArtRequest): Boolean {
            warmed += request
            return true
        }
    }

    private val entry = DeckEntry(cardName = "Opt", setCode = "XLN", collectorNumber = "65", quantity = 4)

    private val card =
        testCard(
            id = 1,
            name = "Opt",
            printings = listOf(CardPrinting(setCode = "XLN", collectorNumber = "65", rarity = Rarity.COMMON)),
        )

    private val deck = Deck(id = DeckId("deck-1"), name = "Test", main = listOf(entry))

    /** The size the builder's deck rows request, read off the production row mapping. */
    private fun builderSize(): CardArtSize = deckCardRow(entry, DeckZone.MAIN, card).artRequest!!.size

    /** The size the add-cards results grid requests, read off the production row mapping. */
    private fun addGridSize(): CardArtSize = card.toCardRow().artRequest!!.size

    @Test
    fun `deck download warms every size the deck surfaces display`() =
        runTest {
            val warmer = RecordingWarmer()
            val downloader =
                DefaultDeckArtDownloader(
                    catalog = FakeCardCatalog(cards = listOf(card)),
                    warmer = warmer,
                    appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            downloader.download(deck)
            advanceUntilIdle()

            val displayed = setOf(builderSize(), addGridSize())
            assertEquals("every size the deck surfaces request must be warmed", displayed, warmer.warmed.map { it.size }.toSet())
            assertEquals(displayed.size, warmer.warmed.size)

            val progress = downloader.progress.value
            assertEquals(PrefetchStatus.COMPLETED, progress.status)
            assertEquals("the total must count the real targets", displayed.size, progress.total)
        }

    @Test
    fun `a double-faced card warms both faces at every displayed size`() =
        runTest {
            val dfcEntry = DeckEntry(cardName = "Delver of Secrets", setCode = "ISD", collectorNumber = "51", quantity = 4)
            val dfc =
                testCard(
                    id = 2,
                    name = "Delver of Secrets",
                    faces = CardFaces(doubleFaced = true, secondSideName = "Insectile Aberration"),
                    printings = listOf(CardPrinting(setCode = "ISD", collectorNumber = "51", rarity = Rarity.COMMON)),
                )
            val warmer = RecordingWarmer()
            val downloader =
                DefaultDeckArtDownloader(
                    catalog = FakeCardCatalog(cards = listOf(dfc)),
                    warmer = warmer,
                    appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                )

            downloader.download(Deck(id = DeckId("deck-2"), name = "DFC", main = listOf(dfcEntry)))
            advanceUntilIdle()

            val displayed = setOf(builderSize(), addGridSize())
            for (size in displayed) {
                for (face in listOf(CardArtFace.FRONT, CardArtFace.BACK)) {
                    val expected = CardArtRequest(setCode = "ISD", collectorNumber = "51", face = face, size = size)
                    assertTrue("missing $expected in ${warmer.warmed}", warmer.warmed.contains(expected))
                }
            }
            assertEquals(displayed.size * 2, warmer.warmed.size)
        }
}
