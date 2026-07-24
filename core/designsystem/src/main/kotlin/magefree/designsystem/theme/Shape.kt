package magefree.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * The brand shape scale. Slightly rounder than the Material 3 defaults to match the soft, card-forward
 * "mage" identity. Component-level corner tokens (for bespoke surfaces) live in [Corner]; these are the
 * Material 3 role shapes the framework reads for its own components.
 */
val MageShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(Corner.extraSmall),
        small = RoundedCornerShape(Corner.small),
        medium = RoundedCornerShape(Corner.medium),
        large = RoundedCornerShape(Corner.large),
        extraLarge = RoundedCornerShape(Corner.extraLarge),
    )
