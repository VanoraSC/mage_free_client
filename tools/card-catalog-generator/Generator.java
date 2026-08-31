import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.DatabaseUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Card catalog generator for mage_free_client.
 *
 * This is the ONLY place in the project that touches XMage's {@code mage.*} types. It runs inside
 * the container build image (which has {@code Mage.Sets} + {@code CardRepository} on the classpath),
 * scans XMage's full card pool, and emits a compact, normalized, app-schema SQLite database that is
 * bundled on-device by {@code :core:cards}. No {@code mage.*} type crosses this boundary.
 *
 * Output schema (see docs / README):
 *   meta(key, value)
 *   card(id, oracle-level fields ...)         -- one row per distinct oracle card (~32k)
 *   printing(id, card_id, set_code, ...)      -- one row per printing (~91.5k)
 *
 * Normalized (card + printing) so identical rules/type/mana text is not repeated across every
 * reprint. All printings are kept (set + collector number + rarity are needed downstream for artwork
 * resolution and deck references).
 *
 * Usage: java Generator <output.sqlite> [xmageRef]
 */
public final class Generator {

    private static final int SCHEMA_VERSION = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Generator <output.sqlite> [xmageRef]");
            System.exit(2);
        }
        String outPath = args[0];
        String xmageRef = args.length > 1 ? args[1] : "unknown";

        // 1. Populate XMage's H2 card DB by scanning the card-set classes (Mage.Sets).
        System.out.println("[generator] scanning XMage card sets ...");
        CardScanner.scan();

        // 2. Read every CardInfo row back out of the freshly built H2 DB.
        ConnectionSource cs = new JdbcConnectionSource(
                DatabaseUtils.prepareH2Connection(DatabaseUtils.DB_NAME_CARDS, true));
        Dao<CardInfo, Object> dao = DaoManager.createDao(cs, CardInfo.class);
        List<CardInfo> all = dao.queryForAll();
        System.out.println("[generator] read " + all.size() + " printing rows from H2");

        // 3. Fold identical-oracle rows into distinct cards; keep every printing.
        Map<String, OracleCard> oracleByKey = new LinkedHashMap<>();
        List<PrintingRow> printings = new ArrayList<>(all.size());
        for (CardInfo ci : all) {
            OracleCard oracle = new OracleCard(ci);
            OracleCard existing = oracleByKey.get(oracle.key);
            if (existing == null) {
                oracleByKey.put(oracle.key, oracle);
                existing = oracle;
            }
            printings.add(new PrintingRow(existing, ci));
        }

        // 4. Deterministic ids: cards sorted by (name, key); printings by (set, number, rarity).
        List<OracleCard> cards = new ArrayList<>(oracleByKey.values());
        cards.sort(Comparator.comparing((OracleCard c) -> c.name).thenComparing(c -> c.key));
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).id = i + 1;
        }
        printings.sort(Comparator
                .comparing((PrintingRow p) -> p.setCode)
                .thenComparingInt(p -> p.collectorNumberInt)
                .thenComparing(p -> p.collectorNumber)
                .thenComparing(p -> p.rarity)
                .thenComparingInt(p -> p.card.id));
        for (int i = 0; i < printings.size(); i++) {
            printings.get(i).id = i + 1;
        }

        // 5. Write the SQLite bundle.
        System.out.println("[generator] writing " + cards.size() + " cards / "
                + printings.size() + " printings -> " + outPath);
        write(outPath, xmageRef, cards, printings);
        System.out.println("[generator] done");
    }

    private static void write(String outPath, String xmageRef,
                              List<OracleCard> cards, List<PrintingRow> printings) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + outPath)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS meta");
                st.executeUpdate("DROP TABLE IF EXISTS printing");
                st.executeUpdate("DROP TABLE IF EXISTS card");
                st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.executeUpdate(
                        "CREATE TABLE card ("
                        + "id INTEGER PRIMARY KEY,"
                        + "name TEXT NOT NULL,"
                        + "mana_cost TEXT NOT NULL,"
                        + "mana_value INTEGER NOT NULL,"
                        + "color_white INTEGER NOT NULL,"
                        + "color_blue INTEGER NOT NULL,"
                        + "color_black INTEGER NOT NULL,"
                        + "color_red INTEGER NOT NULL,"
                        + "color_green INTEGER NOT NULL,"
                        + "supertypes TEXT NOT NULL,"
                        + "card_types TEXT NOT NULL,"
                        + "subtypes TEXT NOT NULL,"
                        + "rules TEXT NOT NULL,"
                        + "power TEXT,"
                        + "toughness TEXT,"
                        + "loyalty TEXT,"
                        + "defense TEXT,"
                        + "split_card INTEGER NOT NULL,"
                        + "split_fuse INTEGER NOT NULL,"
                        + "split_aftermath INTEGER NOT NULL,"
                        + "split_half INTEGER NOT NULL,"
                        + "flip_card INTEGER NOT NULL,"
                        + "flip_card_name TEXT,"
                        + "double_faced INTEGER NOT NULL,"
                        + "double_faced_card INTEGER NOT NULL,"
                        + "double_faced_second_side_name TEXT,"
                        + "night_card INTEGER NOT NULL,"
                        + "second_side_name TEXT,"
                        + "meld_card INTEGER NOT NULL,"
                        + "melds_to_card_name TEXT,"
                        + "card_with_spell_option INTEGER NOT NULL,"
                        + "spell_option_card_name TEXT,"
                        + "extra_deck_card INTEGER NOT NULL)");
                st.executeUpdate(
                        "CREATE TABLE printing ("
                        + "id INTEGER PRIMARY KEY,"
                        + "card_id INTEGER NOT NULL REFERENCES card(id),"
                        + "set_code TEXT NOT NULL,"
                        + "collector_number TEXT NOT NULL,"
                        + "collector_number_int INTEGER NOT NULL,"
                        + "rarity TEXT NOT NULL)");
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO card VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (OracleCard card : cards) {
                    int i = 1;
                    ps.setInt(i++, card.id);
                    ps.setString(i++, card.name);
                    ps.setString(i++, card.manaCost);
                    ps.setInt(i++, card.manaValue);
                    ps.setInt(i++, card.white ? 1 : 0);
                    ps.setInt(i++, card.blue ? 1 : 0);
                    ps.setInt(i++, card.black ? 1 : 0);
                    ps.setInt(i++, card.red ? 1 : 0);
                    ps.setInt(i++, card.green ? 1 : 0);
                    ps.setString(i++, card.supertypes);
                    ps.setString(i++, card.cardTypes);
                    ps.setString(i++, card.subtypes);
                    ps.setString(i++, card.rules);
                    ps.setString(i++, card.power);
                    ps.setString(i++, card.toughness);
                    ps.setString(i++, card.loyalty);
                    ps.setString(i++, card.defense);
                    ps.setInt(i++, card.splitCard ? 1 : 0);
                    ps.setInt(i++, card.splitFuse ? 1 : 0);
                    ps.setInt(i++, card.splitAftermath ? 1 : 0);
                    ps.setInt(i++, card.splitHalf ? 1 : 0);
                    ps.setInt(i++, card.flipCard ? 1 : 0);
                    ps.setString(i++, card.flipCardName);
                    ps.setInt(i++, card.doubleFaced ? 1 : 0);
                    ps.setInt(i++, card.doubleFacedCard ? 1 : 0);
                    ps.setString(i++, card.doubleFacedSecondSideName);
                    ps.setInt(i++, card.nightCard ? 1 : 0);
                    ps.setString(i++, card.secondSideName);
                    ps.setInt(i++, card.meldCard ? 1 : 0);
                    ps.setString(i++, card.meldsToCardName);
                    ps.setInt(i++, card.cardWithSpellOption ? 1 : 0);
                    ps.setString(i++, card.spellOptionCardName);
                    ps.setInt(i++, card.extraDeckCard ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO printing VALUES (?,?,?,?,?,?)")) {
                for (PrintingRow p : printings) {
                    int i = 1;
                    ps.setInt(i++, p.id);
                    ps.setInt(i++, p.card.id);
                    ps.setString(i++, p.setCode);
                    ps.setString(i++, p.collectorNumber);
                    ps.setInt(i++, p.collectorNumberInt);
                    ps.setString(i++, p.rarity);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = c.prepareStatement("INSERT INTO meta VALUES (?,?)")) {
                putMeta(ps, "schema_version", Integer.toString(SCHEMA_VERSION));
                putMeta(ps, "xmage_version", "1.4.60");
                putMeta(ps, "xmage_ref", xmageRef);
                putMeta(ps, "card_count", Integer.toString(cards.size()));
                putMeta(ps, "printing_count", Integer.toString(printings.size()));
            }

            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE INDEX idx_card_name ON card(name)");
                st.executeUpdate("CREATE INDEX idx_card_types ON card(card_types)");
                st.executeUpdate("CREATE INDEX idx_card_mana_value ON card(mana_value)");
                st.executeUpdate("CREATE INDEX idx_printing_set_number ON printing(set_code, collector_number)");
                st.executeUpdate("CREATE INDEX idx_printing_card ON printing(card_id)");
            }
            c.commit();
            // VACUUM must run outside a transaction; compacts + defragments the file.
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("VACUUM");
            }
        }
    }

    private static void putMeta(PreparedStatement ps, String k, String v) throws Exception {
        ps.setString(1, k);
        ps.setString(2, v);
        ps.executeUpdate();
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String joinSpace(List<?> items) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(o.toString());
        }
        return sb.toString();
    }

    /** Oracle-level card (deduped across printings). */
    private static final class OracleCard {
        int id;
        final String name;
        final String manaCost;
        final int manaValue;
        final boolean white, blue, black, red, green;
        final String supertypes, cardTypes, subtypes, rules;
        final String power, toughness, loyalty, defense;
        final boolean splitCard, splitFuse, splitAftermath, splitHalf;
        final boolean flipCard;
        final String flipCardName;
        final boolean doubleFaced, doubleFacedCard;
        final String doubleFacedSecondSideName;
        final boolean nightCard;
        final String secondSideName;
        final boolean meldCard;
        final String meldsToCardName;
        final boolean cardWithSpellOption;
        final String spellOptionCardName;
        final boolean extraDeckCard;
        final String key;

        OracleCard(CardInfo ci) {
            this.name = ci.getName();
            this.manaCost = String.join("", ci.getManaCosts(CardInfo.ManaCostSide.ALL));
            this.manaValue = ci.getManaValue();
            this.white = ci.getColor().isWhite();
            this.blue = ci.getColor().isBlue();
            this.black = ci.getColor().isBlack();
            this.red = ci.getColor().isRed();
            this.green = ci.getColor().isGreen();
            this.supertypes = joinSpace(ci.getSupertypes());
            this.cardTypes = joinSpace(ci.getTypes());
            this.subtypes = joinSpace(ci.getSubTypes());
            this.rules = String.join("\n", ci.getRules());
            this.power = nullIfEmpty(ci.getPower());
            this.toughness = nullIfEmpty(ci.getToughness());
            this.loyalty = nullIfEmpty(ci.getStartingLoyalty());
            this.defense = nullIfEmpty(ci.getStartingDefense());
            this.splitCard = ci.isSplitCard();
            this.splitFuse = ci.isSplitFuseCard();
            this.splitAftermath = ci.isSplitAftermathCard();
            this.splitHalf = ci.isSplitCardHalf();
            this.flipCard = ci.isFlipCard();
            this.flipCardName = nullIfEmpty(ci.getFlipCardName());
            this.doubleFaced = ci.isDoubleFaced();
            this.doubleFacedCard = ci.isDoubleFacedCard();
            this.doubleFacedSecondSideName = nullIfEmpty(ci.getDoubleFacedSecondSideName());
            this.nightCard = ci.isNightCard();
            this.secondSideName = nullIfEmpty(ci.getSecondSideName());
            this.meldCard = ci.isMeldCard();
            this.meldsToCardName = nullIfEmpty(ci.getMeldsToCardName());
            this.cardWithSpellOption = ci.isCardWithSpellOption();
            this.spellOptionCardName = nullIfEmpty(ci.getSpellOptionCardName());
            this.extraDeckCard = ci.isExtraDeckCard();
            this.key = buildKey();
        }

        private String buildKey() {
            // All oracle-level fields; excludes per-printing identity (set, number, rarity, art).
            StringBuilder sb = new StringBuilder(256);
            sb.append(name).append('')
              .append(manaCost).append('')
              .append(manaValue).append('')
              .append(white).append(blue).append(black).append(red).append(green).append('')
              .append(supertypes).append('')
              .append(cardTypes).append('')
              .append(subtypes).append('')
              .append(rules).append('')
              .append(power).append('')
              .append(toughness).append('')
              .append(loyalty).append('')
              .append(defense).append('')
              .append(splitCard).append(splitFuse).append(splitAftermath).append(splitHalf).append('')
              .append(flipCard).append('').append(flipCardName).append('')
              .append(doubleFaced).append(doubleFacedCard).append('').append(doubleFacedSecondSideName).append('')
              .append(nightCard).append('').append(secondSideName).append('')
              .append(meldCard).append('').append(meldsToCardName).append('')
              .append(cardWithSpellOption).append('').append(spellOptionCardName).append('')
              .append(extraDeckCard);
            return sb.toString();
        }
    }

    /** A single printing pointing at its oracle card. */
    private static final class PrintingRow {
        int id;
        final OracleCard card;
        final String setCode;
        final String collectorNumber;
        final int collectorNumberInt;
        final String rarity;

        PrintingRow(OracleCard card, CardInfo ci) {
            this.card = card;
            this.setCode = ci.getSetCode();
            this.collectorNumber = ci.getCardNumber();
            this.collectorNumberInt = ci.getCardNumberAsInt();
            this.rarity = ci.getRarity() != null ? ci.getRarity().name() : "";
        }
    }
}
