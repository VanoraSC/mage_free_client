package magefree.feature.game.board

/*
 * Every string the board draws, in one place.
 *
 * They are `const` and public-in-module so the rendering tests assert against the **same** literal the
 * screen renders, rather than a copy that can drift. (Accessibility semantics are deliberately out of
 * scope for this story, so where a test needs a handle it uses one of these visible strings.)
 */

/** The board's title. */
const val BOARD_TITLE: String = "Game board"

/**
 * What the board says before the first snapshot has folded (`GameState.hasSnapshot == false`).
 *
 * The board itself still renders behind it: requirements §1.2 chose "show the board with empty regions"
 * over "hold the whole board hostage to the deal".
 */
const val WAITING_FOR_FIRST_SNAPSHOT: String = "Waiting for the first update…"

/**
 * The standing statement about what the board can do when the server is **not** asking anything.
 *
 * Story 0055's board carried a "read-only" notice here for the same reason this one carries a quiet
 * line: a player who cannot tell "the app won't let me" from "the game isn't asking me" reads a silent
 * board as a broken one. Now that the board can answer, the honest version of that sentence is that
 * there is nothing outstanding — which is a fact about `GameState.prompt`, not about the app.
 */
const val NO_OUTSTANDING_PROMPT: String = "Nothing to answer right now"

/** Prefix for the seat bar when we have not been told about that seat yet. */
const val OPPONENT_SEAT_LABEL: String = "Opponent"

/** Prefix for your own seat bar when the snapshot has not named it yet. */
const val VIEWER_SEAT_LABEL: String = "You"

/** What a seat bar says instead of a row of zeroes when the seat is not in the snapshot. */
const val NO_SEAT_LABEL: String = "not seated yet"

/** Marks the seat whose turn it is (`GamePlayer.isActive`). */
const val ACTIVE_SEAT_MARK: String = "active"

/** Marks a seat that has conceded or left (`GamePlayer.hasLeft`). */
const val LEFT_SEAT_MARK: String = "left"

/** Zone-count labels for the vitals bar. */
const val LIBRARY_LABEL: String = "Lib"

const val HAND_LABEL: String = "Hand"

const val GRAVEYARD_LABEL: String = "GY"

const val EXILE_LABEL: String = "Exile"

const val MANA_LABEL: String = "Mana"

const val WINS_LABEL: String = "Wins"

/** The empty state of a battlefield band — the ordinary state of both on turn one. */
const val EMPTY_BATTLEFIELD: String = "No permanents"

/** Turn-line wording. */
const val YOUR_TURN_LABEL: String = "your turn"

const val OPPONENT_TURN_SUFFIX: String = "'s turn"

const val UNKNOWN_TURN_OWNER: String = "turn holder unknown"

/** The stack region's own label. */
const val STACK_LABEL: String = "Stack"

/** The stack's empty state — the common case, and it must read sensibly. */
const val EMPTY_STACK: String = "empty"

/**
 * The qualifier the stack carries whenever it holds anything.
 *
 * The opponent's cancel is **not pushed** to us (requirements §17.4, proven by card id), so an object
 * on this stack can be one its caster has already rewound. The board therefore states what the stack
 * actually is — the last thing the server pushed — instead of implying it is live truth.
 */
const val STACK_AS_PUSHED: String = "as last pushed"

/** The collapsed hand's count prefix. */
const val HAND_PEEK_PREFIX: String = "In hand"

/** The collapsed hand's empty state — which the very first snapshot after joining really is in. */
const val EMPTY_HAND: String = "No cards in hand"

/** The expanded hand's empty state. */
const val EMPTY_HAND_EXPANDED: String = "Your hand is empty"

/** The peek control's two labels. */
const val HAND_EXPAND_LABEL: String = "Show hand"

const val HAND_COLLAPSE_LABEL: String = "Hide hand"

/**
 * Prefix for the server's outstanding question in the notice strip.
 *
 * The strip states the question so the player can follow the game even with the floating controls
 * hidden (§16.3). The controls themselves repeat it, because that is where it is answered.
 */
const val PROMPT_PREFIX: String = "Server asks:"

/** The exile summary's empty state, judged by cards rather than by the zone list's size. */
const val EXILE_EMPTY: String = "Exile empty"

/** The notice strip's empty state. */
const val NO_NOTICES: String = "No messages"

