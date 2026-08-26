package magefree.decks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import magefree.decks.di.deckModule
import magefree.decks.internal.db.DeckDatabase
import magefree.decks.model.DeckEntry
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A deck library written **before** the KMP port opens, reads and writes under the ported code
 *.
 *
 * **The fixture is the point.** `resources/fixtures/decks-preport.db` was produced by the pre-port
 * `:core:decks` — `Room.databaseBuilder(context, DeckDatabase::class.java, name)` over the Android
 * support open helper — and committed. A database the test created itself would prove only that the
 * new path is self-consistent; a user's deck library is data that exists nowhere else, so the check
 * that matters is that the *old* file still opens.
 *
 * **It exercises the shipping construction, not a rebuilt copy of it.** The fixture is copied to
 * `getDatabasePath("decks.db")` — where an installed app's library actually sits — and read through
 * the real `deckModule`, so the path resolution, the `AndroidSQLiteDriver` and the multiplatform
 * builder under test are the ones `:app` runs. That is also what supplies the database file on
 * Android for verification standard 2's purposes: `androidContext().getDatabasePath(...)`, reached
 * here rather than merely declared.
 */
@RunWith(RobolectricTestRunner::class)
class ExistingDeckDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val burn = DeckId("fixture-deck-1")
    private val control = DeckId("fixture-deck-2")

    @Before
    fun installFixture() {
        val target = context.getDatabasePath(DeckDatabase.NAME)
        target.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { File(target.absolutePath + it).delete() }

        val bytes =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/decks-preport.db")) {
                "fixtures/decks-preport.db is missing from the test resources"
            }.use { it.readBytes() }
        target.writeBytes(bytes)
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    /** Each call assembles the graph afresh, so a read after a write goes through a new database. */
    private fun repository(): DeckRepository =
        startKoin {
            androidContext(context)
            modules(deckModule)
        }.koin.get()

    @Test
    fun `a pre-port deck library still reads back exactly`() =
        runTest {
            val repo = repository()

            val library = repo.observeLibrary().first()
            assertEquals(listOf("Burn", "Control"), library.map { it.name })

            val summary = library.first()
            assertEquals(burn, summary.id)
            assertTrue(summary.favorite)
            assertEquals(DeckFormat.MODERN, summary.format)
            assertEquals("Pete", summary.author)
            assertEquals(28, summary.mainCount)
            assertEquals(2, summary.sideboardCount)

            val deck = checkNotNull(repo.load(burn))
            assertEquals(
                listOf(
                    DeckEntry("Lightning Bolt", "M10", "146", 4),
                    DeckEntry("Monastery Swiftspear", "KTK", "118", 4),
                    DeckEntry("Mountain", "M10", "244", 20),
                ),
                deck.main,
            )
            assertEquals(listOf(DeckEntry("Smash to Smithereens", "SHM", "100", 2)), deck.sideboard)

            val other = checkNotNull(repo.load(control))
            assertEquals("Control", other.name)
            assertEquals(DeckFormat.STANDARD, other.format)
            assertEquals(listOf(DeckEntry("Counterspell", "MH2", "267", 4)), other.main)
        }

    @Test
    fun `a pre-port deck library still takes writes that survive reopening`() =
        runTest {
            repository().let { repo ->
                repo.rename(burn, "Burn (updated)")
                repo.save(
                    checkNotNull(repo.load(control)).let {
                        it.copy(main = it.main + DeckEntry("Brainstorm", "MH2", "266", 4))
                    },
                )
            }
            stopKoin()

            val reopened = repository()
            assertEquals("Burn (updated)", checkNotNull(reopened.load(burn)).name)
            assertEquals(
                listOf(
                    DeckEntry("Counterspell", "MH2", "267", 4),
                    DeckEntry("Brainstorm", "MH2", "266", 4),
                ),
                checkNotNull(reopened.load(control)).main,
            )
        }
}
