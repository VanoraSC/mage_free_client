package magefree.feature.tables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magefree.feature.tables.host.HostTableScreen
import magefree.feature.tables.host.HostTableViewModel
import magefree.feature.tables.join.JoinTableScreen
import magefree.feature.tables.join.JoinTableViewModel
import magefree.feature.tables.join.JoinTarget
import magefree.feature.tables.room.TableRoomScreen
import magefree.feature.tables.room.TableRoomViewModel
import magefree.network.table.TablePhase
import magefree.network.table.TableRef
import magefree.network.table.TableState

/*
 * The `:feature:tables` entry points, each binding a Hilt ViewModel to its stateless screen and turning
 * a ViewModel one-shot (created / joined / closed) into a hoisted navigation callback. The `:app`
 * navigation graph owns the type-safe routes and supplies the callbacks (mirroring how `:feature:decks`
 * exposes `DecksRoute`); this keeps table navigation declarative and the screens fully previewable.
 */

/** A room the create/join flow lands on: the ids needed to seed [TableRoomViewModel.observe]. */
data class RoomArgs(
    val tableId: String,
    val tableName: String,
    val gameType: String,
    val role: TableRole,
)

/** Host-a-table entry: on a successful create, hands the new room's [RoomArgs] to [onOpenRoom]. */
@Composable
fun HostTableRoute(
    onBack: () -> Unit,
    onOpenRoom: (RoomArgs) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostTableViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.created.collect { ref: TableRef ->
            onOpenRoom(RoomArgs(tableId = ref.tableId, tableName = ref.name, gameType = ref.gameType, role = TableRole.Host))
        }
    }

    HostTableScreen(
        uiState = uiState,
        onBack = onBack,
        onNameChange = viewModel::setName,
        onGameTypeChange = viewModel::setGameType,
        onDeckTypeChange = viewModel::setDeckType,
        onSeatsChange = viewModel::setSeats,
        onRatedChange = viewModel::setRated,
        onFreeMulligansChange = viewModel::setFreeMulligans,
        onWinsNeededChange = viewModel::setWinsNeeded,
        onSkillLevelChange = viewModel::setSkillLevel,
        onRangeChange = viewModel::setRange,
        onSpectatorsChange = viewModel::setSpectatorsAllowed,
        onPasswordChange = viewModel::setPassword,
        onCreate = viewModel::create,
        modifier = modifier,
    )
}

/** Join-a-table entry: binds the [target] and, on a successful join, opens the room via [onOpenRoom]. */
@Composable
fun JoinTableRoute(
    target: JoinTarget,
    onBack: () -> Unit,
    onOpenRoom: (RoomArgs) -> Unit,
    onBuildDeck: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JoinTableViewModel = hiltViewModel(),
) {
    LaunchedEffect(target.tableId) { viewModel.start(target) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.joined.collect { tableId ->
            onOpenRoom(RoomArgs(tableId = tableId, tableName = target.tableName, gameType = target.gameType, role = TableRole.Player))
        }
    }

    JoinTableScreen(
        uiState = uiState,
        onBack = onBack,
        onSeatNameChange = viewModel::setSeatName,
        onSelectDeck = viewModel::selectDeck,
        onPasswordChange = viewModel::setPassword,
        onBuildDeck = onBuildDeck,
        onJoin = viewModel::join,
        modifier = modifier,
    )
}

/**
 * Table-room entry: seeds [TableRoomViewModel.observe] from [args] and pops via [onExit] on a
 * leave/remove. A [TableRole.Spectator] room is entered directly from the lobby's watch action.
 */
@Composable
fun TableRoomRoute(
    args: RoomArgs,
    onExit: () -> Unit,
    onBuildDeck: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableRoomViewModel = hiltViewModel(),
) {
    LaunchedEffect(args.tableId) {
        viewModel.observe(
            tableId = args.tableId,
            seed = TableState(tableId = args.tableId, optionsSummary = args.gameType, phase = TablePhase.Waiting),
            role = args.role,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.close.collect { onExit() } }

    TableRoomScreen(
        uiState = uiState,
        onBack = onExit,
        onStart = viewModel::startMatch,
        onRemove = viewModel::removeTable,
        onLeave = viewModel::leaveTable,
        onSubmitDeck = viewModel::submitDeck,
        onBuildDeck = onBuildDeck,
        modifier = modifier,
    )
}
