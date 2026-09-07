package magefree.app.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.theme.Spacing
import magefree.network.game.CardIconType
import magefree.network.game.CardType
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCardIcon
import magefree.network.game.GameCommandObject
import magefree.network.game.GameCounter
import magefree.network.game.GamePermanent
import magefree.network.game.GamePlayer
import magefree.network.game.GameState
import magefree.network.game.PlayableObject

/*
 * The battlefield, over snapshots shaped like real ones.
 *
 * What a still picture cannot show, and what these boards are chosen to make visible:
 *
 * - **The arrangement holds as the board fills.** An opening board of two lands and a board deep into
 *   a game have to be the same layout, not two that happen to look fine at their own size.
 * - **The empty regions genuinely take no space.** The opening board has no creatures on either side,
 *   and the lands should sit in their corner rather than floating in a reserved row.
 * - **Stacking, at the moment it changes.** Four Plains become a stack of three and a count; tapping
 *   one splits them into two stacks and the count disappears. Three consecutive boards walk that
 *   through, because the transition is the part a still picture cannot show.
 * - **Attachments are on their hosts.** The developed board has an Aura on a creature the opponent
 *   controls, which is the case that is easy to draw twice or in the wrong bucket.
 *
 * The snapshots are hand-built `GameState`s rather than recordings, because what is being judged is
 * the arrangement, and a recording would pin the layout to whatever one game happened to contain.
 */

/**
 * The battlefield section: an entry point, not a preview.
 *
 * **The board is not shown inline, and that is the point.** It was, and a letterbox in the catalog's
 * portrait column is not something the arrangement can be judged from — the card size that only
 * shrinks when the board is busy, the rows that vanish when empty, the two front rows meeting in the
 * middle are all rules about fitting a real window. In a strip a few hundred dp wide they are
 * technically visible and none of them can be assessed. So this opens the real thing.
 */
@Composable
internal fun BattlefieldSection(
    onOpenPreview: () -> Unit,
    onOpenCastMock: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        MageSectionHeader(text = "Battlefield")
        HorizontalDivider()

        Text(
            text =
                "Both open full-window and landscape, because that is the only shape the board is " +
                    "designed for. The battlefield cycles boards to judge the layout by looking; the " +
                    "cast mock is judged by doing — read a card, play or cast it, drag one out of hand.",
            style = MaterialTheme.typography.labelMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            MageSecondaryButton(text = "Open the battlefield", onClick = onOpenPreview)
            MageSecondaryButton(text = "Cast and inspect", onClick = onOpenCastMock)
        }
    }
}

/**
 * One board worth looking at, and what it is showing. Shared with the full-window preview.
 *
 * @property tappable when true the board is built from a live count of tapped Plains rather than being
 *   fixed, so tapping the stack actually taps a land. The stacking rule is about a transition, and a
 *   canned before-and-after cannot show a transition — the animation only exists while it is running.
 */
internal class CatalogBoard(
    val label: String,
    val state: GameState,
    val tappable: Boolean = false,
)

/**
 * The printings the fixtures name, so the preview shows real cards.
 *
 * **Pinned, and Tenth Edition throughout.** The preview is a visual-QA surface: art that changed
 * between runs would make it useless for comparing one build against the next, and one frame style
 * means the only differences on screen are the ones the arrangement introduces. Every pair was read
 * out of the app's own bundled card database rather than recalled, because a wrong collector number is
 * a card that silently fails to load rather than an error anyone sees.
 */
private val Printings: Map<String, Pair<String, String>> =
    mapOf(
        "Forest" to ("10E" to "380"),
        "Island" to ("10E" to "368"),
        "Plains" to ("10E" to "364"),
        "Swamp" to ("10E" to "372"),
        "Mountain" to ("10E" to "376"),
        "Grizzly Bears" to ("10E" to "268"),
        "Llanowar Elves" to ("10E" to "274"),
        "Suntail Hawk" to ("10E" to "50"),
        "Serra Angel" to ("10E" to "39"),
        "Air Elemental" to ("10E" to "64"),
        "Mahamoti Djinn" to ("10E" to "90"),
        "Craw Wurm" to ("10E" to "257"),
        "Shivan Dragon" to ("10E" to "230"),
        "Troll Ascetic" to ("10E" to "305"),
        "Pacifism" to ("10E" to "31"),
        "Icy Manipulator" to ("10E" to "326"),
        "Loxodon Warhammer" to ("10E" to "332"),
        "Rod of Ruin" to ("10E" to "341"),
    )

