package magefree.feature.decks.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import magefree.decks.model.DeckFormat
import magefree.designsystem.theme.Spacing

/**
 * Create/rename dialog: a single name field plus an optional constructed-format picker (shown on
 * create). Both actions are hoisted; the dialog owns only the transient field state.
 */
@Composable
fun DeckNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    showFormat: Boolean,
    onConfirm: (String, DeckFormat?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf<DeckFormat?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Deck name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showFormat) {
                    Text("Format (optional)")
                    FormatPicker(selected = format, onSelected = { format = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, format) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A horizontally-scrolling row of single-choice format chips (tap again to clear). */
@Composable
fun FormatPicker(
    selected: DeckFormat?,
    onSelected: (DeckFormat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        DeckFormat.entries.forEach { fmt ->
            FilterChip(
                selected = selected == fmt,
                onClick = { onSelected(if (selected == fmt) null else fmt) },
                label = { Text(fmt.displayName) },
            )
        }
    }
}

/** Import dialog: paste XMage-compatible deck text; the format is auto-detected on import (0034). */
@Composable
fun ImportDeckDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import deck") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text("Paste a deck list (.dck, .txt, .dec, or MTG Arena). The format is detected automatically.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Deck text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
