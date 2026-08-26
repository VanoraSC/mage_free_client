package magefree.feature.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/** Accessibility label on the control that empties the search field. */
const val CARDS_CLEAR_SEARCH_CONTENT_DESCRIPTION: String = "Clear search"

/**
 * The one card-search text field, shared by `:feature:cards`' search screen and the deck builder's
 * add-cards surface. Both surfaces search the same catalog through the same
 * [CardSearchViewModel], so they get the same field rather than two hand-rolled copies that can drift
 * apart — which is how one of them could have been fixed and the other left dead.
 *
 * **Reachability (verification standard 2): what produces the displayed value on each keystroke?**
 * [displayed] — a Compose snapshot state written **synchronously inside `onValueChange`**, on the same
 * dispatch as the keystroke, before the composition that draws the character. Nothing asynchronous sits
 * between a key press and the glyph: no flow, no debounce, no ViewModel round trip.
 *
 * That is the whole defect this replaced. The field used to be fully controlled on `uiState.query`,
 * which the ViewModel produced from its **debounced** stream (`query.debounce { … }` → `combine` →
 * `_uiState`). A keystroke set the raw query flow while Compose recomposed the field with the old,
 * unchanged state — reverting the character before the debounce could elapse. Text could never
 * accumulate; on-device the placeholder never even cleared.
 *
 * **Nothing writes [displayed] back from the hoisted [query], and that is deliberate.** An earlier cut
 * of this field re-adopted [query] whenever it differed ("an external change"). Fast typing on-device
 * showed why that is wrong: the hoisted value only ever arrives *after* the keystroke that produced it,
 * so mid-word it is legitimately behind what the field shows, and adopting it rewrites the editing
 * buffer under the IME — `input text "lightning"` landed `lghtnin`, losing characters for good. The
 * field's text is therefore owned by the field for as long as it is on screen, and the only two things
 * that can change it from outside are handled explicitly:
 * - **clear** — the affordance lives here, so it empties [displayed] directly and reports it;
 * - **restoration after process death** — [displayed] comes back from `rememberSaveable` while a fresh
 *   ViewModel starts empty, so a one-shot effect re-issues it once, upward, at first composition.
 *
 * @param query the search text the ViewModel holds; read only to re-issue restored text (see above).
 * @param onQueryChange invoked with every edit — this is what feeds the (debounced) catalog pipeline.
 * @param onClearQuery invoked by the trailing clear affordance.
 * @param placeholder the hint shown while the field is empty (it differs per surface).
 */
@Composable
fun CardSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var displayed by rememberSaveable { mutableStateOf(query) }

    // Restored text with a fresh ViewModel behind it: re-run the search once so the results match what
    // is on screen. Keyed on Unit, so it can never fire while the player is typing.
    LaunchedEffect(Unit) {
        if (displayed.isNotEmpty() && displayed != query) onQueryChange(displayed)
    }

    OutlinedTextField(
        value = displayed,
        onValueChange = { edited ->
            // Synchronous, in this order on purpose: show the character, then tell the pipeline.
            displayed = edited
            onQueryChange(edited)
        },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (displayed.isNotEmpty()) {
                IconButton(
                    onClick = {
                        displayed = ""
                        onClearQuery()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = CARDS_CLEAR_SEARCH_CONTENT_DESCRIPTION,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}
