# 0072 — Ordering simultaneous triggered abilities is unplayable

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction: casting, targeting, cancel)
- **Status:** ready

## 1. Objective

Fix a defect found live (Pete, 2026-08-16): with Guide of Souls on the battlefield, casting Ajani,
Nacatl Pariah put two of the viewer's own triggered abilities on the stack simultaneously. The
server correctly asked the player to choose the order — but the resulting prompt is unplayable in
two compounding ways:

1. **Tapping a candidate does nothing.** The prompt renders, the two triggers are shown as cards,
   but tapping either one has no effect — the player cannot answer the prompt at all.
2. **The cards are unlabeled.** Both candidates render with the generic text "ability" (as both
   the card name and the button label beneath it), instead of the triggering permanent's name and
   the actual trigger text — so even if tapping worked, the player has no way to tell the two
   triggers apart to choose a correct order.

## 2. Root cause — confirmed against the pinned XMage source, not assumed

Ordering is `GameImpl.checkTriggered()` (`Mage/src/main/java/mage/game/GameImpl.java:2341`):
when more than one of a player's own triggered abilities would go on the stack at once, it calls
`player.chooseTriggeredAbility(abilities, this)`. The human implementation
(`HumanPlayer.chooseTriggeredAbility`,
`Mage.Server.Plugins/.../HumanPlayer.java:1507`) fires
`game.fireSelectTargetTriggeredAbilityEvent(playerId, "Pick triggered ability (goes to the stack
first)", abilitiesWithNoOrderSet)`, which raises a `PlayerQueryEvent` with `QueryType.PICK_ABILITY`.

`GameController` routes `PICK_ABILITY` to a **second, ability-only overload** of `target(...)`
(`Mage.Server/src/main/java/mage/server/game/GameController.java:881-885`), distinct from the
ordinary card/permanent-targeting overload `PICK_TARGET` uses:

```java
private synchronized void target(UUID playerId, final String question, final Collection<? extends Ability> abilities, ...) {
    perform(playerId, playerId1 -> {
        CardsView cardsView = new CardsView(abilities, game);
        getGameSession(playerId1).target(question, cardsView, null, required, options);
        //                                                       ^^^^ targets is always null here
    });
}
```

Two consequences follow directly from that overload, both confirmed by reading the actual source:

**(a) `targets` is unconditionally `null`.** Unlike `PICK_TARGET` (which always forwards a real
`Set<UUID>`), `PICK_ABILITY` never sends one — the candidate set *is* `cardsView1`, and there is no
separate "which of these are valid" list because all of them always are. On the wire this still
looks like an ordinary `GAME_TARGET` callback, just with `targets = null`.

Our bridge's `GamePromptMapper.target()`
(`bridge/src/main/kotlin/magefree/bridge/mapping/game/GamePromptMapper.kt:58-74`) builds
`TargetPrompt.targetIds` from `message.targets.orEmpty()` — so for this prompt, `targetIds` comes
back **empty**, even though `cards` (from `cardsView1`) correctly holds both triggers.

The app's `BoardControls.kt`, in the `is GamePrompt.Target ->` branch (lines 497–510), computes
what may be tapped **only** from `targetIds`/`options.possibleTargets`:

```kotlin
val pickable = (prompt.targetIds + prompt.options.possibleTargets).toSet()
```

`prompt.cards` is used to build `candidateCards` for *rendering* (line 509) but is never folded
into `pickable`. Since `targetIds` is empty for this prompt, both rendered candidates fail the
`pickable` check and every tap is silently dropped. This is standard 1's "looks complete, silently
does nothing" shape exactly.

**(b) The candidate cards' `name` is upstream's own literal placeholder.** `CardsView`'s
ability-list constructor (`Mage.Common/src/main/java/mage/view/CardsView.java:66-121`) wraps each
triggered ability in an `AbilityView` (`Mage.Common/src/main/java/mage/view/AbilityView.java`),
whose constructor does:

```java
public AbilityView(Ability ability, String sourceName, CardView sourceCard) {
    this.id = ability.getId();
    this.name = "Ability";           // <-- always, for the ordinary battlefield/stack/hand case
    this.sourceName = sourceName;    // private, no public getter
    this.sourceCard = sourceCard;    // public via getSourceCard() — the real permanent/card
    this.rules = new ArrayList<>();
    rules.add(ability.getRule());    // the real trigger text IS here
    ...
}
```

`setName(...)` is only ever called by `CardsView`'s constructor for the emblem/dungeon/plane
special cases (lines 111-118) — never for an ordinary permanent's trigger, which is exactly
Guide of Souls' and Ajani's case. So **upstream itself** sends `name = "Ability"` for both
candidates here; the real identifying name lives in `AbilityView.getSourceCard().name`, a nested
card view our bridge's `GameViewMapper.mapCard()`
(`bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt:190-215`) never looks at — it
reads only `card.name`/`card.rules` off the top-level `CardView`, which is correct for every
ordinary card but wrong for an `AbilityView`. (The trigger's actual rule text *is* already present
in `card.rules` via `ability.getRule()` — only the name is the placeholder.)

## 3. Scope

**In scope**

- **`:bridge`, `GamePromptMapper.target()`** — when `message.targets` is `null` (not merely empty
  — the two are different signals: `PICK_TARGET` always sends a real, possibly-empty `Set`, so
  `null` is unique to the `PICK_ABILITY` shape), default `TargetPrompt.targetIds` to the ids of
  `message.cardsView1` itself. This is the general fix: it makes "everything shown is answerable"
  the rule whenever upstream did not separately narrow the candidate set, rather than special-
  casing this one prompt in the app.
