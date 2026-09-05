package magefree.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.card.CardDisplay
import magefree.designsystem.card.CardTile
import magefree.designsystem.card.FullCardView
import magefree.designsystem.component.EmptyState
import magefree.designsystem.component.ErrorState
import magefree.designsystem.component.LoadingState
import magefree.designsystem.component.MageListRow
import magefree.designsystem.component.MagePrimaryButton
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.component.MageTextButton
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.component.prompt.DecisionEmphasis
import magefree.designsystem.component.prompt.DecisionPrompt
import magefree.designsystem.component.prompt.DecisionPromptChoice
import magefree.designsystem.theme.FontScalePreviews
import magefree.designsystem.theme.MageShapes
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Sizing
import magefree.designsystem.theme.Spacing
import magefree.designsystem.theme.ThemePreviews

/*
 * The developer component catalog — a single scrolling gallery that renders every design-system
 * component in its representative states. It is the fast visual-QA surface: pair it with the
 * [ThemePreviews] / [FontScalePreviews] multipreviews (below) to eyeball the whole system across
 * light/dark and font scales at once, and host it behind a debug entry (see `:app`'s catalog screen)
 * to exercise it live across simulated window widths.
 *
 * It is stateless and composes only the shared components with placeholder inputs — it never fetches
 * data, and card art uses a solid stand-in slot (the design system carries no image dependency).
 */

/**
 * Renders every design-system component grouped by family, each in its main states. Stateless; wrap it
 * in a [MageTheme] (and, when hosting live, in a width-constraining container to simulate window size).
 *
 * @param modifier the [Modifier] for the scrolling gallery.
 * @param artFor resolves a card name to its art. The design system carries no image dependency, so a
 *   host that wants real art supplies this; without it every card falls back to the built-in
 *   placeholder and the catalog still renders offline. It is a resolver rather than one slot because
 *   the board sections show several cards at once — a creature with an Aura on it needs both faces.
 * @param hostSections extra sections the host appends after the design system's own. It exists because
 *   some things worth eyeballing are assembled *above* this module — a cast is a sequence of prompts
 *   driven by game types the design system deliberately cannot see — and the alternative is either a
 *   dependency pointing the wrong way or no eyes-on at all. The host supplies its own
 *   [CatalogSection]-shaped content; nothing here inspects it.
 */
