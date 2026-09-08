package magefree.feature.game.table

import magefree.designsystem.card.BoardFocus
import magefree.network.game.GamePrompt
import magefree.network.game.GameState

/*
 * What the board is currently about.
 *
 * **A card can carry several signals at once, and which of them matters is a property of the moment.**
 * `BoardFocus` exists for exactly that, and the board never passed one — every card was drawn at
 * `Quiet`, whose only focal signal is `Threat`, which nothing produces. So *every* signal on the live
 * board rendered as a thin secondary border, and an attacking creature was told from a merely tapped
 * one by a 3dp line at 85% alpha. Both lean; only one of them is in combat; and at card size that was
 * not a difference a player could see across the table.
 *
 * The derivation is the board's own, which is what `BoardFocus`'s KDoc says it should be: it reads the
 * outstanding prompt and the stack, and nothing about the rules.
 */

/**
 * The focus for one snapshot.
 *
 * In order, most immediate first:
 *
 * - **Combat**, whenever the server says there is any. Attackers and blockers are assigned and are
 *   what the whole board is about until damage is done — including in the priority windows *inside*
 *   combat, which is where a player is deciding what to do about it.
 * - **Targeting**, while something is on the stack or a target is being chosen. The stack is a
 *   question about which permanents it points at.
 * - **PendingCost**, while a cost is being paid: the player is choosing what pays it.
 * - **Playable**, in an ordinary priority window. What can be acted on is what matters.
 * - **Quiet** otherwise — nothing pending, so only danger is worth raising a voice about.
 *
 * Combat outranks targeting deliberately. A combat trick puts a spell on the stack *during* combat,
 * and a board that then stopped emphasising who was attacking would drop the thing the trick is about.
 */
fun boardFocus(state: GameState): BoardFocus =
    when {
        state.combat.isNotEmpty() -> BoardFocus.Combat
        state.stack.isNotEmpty() || state.prompt is GamePrompt.Target -> BoardFocus.Targeting
        state.prompt is GamePrompt.PlayMana || state.prompt is GamePrompt.PlayXMana -> BoardFocus.PendingCost
        state.prompt is GamePrompt.Select -> BoardFocus.Playable
        else -> BoardFocus.Quiet
    }

/**
 * What the board is about, for the cards drawn inside it.
 *
 * Ambient rather than a parameter on five signatures: every card on a board shares one answer, and it
 * is the *board* that knows it. A card outside a board — a preview, a catalog entry — reads the
 * default and is [BoardFocus.Quiet], which is what it was before this existed.
 */
internal val LocalBoardFocus = androidx.compose.runtime.compositionLocalOf { BoardFocus.Quiet }
