package magefree.app.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * **hermetic wiring guards** (verification standard 2, mechanised).
 *
 * A feature can be built, unit-tested, merged and **never reachable**, if `:app` does
 * not depend on it and nothing rendered it, so the installed APK could not sign in. Nothing failed,
 * because every existing test drove screens directly and never asked *"can the app get here?"*.
 *
 * These guards ask exactly that, twice, for every `:feature:*` module in the build:
 *
 * 1. [everyFeatureModuleIsPresentOnTheAppsRuntimeClasspath] — the feature's entry point and its
 *    **runtime-only** types (Hilt view models, which nothing calls at compile time — they are
 *    constructed reflectively) load from `:app`'s own class loader. This catches a dependency that is
 *    compile-only, excluded, or otherwise missing at runtime — the "compiles, then crashes/blank
 *    screen on device" shape.
 * 2. [theAppsOwnCodeReferencesEveryFeatureEntryPoint] — `:app`'s compiled classes actually **call**
 *    each feature's entry point. Read out of the class files' constant pools, so it is a fact about
 *    the built artifact, not about what the source looked like. This catches the built-but-unreachable case exactly: the module
 *    can be on the classpath and still be dead weight if no destination renders it.
 *
 * The set of features is read from `settings.gradle.kts` rather than hard-coded, so a **new**
 * `:feature:*` module fails this guard until it is deliberately listed in [FEATURE_ENTRY_POINTS] —
 * the list cannot silently go stale as the app grows.
 *
 * The third leg — that a **navigation destination** exists for each feature in the real graph — lives
 * in `src/testDebug`'s `FeatureDestinationWiringTest` (it needs Robolectric to compose the graph).
 */
