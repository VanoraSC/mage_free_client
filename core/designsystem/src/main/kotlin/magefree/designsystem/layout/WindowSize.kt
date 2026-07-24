package magefree.designsystem.layout

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/*
 * The single access point for the window **width** size class.
 *
 * Adaptive layout decisions (bottom bar vs. navigation rail, one- vs. multi-pane) key off how wide the
 * available window is. Rather than have every caller reach for the experimental Material window
 * size-class API, this file is the ONE place that touches it: callers obtain a [WindowWidthClass] from
 * here via [windowWidthClass]. Because the experimental call is centralized, a future migration to the
 * stable `currentWindowAdaptiveInfo()` is a single-file edit here — no caller changes.
 *
 * [WindowWidthClass] is our own small enum (not Material's `WindowWidthSizeClass`) so callers are fully
 * decoupled from the underlying API and get intent-named helpers ([WindowWidthClass.usesNavigationRail],
 * [WindowWidthClass.usesBottomBar]).
 */

/**
 * The window width bucket that drives adaptive chrome. Mirrors Material's width size classes but is
 * owned by the design system so callers never import the experimental API.
 */
enum class WindowWidthClass {
    /** Phones in portrait (and small windows): navigation lives in a thumb-reachable bottom bar. */
    Compact,

    /** Larger phones landscape / small tablets / foldables: navigation moves to a side rail. */
    Medium,

    /** Tablets and large windows: navigation rail, with room for wider / multi-pane content. */
    Expanded,
    ;

    /** True when the primary navigation should be a side [NavigationRail] (medium/expanded widths). */
    val usesNavigationRail: Boolean get() = this != Compact

    /** True when the primary navigation should be a bottom [NavigationBar] (compact width). */
    val usesBottomBar: Boolean get() = this == Compact
}

/**
 * The width breakpoints (in dp) separating the [WindowWidthClass] buckets. These mirror the Material
 * window size-class thresholds so [windowWidthClassFor] and [windowWidthClass] agree by construction.
 */
object WindowWidthBreakpoints {
    /** Below this width the window is [WindowWidthClass.Compact]. */
    val Medium: Dp = 600.dp

    /** At or above this width the window is [WindowWidthClass.Expanded]. */
    val Expanded: Dp = 840.dp
}

/**
 * Pure mapping from an available [width] to a [WindowWidthClass], using [WindowWidthBreakpoints].
 *
 * Provided for callers that already have a width (e.g. a `BoxWithConstraints` `maxWidth`) and for
 * unit tests documenting the thresholds; it needs no Compose runtime. [windowWidthClass] routes the
 * runtime path through the Material API and produces the same result.
 */
fun windowWidthClassFor(width: Dp): WindowWidthClass =
    when {
        width < WindowWidthBreakpoints.Medium -> WindowWidthClass.Compact
        width < WindowWidthBreakpoints.Expanded -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }

/**
 * The single runtime access point: derive the [WindowWidthClass] from the available window [size].
 *
 * This is the ONE call site of the experimental Material window size-class API in the codebase; the
 * `@OptIn` is contained here. Callers pass the space they have (typically a `BoxWithConstraints`
 * `DpSize(maxWidth, maxHeight)`) and receive the design-system [WindowWidthClass].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun windowWidthClass(size: DpSize): WindowWidthClass = WindowSizeClass.calculateFromSize(size).widthSizeClass.toWindowWidthClass()

/** Maps Material's [WindowWidthSizeClass] onto our [WindowWidthClass]. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
internal fun WindowWidthSizeClass.toWindowWidthClass(): WindowWidthClass =
    when (this) {
        WindowWidthSizeClass.Compact -> WindowWidthClass.Compact
        WindowWidthSizeClass.Medium -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }
