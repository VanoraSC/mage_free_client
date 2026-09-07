# 0112 — The table becomes the board

- **Story:** #197
- **Epic:** EPIC-19 — Game Board Rebuild
- **Depends on:** 0105–0111 (everything in `feature/game/table`), and the whole of the old
  `feature/game/board` package, which this story retires the rendering half of.
- **Specified by:** [`docs/ui-modernization-plan.md`](../ui-modernization-plan.md) §7.4 (board
  layout), §7.19 (landscape), §11 (how the new UI arrives while the old one still runs).

## 1. Objective

Put the rebuilt board in front of a real server. The table tier becomes the screen `GameBoardRoute`
composes, and the game's destination moves to the root graph where the board gets the whole window.

## 2. Context & background

**The rebuilt board has never rendered a real game.** Six stories built `feature/game/table` — the
battlefield, the land stacks, the hand, the vitals, the status rail, the zone windows — and every one
of them was judged in the catalog, against fixtures from `BattlefieldGallery`. The live route still
composes `feature/game/board`, the portrait board from 0060–0080. Nothing a player can reach draws a
single line of the new tier.

**The gap is a composition root, not a model.** `battlefieldModel`, `tableVitals`, `tableZones`,
`handCards` and `playableElsewhere` all take a `GameState` — the server's own snapshot, the same one
`BoardUi.from` reads. `BattlefieldLayout` already accepts the hand, the playable-elsewhere group, the
vitals and the phase bar. What is missing is a screen that hands it a live snapshot and connects its
callbacks to the ViewModel that already exists.

**The prompt machinery is right and stays.** `controlsFor` projects `PromptControlsUi` from
`GameState.prompt` alone; `PassPolicy` owns when the app answers a priority prompt; `BoardAction` is
the single seam from a gesture to a server verb. All of that is server-driven, tested, and was proven
against a live upstream. This story reuses it **unchanged** and replaces only what draws it. Nothing
here re-decides a rule, and nothing here invents a question.

**The board needs the whole window, and today it does not get one.** `GameBoardNavRoute` lives in the
shell graph, so the board renders inside `AppShell`'s chrome — a connection strip above it and, in
landscape, a navigation rail down its left. `BattlefieldPreviewScreen`'s own doc makes the argument
already: a board in a letterbox is technically visible and cannot be assessed. The root graph is
where a game belongs, and `AppNavHost` says so — `GameRoute` has rendered a full-bleed placeholder
with no shell chrome since the shell was written, waiting for exactly this.

## 3. Scope

**In scope**
- A composition root in `:feature:game` that draws the table tier from `GameBoardUiState`.
- `GameBoardUiState` carrying the server's snapshot, so the tier that renders from a `GameState` gets
  one.
- The existing prompt surfaces — the floating controls, the hidden-controls toggle, the card detail
  overlay — floated over the new board, unchanged.
- The phase bar, driven by the snapshot's own turn and step.
- The game's destination moved to the root graph, landscape and immersive, carrying its game id.
- Retiring the old board's rendering: `GameBoardScreen`, `BoardRegions`, `BoardCards`, and the
  duplicate `StatusRail` that has been sitting alongside the table tier's own.
- Retiring `ImmersiveGameScreen`, the placeholder the real board replaces.

**Out of scope**
- **Redesigning the prompt surfaces for the new layout.** Where priority lives, how a cast in flight
  reads, tapping a permanent to target it, expressing attack and block pairing on a board that now
  has real creature rows — that is the next story, and it wants a screenshot with a pen on it before
  it is designed. This one borrows the panel that works.
- **Stops.** The phase bar draws where the game is; its stop toggles are not wired, because a stop
  that does not change when the app passes would be a control that lies. `PassPolicy` is where that
  lands, with auto-pass.
- **`BoardUi`.** It still projects the card the detail overlay reads and the priority the toggle
  restates. It retires when the prompt surfaces are redesigned, not before.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`, `:core:network`, `:feature:game`, `:app`.

## 5. Design & approach

**One screen, four layers, in the order a press should reach them.**

```
 ┌──────────────────────────────────────────────────────┐
 │  BattlefieldLayout — rail, lands, battlefield,       │  the board
 │  phase bar, hand                                     │
 ├──────────────────────────────────────────────────────┤
 │  PlayerOverlay — one seat's piles, on a scrim        │  a look
 ├──────────────────────────────────────────────────────┤
 │  FloatingControls / HiddenControlsToggle             │  the question
 ├──────────────────────────────────────────────────────┤
 │  CardDetailOverlay — the card, and what may be done  │  the decision
 └──────────────────────────────────────────────────────┘
