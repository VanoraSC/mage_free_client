package magefree.designsystem.board

import androidx.compose.ui.graphics.Color

/*
 * The board's colour tokens: a neutral grey ground, and saturated colour reserved for information.
 *
 * The board carries no illustrated playmat and no decorative background. Zones and layers are told
 * apart by **value and elevation**, never by hue or texture, which leaves saturated colour free to
 * mean exactly one thing: the game is telling the player something. A highlight has to compete with
 * nothing for attention, which is what makes it readable at card size.
 *
 * These tokens sit **beside** the app's brand palette rather than replacing it. They are consumed by
 * board surfaces only; every other screen keeps the brand colour scheme.
 */

/**
 * The grey ground, as roles rather than swatch numbers.
 *
 * The order of [valueRamp] is the contract: each role is perceptibly lighter than the one before it,
 * by enough that a player separates two adjacent regions with value alone. `BoardColorsTest` holds
 * the ramp to [MIN_LIGHTNESS_STEP], so a step cannot be quietly flattened by a later adjustment.
 */
object BoardSurface {
    /** The board's own ground — the deepest surface, behind everything else. */
    val ground: Color = Color(0xFF0B0B0B)

    /** A zone sitting on the ground: a battlefield, the hand. */
    val zone: Color = Color(0xFF1E1E1E)

    /** A zone that is currently the subject of attention, or nested inside another zone. */
    val zoneRaised: Color = Color(0xFF2B2B2B)

    /** The frame of a card at rest. */
    val card: Color = Color(0xFF3D3D3D)

    /** The frame of a card that is picked up, spotlighted, or otherwise lifted off its zone. */
    val cardRaised: Color = Color(0xFF525252)

    /** A layer floating over the board: the stack, the Prompt, revealed cards, notices. */
    val floating: Color = Color(0xFF6A6A6A)

    /** Text and icons on any board surface. Clears 4.5:1 against every one of them. */
    val onSurface: Color = Color(0xFFF0F0F0)

    /** Secondary text on any board surface — a type line, a count. Clears 3:1 against every one. */
    val onSurfaceMuted: Color = Color(0xFFC4C4C4)

    /**
     * Every surface, ordered from deepest to lightest. Adjacent entries are the pairs a player is
     * asked to tell apart.
     */
    val valueRamp: List<Color> = listOf(ground, zone, zoneRaised, card, cardRaised, floating)

    /**
     * The surfaces a signal is drawn over: the board's own grounds, behind and between cards. A
     * signal marks a card by its border and its surroundings, so these — not the card frames — are
     * what it must stay visible against. Over card art no palette can make that guarantee, and the
     * component drawing there owns it.
     */
    val signalGrounds: List<Color> = listOf(ground, zone, zoneRaised)

    /**
     * The smallest perceptual-lightness step (CIE L\*) allowed between adjacent [valueRamp] entries.
     * Well above the just-noticeable difference for two large adjacent areas, because a zone boundary
     * has to be obvious in peripheral vision rather than merely detectable when looked for.
     */
    const val MIN_LIGHTNESS_STEP: Double = 5.0

    /** The contrast [onSurface] holds against every surface — the WCAG minimum for body text. */
    const val MIN_TEXT_CONTRAST: Double = 4.5

    /** The contrast [onSurfaceMuted] holds against every surface — the WCAG minimum for large text. */
    const val MIN_MUTED_CONTRAST: Double = 3.0
}

/**
 * The information colours: one per signal the board sends, each named for what it means.
 *
 * A name that describes the pixel has to change when the pixel does; a name that describes the
 * meaning survives every retune. There is no `Green500` here and there should never be one.
 *
 * Every entry clears [MIN_SIGNAL_CONTRAST] against every [BoardSurface.signalGrounds] entry, and no
 * two are within [MIN_SIGNAL_DIFFERENCE] of each other, so a player never has to decide which of two
 * similar marks they are looking at.
 */
object BoardSignal {
    /** This can be played, cast or activated right now. */
    val playable: Color = Color(0xFF4ADE80)

    /** This is targeting, or is targeted by, something on the stack. */
    val targeting: Color = Color(0xFFC084FC)

    /** This creature is attacking. */
    val attacking: Color = Color(0xFFEF4444)

    /** This creature is blocking. */
    val blocking: Color = Color(0xFF38BDF8)

    /** A cost being assembled will consume this — the mana, the creatures, the cards exiled. */
    val pendingCost: Color = Color(0xFFFBBF24)

    /** This is dangerous to the player: lethal on board, a poison count, an unanswered threat. */
    val threat: Color = Color(0xFFFB7185)

    /** Every signal, for the checks that must hold across all of them and for the catalog. */
    val all: List<Color> = listOf(playable, targeting, attacking, blocking, pendingCost, threat)

    /**
     * The contrast every signal holds against every board ground. 3:1 is the WCAG minimum for a
     * graphical element that carries meaning, which is exactly what these are.
     */
    const val MIN_SIGNAL_CONTRAST: Double = 3.0

    /**
     * The smallest ΔE\*ab allowed between any two signals. Two marks that mean different things must
     * not be a shade apart, because the player reads them at card size and in peripheral vision.
     */
    const val MIN_SIGNAL_DIFFERENCE: Double = 25.0
}
