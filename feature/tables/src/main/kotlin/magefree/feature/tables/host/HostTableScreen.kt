package magefree.feature.tables.host

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import magefree.decks.legality.DeckLegalityResult
import magefree.decks.model.DeckFormat
import magefree.decks.model.DeckId
import magefree.decks.model.DeckSummary
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing
import magefree.feature.tables.DeckPicker
import magefree.feature.tables.LegalitySummary
import magefree.model.SkillLevel
import magefree.network.table.RangeOfInfluence
import magefree.network.table.SeatPlayerType

/** Title of the host-a-table screen; shared with tests so the two agree. */
const val HOST_TITLE: String = "Host a table"

/** Label on the create action. */
const val HOST_CREATE_LABEL: String = "Create table"

/**
 * Stateless host-a-table form. Every table setting maps to a subset of 0037's `CreateTableOptions`; the
 * seat rows say who fills each non-host seat (another human, or an AI to seat at create-time), and — since
 * story 0041 — the host picks and validates its **own deck** here, because hosting takes a seat. The
 * single dominant [HOST_CREATE_LABEL] action is enabled only for a valid form with a picked, legal deck
 * ([HostTableUiState.canCreate]). Every event is hoisted; the composable performs no I/O.
 */
@Composable
fun HostTableScreen(
    uiState: HostTableUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onGameTypeChange: (String) -> Unit,
    onDeckTypeChange: (String) -> Unit,
    onSeatsChange: (Int) -> Unit,
    onOpponentTypeChange: (Int, SeatPlayerType) -> Unit,
    onRatedChange: (Boolean) -> Unit,
    onFreeMulligansChange: (Int) -> Unit,
    onWinsNeededChange: (Int) -> Unit,
    onSkillLevelChange: (SkillLevel) -> Unit,
    onRangeChange: (RangeOfInfluence) -> Unit,
    onSpectatorsChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSeatNameChange: (String) -> Unit,
    onSelectDeck: (DeckId) -> Unit,
    onBuildDeck: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = uiState.form
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = HOST_TITLE,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = onNameChange,
                label = { Text("Table name") },
                singleLine = true,
                isError = form.name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            MageSectionHeader(text = "Game type")
            ChipSelect(options = HOST_GAME_TYPES, selected = form.gameType, label = { it }, onSelect = onGameTypeChange)

            MageSectionHeader(text = "Deck type")
            ChipSelect(options = HOST_DECK_TYPES, selected = form.deckType, label = { it }, onSelect = onDeckTypeChange)

            MageSectionHeader(text = "Seats")
            Stepper(value = form.seats, onChange = onSeatsChange, valueLabel = "${form.seats} players")
            Text(
                text = "Seat 1 · you",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            form.opponents.forEachIndexed { index, type ->
                Text(text = "Seat ${index + 2}", style = MaterialTheme.typography.bodySmall)
                ChipSelect(
                    options = HOST_SEAT_TYPES,
                    selected = type,
                    label = { it.seatTypeLabel() },
                    onSelect = { picked -> onOpponentTypeChange(index, picked) },
                )
            }

            MageSectionHeader(text = "Your seat")
            OutlinedTextField(
                value = uiState.seatName,
                onValueChange = onSeatNameChange,
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MageSectionHeader(text = "Choose your deck")
            DeckPicker(
                decks = uiState.library,
                selectedId = uiState.selectedDeckId,
                onSelect = onSelectDeck,
                onBuildDeck = onBuildDeck,
            )
            if (uiState.selectedDeckId != null || uiState.format == null) {
                LegalitySummary(format = uiState.format, result = uiState.legality)
            }

            MageSectionHeader(text = "Skill level")
            ChipSelect(
                options = listOf(SkillLevel.Beginner, SkillLevel.Casual, SkillLevel.Serious),
                selected = form.skillLevel,
                label = { it.name },
                onSelect = onSkillLevelChange,
            )

            MageSectionHeader(text = "Range of influence")
            ChipSelect(
                options = listOf(RangeOfInfluence.One, RangeOfInfluence.Two, RangeOfInfluence.All),
                selected = form.range,
                label = { it.name },
                onSelect = onRangeChange,
            )

            MageSectionHeader(text = "Match")
            Stepper(value = form.winsNeeded, onChange = onWinsNeededChange, valueLabel = "${form.winsNeeded} win(s) to take the match")
            Stepper(value = form.freeMulligans, onChange = onFreeMulligansChange, valueLabel = "${form.freeMulligans} free mulligan(s)")
            SwitchRow(text = "Rated", checked = form.rated, onCheckedChange = onRatedChange)
            SwitchRow(text = "Allow spectators", checked = form.spectatorsAllowed, onCheckedChange = onSpectatorsChange)

            OutlinedTextField(
                value = form.password,
                onValueChange = onPasswordChange,
                label = { Text("Password (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // With no saved deck at all there is nothing to gate on: the picker above renders the
            // "build a legal deck" call to action instead, exactly as the join flow does.
            if (!uiState.hasNoDecks) {
                MagePrimaryButton(
                    text = if (uiState.isSubmitting) "Creating…" else HOST_CREATE_LABEL,
                    onClick = onCreate,
                    enabled = uiState.canCreate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A short human label for a seat's occupant kind, as offered on the host form. */
private fun SeatPlayerType.seatTypeLabel(): String =
    when (this) {
        SeatPlayerType.Human -> "Human"
        SeatPlayerType.ComputerMonteCarlo -> "AI (Monte Carlo)"
        SeatPlayerType.ComputerMad -> "AI (Mad)"
        SeatPlayerType.ComputerDraftBot -> "AI (draft bot)"
        SeatPlayerType.Unknown -> "Unknown"
    }

/** A single-select horizontal chip group over [options]; [label] renders each option's text. */
@Composable
private fun <T> ChipSelect(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

/** A minus/plus stepper with a value caption; clamping is the caller's (the ViewModel's) job. */
@Composable
private fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        IconButton(onClick = { onChange(value - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(text = valueLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(value + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/** A labelled switch row. */
@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---- Previews ---------------------------------------------------------------------------------

private val previewDecks =
    listOf(
        DeckSummary(DeckId("deck-1"), "Mono-Red Aggro", null, DeckFormat.STANDARD, true, 60, 15, 0, 0),
        DeckSummary(DeckId("deck-2"), "Azorius Control", null, DeckFormat.STANDARD, false, 60, 15, 0, 0),
    )

@Composable
private fun previewScreen(uiState: HostTableUiState) {
    MageTheme {
        HostTableScreen(
            uiState = uiState,
            onBack = {},
            onNameChange = {},
            onGameTypeChange = {},
            onDeckTypeChange = {},
            onSeatsChange = {},
            onOpponentTypeChange = { _, _ -> },
            onRatedChange = {},
            onFreeMulligansChange = {},
            onWinsNeededChange = {},
            onSkillLevelChange = {},
            onRangeChange = {},
            onSpectatorsChange = {},
            onPasswordChange = {},
            onSeatNameChange = {},
            onSelectDeck = {},
            onBuildDeck = {},
            onCreate = {},
        )
    }
}

@Preview(name = "Host - light", showBackground = true, heightDp = 1400)
@Preview(name = "Host - dark", showBackground = true, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HostTableScreenPreview() =
    previewScreen(
        HostTableUiState(
            form = HostTableForm(name = "Friday duel", opponents = listOf(SeatPlayerType.ComputerMad)),
            library = previewDecks,
            format = DeckFormat.STANDARD,
            selectedDeckId = DeckId("deck-1"),
            legality = DeckLegalityResult(DeckFormat.STANDARD, isLegal = true, violations = emptyList()),
        ),
    )

@Preview(name = "Host - no decks", showBackground = true, heightDp = 1400)
@Composable
private fun HostTableNoDecksPreview() =
    previewScreen(HostTableUiState(form = HostTableForm(name = "Friday duel"), format = DeckFormat.STANDARD))
