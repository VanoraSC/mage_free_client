package magefree.designsystem.board

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The board's elevation and type scales.
 *
 * Both exist because the board is a different problem from the rest of the app. Elevation is how the
 * board says "this is floating over the battlefield rather than part of it", which is a distinction
 * the app's other screens never have to make. Type is sized for a card on a battlefield rather than
 * for a list row, and the smallest style here — a card name at board size — is the legibility floor
 * the whole layout is derived from.
 */

/**
 * Elevation for the board's two-layer model: a base layer that never moves, and floating layers over
 * it that come and go.
 *
 * The battlefield stays at [base] permanently. Anything transient — the stack, combat assignment,
 * revealed cards, the Prompt — renders above it rather than beside it, so it can appear and disappear
 * without the battlefield reflowing underneath.
 */
object BoardElevation {
    /** The battlefield and everything else in the base layer. Never lifts. */
    val base: Dp = 0.dp

    /** A card at rest on a zone. */
    val card: Dp = 1.dp

    /** A card lifted off its zone: picked up, spotlighted, mid-move. */
    val cardRaised: Dp = 3.dp

    /** A floating layer over the board: the stack, combat assignment, revealed cards. */
    val floating: Dp = 8.dp

    /** The topmost floating layer, which nothing else covers. */
    val overlay: Dp = 16.dp
}

/**
 * Type for the board, sized for cards rather than for lists.
 *
 * [cardName] is the load-bearing one: it is the smallest thing on the board that must stay readable,
 * so it is what card size is floored at rather than a consequence of whatever size the cards end up.
 */
object BoardTypography {
    /** A card's name at Board size. The legibility floor the board's sizing is derived from. */
    val cardName: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 13.sp,
        )

    /** Power and toughness on a card face. Heavier than the name: it changes, and changes matter. */
    val cardStats: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            lineHeight = 15.sp,
        )

    /** A counter's value on a card face. */
    val counter: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            lineHeight = 11.sp,
        )

    /** A count or a small annotation beside a pile or a zone. */
    val annotation: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 13.sp,
        )

    /** A life total. The largest thing on the board that is not a card. */
    val vitals: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 24.sp,
        )

    /** The Prompt's question, in the server's own words. */
    val promptTitle: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        )

    /** The Prompt's supporting text and its progress line. */
    val promptBody: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
}