@Composable
fun ComponentCatalog(
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
    hostSections: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        // **First, not last.** The host's sections are the ones that open a whole surface — the board,
        // the cast flow — and they are what somebody reviewing the game comes here for. Putting them
        // after every component meant scrolling past the entire design system to reach the two entries
        // anyone actually uses, several times an evening.
        hostSections?.invoke()

        CatalogSection(title = "Buttons") {
            MagePrimaryButton(text = "Primary", onClick = {})
            MagePrimaryButton(text = "Primary + icon", onClick = {}, icon = Icons.Filled.Star)
            MageSecondaryButton(text = "Secondary", onClick = {})
            MageSecondaryButton(text = "Secondary + icon", onClick = {}, icon = Icons.Filled.Add)
            MageTextButton(text = "Text", onClick = {})
            MagePrimaryButton(text = "Disabled", onClick = {}, enabled = false)
        }

        CatalogSection(title = "List rows") {
            MageListRow(headline = "Title only", onClick = {})
            MageListRow(
                headline = "Title with supporting text",
                supportingText = "Secondary line describing the item",
                onClick = {},
            )
            MageListRow(
                headline = "Leading and trailing",
                supportingText = "Both slots populated",
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.width(Sizing.iconLarge).height(Sizing.iconLarge),
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
        }

        CatalogSection(title = "Section chrome") {
            MageTopAppBar(title = "Decks")
            MageSectionHeader(text = "Recently played")
        }

        CatalogSection(title = "State views") {
            LoadingState(message = "Loading decks")
            EmptyState(
                title = "No decks yet",
                message = "Create your first deck to get started.",
                icon = Icons.Filled.Star,
                actionLabel = "New deck",
                onAction = {},
            )
            ErrorState(
                message = "Couldn't reach the server.",
                icon = Icons.Filled.Star,
                onRetry = {},
            )
        }

        CatalogSection(title = "Decision prompt") {
            DecisionPrompt(
                title = "You may cast Lightning Bolt",
                message = "Deal 3 damage to any target, or hold priority.",
                choices = CatalogPromptChoices,
                onChoice = {},
            )
        }

        CatalogSection(title = "Mana and tap symbols") {
            SymbolGallery()
        }

        CatalogSection(title = "Board tokens") {
            BoardTokenGallery()
        }

        CatalogSection(title = "Prompt") {
            PromptGallery()
        }

        CatalogSection(title = "Badges and counters") {
            BadgeAndCounterGallery()
        }

        CatalogSection(title = "Board card tier") {
            BoardCardGallery(artFor = artFor)
        }

        CatalogSection(title = "Animation host") {
            BoardHostGallery(artFor = artFor)
        }

        CatalogSection(title = "Board inspect view") {
            BoardInspectGallery(artFor = artFor)
        }

        CatalogSection(title = "Card tile") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                CardTile(
                    card = CatalogCreature,
                    onTap = {},
                    onLongPressPeek = {},
                    art = { artModifier -> Box(artModifier.background(Color(0xFF3B6E3B))) },
                    modifier = Modifier.width(140.dp),
                )
                CardTile(
                    card = CatalogLongName,
                    onTap = {},
                    modifier = Modifier.width(140.dp),
                )
            }
        }

        CatalogSection(title = "Full card view") {
            // Fixed height: the full view fills its space, so the gallery gives it a bounded window.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(FullCardViewShowcaseHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MageShapes.medium),
            ) {
                FullCardView(
                    card = CatalogSpell,
                    onClose = {},
                    art = { artModifier -> Box(artModifier.background(Color(0xFFB5462A))) },
                    modifications = listOf("+1/+1 counter"),
                    activatableAbilities = listOf("{T}: Deal 1 damage to any target"),
                )
            }
        }
    }
}

/** One titled group in the catalog: a [MageSectionHeader] over its stacked component samples. */
@Composable
private fun CatalogSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        MageSectionHeader(text = title)
        HorizontalDivider()
        content()
    }
}

private val FullCardViewShowcaseHeight = 520.dp

private val CatalogCreature =
    CardDisplay(
        name = "Grizzly Bears",
        manaCost = "{1}{G}",
        typeLine = "Creature — Bear",
    )

private val CatalogLongName =
    CardDisplay(
        name = "Rin and Seri, Inseparable",
        manaCost = "{1}{R}{G}{W}",
        typeLine = "Legendary Creature — Dog Cat",
    )

private val CatalogSpell =
    CardDisplay(
        name = "Lightning Bolt",
        manaCost = "{R}",
        typeLine = "Instant",
        oracleText = "Lightning Bolt deals 3 damage to any target.",
    )

private val CatalogPromptChoices =
    listOf(
        DecisionPromptChoice(label = "Cast it", emphasis = DecisionEmphasis.Primary),
        DecisionPromptChoice(label = "Save for later", emphasis = DecisionEmphasis.Secondary),
        DecisionPromptChoice(
            label = "Pass",
            contentDescription = "Pass priority",
            emphasis = DecisionEmphasis.Tertiary,
        ),
    )

@ThemePreviews
@Composable
private fun ComponentCatalogPreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ComponentCatalog()
        }
    }
}

// Dynamic-type check: the whole system at 1.0x / 1.3x / 2.0x — text and containers must grow without
// clipping meaningful content.
@FontScalePreviews
@Composable
private fun ComponentCatalogFontScalePreview() {
    MageTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            ComponentCatalog()
        }
    }
}
