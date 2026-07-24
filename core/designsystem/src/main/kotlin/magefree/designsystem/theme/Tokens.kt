package magefree.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design tokens: the single source of visual truth for spacing, corner radii, elevation, and sizing.
 *
 * Components and screens reference these named steps instead of magic numbers, so the whole app stays
 * on one rhythm and a global adjustment is a one-line change. Grouped into small `object`s exposing
 * [Dp] constants; kept intentionally minimal (foundation only — richer scales arrive with the
 * components in 0013–0015).
 */

/** 4dp-based spacing scale for padding, gaps, and margins. */
object Spacing {
    /** 4dp — hairline gaps between tightly related elements. */
    val extraSmall: Dp = 4.dp

    /** 8dp — default gap inside a component. */
    val small: Dp = 8.dp

    /** 16dp — the standard content/screen padding. */
    val medium: Dp = 16.dp

    /** 24dp — separation between distinct sections. */
    val large: Dp = 24.dp

    /** 32dp — generous breathing room around focal content. */
    val extraLarge: Dp = 32.dp
}

/** Corner-radius scale, aligned with [MageShapes]. */
object Corner {
    val extraSmall: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 20.dp
    val extraLarge: Dp = 28.dp
}

/** Elevation scale for shadows/tonal elevation of raised surfaces. */
object Elevation {
    /** 0dp — flush with the background. */
    val none: Dp = 0.dp

    /** 1dp — subtle lift for resting cards. */
    val level1: Dp = 1.dp

    /** 3dp — raised surfaces (e.g. hovered/focused cards). */
    val level2: Dp = 3.dp

    /** 6dp — floating surfaces (dialogs, menus). */
    val level3: Dp = 6.dp

    /** 12dp — the highest, most prominent surfaces. */
    val level4: Dp = 12.dp
}

/** Fixed sizing tokens, notably the accessibility minimum touch target. */
object Sizing {
    /** 48dp — the minimum interactive touch-target size (accessibility requirement). */
    val minTouchTarget: Dp = 48.dp

    /** 24dp — standard inline icon size. */
    val iconSmall: Dp = 24.dp

    /** 40dp — prominent/leading icon size. */
    val iconLarge: Dp = 40.dp
}
