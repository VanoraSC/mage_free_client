package magefree.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import magefree.model.ServerTarget
import magefree.network.di.networkModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A server list written **before** the KMP port is still there after it (story 0084).
 *
 * **The fixture is the point.** `resources/fixtures/server_targets.preferences_pb` was produced by
 * the pre-port `ServerRepository` — its `server_targets` key, its JSON encoding, DataStore's own
 * on-disk format — and committed. A store the test filled itself would prove only that the new path
 * is self-consistent; the servers a person has added exist only on their device, so the check that
 * matters is that the *old* file is the one the ported code opens.
 *
 * **It exercises the shipping construction, not a rebuilt copy of it.** The fixture is placed at
 * `filesDir/datastore/server_targets.preferences_pb` — where the `preferencesDataStore` delegate
 * resolves the file, and so where an installed app's list actually sits — and read through the real
 * `networkModule`. That is also what supplies the DataStore path on Android for verification
 * standard 2's purposes: the delegate, reached here rather than merely declared.
 *
 * Reading is the whole assertion, deliberately. The write path is `ServerRepositoryTest`'s subject,
 * and it cannot be exercised against a real file on a Windows host: DataStore's JVM file storage
 * renames a temp file over the target, which Windows refuses when the target exists — a limitation
 * of DataStore's own (AndroidX-tested, correct-on-device) file layer, already recorded there.
 */
@RunWith(RobolectricTestRunner::class)
class SavedServersSurviveUpgradeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val local = ServerTarget(host = "bridge.local", port = 9000, displayName = "Local bridge")
    private val remote = ServerTarget(host = "xmage.example.com", port = 443, secure = true)

    @Before
    fun installFixture() {
        // The path the `preferencesDataStore(name = "server_targets")` delegate resolves to.
        val target = File(context.filesDir, "datastore/server_targets.preferences_pb")
        target.parentFile?.mkdirs()
        target.delete()

        val bytes =
            checkNotNull(
                javaClass.classLoader?.getResourceAsStream("fixtures/server_targets.preferences_pb"),
            ) { "fixtures/server_targets.preferences_pb is missing from the test resources" }
                .use { it.readBytes() }
        target.writeBytes(bytes)
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `a pre-port server list is still there after the port`() =
        runTest {
            val koin =
                startKoin {
                    androidContext(context)
                    modules(networkModule)
                }.koin

            assertEquals(listOf(local, remote), koin.get<ServerRepository>().servers.first())
        }
}
