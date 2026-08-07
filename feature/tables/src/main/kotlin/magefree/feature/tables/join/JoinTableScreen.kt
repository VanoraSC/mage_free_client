package magefree.feature.tables.join

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Label on the join action; shared with tests so the two agree. */
const val JOIN_LABEL: String = "Join table"

/**
 * Stateless join-a-table screen. Renders the table's identity, a saved-deck picker (offline), the picked
 * deck's legality for the table's format, and a password field when the table is protected. The single
 * dominant [JOIN_LABEL] action is enabled only for a legal, password-satisfied pick
 * ([JoinTableUiState.canJoin]). Every event is hoisted; the composable performs no I/O.
 */
@Composable
fun JoinTableScreen(
    uiState: JoinTableUiState,
    onBack: () -> Unit,
    onSeatNameChange: (String) -> Unit,
    onSelectDeck: (DeckId) -> Unit,
    onPasswordChange: (String) -> Unit,
    onBuildDeck: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MageTopAppBar(
                title = "Join ${uiState.target.tableName}",
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
            Text(
                text = "${uiState.target.gameType} · ${uiState.target.deckType}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = uiState.seatName,
                onValueChange = onSeatNameChange,
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            MageSectionHeader(text = "Choose a deck")
            DeckPicker(
                decks = uiState.library,
                selectedId = uiState.selectedDeckId,
                onSelect = onSelectDeck,
                onBuildDeck = onBuildDeck,
            )

            if (uiState.selectedDeckId != null || uiState.format == null) {
                LegalitySummary(format = uiState.format, result = uiState.legality)
            }

            if (uiState.target.passworded) {
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Table password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = uiState.password.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!uiState.hasNoDecks) {
                MagePrimaryButton(
                    text = if (uiState.isSubmitting) "Joining…" else JOIN_LABEL,
                    onClick = onJoin,
                    enabled = uiState.canJoin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---- Previews ---------------------------------------------------------------------------------

private val previewTarget =
    JoinTarget(
        tableId = "t-1",
        tableName = "Friday duel",
        gameType = "Two Player Duel",
        deckType = "Constructed - Modern",
        passworded = true,
    )

private val previewDecks =
    listOf(
        DeckSummary(DeckId("deck-1"), "Mono-Red Aggro", null, DeckFormat.MODERN, true, 60, 15, 0, 0),
        DeckSummary(DeckId("deck-2"), "Azorius Control", null, DeckFormat.MODERN, false, 60, 15, 0, 0),
    )

@Composable
private fun previewScreen(uiState: JoinTableUiState) {
    MageTheme {
        JoinTableScreen(
            uiState = uiState,
            onBack = {},
            onSeatNameChange = {},
            onSelectDeck = {},
            onPasswordChange = {},
            onBuildDeck = {},
            onJoin = {},
        )
    }
}

@Preview(name = "Join - pick (light)", showBackground = true, heightDp = 780)
@Preview(name = "Join - pick (dark)", showBackground = true, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun JoinPickPreview() =
    previewScreen(
        JoinTableUiState(
            target = previewTarget,
            library = previewDecks,
            format = DeckFormat.MODERN,
            selectedDeckId = DeckId("deck-1"),
            legality = DeckLegalityResult(DeckFormat.MODERN, isLegal = true, violations = emptyList()),
        ),
    )

@Preview(name = "Join - no decks", showBackground = true, heightDp = 780)
@Composable
private fun JoinNoDecksPreview() =
    previewScreen(JoinTableUiState(target = previewTarget.copy(passworded = false), format = DeckFormat.MODERN))
