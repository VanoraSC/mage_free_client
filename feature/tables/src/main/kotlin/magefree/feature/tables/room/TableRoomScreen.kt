package magefree.feature.tables.room

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageListRow
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.feature.tables.TableRole
import magefree.network.table.Seat
import magefree.network.table.SeatPlayerType
import magefree.network.table.TablePhase
import magefree.network.table.TableServerState
import magefree.network.table.TableState

/** Title of the table room; shared with tests so the two agree. */
const val ROOM_TITLE: String = "Table room"

/** The terminal "match starting" text — the game hand-off marker; shared with tests. */
const val MATCH_STARTING_LABEL: String = "Match starting…"

/**
 * Stateless table room. Renders the table's **actual** seats (who sits where, of what kind, which slots
 * are open) and the server's own table state, the format/options summary, and
 * the role-appropriate actions (host start/remove; player leave; spectator read-only). The deck is
 * bound when the seat is taken, so the room has no deck surface. On
 * the match-start signal it shows the terminal [MATCH_STARTING_LABEL] hand-off state — no gameplay. Every
 * event is hoisted; the composable performs no I/O.
 */
@Composable
fun TableRoomScreen(
    uiState: TableRoomUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRemove: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = ROOM_TITLE,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier.fillMaxSize().padding(innerPadding)
        when {
            uiState.isMatchStarting -> MatchStartingView(modifier = content)
            uiState.isLoading && uiState.seats.isEmpty() -> LoadingState(modifier = content, message = "Joining table")
            else ->
                RoomContent(
                    uiState = uiState,
                    onStart = onStart,
                    onRemove = onRemove,
                    onLeave = onLeave,
                    modifier = content,
                )
        }
    }
}

/** The terminal hand-off surface: the game has begun on the server; the game layer replaces this with the board. */
@Composable
private fun MatchStartingView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(Spacing.large),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = MATCH_STARTING_LABEL,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "The match has started on the server. The in-game view arrives in a later update.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RoomContent(
    uiState: TableRoomUiState,
    onStart: () -> Unit,
    onRemove: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        uiState.optionsSummary?.let { summary ->
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "Phase: ${uiState.table.phase.label()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Table: ${uiState.serverState.label()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MageSectionHeader(text = "Seats (${uiState.seatsFilled}/${uiState.seats.size})")
        if (uiState.seats.isEmpty()) {
            Text(
                text = "Reading the table…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            uiState.seats.forEach { seat -> SeatRow(seat) }
        }

        if (uiState.actionError != null) {
            Text(text = uiState.actionError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        // No deck surface here. The deck is bound when the seat is taken — upstream's `joinTable`
        // loads it, validates it against the table's format and seats the player with it — and
        // `submitDeck`/`updateDeck` return early unless the table is in SIDEBOARDING or CONSTRUCTING,
        // which this room never is. A picker here would be a control the server discards.

        // Role-appropriate actions.
        when (uiState.role) {
            TableRole.Host -> {
                MagePrimaryButton(
                    text = "Start match",
                    onClick = onStart,
                    enabled = uiState.canStart,
                    modifier = Modifier.fillMaxWidth(),
                )
                MageTextButton(text = "Remove table", onClick = onRemove, modifier = Modifier.fillMaxWidth())
            }
            TableRole.Player ->
                MageSecondaryButton(text = "Leave table", onClick = onLeave, modifier = Modifier.fillMaxWidth())
            TableRole.Spectator ->
                Text(
                    text = "You are spectating this table.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/**
 * One seat row: who sits in the slot (or that it is open) and what kind of player it holds. There is no
 * "ready" caption — readiness is a table-level property the server reports, not a per-seat
 * flag XMage exposes.
 */
@Composable
private fun SeatRow(seat: Seat) {
    val name = seat.name ?: seat.playerId ?: "Open seat"
    val type = if (seat.playerType == SeatPlayerType.Human) "Human" else seat.playerType.name
    val status =
        buildList {
            add("Seat ${seat.index + 1}")
            add(type)
            if (seat.isOwner) add("host")
            if (!seat.isOccupied) add("empty")
        }.joinToString(" · ")
    MageListRow(
        headline = name,
        supportingText = status,
        leadingContent = { Icon(imageVector = Icons.Filled.Person, contentDescription = null) },
    )
}

/** A short human label for a [TablePhase]. */
private fun TablePhase.label(): String =
    when (this) {
        TablePhase.Waiting -> "Waiting for players"
        TablePhase.Constructing -> "Constructing decks"
        TablePhase.Starting -> "Starting"
        TablePhase.Started -> "Started"
    }

/** A short human label for the server's own [TableServerState]. */
private fun TableServerState.label(): String =
    when (this) {
        TableServerState.Waiting -> "Waiting for players"
        TableServerState.ReadyToStart -> "Ready to start"
        TableServerState.Starting -> "Starting"
        TableServerState.Drafting -> "Drafting"
        TableServerState.Constructing -> "Constructing"
        TableServerState.Dueling -> "In game"
        TableServerState.Sideboarding -> "Sideboarding"
        TableServerState.Finished -> "Finished"
        TableServerState.Unknown -> "Reading…"
    }

// ---- Previews ---------------------------------------------------------------------------------

private fun previewSeats() =
    listOf(
        Seat(index = 0, name = "You", isOccupied = true, isOwner = true),
        Seat(index = 1, name = null, playerType = SeatPlayerType.ComputerMad, isOccupied = false),
    )

@Composable
private fun previewScreen(uiState: TableRoomUiState) {
    MageTheme {
        TableRoomScreen(
            uiState = uiState,
            onBack = {},
            onStart = {},
            onRemove = {},
            onLeave = {},
        )
    }
}

@Preview(name = "Room - host (light)", showBackground = true, heightDp = 720)
@Preview(name = "Room - host (dark)", showBackground = true, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoomHostPreview() =
    previewScreen(
        TableRoomUiState(
            table =
                TableState(
                    tableId = "t-1",
                    optionsSummary = "Two Player Duel",
                    seats = previewSeats(),
                    serverState = TableServerState.Waiting,
                    phase = TablePhase.Waiting,
                ),
            role = TableRole.Host,
            isLoading = false,
        ),
    )

@Preview(name = "Room - match starting", showBackground = true, heightDp = 720)
@Composable
private fun RoomStartingPreview() =
    previewScreen(
        TableRoomUiState(
            table = TableState(tableId = "t-1", phase = TablePhase.Starting),
            role = TableRole.Host,
            isLoading = false,
        ),
    )
