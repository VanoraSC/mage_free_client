# 0097 — The Prompt: one component, one position, three states

- **Epic:** EPIC-19 — Game Board Rebuild (Phase 1)
- **Depends on:** 0095 (the tokens it renders with).

## 1. Objective

Build the Prompt component §7.2 specifies: **Idle**, **Asking** and **Board-interactive**, in one
fixed position, where the board-interactive state **never blocks the board**.

## 2. Context & background

**The Prompt is where the player learns what the game wants.** §7.2 gives it one component and one
position so that the answer to "what am I being asked?" is always in the same place, and three
states so the same surface can be quiet, insistent, or subordinate to the board:

- **Idle** — whose priority it is, and what phase we are in. Low contrast.
- **Asking** — the server wants a decision. High contrast, thumb-reachable actions, stating the
  question in the server's own words, cleaned of markup.
- **Board-interactive** — the decision needs the board touched (targets, attackers, blockers). The
  Prompt shrinks to a header, progress ("2 of 3 targets") and Confirm/Cancel, and never covers the
  board.

**The hard part is already solved, and it is not this component.** `:feature:game`'s `controlsFor`
translates every `GamePrompt` into the controls that answer it — the labels the server supplied, the
candidate ids for each combat role, whether cancel is legitimate, and the deliberate refusal to
answer `GamePrompt.Unrecognised`. That translation is correct and hard-won. This story does not
reimplement it; it builds the surface a translation like it renders into.

**What exists in the design system is a different shape.** `DecisionPrompt` is a bottom-anchored
surface with full-width choice buttons — a modal decision list. It has no Idle state, no
board-interactive state, and by construction it occupies the bottom of the screen, which is exactly
what §7.2's third state must not do. It stays where it is, serving the old board.

**Landscape is the frame.** Every new surface renders phone landscape, so "thumb-reachable" means
the lower corners, and a full-width bottom bar costs proportionally more of the board than it does in
portrait. The position the Prompt takes is a real design decision, not a detail.

## 3. Scope

**In scope**
- The Prompt component with the three states, rendered from a presentation model.
- Transitions between the states, using 0095's motion tokens.
- Server wording shown as the server wrote it, with markup stripped.
- Server-supplied button labels preferred over hard-coded ones wherever they are present.
- Progress in the board-interactive state ("2 of 3 targets").
- A catalog entry covering all three states, including a long question and a long label.

**Out of scope**
- Wiring the Prompt to a live game. It is built and tested standalone here; the new board mounts it.
- The board-side half of a board-interactive decision — highlights on candidates, targeting arrows,
  tapping a card to choose it. Those belong to the board.
- Any change to `DecisionPrompt`, `controlsFor`, or the existing board. If the old board's behaviour
  changes, the story went wrong.
- A prompt queue. Upstream blocks on the answer, so there is at most one outstanding prompt per seat
  and a new one replaces the old — the domain model already says so and the component follows it.

## 4. Prerequisites & toolchain

Project baseline; `:core:designsystem`.

## 5. Design & approach

**"Never blocks the board" is the requirement to design against first**, because it constrains
everything else: the board-interactive state has a size budget and the layout has to hold inside it.
Designing the Asking state first and then shrinking it is how that requirement gets lost.

**The component is stateless and takes a presentation model**, not a `GamePrompt`. The design system
does not depend on the network module, and keeping the Prompt free of game types is what lets it be
tested without a game — the same split `CardDisplay` already uses for cards.

**Idle is not empty.** It carries priority and phase, which are the two things a player checks
constantly. An Idle state that shows nothing turns every phase question into a hunt elsewhere on the
screen.

**Cancel is offered only where cancelling is real.** Some prompts cannot be declined, and a Cancel
that the server discards is worse than no Cancel. The presentation model therefore carries whether
cancel is available rather than the component assuming it.

## 6. Implementation steps

1. Read §7.2, and read `controlsFor`/`PromptControlsUi` to see what a real translation needs the
   surface to be able to render.
2. Define the presentation model for the three states.
3. Build the component against 0095's tokens, board-interactive state first.
4. Add the state transitions on the motion tokens.
5. Catalog entry for all three states.

## 7. Testing & verification

- **Proven failing first (standard 1):** the test that the board-interactive state stays within its
  size budget must fail against a full-height rendering, then pass.
- **Hermetic Compose (`src/testDebug`):** each state renders its required content; the
  board-interactive state stays within its budget and shows progress; server-supplied labels win over
  defaults; markup is stripped from the question; Cancel is absent when cancelling is not available.
- **The old board is untouched**, asserted by its existing tests passing unedited.
- **Eyes-on:** the catalog in landscape — is the Asking state reachable by thumb, and does the
  board-interactive state leave the board usable.

## 8. Acceptance criteria

- [ ] One Prompt component renders Idle, Asking and Board-interactive from a presentation model.
- [ ] The board-interactive state provably does not cover the board.
- [ ] Server wording and server labels are used where present; markup is stripped.
- [ ] `DecisionPrompt`, `controlsFor` and the existing board are unchanged.
- [ ] `./gradlew check` passes and the catalog shows all three states.

## 9. References

- `docs/ui-modernization-plan.md` §7.2 (the three states), §3.1 (signals), §11 Phase 1.
- `feature/game/src/main/kotlin/magefree/feature/game/board/BoardControls.kt` — the existing
  prompt-to-controls translation this surface is shaped to serve.
- `core/network/src/commonMain/kotlin/magefree/network/game/GamePrompt.kt` — the closed prompt set.