- **`:bridge`, `GameViewMapper.mapCard()`** — detect a `CardView` that is actually an
  `mage.view.AbilityView` (it always is, for this prompt) and use its `getSourceCard()`'s name
  (and, if useful for rendering, its other identifying fields — set code, art) as the displayed
  `GameCardView.name`, instead of the literal `"Ability"`. `rules` already carries the real trigger
  text and needs no change.
- Re-verify `SelectPrompt`/other `TargetPrompt` call sites are unaffected — this touches a shared
  mapper and a shared card-mapping function used by every prompt kind, not just this one.

**Out of scope**

- Any change to how the *order itself* is communicated back to the server. The player answers each
  pick with the same `SendPlayerUuid` `TargetPrompt` already uses (one ability id per tap, the
  server re-prompts with a shrinking candidate set) — this is a rendering/pickability defect, not a
  protocol-shape gap.
- Non-ability `AbilityView`-wrapped objects from the `OUTSIDE`/`COMMAND` zone branches (emblems,
  dungeons, planes) — those already get a real `setName(...)` call upstream and are unaffected.

## 4. Constraints already verified — do not rediscover

- `PICK_ABILITY`'s `target(...)` overload passing `targets = null` is
  `GameController.java:881-885`, read directly — do not re-derive this from the bridge side.
- `AbilityView`'s constructor hardcoding `this.name = "Ability"` for the ordinary case is
  `AbilityView.java` (no line numbers in the fetched source; the whole file is ~40 lines), read
  directly. `setName(...)` exists and is called only for emblems/dungeons/planes.
- The real trigger text is already flowing correctly via `rules` (`ability.getRule()`) — only the
  `name` field is the placeholder. Do not touch `rules` mapping.

## 5. Verification

- **Standard 1**, and the test must discriminate the *actual* bug: a hermetic
  `GamePromptMapper` test asserting that a `GameClientMessage` with `targets = null` and a
  populated `cardsView1` produces a `TargetPrompt` whose `targetIds` equals the `cardsView1` ids —
  not empty. Prove it fails against the unfixed mapper first.
- A `GameViewMapper.mapCard` test (or a mock `AbilityView`, if constructible from Kotlin test code
  against the real `mage.view` classes) asserting the mapped `GameCardView.name` is the source's
  name, not the literal string `"Ability"`.
- **Standard 2 (reachability):** name what produces the `null` targets — `GameController.java`'s
  ability-only `target(...)` overload, called only from the `PICK_ABILITY` case.
- **Hermetic gate**, `bridge/src/test` — both mapper tests above, no live server needed.
- **Live**, if practical: reproduce the original scenario (Guide of Souls + an ETB that fires
  simultaneous own-triggers, e.g. Ajani, Nacatl Pariah) and confirm both the naming and tap fixes.
- **Eyes-on (standard 3) — hand Pete this checklist.** Do **not** drive the UI programmatically.
  1. Get a triggered-ability-order prompt (Guide of Souls + any spell/permanent that adds a second
     simultaneous trigger works, as in the original report).
  2. Confirm each candidate card shows the real source name and its actual trigger text, not
     "ability".
  3. Tap a candidate; confirm it is accepted (the prompt advances/re-prompts for the next pick,
     the same way an ordinary target pick does).
  4. Confirm the final resolution order on the stack matches what was picked.

## 6. Acceptance criteria

- [ ] A `TargetPrompt` built from a `null`-targets, populated-`cardsView1` message has `targetIds`
      covering every candidate — tapping any rendered candidate is accepted.
- [ ] Candidate cards for a triggered-ability-order prompt show the triggering source's real name,
      not the literal "ability" placeholder.
- [ ] The trigger's actual rule text continues to render (already correct — must not regress).
- [ ] No other `TargetPrompt`/`SelectPrompt` call site regresses (the mapper functions touched are
      shared).
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `Mage/src/main/java/mage/game/GameImpl.java:2341` — `checkTriggered()`, where ordering is
  triggered once `abilities.size() > 1`.
- `Mage.Server.Plugins/.../HumanPlayer.java:1507` — `chooseTriggeredAbility`, fires the query.
- `Mage.Server/src/main/java/mage/server/game/GameController.java:194-195, 881-885` — routes
  `PICK_ABILITY` to the ability-only `target(...)` overload; `targets` is always `null` there.
- `Mage.Common/src/main/java/mage/view/CardsView.java:66-121` — builds an `AbilityView` per
  ability, keyed by `ability.getId()`.
- `Mage.Common/src/main/java/mage/view/AbilityView.java` — `name = "Ability"` unconditionally for
  the ordinary case; real source in `getSourceCard()`; real rule text in `rules`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/game/GamePromptMapper.kt:58-74` — `target()`,
  where `targetIds` is built from `message.targets`.
- `bridge/src/main/kotlin/magefree/bridge/mapping/GameViewMapper.kt:190-215` — `mapCard()`, where
  the display name is read.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControls.kt:497-510` — the
  `GamePrompt.Target` branch, where `pickable` is computed from `targetIds`.
- `docs/stories/0057-board-interaction-casting-targeting-cancel.md` — the original `TargetPrompt`
  tap-handling this story's dependency builds on.