private fun card(
    id: String,
    name: String,
    types: List<CardType>,
    isCreature: Boolean = false,
    power: String? = null,
    toughness: String? = null,
    manaCost: String? = null,
    counters: List<GameCounter> = emptyList(),
    icons: List<GameCardIcon> = emptyList(),
) = GameCard(
    id = id,
    name = name,
    setCode = Printings[name]?.first,
    collectorNumber = Printings[name]?.second,
    manaCost = manaCost,
    typeLine = types.joinToString(" ") { it.name },
    power = power,
    toughness = toughness,
    cardTypes = types,
    isCreature = isCreature,
    counters = counters,
    icons = icons,
)

private fun land(
    id: String,
    name: String,
    tapped: Boolean = false,
) = GamePermanent(card = card(id, name, listOf(CardType.Land)), isTapped = tapped)

private fun creature(
    id: String,
    name: String,
    power: String,
    toughness: String,
    manaCost: String,
    tapped: Boolean = false,
    counters: List<GameCounter> = emptyList(),
    icons: List<GameCardIcon> = emptyList(),
    attachments: List<String> = emptyList(),
) = GamePermanent(
    card =
        card(
            id = id,
            name = name,
            types = listOf(CardType.Creature),
            isCreature = true,
            power = power,
            toughness = toughness,
            manaCost = manaCost,
            counters = counters,
            icons = icons,
        ),
    isTapped = tapped,
    attachments = attachments,
)

/**
 * Four Plains, at the three moments that make the stacking rule legible.
 *
 * The transition is the part that is hard to reason about from a still picture. Four Plains show three
 * faces and a count; tap one and the untapped stack drops to three faces with **no** badge — three is
 * countable again — beside a tapped stack of one; tap another and the two stacks are two and two.
 */
private fun plainsBoard(tapped: Int) =
    GameState(
        gameId = "catalog",
        viewerPlayerId = "me",
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    battlefield = (1..PLAINS_COUNT).map { index -> land("p$index", "Plains", tapped = index <= tapped) },
                ),
                GamePlayer(playerId = "them", name = "Opponent", battlefield = listOf(land("i1", "Island"))),
            ),
    )

/** Two lands a side and nothing else: the board every game starts as. */
private val Opening =
    GameState(
        gameId = "catalog",
        viewerPlayerId = "me",
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    battlefield = listOf(land("f1", "Forest"), land("f2", "Forest", tapped = true)),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    battlefield = listOf(land("i1", "Island"), land("i2", "Island")),
                ),
            ),
    )

