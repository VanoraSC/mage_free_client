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
 * The standing statement that this board **cannot act**. It is not decoration: a player who cannot tell
 * "the app won't let me" from "the game isn't asking me" would read a read-only board as a broken one.
 */
const val READ_ONLY_NOTICE: String = "Read-only board — playing arrives in a later update"

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
 * Prefix for the server's outstanding question.
 *
 * The question is shown so the player can follow what the game is doing. It is **not** answerable here:
 * answering is story 0056, and offering a control that silently did nothing would be worse than showing
 * no control at all.
 */
const val PROMPT_PREFIX: String = "Server asks:"

/** The exile summary's empty state, judged by cards rather than by the zone list's size. */
const val EXILE_EMPTY: String = "Exile empty"

/** The notice strip's empty state. */
const val NO_NOTICES: String = "No messages"

/** Prefix for a declined `joinGame`, carrying the server's own reason. */
const val JOIN_FAILED_PREFIX: String = "Couldn't join the game:"
