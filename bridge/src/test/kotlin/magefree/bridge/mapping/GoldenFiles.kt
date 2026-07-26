package magefree.bridge.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File

/**
 * The golden-file drift detector for the mapper boundary. A test serializes a mapped app-schema
 * message with `magefree.protocol.ProtocolJson.json` and calls [assertMatchesGolden]; the JSON is
 * compared against a committed file under `bridge/src/test/resources/golden/mapping/`.
 *
 * When an upstream `mage.view.*` shape changes, constructing the fixture or this assertion breaks
 * **loudly at the mapper** — exactly the intended blast radius (see `docs/architecture.md`).
 *
 * Setting `UPDATE_GOLDEN=1` regenerates the golden from the current output instead of asserting — the
 * intentional way to accept a mapper change. Goldens must be deterministic; callers normalize any
 * non-deterministic field (e.g. stub the timestamp) before asserting.
 */
public object GoldenFiles {
    // The Test task's working directory is the module dir (bridge/), so the source resources are here.
    // Reading/writing the source tree (not build/resources) keeps UPDATE_GOLDEN's output in the commit.
    private val goldenDir = File("src/test/resources/golden/mapping")

    /**
     * Asserts [actualJson] equals the committed golden named [name] (`<name>.json`). With
     * `UPDATE_GOLDEN=1`, writes [actualJson] as the new golden and returns without asserting.
     */
    public fun assertMatchesGolden(
        name: String,
        actualJson: String,
    ) {
        val file = File(goldenDir, "$name.json")
        if (System.getenv("UPDATE_GOLDEN") == "1") {
            goldenDir.mkdirs()
            file.writeText(actualJson + "\n")
            return
        }
        require(file.exists()) {
            "Missing golden ${file.path}; run with UPDATE_GOLDEN=1 to generate it."
        }
        assertEquals(file.readText().trim(), actualJson.trim(), "golden mismatch for $name")
    }
}