```

**A press on a card selects it; the detail overlay is where it is committed.** That is the old
board's rule and it survives unchanged, because it is the rule that makes one gesture work everywhere
— on the battlefield, in the hand, in a land stack and in a zone window. `PromptControlsUi.actionFor`
already answers what a tap on an object means to the server right now, and `actionLabelFor` already
says what to call it; the overlay reads both.

**A land stack is two affordances and the board decides what each means.** An upright copy is the
card you would pick up, so pressing one selects that permanent — which, mid-cast, is how mana gets
paid. A turned copy is already lying down; pressing one selects it too, so it can be read, but there
is no such thing as untapping a land at will and nothing pretends otherwise.

**The controls float in the top-right corner, which is the only corner they can have.** The portrait
board put them along the bottom, and that is wrong here for a reason worth stating: the hand is drawn
from the bottom edge upward, and pressing a card in the hand is *how a priority prompt is answered* —
a panel over the hand covers the answer to its own question. The hermetic test found this the
straightforward way, by pressing a hand card through a panel that was on top of it. The other three
regions are spoken for as well: the status rail runs down the left, the phase bar sits on the hand,
and the creature rows meet on the centre line, which is the one thing a glance at a battlefield has
to be able to read. What is left is the strip above the opponent's non-creature permanents.

It is not where a thumb rests, and that is a trade this story makes deliberately rather than a place
it thinks the question belongs. The exit shares the corner and the column, so neither control has to
know where the other ended up. The toggle collapses the panel to a single control, and — as on the
old board — the collapsed toggle restates that the server is waiting, because a hidden control set
must never hide that.

**The phase bar is read-only.** `PhaseBarState` is projected from `GameState.step` and
`GameState.activePlayerId`; the stops keep upstream's own default (both main phases), which is what
`ManualPassPolicy` effectively enforces by never passing on its own. Nothing is toggled because
nothing yet acts on a toggle.

**The board takes the window.** The route moves to the root graph beside the catalog's battlefield
preview, which needed the whole window for the same reason and got there first. `LandscapeOnly` and
`ImmersiveSystemUi` scope orientation and the system bars to the composition — §11's rule that
orientation is *requested at runtime*, not locked in the manifest, so the portrait screens that still
work keep working. `LandscapeOnly`'s own doc already named itself the seam the real board would use.

**The table room reaches the board through a hoisted callback**, as entering a game already did:
`onEnterGame` now carries the game id, and the shell graph no longer has a game destination of its
own. The placeholder that callback used to reach — `ImmersiveGameScreen`, and the Settings entry that
opened it — goes, because the thing it stood in for now exists.

## 6. Implementation steps

1. `GameBoardUiState.snapshot`, set from the same emission `BoardUi.from` is projected from.
2. `phaseBarState(state)`: the snapshot's step and turn, as the bar's own vocabulary.
3. `TableBoardScreen`: the four layers, and the callbacks into `BoardAction`.
4. `GameBoardRoute` composes it, with the production art resolver.
5. Move the destination to the root graph; retire `ImmersiveGameScreen` and the shell's own game
   destination.
6. Delete the old board's rendering, and the duplicate `StatusRail` with it.

## 7. Testing & verification

- **Proven failing first (standard 1).** The screen is new, so "fails against the old one" is not a
  thing that can be run — but two of its tests failed against the first implementation of it and
  drove real changes, which is the same evidence:
  - *pressing a card in hand raises it* failed because the controls panel was drawn over the hand,
    and pressing a card in the hand is how a priority prompt is answered. The panel moved.
  - *before the first snapshot the board says so* failed because the screen keyed the line off having
    no snapshot at all, and `observeGame` opens with an empty seed that arrives immediately. It reads
    the server's own `hasSnapshot` now, which is what that seed is false for.
- **Unit:** the snapshot is carried whole and the seed is distinguishable from a game
  (`GameBoardViewModelTest`); every `PhaseStep` the server can send maps onto a step the bar draws or
  onto none, the steps nobody receives priority in map onto none, first-strike damage shares the
  combat-damage step, and whose turn it is comes from the active seat rather than from priority
  (`TablePhasesTest`).
- **Hermetic Compose (`TableBoardScreenTest`):** the board, the rail and the hand render from a
  snapshot; the waiting line shows on the seed and not on a game; a declined join keeps the server's
  reason; a priority prompt draws its controls; a quiet game says there is nothing to answer; hiding
  the controls leaves the waiting statement standing; nothing is modal; a press on a hand card raises
  it and sends nothing; the raised card offers the server's own action and committing emits it; a card
  the server has not offered has nothing to commit; there is always a way off the board.
- **Wiring:** the root graph mounts the board (`FeatureDestinationWiringTest`), it is not a tab
  (`GameRouteTest`), and it resolves its ViewModel through `koinViewModel()`
  (`ScreenViewModelResolutionTest`).
- **Eyes-on:** a real game against the containerised XMage server, on device.

## 8. Acceptance criteria

- [ ] Joining a game draws the rebuilt board, full window, landscape.
- [ ] The board shows both seats' vitals, lands, battlefields, the hand, and the phase bar, from the
      server's own snapshot.
- [ ] A prompt from the server shows its controls; answering one sends the action it named.
- [ ] Hiding the controls still says the server is waiting.
- [ ] Pressing any card — battlefield, hand, land stack, zone window — opens its detail, and the
      detail offers the action the server allows, if any.
- [ ] Pressing a seat opens that seat's zone window; a press outside closes it.
- [ ] Leaving the board restores the previous orientation and the system bars.
- [ ] The old board's rendering is gone from the tree, and only one `StatusRail` remains.