class FeatureWiringGuardTest {
    @Test
    fun everyFeatureModuleIsPresentOnTheAppsRuntimeClasspath() {
        val loader = javaClass.classLoader!!
        val missing =
            featureEntryPoints().flatMap { (module, entry) ->
                (listOf(entry.entryPointClass) + entry.runtimeOnlyClasses).mapNotNull { className ->
                    runCatching { Class.forName(className, false, loader) }
                        .fold(onSuccess = { null }, onFailure = { "$module -> $className" })
                }
            }

        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Feature module(s) built but NOT on :app's runtime classpath.")
                    appendLine("Each line is a type the app must be able to load at runtime but cannot:")
                    missing.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine(
                        "Fix the wiring in app/build.gradle.kts (an `implementation(project(\":feature:…\"))`), " +
                            "not this test.",
                    )
                },
            )
        }
    }

    @Test
    fun theAppsOwnCodeReferencesEveryFeatureEntryPoint() {
        val appClasses = appClassFiles()
        assertTrue(
            "scanned no :app classes at all — the guard would pass vacuously; " +
                "code source was ${appCodeSource()}",
            appClasses.size >= MIN_EXPECTED_APP_CLASSES,
        )

        val referenced = appClasses.values.flatMapTo(mutableSetOf()) { referencedClassNames(it) }

        val unreferenced =
            featureEntryPoints().filterValues { entry ->
                entry.entryPointClass.replace('.', '/') !in referenced
            }

        if (unreferenced.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Feature module(s) on the classpath but UNREACHABLE from :app's own code.")
                    appendLine("This is the  defect: built, tested, merged, and rendered by nothing.")
                    unreferenced.forEach { (module, entry) ->
                        appendLine("  - $module: nothing in :app calls ${entry.entryPointClass}")
                    }
                    appendLine()
                    appendLine("Scanned ${appClasses.size} :app class files from ${appCodeSource()}.")
                    appendLine("Mount the feature (a destination in AppNavHost/MageNavHost/AppShell); don't relax this test.")
                },
            )
        }
    }

    @Test
    fun theFeatureListCoversEveryFeatureModuleInTheBuild() {
        val declared = featureModulesDeclaredInSettings()
        assertTrue("found no `:feature:*` modules in settings.gradle.kts — the scan is broken", declared.isNotEmpty())
        assertEquals(
            "every `:feature:*` module in settings.gradle.kts must be covered by a reachability expectation " +
                "(add it to FEATURE_ENTRY_POINTS, with the entry point :app renders it through)",
            declared,
            FEATURE_ENTRY_POINTS.keys.toSortedSet(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // What "reachable" means, per feature
    // ---------------------------------------------------------------------------------------------

    /**
     * @param entryPointClass the JVM class `:app` must call into to render the feature. Kotlin
     *   top-level functions compile to a `…Kt` facade named after their **file**, so this is the file
     *   holding the feature's entry composable.
     * @param runtimeOnlyClasses types nothing references at compile time — Hilt view models are
     *   constructed reflectively — so only a runtime-classpath check can prove they shipped.
     */
    private data class FeatureEntry(
        val entryPointClass: String,
        val runtimeOnlyClasses: List<String>,
    )

    private fun featureEntryPoints(): Map<String, FeatureEntry> = FEATURE_ENTRY_POINTS

    // ---------------------------------------------------------------------------------------------
    // settings.gradle.kts
    // ---------------------------------------------------------------------------------------------

    private fun featureModulesDeclaredInSettings(): Set<String> {
        val settings = repoRoot().resolve("settings.gradle.kts")
        assertTrue("settings.gradle.kts not found at $settings", settings.isFile)
        return INCLUDE_FEATURE.findAll(settings.readText()).map { it.groupValues[1] }.toSortedSet()
    }

    /** Walk up from the test's working directory (the `:app` project dir) to the repo root. */
    private fun repoRoot(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "no user.dir" }
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            if (dir.resolve("settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above $workingDir")
    }

    // ---------------------------------------------------------------------------------------------
    // Reading :app's compiled classes
    // ---------------------------------------------------------------------------------------------

    private fun appCodeSource(): File {
        val location =
            Class
                .forName("magefree.app.navigation.MageNavHostKt", false, javaClass.classLoader)
                .protectionDomain!!
                .codeSource.location
        return File(location.toURI())
    }

    /** Every compiled class under the `magefree.app` package, keyed by path, from :app's code source. */
    private fun appClassFiles(): Map<String, ByteArray> {
        val source = appCodeSource()
        return when {
            source.isDirectory ->
                source
                    .resolve(APP_PACKAGE_DIR)
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .associate { it.relativeTo(source).invariantPath() to it.readBytes() }

            source.isFile ->
                ZipFile(source).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.startsWith("$APP_PACKAGE_DIR/") && it.name.endsWith(".class") }
                        .associate { it.name to zip.getInputStream(it).readBytes() }
                }

            else -> error("cannot read :app classes from $source")
        }
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    // ---------------------------------------------------------------------------------------------
    // Class-file constant pool
    // ---------------------------------------------------------------------------------------------

    /**
     * The internal names of every class referenced by [classBytes], read from its constant pool.
     *
     * A `CONSTANT_Class` entry is emitted for the owner of every field/method the class touches, so
     * "`:app` calls `LobbyScreenKt.LobbyRoute(...)`" shows up here and disappears the moment the call
     * does — which is precisely the signal this guard needs. Matching on the constant pool rather than
     * searching the bytes for a string means a stray string literal cannot fake a reference.
     */
    private fun referencedClassNames(classBytes: ByteArray): Set<String> {
        val input = java.io.DataInputStream(classBytes.inputStream())
        require(input.readInt() == CLASS_FILE_MAGIC) { "not a class file" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version

        val constantPoolCount = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classNameIndexes = ArrayList<Int>()

        var index = 1
        while (index < constantPoolCount) {
            when (val tag = input.readUnsignedByte()) {
                TAG_UTF8 -> utf8[index] = input.readUTF()
                TAG_CLASS -> classNameIndexes += input.readUnsignedShort()
                TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> input.skipFully(2)
                TAG_INTEGER, TAG_FLOAT -> input.skipFully(4)
                TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF, TAG_NAME_AND_TYPE,
                TAG_DYNAMIC, TAG_INVOKE_DYNAMIC,
                -> input.skipFully(4)
                TAG_METHOD_HANDLE -> input.skipFully(3)
                // Long/Double occupy two constant-pool slots (JVMS 4.4.5).
                TAG_LONG, TAG_DOUBLE -> {
                    input.skipFully(8)
                    index++
                }
                else -> error("unknown constant pool tag $tag at #$index")
            }
            index++
        }
        return classNameIndexes.mapNotNullTo(mutableSetOf()) { utf8[it] }
    }

    private fun java.io.DataInputStream.skipFully(bytes: Int) {
        var remaining = bytes
        while (remaining > 0) remaining -= skipBytes(remaining).also { require(it > 0) { "truncated class file" } }
    }

    private companion object {
        const val CLASS_FILE_MAGIC = -0x35014542 // 0xCAFEBABE
        const val TAG_UTF8 = 1
        const val TAG_INTEGER = 3
        const val TAG_FLOAT = 4
        const val TAG_LONG = 5
        const val TAG_DOUBLE = 6
        const val TAG_CLASS = 7
        const val TAG_STRING = 8
        const val TAG_FIELD_REF = 9
        const val TAG_METHOD_REF = 10
        const val TAG_INTERFACE_METHOD_REF = 11
        const val TAG_NAME_AND_TYPE = 12
        const val TAG_METHOD_HANDLE = 15
        const val TAG_METHOD_TYPE = 16
        const val TAG_DYNAMIC = 17
        const val TAG_INVOKE_DYNAMIC = 18
        const val TAG_MODULE = 19
        const val TAG_PACKAGE = 20

        const val APP_PACKAGE_DIR = "magefree/app"

        /** A floor, so a scan that finds (almost) nothing is treated as a broken guard, not a pass. */
        const val MIN_EXPECTED_APP_CLASSES = 20

        val INCLUDE_FEATURE = Regex("""include\("(:feature:[A-Za-z0-9_-]+)"\)""")

        /**
         * Every `:feature:*` module and the entry point `:app` must render it through.
         *
         * Kept as data so adding a feature is a one-line, deliberate act —
         * [theFeatureListCoversEveryFeatureModuleInTheBuild] fails if a module is added to the build
         * and not to this map.
         */
        val FEATURE_ENTRY_POINTS =
            mapOf(
                // The root graph's start destination (AppNavHost).
                ":feature:connect" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.connect.ConnectFlowKt",
                        runtimeOnlyClasses =
                            listOf(
                                "magefree.feature.connect.ServerListViewModel",
                                "magefree.feature.connect.SignInViewModel",
                            ),
                    ),
                // the lobby browser behind Home's "Play" (MageNavHost's LobbyRoute).
                ":feature:lobby" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.lobby.LobbyScreenKt",
                        runtimeOnlyClasses = listOf("magefree.feature.lobby.LobbyViewModel"),
                    ),
                // the card catalog browser behind the deck library's "Browse cards".
                ":feature:cards" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.cards.CardsRouteKt",
                        runtimeOnlyClasses =
                            listOf(
                                "magefree.feature.cards.CardSearchViewModel",
                                "magefree.feature.cards.CardInspectionViewModel",
                            ),
                    ),
                // the deck library + builder on the Decks tab (AppShell's decksScreen default).
                ":feature:decks" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.decks.DecksRouteKt",
                        runtimeOnlyClasses =
                            listOf(
                                "magefree.feature.decks.library.LibraryViewModel",
                                "magefree.feature.decks.builder.BuilderViewModel",
                            ),
                    ),
                // the read-only game board, reached from the table room's MatchStarting
                // hand-off (MageNavHost's GameBoardNavRoute). without it the hand-off ended at a
                // terminal "Match starting…" screen and no destination consumed the game id at all.
                ":feature:game" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.game.GameRoutesKt",
                        runtimeOnlyClasses = listOf("magefree.feature.game.board.GameBoardViewModel"),
                    ),
                // host / join / table-room, reached from the lobby.
                ":feature:tables" to
                    FeatureEntry(
                        entryPointClass = "magefree.feature.tables.TablesRoutesKt",
                        runtimeOnlyClasses =
                            listOf(
                                "magefree.feature.tables.host.HostTableViewModel",
                                "magefree.feature.tables.join.JoinTableViewModel",
                                "magefree.feature.tables.room.TableRoomViewModel",
                            ),
                    ),
            )
    }
}