/** Mid-game: creatures on both sides, an artifact at the back, an attack in progress. */
private val Developed =
    GameState(
        gameId = "catalog",
        viewerPlayerId = "me",
        combat = listOf(CombatGroup(defenderId = "them", attackerIds = listOf("bears"), blockerIds = listOf("wurm"))),
        // A hand of five with two castable. The contrast is the point of the playable highlight: a
        // hand where everything or nothing is lit shows nothing. The untapped Forests pay for the
        // Elves and the Hawk; the Dragon and the Djinn are out of reach.
        hand =
            listOf(
                card("hand-elves", "Llanowar Elves", listOf(CardType.Creature), isCreature = true, manaCost = "{G}"),
                card("hand-hawk", "Suntail Hawk", listOf(CardType.Creature), isCreature = true, manaCost = "{W}"),
                card("hand-dragon", "Shivan Dragon", listOf(CardType.Creature), isCreature = true, manaCost = "{4}{R}{R}"),
                card("hand-djinn", "Mahamoti Djinn", listOf(CardType.Creature), isCreature = true, manaCost = "{4}{U}{U}"),
                card("hand-pacifism", "Pacifism", listOf(CardType.Enchantment), manaCost = "{1}{W}"),
            ),
        // Every untapped land is playable, which is what a real snapshot says: the server offers all
        // of them at once. Marking only one splits the Forests into two stacks for a reason no game
        // produces, which is a fixture bug that reads as a layout bug.
        playable =
            listOf(
                PlayableObject(objectId = "f4"),
                PlayableObject(objectId = "f5"),
                PlayableObject(objectId = "hand-elves"),
                PlayableObject(objectId = "hand-hawk"),
            ),
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    // The board most worth checking the vitals against: this seat is two poison from
                    // losing and holds an emblem, the other has neither. A board where both seats
                    // carry everything shows nothing about what appears only when it matters.
                    life = 14,
                    libraryCount = 27,
                    handCount = 5,
                    graveyardCount = 3,
                    wins = 1,
                    winsNeeded = 2,
                    hasPriority = true,
                    isActive = true,
                    counters = listOf(GameCounter("poison", 8), GameCounter("energy", 2)),
                    isMonarch = true,
                    designationNames = listOf("City's Blessing"),
                    commandList = listOf(GameCommandObject(id = "emblem-1", name = "Emblem — Elspeth, Knight-Errant")),
                    battlefield =
                        listOf(
                            creature(
                                id = "bears",
                                name = "Grizzly Bears",
                                power = "4",
                                toughness = "4",
                                manaCost = "{1}{G}",
                                counters = listOf(GameCounter("+1/+1", 2)),
                                icons = listOf(GameCardIcon(type = CardIconType.AbilityTrample, hint = "Trample")),
                            ),
                            creature(
                                id = "hawk",
                                name = "Suntail Hawk",
                                power = "1",
                                toughness = "1",
                                manaCost = "{W}",
                                icons = listOf(GameCardIcon(type = CardIconType.AbilityFlying, hint = "Flying")),
                            ),
                            GamePermanent(card = card("icy", "Icy Manipulator", listOf(CardType.Artifact), manaCost = "{4}")),
                            // Three tapped Forests and two untapped: two stacks, neither big enough
                            // for a count, which is the ordinary mid-game shape.
                            land("f1", "Forest", tapped = true),
                            land("f2", "Forest", tapped = true),
                            land("f3", "Forest", tapped = true),
                            land("f4", "Forest"),
                            land("f5", "Forest"),
                            land("pl1", "Plains", tapped = true),
                            // Your Aura, on their creature. It should appear on the Wurm and nowhere
                            // else, on the far side of the board from the seat that controls it.
                            GamePermanent(
                                card = card("pacifism", "Pacifism", listOf(CardType.Enchantment), manaCost = "{1}{W}"),
                                attachedTo = "wurm",
                                isAttachedToPermanent = true,
                                attachedControllerDiffers = true,
                            ),
                        ),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    // No counters, no designations, no emblem — the contrast that shows a chip appears
                    // only when there is something to say.
                    life = 20,
                    libraryCount = 31,
                    handCount = 4,
                    graveyardCount = 1,
                    winsNeeded = 2,
                    battlefield =
                        listOf(
                            creature(
                                id = "wurm",
                                name = "Craw Wurm",
                                power = "6",
                                toughness = "4",
                                manaCost = "{4}{G}{G}",
                                attachments = listOf("pacifism"),
                            ),
                            creature(
                                id = "troll",
                                name = "Troll Ascetic",
                                power = "3",
                                toughness = "2",
                                manaCost = "{1}{G}{G}",
                                icons = listOf(GameCardIcon(type = CardIconType.AbilityHexproof, hint = "Hexproof from all")),
                            ),
                            land("i3", "Island", tapped = true),
                            land("i4", "Island"),
                            land("s1", "Swamp"),
                        ),
                ),
            ),
    )

/**
 * A board busy enough that the derived size has to give ground — and a land corner that does not.
 *
 * Nine creatures shrink the front row; twelve lands collapse into three stacks and take about as much
 * room as three lands. That difference is the whole argument for stacking, and it is only visible with
 * both on screen at once.
 */
private val Crowded =
    GameState(
        gameId = "catalog",
        viewerPlayerId = "me",
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    battlefield =
                        (1..9).map { index -> creature("elf$index", "Llanowar Elves", "1", "1", manaCost = "{G}") } +
                            (1..7).map { index -> land("f$index", "Forest", tapped = index % 3 == 0) } +
                            (1..5).map { index -> land("m$index", "Mountain") },
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    battlefield =
                        listOf(
                            creature("dragon", "Shivan Dragon", "5", "5", "{4}{R}{R}"),
                            creature("djinn", "Mahamoti Djinn", "5", "6", "{4}{U}{U}"),
                        ),
                ),
            ),
    )

