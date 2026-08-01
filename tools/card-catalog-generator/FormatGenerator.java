import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Format-legality generator for story 0033 (mage_free_client), a sibling of {@link Generator}.
 *
 * Like the card-catalog generator, this is a container-only tool that touches XMage's {@code mage.*}
 * types and emits an app-schema, offline data bundle — here a small {@code formats.json} consumed by
 * {@code :core:decks}' offline {@code DeckLegality} checker. No {@code mage.*} type crosses the
 * boundary onto the device.
 *
 * XMage keeps the per-format legality definitions (legal set codes, banned/restricted card names,
 * the constructed constraints) in {@code mage.deck.*} subclasses of {@code mage.cards.decks.Constructed}.
 * Those classes are NOT in the pinned {@code org.mage:*} jars — they live only in upstream source
 * ({@code Mage.Server.Plugins/Mage.Deck.Constructed}). The generator's Docker build shallow-clones the
 * pinned upstream ref, compiles just those format sources against the pinned jars, and this tool then
 * instantiates each format and reads its data reflectively.
 *
 * Each format populates its {@code setCodes} in its constructor by filtering
 * {@code Sets.getInstance()} on {@code ExpansionSet} set-type flags + date cutoffs, so instantiation
 * needs only the set registry (already on the classpath) — no full card scan.
 *
 * Reproducibility note: {@code mage.deck.Standard} computes its legal sets from the *current date*
 * (rotation), so its {@code legalSetCodes} are date-sensitive; {@code generatedAt} records the run
 * date. Every other format is stable at a given upstream ref.
 *
 * Usage: java FormatGenerator <output.json> [xmageRef]
 */
public final class FormatGenerator {

    private static final int SCHEMA_VERSION = 1;
    private static final String XMAGE_VERSION = "1.4.60";

    /** The sideboard maximum XMage's Constructed.validate() hard-codes (100.4a). */
    private static final int SIDEBOARD_MAX = 15;
    /** The default per-card copy limit Constructed enforces (checkCounts(4, ...)). */
    private static final int MAX_COPIES_DEFAULT = 4;
    /** How Integer.MAX_VALUE (unlimited copies) is encoded in the bundle. */
    private static final int UNLIMITED = -1;

    /** The common paper constructed formats we bundle: {key, simple-class-name, display name}. */
    private static final String[][] FORMATS = {
            {"standard", "Standard", "Standard"},
            {"pioneer", "Pioneer", "Pioneer"},
            {"modern", "Modern", "Modern"},
            {"legacy", "Legacy", "Legacy"},
            {"vintage", "Vintage", "Vintage"},
            {"pauper", "Pauper", "Pauper"},
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FormatGenerator <output.json> [xmageRef]");
            System.exit(2);
        }
        String outPath = args[0];
        String xmageRef = args.length > 1 ? args[1] : "unknown";

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("xmageVersion", XMAGE_VERSION);
        root.put("xmageRef", xmageRef);
        root.put("generatedAt", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE));
        root.put("sideboardMax", SIDEBOARD_MAX);
        root.put("maxCopiesDefault", MAX_COPIES_DEFAULT);
        root.put("maxCopiesOverrides", readMaxCopiesOverrides());

        List<Object> formats = new ArrayList<>();
        for (String[] f : FORMATS) {
            formats.add(readFormat(f[0], f[1], f[2]));
        }
        root.put("formats", formats);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
        String json = gson.toJson(root) + "\n";
        Files.write(Paths.get(outPath), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("[format-generator] wrote " + formats.size() + " formats -> " + outPath);
    }

    /**
     * Read DeckValidator's static per-card copy-limit map (basic lands + deck-of-N cards). Encoded
     * with {@link #UNLIMITED} for Integer.MAX_VALUE so the on-device model needs no sentinel of its own.
     */
    private static Map<String, Integer> readMaxCopiesOverrides() throws Exception {
        Class<?> validator = Class.forName("mage.cards.decks.DeckValidator");
        Field field = validator.getDeclaredField("maxCopiesMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> raw = (Map<String, Integer>) field.get(null);
        // TreeMap => keys emitted in a stable (sorted) order for a reproducible bundle.
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue() == Integer.MAX_VALUE ? UNLIMITED : e.getValue());
        }
        return out;
    }

    private static Map<String, Object> readFormat(String key, String className, String displayName)
            throws Exception {
        Class<?> constructed = Class.forName("mage.cards.decks.Constructed");
        Object format = Class.forName("mage.deck." + className).getDeclaredConstructor().newInstance();

        @SuppressWarnings("unchecked")
        List<String> setCodes = (List<String>) constructed.getMethod("getSetCodes").invoke(format);
        int deckMinSize = (int) constructed.getMethod("getDeckMinSize").invoke(format);

        List<String> banned = readStringField(constructed, format, "banned");
        List<String> restricted = readStringField(constructed, format, "restricted");
        List<String> rarities = readRarities(constructed, format);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("displayName", displayName);
        out.put("validatorName", constructed.getMethod("getName").invoke(format));
        out.put("deckMinSize", deckMinSize);
        out.put("sideboardMax", SIDEBOARD_MAX);
        out.put("maxCopiesDefault", MAX_COPIES_DEFAULT);
        out.put("allowedRarities", rarities.isEmpty() ? null : rarities); // null => no rarity restriction
        out.put("legalSetCodes", sorted(setCodes));
        out.put("banned", sorted(banned));
        out.put("restricted", sorted(restricted));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringField(Class<?> owner, Object instance, String name)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return new ArrayList<>((List<String>) field.get(instance));
    }

    private static List<String> readRarities(Class<?> owner, Object instance) throws Exception {
        Field field = owner.getDeclaredField("rarities");
        field.setAccessible(true);
        List<?> raw = (List<?>) field.get(instance);
        List<String> out = new ArrayList<>();
        for (Object r : raw) {
            out.add(((Enum<?>) r).name()); // mage.constants.Rarity -> stable enum name (COMMON, LAND, ...)
        }
        return sorted(out);
    }

    private static List<String> sorted(List<String> in) {
        List<String> copy = new ArrayList<>(in);
        Collections.sort(copy);
        return copy;
    }
}
