package magefree.feature.lobby

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.MageListRow
import magefree.designsystem.theme.Corner
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.model.LobbyTable
import magefree.model.SkillLevel
import magefree.model.TableState

/**
 * One [LobbyTable] rendered for the browser: a design-system [MageListRow] carrying the table name,
 * host + format + seats + state, with an at-a-glance strip of flag pills (rated / passworded /
 * limited / tournament) beneath. The whole row is a tap target for the read-only detail/preview
 * (joining is deferred to EPIC-07).
 *
 * Stateless; every event is hoisted via [onClick].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableRow(
    table: LobbyTable,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        MageListRow(
            headline = table.name,
            supportingText = tableSupportingText(table),
            trailingContent = {
                Text(
                    text = seatsLabel(table),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            },
        )
        val flags = table.flags()
        if (flags.isNotEmpty()) {
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.medium,
                            end = Spacing.medium,
                            bottom = Spacing.small,
                        ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                flags.forEach { flag ->
                    TableFlagPill(
                        label = flag.label,
                        container = flag.container(),
                        content = flag.content(),
                    )
                }
            }
        }
    }
}

/** The host, format, deck, and seats summary line shown beneath the table name. */
internal fun tableSupportingText(table: LobbyTable): String =
    buildList {
        add(table.host)
        add(table.gameType)
        if (table.deckType.isNotBlank()) add(table.deckType)
        add(table.state.label())
    }.joinToString(" · ")

/** Compact seats indicator, e.g. `1/2`. */
internal fun seatsLabel(table: LobbyTable): String = "${table.seatsFilled}/${table.seatsTotal}"

/** A human-readable label for a [TableState]. */
internal fun TableState.label(): String =
    when (this) {
        TableState.Waiting -> "Waiting"
        TableState.ReadyToStart -> "Ready"
        TableState.Starting -> "Starting"
        TableState.Drafting -> "Drafting"
        TableState.Constructing -> "Constructing"
        TableState.Dueling -> "In progress"
        TableState.Sideboarding -> "Sideboarding"
        TableState.Finished -> "Finished"
        TableState.Unknown -> "Unknown"
    }

/** A human-readable label for a [SkillLevel]. */
internal fun SkillLevel.label(): String =
    when (this) {
        SkillLevel.Beginner -> "Beginner"
        SkillLevel.Casual -> "Casual"
        SkillLevel.Serious -> "Serious"
        SkillLevel.Unknown -> "Any skill"
    }

/**
 * The key table flags surfaced as at-a-glance pills. Each maps to a Material color role so a flag is
 * never conveyed by color alone (the label always carries the meaning).
 */
internal enum class TableFlag(
    val label: String,
) {
    Rated("Rated"),
    Passworded("Passworded"),
    Limited("Limited"),
    Tournament("Tournament"),
}

/** The active [TableFlag]s for this table, in a stable order. */
internal fun LobbyTable.flags(): List<TableFlag> =
    buildList {
        if (isRated) add(TableFlag.Rated)
        if (isPassworded) add(TableFlag.Passworded)
        if (isLimited) add(TableFlag.Limited)
        if (isTournament) add(TableFlag.Tournament)
    }

@Composable
private fun TableFlag.container(): Color =
    when (this) {
        TableFlag.Passworded -> MaterialTheme.colorScheme.tertiaryContainer
        TableFlag.Tournament -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

@Composable
private fun TableFlag.content(): Color =
    when (this) {
        TableFlag.Passworded -> MaterialTheme.colorScheme.onTertiaryContainer
        TableFlag.Tournament -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

/** A small token-styled pill for a single table flag. */
@Composable
private fun TableFlagPill(
    label: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(Corner.small),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier.padding(
                    horizontal = Spacing.small,
                    vertical = Spacing.extraSmall,
                ),
        )
    }
}

@Preview(name = "TableRow - light", showBackground = true)
@Preview(
    name = "TableRow - dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TableRowPreview() {
    MageTheme {
        Column {
            TableRow(table = sampleTables[0], onClick = {})
            TableRow(table = sampleTables[1], onClick = {})
            TableRow(table = sampleTables[2], onClick = {})
        }
    }
}

/** Sample tables shared by this file's and the screen's previews. */
internal val sampleTables: List<LobbyTable> =
    listOf(
        LobbyTable(
            id = "t-1",
            name = "Pete's Standard duel",
            host = "pete",
            gameType = "Two Player Duel",
            deckType = "Constructed - Standard",
            state = TableState.Waiting,
            seatsFilled = 1,
            seatsTotal = 2,
            isTournament = false,
            isRated = true,
            isPassworded = false,
            isLimited = false,
            skillLevel = SkillLevel.Casual,
            createdAtEpochMs = 3L,
        ),
        LobbyTable(
            id = "t-2",
            name = "Friday night draft",
            host = "alice",
            gameType = "Booster Draft",
            deckType = "Limited - Draft",
            state = TableState.Drafting,
            seatsFilled = 8,
            seatsTotal = 8,
            isTournament = true,
            isRated = true,
            isPassworded = false,
            isLimited = true,
            skillLevel = SkillLevel.Serious,
            createdAtEpochMs = 2L,
        ),
        LobbyTable(
            id = "t-3",
            name = "Private commander pod",
            host = "bob",
            gameType = "Commander Free For All",
            deckType = "Constructed - Commander",
            state = TableState.Waiting,
            seatsFilled = 2,
            seatsTotal = 4,
            isTournament = false,
            isRated = false,
            isPassworded = true,
            isLimited = false,
            skillLevel = SkillLevel.Casual,
            createdAtEpochMs = 1L,
        ),
    )
