package magefree.cards.internal

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import magefree.cards.CardCatalog
import magefree.cards.model.Card
import magefree.cards.model.CardColor
import magefree.cards.model.CardFaces
import magefree.cards.model.CardFilter
import magefree.cards.model.CardId
import magefree.cards.model.CardPrinting
import magefree.cards.model.CatalogCounts
import magefree.cards.model.ColorMatch
import magefree.cards.model.ManaCost
import magefree.cards.model.Rarity
import magefree.cards.model.TypeLine

/**
 * [CardCatalog] over the bundled read-only SQLite database. Queries are indexed local lookups run on
 * the injected IO dispatcher. The database is opened lazily (and once) on first use via
 * [databaseProvider], so constructing the catalog never touches disk on the main thread.
 *
 * The impl is deliberately framework-thin (raw SQL over [SQLiteConnection]) so the same code runs on
 * device, on the JVM, and under a Robolectric unit test against a fixture database.
 *
 * **This runs on `androidx.sqlite`'s driver API**, not `android.database.sqlite`,
 * which is multiplatform. Every SQL string here is byte-identical to the pre-port version on
 * purpose: the catalog is an immutable bundled asset, so the same queries must return the same rows,
 * and that is checkable by equality rather than by argument. The differences are mechanical —
 * `rawQuery` + `Cursor.moveToNext()` becomes `prepare` + `SQLiteStatement.step()`, and column
 * indices come from [columnIndices] because the driver API has no `getColumnIndexOrThrow`.
 */
