package magefree.feature.game.table

import magefree.cards.art.CardArtRequest
import magefree.designsystem.card.BoardCardState
import magefree.designsystem.card.BoardCounter
import magefree.designsystem.card.CardDisplay
import magefree.network.game.GameState

/*
 * The stack: what is happening right now.
 *
 * **Top first.** Upstream sends the stack bottom-first — the order things were put on it — and it
 * resolves in the reverse. A player reading it is asking "what happens next", so it is turned round
 * here, once, and every consumer reads the same order.
 *
 * **The text is the server's, and it is the game-aware one.** `GameCard.rules` on a stack object is
 * the ability as it exists now, after layers and after whatever modified it — not the printing's. That
 * is the whole reason to show it: a board that showed the printed text would be showing something the
 * game has already moved past.
 *
 * **The targets are the server's too.** `GameCard.targets` is filled by upstream's own `addTargets`,
 * so what a spell is pointing at is never worked out here.
 *
 * **Not authoritative between pushes.** The server does not push the opponent a snapshot when a cast
 * is cancelled, so an object the caster has already rewound can linger until the next push. That is a
 * property of the wire and is stated where it is drawn rather than papered over.
 */

/**
 * One object on the stack, as the board draws it.
 *
 * @property id the server's own object id — what an arrow starts from and what a press names.
 * @property state everything the Board card tier needs to draw it, so a spell on the stack looks like
 *   the card it is rather than like a row in a list.
 * @property art the printing the server named, or `null` for one it did not.
 * @property rules the server's own game-aware text for this object, in its own order. Empty is a real
 *   state — a vanilla creature spell has no rules text at all.
 * @property targetIds every object this one is pointing at, in the server's order. An id here may name
 *   something the board does not draw: a card in a graveyard, or a player.
 */
data class TableStackObject(
    val id: String,
    val state: BoardCardState,
    val art: CardArtRequest? = null,
    val rules: List<String> = emptyList(),
    val targetIds: List<String> = emptyList(),
)

/**
 * The stack for one snapshot, top first.
 *
 * Empty is the ordinary state — most of a game has nothing on the stack — and the board draws no
 * region at all for it, which is the same rule every other region on this board follows.
 */
fun tableStack(state: GameState): List<TableStackObject> =
    state.stack.asReversed().map { card ->
        TableStackObject(
            id = card.id,
            // The same card the battlefield draws, deliberately: an object on the stack *is* the card,
            // and a player who had to learn a second representation for the seconds it spends there
            // has been given a second thing to read for nothing.
            //
            // **Never tapped, never in combat, never marked playable.** All three are properties of a
            // permanent, and a spell on the stack is not one. Leaning a stack object over would be
            // saying something about it that cannot be true.
            state =
                BoardCardState(
                    card =
                        CardDisplay(
                            name = card.name,
                            manaCost = card.manaCost,
                            typeLine = card.typeLine,
                            oracleText = card.rules.joinToString("\n").takeIf { it.isNotBlank() },
                        ),
                    power = card.shownPower,
                    toughness = card.shownToughness,
                    counters = card.counters.map { BoardCounter(name = it.name, count = it.count) },
                    badges = card.icons.mapNotNull(::badgeOf),
                ),
            art = artRequestOf(card),
            rules = card.rules.mapNotNull { it.trim().ifBlank { null } },
            targetIds = card.targets,
        )
    }
