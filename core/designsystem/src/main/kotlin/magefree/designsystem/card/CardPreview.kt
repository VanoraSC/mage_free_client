package magefree.designsystem.card

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.text.SymbolText
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.Spacing

/*
 * A card, read properly.
 *
 * §7.5 reserves the **Full** tier for inspection, and this is what that looks like on a landscape
 * board: the card face big enough to read, and a column beside it for everything the face cannot say.
 *
 * **It is not [BoardInspectView], and that is deliberate.** That view inspects a *permanent* — it draws
 * the Board tier, sizes itself against the whole attachment assembly, and lists counters and badges.
 * This inspects a *card*: what matters is the face itself and the text on it. The two share a
 * silhouette and almost nothing else, and folding them together would make one component with two
 * disjoint halves.
 *
 * **The card is sized by its height, and the width follows.** A card has one shape. Give it a width and
 * let the height fall out and you get a preview that is either cropped or stretched depending on the
 * device; give it the height it is allowed and multiply by the card's own ratio and it is right
 * everywhere.
 */

/**
 * What the panel beside the card says.
 *
 * @property card the name, cost and type line — the fields the face prints, repeated because the panel
 *   is what a screen reader and a small screen fall back to.
 * @property power current power, after effects rather than as printed. Null for a non-creature.
 * @property toughness current toughness, on the same terms.
 * @property abilities what this card can do **right now**, which for a permanent in play includes
 *   anything granted to it. Upstream's `CardView.rules`.
 * @property oracleText what the card says as **printed**. A different thing from [abilities], and shown
 *   beside it precisely so the difference is visible: a creature granted flying until end of turn has
 *   it in one and not the other.
 * @property action the one thing that can be done with this card right now, or null when there is
 *   nothing. Naming it is the caller's job — see [CardPreviewAction].
 */
data class CardPreviewState(
    val card: CardDisplay,
    val power: String? = null,
    val toughness: String? = null,
    val abilities: List<String> = emptyList(),
    val oracleText: String? = null,
    val action: CardPreviewAction? = null,
    val attachments: List<CardPreviewAttachment> = emptyList(),
)

/**
 * A permanent attached to the one being read.
 *
 * **An enchanted creature cannot be read without them.** Pacifism is the reason the Craw Wurm is not
 * attacking, and a panel that listed the Wurm's own abilities and stopped would be describing a card
 * rather than the permanent on the board. At board size the Aura is a name band behind its host, so
 * this is the only place its text can actually be read.
 *
 * @property rules the server's game-aware text for the attachment itself.
 */
data class CardPreviewAttachment(
    val name: String,
    val manaCost: String? = null,
    val rules: List<String> = emptyList(),
)

/**
 * The action offered on an inspected card.
 *
 * @property label what the button says — *Play* for a land, *Cast* for a spell. Which word to use is a
 *   question about the card, so the caller answers it; this only draws what it is given.
 */
data class CardPreviewAction(
    val label: String,
    val onAct: () -> Unit,
)

/**
 * A card at [heightShare] of the available height, with its details beside it.
 *
 * @param state the card and everything said about it.
 * @param onDismiss called when the press lands anywhere but the card and its panel. Dismissal is a
 *   press outside rather than a close button: the overlay already covers the board, so the press has
 *   to be caught anyway, and catching it is the same gesture a player would reach for.
 * @param modifier the [Modifier] for the overlay.
 * @param art the card's own art, at the Full tier.
 * @param heightShare how much of the available height the card takes.
 */
@Composable
fun CardPreview(
    state: CardPreviewState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    art: CardArtSlot? = null,
    heightShare: Float = DEFAULT_HEIGHT_SHARE,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // **The scrim is a sibling behind the content, not a wrapper around it.** Wrapped, its
        // `clickable` merges the card and the panel into one accessibility node — so a screen reader
        // announces the whole overlay as a single button, and a press anywhere inside it dismisses,
        // including on the Cast button. Behind it, the card and the panel keep their own nodes and
        // their own presses, and the scrim catches only what misses them.
        //
        // No ripple and no role: it is a background, and a background that highlighted itself under a
        // finger would read as a button.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ).testTag(CardPreviewTestTags.SCRIM),
        )

        val cardHeight = maxHeight * heightShare
        val cardWidth = cardHeight * CARD_ASPECT_RATIO

        Row(
            modifier =
                Modifier
                    .height(cardHeight)
                    // Swallows presses that land on the content but on nothing in it. Without this
                    // they fall through to the scrim, so a press on the panel's own background — a
                    // finger reaching for Cast and missing by a few dp — closes the card the player
                    // was reading. `detectTapGestures` rather than `clickable` because this is not a
                    // button and should not be announced as one.
                    .pointerInput(Unit) { detectTapGestures { } },
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Box(
                modifier =
                    Modifier
                        .width(cardWidth)
                        .fillMaxHeight()
                        .clip(MageShapes.medium)
                        // The card *does* dismiss: it is the biggest target on screen and the thing a
                        // player is looking at, so pressing it to put it down is the obvious gesture.
                        // The panel deliberately does not, because that is where the buttons are.
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                        .testTag(CardPreviewTestTags.CARD),
            ) {
                CardArtRegion(card = state.card, art = art, modifier = Modifier.fillMaxSize())
            }

            DetailPanel(
                state = state,
                modifier = Modifier.width(PanelWidth).fillMaxHeight(),
            )
        }
    }
}