internal class SqliteCardCatalog(
    private val databaseProvider: suspend () -> SQLiteConnection,
    private val ioDispatcher: CoroutineDispatcher,
) : CardCatalog {
    private val openMutex = Mutex()

    @Volatile
    private var database: SQLiteConnection? = null

    private suspend fun db(): SQLiteConnection {
        database?.let { return it }
        return openMutex.withLock {
            database ?: databaseProvider().also { database = it }
        }
    }

    private suspend fun <T> onDb(block: (SQLiteConnection) -> T): T = withContext(ioDispatcher) { block(db()) }

    override suspend fun card(id: CardId): Card? =
        onDb { db ->
            loadCards(db, listOf(id.value)).firstOrNull()
        }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Card> =
        onDb { db ->
            val q = query.trim()
            if (q.isEmpty()) return@onDb emptyList()

            val ids = ArrayList<Long>()
            val names = ArrayList<String>()
            db.query(
                "SELECT id, name FROM card WHERE name LIKE ? ESCAPE '\\'",
                listOf("%" + escapeLike(q) + "%"),
            ) { c ->
                while (c.step()) {
                    ids.add(c.getLong(0))
                    names.add(c.getText(1))
                }
            }
            if (ids.isEmpty()) return@onDb emptyList()

            val order =
                ids.indices.sortedWith(
                    compareBy({ rank(names[it], q) }, { names[it].length }, { names[it].lowercase() }),
                )
            val topIds =
                order
                    .asSequence()
                    .take(limit)
                    .map { ids[it] }
                    .toList()
            loadCards(db, topIds)
        }

    override suspend fun cardsByName(names: Collection<String>): Map<String, Card> =
        onDb { db ->
            val wanted =
                names
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
            if (wanted.isEmpty()) return@onDb emptyMap()

            // `name COLLATE NOCASE IN (…)` is an equality predicate served by the NOCASE name index
            // (see CardCatalogDatabase.prepare) — no leading wildcard, no full scan, one statement per
            // batch instead of one query per name.
            val ids = ArrayList<Long>(wanted.size)
            for (chunk in wanted.chunked(NAME_BATCH)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.query(
                    "SELECT id FROM card WHERE name COLLATE NOCASE IN ($placeholders)",
                    chunk,
                ) { c ->
                    while (c.step()) ids.add(c.getLong(0))
                }
            }
            if (ids.isEmpty()) return@onDb emptyMap()

            val byLowerName = HashMap<String, Card>(ids.size)
            for (card in loadCards(db, ids)) {
                val key = card.name.lowercase()
                if (!byLowerName.containsKey(key)) byLowerName[key] = card
            }
            val out = LinkedHashMap<String, Card>(wanted.size)
            for (name in wanted) {
                val key = name.lowercase()
                byLowerName[key]?.let { out[key] = it }
            }
            out
        }

    override suspend fun filter(
        criteria: CardFilter,
        limit: Int,
    ): List<Card> =
        onDb { db ->
            val where = ArrayList<String>()
            val args = ArrayList<String>()

            criteria.nameQuery?.takeIf { it.isNotBlank() }?.let {
                where += "name LIKE ? ESCAPE '\\'"
                args += "%" + escapeLike(it.trim()) + "%"
            }
            if (criteria.colors.isNotEmpty()) {
                where += colorPredicate(criteria.colors, criteria.colorMatch)
            }
            criteria.cardType?.let {
                where += "(' ' || card_types || ' ') LIKE ? ESCAPE '\\'"
                args += "% " + escapeLike(it) + " %"
            }
            criteria.subType?.let {
                where += "(' ' || subtypes || ' ') LIKE ? ESCAPE '\\'"
                args += "% " + escapeLike(it) + " %"
            }
            criteria.manaValue?.let {
                where += "mana_value BETWEEN ? AND ?"
                args += it.first.toString()
                args += it.last.toString()
            }
            criteria.setCode?.let {
                where += "EXISTS (SELECT 1 FROM printing p WHERE p.card_id = card.id AND p.set_code = ?)"
                args += it
            }
            criteria.rarity?.let {
                where += "EXISTS (SELECT 1 FROM printing p WHERE p.card_id = card.id AND p.rarity = ?)"
                args += it.name
            }
            if (criteria.legalSets.isNotEmpty()) {
                val placeholders = criteria.legalSets.joinToString(",") { "?" }
                where += "EXISTS (SELECT 1 FROM printing p WHERE p.card_id = card.id AND p.set_code IN ($placeholders))"
                args += criteria.legalSets
            }

            val sql =
                buildString {
                    append("SELECT * FROM card")
                    if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
                    append(" ORDER BY name LIMIT ").append(limit.coerceAtLeast(0))
                }

            val cards = ArrayList<CardRow>()
            db.query(sql, args) { c ->
                val cols = c.columnIndices()
                while (c.step()) cards += c.toCardRow(cols)
            }
            attachPrintings(db, cards)
        }

    override suspend fun counts(): CatalogCounts =
        onDb { db ->
            db.query("SELECT (SELECT COUNT(*) FROM card), (SELECT COUNT(*) FROM printing)") { c ->
                c.step()
                CatalogCounts(cardCount = c.getInt(0), printingCount = c.getInt(1))
            }
        }

    /** Load the given card ids (preserving order) with their printings attached. */
    private fun loadCards(
        db: SQLiteConnection,
        ids: List<Long>,
    ): List<Card> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val byId = LinkedHashMap<Long, CardRow>()
        db.query(
            "SELECT * FROM card WHERE id IN ($placeholders)",
            ids.map { it.toString() },
        ) { c ->
            val cols = c.columnIndices()
            while (c.step()) {
                val row = c.toCardRow(cols)
                byId[row.id] = row
            }
        }
        val ordered = ids.mapNotNull { byId[it] }
        return attachPrintings(db, ordered)
    }

    private fun attachPrintings(
        db: SQLiteConnection,
        rows: List<CardRow>,
    ): List<Card> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val printings = HashMap<Long, MutableList<CardPrinting>>()
        db.query(
            "SELECT card_id, set_code, collector_number, rarity FROM printing " +
                "WHERE card_id IN ($placeholders) " +
                "ORDER BY set_code, collector_number_int, collector_number",
            ids.map { it.toString() },
        ) { c ->
            while (c.step()) {
                val cardId = c.getLong(0)
                printings.getOrPut(cardId) { ArrayList() }.add(
                    CardPrinting(
                        setCode = c.getText(1),
                        collectorNumber = c.getText(2),
                        rarity = Rarity.fromRaw(c.getText(3)),
                    ),
                )
            }
        }
        return rows.map { it.toCard(printings[it.id].orEmpty()) }
    }

    private companion object {
        /** Names per `IN (…)` statement — well under SQLite's 999 bound-variable ceiling. */
        const val NAME_BATCH = 400

        /**
         * Prepare [sql], bind [args] as text in order, and run [block] over the statement.
         *
         * Everything is bound with `bindText`, including the numeric predicates
         * (`mana_value BETWEEN ? AND ?`), because that is exactly what the pre-port
         * `SQLiteDatabase.rawQuery(sql, Array<String>)` did — SQLite applies the same affinity
         * coercion either way. Binding numbers as longs here would be tidier and would be a
         * behaviour change, which this port does not make.
         */
        inline fun <T> SQLiteConnection.query(
            sql: String,
            args: List<String> = emptyList(),
            block: (SQLiteStatement) -> T,
        ): T =
            prepare(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.bindText(index + 1, arg) }
                block(statement)
            }

        /**
         * Column name → index for the statement's current result shape.
         *
         * The driver API exposes `getColumnName(i)` but no `getColumnIndexOrThrow(name)`, and the
         * `SELECT *` queries here read ~25 columns by name. Building the map once per statement
         * keeps that an O(1) lookup per field instead of a linear scan per field per row.
         */
        fun SQLiteStatement.columnIndices(): Map<String, Int> =
            buildMap {
                for (index in 0 until getColumnCount()) put(getColumnName(index), index)
            }

        /** exact=0, prefix=1, word-prefix=2, substring=3 — lower sorts first. */
        fun rank(
            name: String,
            query: String,
        ): Int {
            val n = name.lowercase()
            val q = query.lowercase()
            return when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.split(WORD_BOUNDARY).any { it.startsWith(q) && it.isNotEmpty() } -> 2
                else -> 3
            }
        }

        val WORD_BOUNDARY = Regex("[^\\p{L}\\p{N}]+")

        fun escapeLike(input: String): String = input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        fun colorPredicate(
            colors: Set<CardColor>,
            match: ColorMatch,
        ): String {
            val cols = colors.map { it.column() }
            return when (match) {
                ColorMatch.ANY -> cols.joinToString(" OR ", "(", ")") { "$it = 1" }
                ColorMatch.ALL -> cols.joinToString(" AND ", "(", ")") { "$it = 1" }
                ColorMatch.EXACT ->
                    CardColor.entries.joinToString(" AND ", "(", ")") { c ->
                        "${c.column()} = ${if (c in colors) 1 else 0}"
                    }
            }
        }

        fun CardColor.column(): String =
            when (this) {
                CardColor.WHITE -> "color_white"
                CardColor.BLUE -> "color_blue"
                CardColor.BLACK -> "color_black"
                CardColor.RED -> "color_red"
                CardColor.GREEN -> "color_green"
            }

        fun splitTokens(value: String?): List<String> = value?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

        fun SQLiteStatement.toCardRow(columns: Map<String, Int>): CardRow {
            fun index(name: String): Int =
                columns[name] ?: throw IllegalStateException("card row has no column '$name' (have: ${columns.keys})")

            // `getText` on a NULL column is not defined to return null, so nullability is decided by
            // `isNull` first. The pre-port `Cursor.getString` returned null for a NULL column, and
            // several of these fields are genuinely null (power, loyalty, flip_card_name, …).
            fun s(name: String): String? = index(name).let { if (isNull(it)) null else getText(it) }

            fun i(name: String): Int = getInt(index(name))

            fun b(name: String): Boolean = i(name) != 0
            val colors =
                buildSet {
                    if (b("color_white")) add(CardColor.WHITE)
                    if (b("color_blue")) add(CardColor.BLUE)
                    if (b("color_black")) add(CardColor.BLACK)
                    if (b("color_red")) add(CardColor.RED)
                    if (b("color_green")) add(CardColor.GREEN)
                }
            return CardRow(
                id = getLong(index("id")),
                name = s("name").orEmpty(),
                manaCost = ManaCost(s("mana_cost").orEmpty()),
                manaValue = i("mana_value"),
                colors = colors,
                typeLine =
                    TypeLine(
                        superTypes = splitTokens(s("supertypes")),
                        cardTypes = splitTokens(s("card_types")),
                        subTypes = splitTokens(s("subtypes")),
                    ),
                rules = s("rules").orEmpty().split('\n').filter { it.isNotEmpty() },
                power = s("power"),
                toughness = s("toughness"),
                loyalty = s("loyalty"),
                defense = s("defense"),
                faces =
                    CardFaces(
                        splitCard = b("split_card"),
                        splitFuse = b("split_fuse"),
                        splitAftermath = b("split_aftermath"),
                        splitHalf = b("split_half"),
                        flipCard = b("flip_card"),
                        flipCardName = s("flip_card_name"),
                        doubleFaced = b("double_faced"),
                        modalDoubleFaced = b("double_faced_card"),
                        secondSideName = s("second_side_name") ?: s("double_faced_second_side_name"),
                        nightCard = b("night_card"),
                        meldCard = b("meld_card"),
                        meldsToCardName = s("melds_to_card_name"),
                        hasSpellOption = b("card_with_spell_option"),
                        spellOptionCardName = s("spell_option_card_name"),
                    ),
                extraDeckCard = b("extra_deck_card"),
            )
        }
    }

    /** Oracle-card fields read from a `card` row, before printings are attached. */
    private class CardRow(
        val id: Long,
        val name: String,
        val manaCost: ManaCost,
        val manaValue: Int,
        val colors: Set<CardColor>,
        val typeLine: TypeLine,
        val rules: List<String>,
        val power: String?,
        val toughness: String?,
        val loyalty: String?,
        val defense: String?,
        val faces: CardFaces,
        val extraDeckCard: Boolean,
    ) {
        fun toCard(printings: List<CardPrinting>): Card =
            Card(
                id = CardId(id),
                name = name,
                manaCost = manaCost,
                manaValue = manaValue,
                colors = colors,
                typeLine = typeLine,
                rules = rules,
                power = power,
                toughness = toughness,
                loyalty = loyalty,
                defense = defense,
                faces = faces,
                extraDeckCard = extraDeckCard,
                printings = printings,
            )
    }
}