/**
 * A hand too wide to lay out flat, which is the only way to see the rule the hand is built around.
 *
 * Twelve cards at a comfortable tile size do not fit across a phone in landscape. §7.4 forbids the two
 * obvious answers — scrolling them and hiding them behind a peek edge — so they overlap, and the thing
 * to check is that the twelfth is on screen and the first still shows enough of itself to be told
 * apart. Half of them are castable, so the highlight is visible against cards that are not.
 */
private val FullHand =
    GameState(
        gameId = "catalog",
        viewerPlayerId = "me",
        hand =
            (1..12).map { index ->
                val name = if (index % 2 == 0) "Llanowar Elves" else "Shivan Dragon"
                card("draw$index", name, listOf(CardType.Creature), isCreature = true, manaCost = "{G}")
            },
        playable = (1..12).filter { it % 2 == 0 }.map { PlayableObject(objectId = "draw$it") },
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    battlefield = (1..4).map { index -> land("f$index", "Forest", tapped = index > 2) },
                ),
                GamePlayer(playerId = "them", name = "Opponent", battlefield = listOf(land("i1", "Island"))),
            ),
    )

/** The worked example the stacking rule was specified against. */
private const val PLAINS_COUNT = 4

internal val Boards =
    listOf(
        CatalogBoard(label = "Opening — two lands a side", state = Opening),
        CatalogBoard(label = "Four Plains — tap them", state = plainsBoard(tapped = 0), tappable = true),
        CatalogBoard(label = "Developed — an Aura across the board, two land stacks", state = Developed),
        CatalogBoard(label = "Crowded — nine creatures shrink; twelve lands do not", state = Crowded),
        CatalogBoard(label = "A hand of twelve — overlapping, none of it off screen", state = FullHand),
    )

/** The Plains board at a given number tapped, for the one board that is played rather than posed. */
internal fun tappedPlainsBoard(tapped: Int): GameState = plainsBoard(tapped.coerceIn(0, PLAINS_COUNT))

/** How many Plains the worked example puts on the board. */
internal const val CATALOG_PLAINS = PLAINS_COUNT

/** The board at [step], wrapping so the preview's one button can cycle forever. */
internal fun catalogBoard(step: Int): CatalogBoard = Boards[step.mod(Boards.size)]

/**
 * The board the cast mock uses: a real hand with a mixture the player has to choose between.
 *
 * Two lands and three spells, three of them castable — a Forest to play, an Elves and a Hawk to cast,
 * and a Dragon and a Djinn out of reach. That mixture is what makes the mock worth pressing: the
 * button says *Play* on one and *Cast* on another, and two cards offer nothing at all.
 */
internal fun castMockBoard(): GameState =
    GameState(
        gameId = "cast-mock",
        viewerPlayerId = "me",
        hand =
            listOf(
                card("mock-forest", "Forest", listOf(CardType.Land)),
                card(
                    "mock-elves",
                    "Llanowar Elves",
                    listOf(CardType.Creature),
                    isCreature = true,
                    power = "1",
                    toughness = "1",
                    manaCost = "{G}",
                ),
                card(
                    "mock-hawk",
                    "Suntail Hawk",
                    listOf(CardType.Creature),
                    isCreature = true,
                    power = "1",
                    toughness = "1",
                    manaCost = "{W}",
                ),
                card(
                    "mock-dragon",
                    "Shivan Dragon",
                    listOf(CardType.Creature),
                    isCreature = true,
                    power = "5",
                    toughness = "5",
                    manaCost = "{4}{R}{R}",
                ),
                card("mock-pacifism", "Pacifism", listOf(CardType.Enchantment), manaCost = "{1}{W}"),
            ),
        playable =
            listOf(
                PlayableObject(objectId = "mock-forest"),
                PlayableObject(objectId = "mock-elves"),
                PlayableObject(objectId = "mock-hawk"),
            ),
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
                    battlefield =
                        listOf(
                            creature("mock-bears", "Grizzly Bears", "2", "2", "{1}{G}"),
                            land("mock-f1", "Forest"),
                            land("mock-f2", "Forest"),
                            land("mock-p1", "Plains", tapped = true),
                        ),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    battlefield = listOf(creature("mock-wurm", "Craw Wurm", "6", "4", "{4}{G}{G}"), land("mock-i1", "Island")),
                ),
            ),
    )
