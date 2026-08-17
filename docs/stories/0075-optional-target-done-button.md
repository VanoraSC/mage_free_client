# 0075 — An optional target prompt needs a real "Done" with zero picks

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0057 (board interaction: casting, targeting, cancel)

## 1. Objective

Fix a defect found live (Pete, 2026-08-17): activated Gideon, Battle-Forged's +2 ability ("Up to one
target creature an opponent controls…") against an opponent with no creatures on board. The prompt
correctly showed `targetIds = []`, `required = false` — there is nothing to pick and nothing is
required — but the board offered no visible, honestly-labeled way to finish. The only button shown
was **"Cancel this cast"**, which reads as aborting the whole ability, not as "proceed with zero
targets" — confirmed from the bridge log that the game only advanced ~1m42s later, once that button
was (uncertainly) pressed.

## 2. Root cause — confirmed from a live bridge log, not assumed

The prompt that arrived, verbatim from the bridge log:

```
TargetPrompt(message=Select up to one creature an opponent controls, cards=[], targetIds=[],
  required=false,
  options=GamePromptOptions(text={UI.right.btn.text=Done, targetZone=BATTLEFIELD, ...}, ids={chosenTargets=[]}))
```

Upstream itself sent `UI.right.btn.text = "Done"` — its own explicit label for finishing this prompt
with the current (here, zero) selections. `controlsFor`'s `GamePrompt.Target` branch
(`feature/game/.../board/BoardControls.kt:497-533`) reads that same field
(`prompt.options.rightButtonText`) but only renders it **inside `if (hasPicked)`** (line 518), and
`hasPicked` (line 504) is `chosen.isNotEmpty() || hasPickedTarget` — false here, since nothing was
picked and nothing needed to be. So the one button upstream explicitly asked for never appears.

The only button that *does* appear is `CANCEL_CAST_LABEL` ("Cancel this cast" — line 528-530), shown
whenever `!prompt.isRequired`, which is true here for an unrelated reason: it exists to let the
player back out of an in-flight *spell cast* (§16.5's rewind). Both buttons happen to send the same
wire message (`SendPlayerBoolean(false)`, per this file's own comment at line 516-517) — so tapping
"Cancel this cast" here *does* work, functionally — but the label is wrong for this situation
(nothing is being cast, and Gideon's ability is already committed; only the optional target step
remains), which is exactly what left Pete unsure whether it was safe to press.

**The general shape of the bug:** any `GamePrompt.Target` where `required = false` is, by definition,
answerable with zero targets — that is what "not required" means for a target count. The current
code treats "may finish with what I have" as *only* reachable after picking something, which is
correct for a **required** prompt (0 is not a legal answer there) but wrong for an **optional** one
(0 is always a legal answer there, from the first moment the prompt arrives).

## 3. Scope

**In scope**
- `controlsFor`'s `GamePrompt.Target` branch: show the right/"Done" button whenever `hasPicked ||
  !prompt.isRequired` — not only `hasPicked`. This makes upstream's own `Done` label appear
  immediately for an optional prompt, with zero targets picked, exactly matching the server's own
  intent for this case.
- Leave the separate `CANCEL_CAST_LABEL` button's own condition (`!prompt.isRequired`) unchanged —
  it still has a real job for the in-flight-cast case story 0057 built it for. This story does not
  merge or relabel the two buttons; it only stops "Done" from being hidden when it is legitimately
  the correct primary action.
- No protocol change — both buttons already send the correct, existing wire message
  (`BoardAction.FinishTargeting`/`BoardAction.CancelPrompt`, both → `SendPlayerBoolean(false)`); this
  is a pure display-condition fix.

**Out of scope**
- Any redesign of when "Cancel this cast" itself should or shouldn't appear, or its label — that is
  story 0057's territory and is not the reported defect (it does work, it's just not the button that
  should have been reached for here).
- Any other prompt kind (`Select`, `Ask`, `ChooseAbility`, etc.) — this is specific to
  `GamePrompt.Target`'s two-button (Done/Cancel) shape.

## 4. Constraints already verified — do not rediscover

- The exact prompt shape, read directly from a live bridge log (not synthesized): `targetIds=[]`,
  `required=false`, `options.text["UI.right.btn.text"] = "Done"`.
- Both `FinishTargeting` and `CancelPrompt` already send the identical wire message
  (`BoardControls.kt:516-517`'s own comment) — confirmed this is an existing, deliberate design, not
  something this story needs to change.
- `hasPicked`'s definition (`BoardControls.kt:504`) is unchanged by this story — only where it is
  read to gate the Done button changes.

## 5. Verification

- **Standard 1**, discriminating test: a `GamePrompt.Target` with `isRequired = false`, empty
  `targetIds`, and a `PromptOptions.rightButtonText` of `"Done"` must produce a `Targeting` control
  set whose buttons include one with `action = BoardAction.FinishTargeting` and `label = "Done"` —
  **before** any pick (`hasPickedTarget = false`). Proven to fail against the unfixed `if (hasPicked)`
  gate first.
- A required prompt with zero picks must still **not** offer Done (0 is not a legal answer there) —
  the existing coverage for the required case must keep passing unmodified.
- **Standard 2 (reachability):** `prompt.options.rightButtonText`/`prompt.isRequired` both come
  straight from the server's own `GamePromptOptions`/`TargetPrompt.required` — no new field, no new
  producer to name.
- **Hermetic gate:** `BoardControlsTest` (existing file, same pattern as its other `GamePrompt.Target`
  cases).
- **Live, if practical:** activate an ability with an optional "up to one" target against a board with
  no legal targets, confirm a correctly-labeled "Done" button appears immediately.

## 6. Acceptance criteria

- [ ] An optional (`required = false`) target prompt offers its "Done"/right button immediately, with
      zero targets picked, using the server's own label when it sent one.
- [ ] A required target prompt's behavior is unchanged — Done still only appears once something is
      picked.
- [ ] `CANCEL_CAST_LABEL`'s own condition and behavior are unchanged.

## 7. References

- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControls.kt:497-533` — the
  `GamePrompt.Target` branch.
- `feature/game/src/test/kotlin/magefree/feature/game/board/BoardControlsTest.kt` — existing
  `GamePrompt.Target` coverage to extend.
- Live bridge log (Pete, 2026-08-17) — the exact `TargetPrompt` that reproduced this, quoted in §2.
- `docs/stories/0057-board-interaction-casting-targeting-cancel.md` — where both buttons' original
  design (and the shared wire message) comes from.