/** Prefix for a declined `joinGame`, carrying the server's own reason. */
const val JOIN_FAILED_PREFIX: String = "Couldn't join the game:"

// ---- story 0057: the floating controls (§16.2), the toggle (§16.3), casting and cancel ------------

/** The floating panel's own heading, so the player can see at a glance that it is the server asking. */
const val CONTROLS_TITLE: String = "The server asks"

/** The visibility toggle's two labels (§16.3). */
const val HIDE_CONTROLS_LABEL: String = "Hide controls"

const val SHOW_CONTROLS_LABEL: String = "Show controls"

/**
 * What the **hidden** toggle adds when the server is waiting on the player.
 *
 * §16.3's hard constraint: hiding the controls must never hide that the server is waiting, or a hidden
 * control set becomes an invisible stall and the game looks frozen. The status rail's priority banner
 * survives the toggle by construction (it is part of the board, not of the floating controls); this puts
 * the same fact on the toggle itself, where the finger that hid them already is.
 */
const val WAITING_ON_YOU_WHILE_HIDDEN: String = "The server is waiting on you"

/** Pass priority — the most repeated interaction in a game (§9.1), so it is always the primary button. */
const val PASS_LABEL: String = "Pass priority"

/** The player's confirmation that they have chosen enough targets: the final *done* (§17.2). */
const val DONE_LABEL: String = "Done choosing"

/**
 * The cancel offered wherever the server permits it (§16.5).
 *
 * It says *cast* rather than *spell* deliberately: the card is already on the stack when the prompt
 * arrives (CR 601 / §16.5), so the board must not claim the spell is uncast — what is being cancelled is
 * the act of casting it, which the server then rewinds.
 */
const val CANCEL_CAST_LABEL: String = "Cancel this cast"

/** What the cast context says while the server is rewinding — see [CANCEL_CAST_LABEL]. */
const val CANCELLING_NOTE: String = "Cancelling — the server rewinds the cast; the stack may still show it for a beat."

/** The prefix on the persistent cast context (§6.4: casting is one continuous act). */
const val CASTING_PREFIX: String = "Casting"

/** Fallbacks for a yes/no question the server sent no button labels with. */
const val YES_LABEL: String = "Yes"

const val NO_LABEL: String = "No"

/** The label on a pile in a choose-a-pile prompt. */
const val PILE_LABEL: String = "Pile"

/** Prefix for spending mana that is already floating in the pool. */
const val UNLOCK_MANA_PREFIX: String = "Use"

/** Confirms a chosen number. */
const val CONFIRM_AMOUNT_LABEL: String = "Confirm"

/** The stepper's two controls. */
const val AMOUNT_DECREASE_LABEL: String = "−"

const val AMOUNT_INCREASE_LABEL: String = "+"

/** What a tapped card's detail offers when the server has offered that card. */
const val PLAY_ACTION_LABEL: String = "Play"

const val TARGET_ACTION_LABEL: String = "Choose as target"

const val MANA_ACTION_LABEL: String = "Tap for mana"

/** Closes the tapped-card detail without acting (§11.1: any tap elsewhere closes it). */
const val CLOSE_DETAIL_LABEL: String = "Close"

/**
 * What the controls say for a prompt kind this build does not recognise.
 *
 * `GamePrompt.Unrecognised` has **no answering method** (0052, on purpose: nothing knows what a valid
 * reply is), so it renders as a notice with no buttons rather than as a control the player cannot
 * satisfy. The board menu stays available, which is what 0052's KDoc says a UI should do.
 */
const val UNRECOGNISED_PROMPT_NOTICE: String =
    "The server asked something this version doesn't understand. It can't be answered here — the next update replaces it."

/** What the controls say when the server offered nothing playable in a priority window. */
const val NOTHING_OFFERED_NOTE: String = "The server offers nothing to play — you can still pass."

/** The board menu, and the two **separate** ways to leave (§12.2). */
const val BOARD_MENU_LABEL: String = "Game menu"

const val CONCEDE_LABEL: String = "Concede game"

const val CONCEDE_CONFIRM_LABEL: String = "Concede — you lose this game"

const val QUIT_MATCH_LABEL: String = "Quit match"

const val QUIT_MATCH_CONFIRM_LABEL: String = "Quit — you leave the whole match"

/** Prefix for a game action the server declined, carrying its own reason. */
const val ACTION_FAILED_PREFIX: String = "The server declined:"
