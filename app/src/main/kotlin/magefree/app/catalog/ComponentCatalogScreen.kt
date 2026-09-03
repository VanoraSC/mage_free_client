package magefree.app.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.catalog.ComponentCatalog
import magefree.designsystem.component.MageTopAppBar
import magefree.designsystem.layout.contentInsets
import magefree.designsystem.theme.MageTheme
import magefree.designsystem.theme.Spacing

/** Title of the component-catalog debug screen; shared with tests so the two agree. */
const val CATALOG_TITLE: String = "Component catalog"

/** Accessibility label on the catalog's close / back control. */
const val CATALOG_CLOSE_CONTENT_DESCRIPTION: String = "Close catalog"

/** Accessibility label on the light/dark theme toggle. */
const val CATALOG_THEME_TOGGLE_CONTENT_DESCRIPTION: String = "Toggle light or dark theme"

/** Accessibility label on the simulated-width toggle. */
const val CATALOG_WIDTH_TOGGLE_CONTENT_DESCRIPTION: String = "Toggle simulated window width"

/**
 * The simulated content width of a phone held in landscape — the app's only orientation (§7.19).
 *
 * Portrait widths are not simulated here because no new surface renders in portrait: a board
 * component judged in a 360 dp column is being judged against a shape it will never be given, and the
 * first thing that goes wrong is a row of permanents piling up at the edge.
 */
private val PhoneSimWidth = 891.dp

/** The simulated content width of a tablet in landscape, which renders the same layout scaled. */
private val TabletSimWidth = 1280.dp

/**
 * The developer component-catalog screen: hosts the design system's [ComponentCatalog] behind a
 * debug-only entry (reached from the Settings dev affordance), adding live controls the static
 * previews can't offer — a **light/dark toggle** and a **simulated-width** toggle — for fast on-device
 * visual QA of every component across themes and window widths.
 *
 * The whole screen is wrapped in its own [MageTheme] so the toggle re-themes the chrome too. It owns
 * its chrome insets via the [Scaffold] (consuming `innerPadding`) and the catalog applies only its
 * content insets, dogfooding the inset-ownership convention.
 *
 * @param onExit invoked by the back control to leave the catalog (pops back to the shell).
 * @param modifier the [Modifier] for the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentCatalogScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    artFor: ((String) -> CardArtSlot?)? = null,
    onOpenBattlefield: () -> Unit = {},
) {
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    var wide by rememberSaveable { mutableStateOf(false) }

    MageTheme(darkTheme = darkTheme) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                MageTopAppBar(
                    title = CATALOG_TITLE,
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = CATALOG_CLOSE_CONTENT_DESCRIPTION,
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding)
                        .fillMaxSize(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .contentInsets()
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    OutlinedButton(
                        onClick = { darkTheme = !darkTheme },
                        modifier =
                            Modifier.semantics {
                                contentDescription = CATALOG_THEME_TOGGLE_CONTENT_DESCRIPTION
                            },
                    ) {
                        Text(text = if (darkTheme) "Theme: Dark" else "Theme: Light")
                    }
                    OutlinedButton(
                        onClick = { wide = !wide },
                        modifier =
                            Modifier.semantics {
                                contentDescription = CATALOG_WIDTH_TOGGLE_CONTENT_DESCRIPTION
                            },
                    ) {
                        Text(text = if (wide) "Width: Tablet" else "Width: Phone")
                    }
                }

                // Simulated window width: constrain the catalog's max width, centered, so the wide
                // setting visibly narrows content on large screens (a no-op when the device is already
                // narrower). The catalog owns its content insets.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ComponentCatalog(
                        artFor = artFor,
                        // Assembled here rather than in the design system, which deliberately cannot
                        // see game types: a cast is a sequence of `GamePrompt`s, and the only thing
                        // worth looking at is the sequence.
                        hostSections = {
                            CastFlowSection()
                            BattlefieldSection(onOpenPreview = onOpenBattlefield)
                        },
                        modifier =
                            Modifier
                                .widthIn(max = if (wide) TabletSimWidth else PhoneSimWidth)
                                .contentInsets(),
                    )
                }
            }
        }
    }
}

@Preview(name = "Catalog screen — light", showBackground = true, heightDp = 900)
@Composable
private fun ComponentCatalogScreenPreview() {
    ComponentCatalogScreen(onExit = {})
}
