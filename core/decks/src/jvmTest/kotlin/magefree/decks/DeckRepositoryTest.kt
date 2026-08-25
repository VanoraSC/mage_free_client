package magefree.decks

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import magefree.decks.internal.RoomDeckRepository
import magefree.decks.internal.db.DeckDatabase
import magefree.decks.model.Deck
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The deck library persists and reads back through real SQLite (via Room), on the `jvm()` target with
 * no Android runtime at all (story 0085). Covers create / save+load / rename / favorite / duplicate / delete,
 * the reactive library flow, and that data survives being re-read through a fresh repository instance
 * over the same store (persistence). Everything is local; no network is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepositoryTest {
    private lateinit var db: DeckDatabase
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = AtomicInteger(1_000)

    private fun newRepository(): DeckRepository {
        val ids = AtomicInteger(0)
        return RoomDeckRepository(
            dao = db.deckDao(),
            ioDispatcher = dispatcher,
            now = { clock.getAndIncrement().toLong() },
            newId = { "deck-${ids.incrementAndGet()}" },
        )
    }

    @Before
    fun setUp() {
        // Story 0085: Room's multiplatform in-memory builder over `BundledSQLiteDriver`, which
        // carries its own SQLite so no Android runtime is involved. The pre-move Android builder
        // took a `Context` and needed `setQueryExecutor`/`allowMainThreadQueries` to run everything
        // on the calling thread; `setQueryCoroutineContext` with the test dispatcher is the same
        // arrangement expressed the multiplatform way. Not one assertion below changed.
        db =
            Room
                .inMemoryDatabaseBuilder<DeckDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(dispatcher)
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create persists an empty deck that load returns`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val created = repo.create("My Deck", DeckFormat.MODERN)

            val loaded = repo.load(created.id)
            assertEquals(created, loaded)
            assertEquals("My Deck", loaded!!.name)
            assertEquals(DeckFormat.MODERN, loaded.format)
        }

    @Test
    fun `save then load preserves ordered main and sideboard entries`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val id = repo.create("Deck").id
            val deck =
                Deck(
                    id = id,
                    name = "Deck",
                    format = DeckFormat.LEGACY,
                    main =
                        listOf(
                            DeckEntry("Brainstorm", "ICE", "78", 4),
                            DeckEntry("Island", "ICE", "337", 20),
                        ),
                    sideboard = listOf(DeckEntry("Flusterstorm", "MH2", "44", 2)),
                )
            repo.save(deck)

            val loaded = repo.load(id)!!
            assertEquals(deck.main, loaded.main)
            assertEquals(deck.sideboard, loaded.sideboard)
        }

    @Test
    fun `rename and favorite update the stored deck`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val id = repo.create("Old Name").id

            repo.rename(id, "New Name")
            repo.setFavorite(id, true)

            val loaded = repo.load(id)!!
            assertEquals("New Name", loaded.name)
            assertTrue(loaded.favorite)
        }

    @Test
    fun `duplicate copies cards under a new id and clears favorite`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val id = repo.create("Original").id
            repo.save(
                Deck(
                    id = id,
                    name = "Original",
                    main = listOf(DeckEntry("Llanowar Elves", "M19", "314", 4)),
                ),
            )
            repo.setFavorite(id, true)

            val copy = repo.duplicate(id)!!
            assertNotEquals(id, copy.id)
            assertEquals("Original (copy)", copy.name)
            assertEquals(false, copy.favorite)
            assertEquals(repo.load(id)!!.main, copy.main)
        }

    @Test
    fun `delete removes the deck`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val id = repo.create("Doomed").id

            repo.delete(id)

            assertNull(repo.load(id))
        }

    @Test
    fun `duplicate of a missing deck returns null`() =
        runTest(dispatcher) {
            val repo = newRepository()
            assertNull(repo.duplicate(DeckId("nope")))
        }

    @Test
    fun `library flow emits decks favorites-first and reacts to changes`() =
        runTest(dispatcher) {
            val repo = newRepository()
            val a = repo.create("Alpha").id
            val b = repo.create("Beta").id

            repo.observeLibrary().test {
                // Both present initially (most-recently-updated first: Beta then Alpha).
                val initial = awaitItem()
                assertEquals(setOf("Alpha", "Beta"), initial.map { it.name }.toSet())

                // Favoriting Alpha pushes it to the front.
                repo.setFavorite(a, true)
                val afterFavorite = awaitItem()
                assertEquals("Alpha", afterFavorite.first().name)
                assertTrue(afterFavorite.first { it.id == a }.favorite)

                // Deleting Beta drops it from the library.
                repo.delete(b)
                val afterDelete = awaitItem()
                assertEquals(listOf("Alpha"), afterDelete.map { it.name })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `saved decks persist across a fresh repository over the same store`() =
        runTest(dispatcher) {
            val id = newRepository().create("Persisted", DeckFormat.PAUPER).id

            // A brand-new repository instance sees the deck — it lives in the store, not in memory.
            val reopened = newRepository().load(id)
            assertEquals("Persisted", reopened!!.name)
            assertEquals(DeckFormat.PAUPER, reopened.format)
        }
}
