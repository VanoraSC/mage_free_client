package magefree.decks.internal.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * The deck header row. Card lines live in [DeckCardEntity], linked by [id] with a cascading delete so
 * removing a deck removes its cards. `format` stores [magefree.decks.model.DeckFormat.key] (nullable).
 */
@Entity(tableName = "decks")
internal data class DeckEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String?,
    val format: String?,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One card line of a deck. [position] preserves the entry order within a [zone] (`MAIN`/`SIDEBOARD`);
 * identity is the printing (set code + collector number) plus the card name, matching [DeckCardEntity]
 * to XMage's `DeckCardInfo`.
 */
@Entity(
    tableName = "deck_cards",
    foreignKeys = [
        ForeignKey(
            entity = DeckEntity::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deckId")],
)
internal data class DeckCardEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deckId: String,
    val zone: String,
    val position: Int,
    val cardName: String,
    val setCode: String,
    val collectorNumber: String,
    val quantity: Int,
)

/** A deck header with all its card lines, assembled by Room in one transactional read. */
internal data class DeckWithCards(
    @Embedded val deck: DeckEntity,
    @Relation(parentColumn = "id", entityColumn = "deckId")
    val cards: List<DeckCardEntity>,
)

/** Library-list projection: the deck header plus pre-aggregated main/sideboard card counts. */
internal data class DeckSummaryRow(
    val id: String,
    val name: String,
    val author: String?,
    val format: String?,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(name = "mainCount") val mainCount: Int,
    @ColumnInfo(name = "sideboardCount") val sideboardCount: Int,
)