/**
 * Everything about the card that its face cannot say at this size, top down.
 *
 * Scrolls, because a card with six abilities and a paragraph of oracle text exists and the panel is
 * the height of the card rather than the height of its contents. The action sits *outside* the scroll:
 * a button that scrolled away would be a button the player could not find.
 */
@Composable
private fun DetailPanel(
    state: CardPreviewState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surface, MageShapes.medium)
                .padding(Spacing.medium)
                .testTag(CardPreviewTestTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = state.card.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            state.card.manaCost?.takeIf { it.isNotBlank() }?.let { cost ->
                SymbolText(text = cost, style = MaterialTheme.typography.titleSmall)
            }

            state.card.typeLine?.takeIf { it.isNotBlank() }?.let { typeLine ->
                Text(
                    text = typeLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.abilities.isNotEmpty()) {
                HorizontalDivider()
                state.abilities.forEach { ability ->
                    // Through the symbol renderer: an ability is the server's own text and is mostly
                    // symbols — `{T}: Add {G}.` reads as punctuation without it.
                    SymbolText(
                        text = ability,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(CardPreviewTestTags.ABILITIES),
                    )
                }
            }

            // After the abilities, because a creature's size is the thing a player checks last before
            // deciding — and it is the one line that changes without the text changing.
            if (state.power != null && state.toughness != null) {
                HorizontalDivider()
                Text(
                    text = "${state.power}/${state.toughness}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(CardPreviewTestTags.POWER_TOUGHNESS),
                )
            }

            // Before the oracle text, because what is attached to a permanent is current board state
            // and the printing is not — and because an Aura is very often the answer to the question
            // the player opened the card to ask.
            if (state.attachments.isNotEmpty()) {
                HorizontalDivider()
                state.attachments.forEach { attachment ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                        modifier = Modifier.testTag(CardPreviewTestTags.attachment(attachment.name)),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            attachment.manaCost?.takeIf { it.isNotBlank() }?.let { cost ->
                                SymbolText(text = cost, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        attachment.rules.forEach { rule ->
                            SymbolText(
                                text = rule,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.oracleText?.takeIf { it.isNotBlank() }?.let { oracle ->
                HorizontalDivider()
                SymbolText(
                    text = oracle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CardPreviewTestTags.ORACLE),
                )
            }
        }

        state.action?.let { action ->
            MagePrimaryButton(
                text = action.label,
                onClick = action.onAct,
                modifier = Modifier.fillMaxWidth().testTag(CardPreviewTestTags.ACTION),
            )
        }
    }
}

/** Test tags for the preview's parts, which are told apart by position rather than by text. */
object CardPreviewTestTags {
    /** The whole overlay, and the thing a dismissing press lands on. */
    const val SCRIM: String = "card-preview-scrim"
    const val CARD: String = "card-preview-card"
    const val PANEL: String = "card-preview-panel"
    const val ABILITIES: String = "card-preview-abilities"
    const val POWER_TOUGHNESS: String = "card-preview-pt"
    const val ORACLE: String = "card-preview-oracle"
    const val ACTION: String = "card-preview-action"

    /** One attached permanent's block, by its name. */
    fun attachment(name: String): String = "card-preview-attachment-$name"
}

/**
 * How much of the height the card takes.
 *
 * Large enough to read the printed text on the face, and short enough that the board is still visible
 * around it — an inspection that filled the screen would be a screen change rather than a look.
 */
private const val DEFAULT_HEIGHT_SHARE = 0.75f

/** Wide enough for a line of rules text without wrapping every few words. */
private val PanelWidth = 260.dp

/** Dark enough that the board reads as behind, light enough that it still reads. */
private val ScrimColor = Color.Black.copy(alpha = 0.72f)
