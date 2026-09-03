package magefree.app.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import magefree.designsystem.card.CardArtSlot
import magefree.designsystem.component.MageSecondaryButton
import magefree.designsystem.component.MageSectionHeader
import magefree.designsystem.theme.Spacing
import magefree.feature.game.table.BattlefieldLayout
import magefree.feature.game.table.battlefieldModel
import magefree.network.game.CardIconType
import magefree.network.game.CardType
import magefree.network.game.CombatGroup
import magefree.network.game.GameCard
import magefree.network.game.GameCardIcon
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
 *   a game have to be the same layout, not two that happen to look fine at their own size. Stepping
 *   between them is the fastest way to see the derived card size do its job.
 * - **The empty regions genuinely take no space.** The opening board has no creatures on either side,
 *   and the lands should sit where the lands sit, not floating in the middle of a reserved row.
 * - **Attachments are on their hosts.** The developed board has an Aura on a creature the opponent
 *   controls, which is the case that is easy to draw twice or in the wrong bucket.
 *
 * The snapshots are hand-built `GameState`s rather than recordings, because what is being judged is
 * the arrangement, and a recording would pin the layout to whatever one game happened to contain.
 */

/** The battlefield section, assembled here because the design system cannot see game types. */
@Composable
internal fun BattlefieldSection() {
    var step by remember { mutableIntStateOf(0) }
    var inspected by remember { mutableStateOf<String?>(null) }
    val board = Boards[step]

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        MageSectionHeader(text = "Battlefield")
        HorizontalDivider()

        MageSecondaryButton(
            text = board.label,
            onClick = {
                step = (step + 1) % Boards.size
                inspected = null
            },
        )

        Text(
            text = inspected?.let { "Tapped: $it" } ?: "Tap a permanent to check it reports its own id.",
            style = MaterialTheme.typography.labelMedium,
        )

        // **A phone-landscape window, not a tall one.** The catalog scrolls vertically, so the board
        // needs a bounded height — and the obvious bounded height, a fixed dp, gives a nearly square
        // box in this column and a board shape the app will never have. Every rule §7.4 states is
        // about *fitting*: the derived card size, the rows that vanish when empty, the front rows
        // meeting in the middle. Judging any of them in the wrong aspect ratio judges the wrong thing,
        // so the window is the real one's proportions and the width toggle above makes it bigger.
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(PHONE_LANDSCAPE_RATIO)) {
            BattlefieldLayout(
                model = battlefieldModel(board.state),
                artFor = CatalogBattlefieldArt,
                onInspect = { inspected = it },
            )
        }
    }
}

/** One board worth looking at, and what it is showing. */
private class CatalogBoard(
    val label: String,
    val state: GameState,
)

/**
 * A phone held sideways, which is the only shape the board targets (§7.19).
 *
 * 891 × 411 is the window a 1080 × 2400 phone gives in landscape, and it is what the hermetic tests
 * are configured against, so the catalog and the tests are looking at the same board.
 */
private const val PHONE_LANDSCAPE_RATIO = 891f / 411f

/** No art dependency here either; the arrangement is what is being judged. */
private val CatalogBattlefieldArt: ((String) -> CardArtSlot?)? = null

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
        combat = listOf(CombatGroup(defenderId = "them", attackerIds = listOf("bears"), blockerIds = listOf("wall"))),
        playable = listOf(PlayableObject(objectId = "f4")),
        players =
            listOf(
                GamePlayer(
                    playerId = "me",
                    name = "You",
                    isViewer = true,
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
                            GamePermanent(card = card("talisman", "Talisman of Unity", listOf(CardType.Artifact), manaCost = "{2}")),
                            land("f3", "Forest", tapped = true),
                            land("f4", "Forest"),
                            land("p1", "Plains", tapped = true),
                            // Your Aura, on their creature. It should appear on the Wall and nowhere
                            // else, on the far side of the board from the seat that controls it.
                            GamePermanent(
                                card = card("pacifism", "Pacifism", listOf(CardType.Enchantment), manaCost = "{1}{W}"),
                                attachedTo = "wall",
                                isAttachedToPermanent = true,
                                attachedControllerDiffers = true,
                            ),
                        ),
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    battlefield =
                        listOf(
                            creature(
                                id = "wall",
                                name = "Wall of Omens",
                                power = "0",
                                toughness = "4",
                                manaCost = "{1}{W}",
                                icons = listOf(GameCardIcon(type = CardIconType.AbilityDefender, hint = "Defender")),
                                attachments = listOf("pacifism"),
                            ),
                            creature(
                                id = "shrouded",
                                name = "Silhana Ledgewalker",
                                power = "1",
                                toughness = "1",
                                manaCost = "{1}{G}",
                                icons = listOf(GameCardIcon(type = CardIconType.AbilityHexproof, hint = "Shroud")),
                            ),
                            land("i3", "Island", tapped = true),
                            land("i4", "Island"),
                            land("s1", "Swamp"),
                        ),
                ),
            ),
    )

/** A board wide enough that the derived size has to give ground, and then the row scrolls. */
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
                        (1..9).map { index ->
                            creature("t$index", "Saproling", "1", "1", manaCost = "")
                        } + (1..7).map { index -> land("l$index", "Forest", tapped = index % 3 == 0) },
                ),
                GamePlayer(
                    playerId = "them",
                    name = "Opponent",
                    battlefield = listOf(creature("dragon", "Shivan Dragon", "5", "5", "{4}{R}{R}")),
                ),
            ),
    )

private val Boards =
    listOf(
        CatalogBoard(label = "Opening — two lands a side", state = Opening),
        CatalogBoard(label = "Developed — creatures, an artifact, an Aura across the board", state = Developed),
        CatalogBoard(label = "Crowded — sixteen permanents on one side", state = Crowded),
    )
