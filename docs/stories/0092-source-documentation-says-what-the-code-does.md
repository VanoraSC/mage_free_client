# 0092 — Source documentation says what the code does

- **Epic:** none — cross-cutting housekeeping.
- **Depends on:** nothing. Touches no behaviour.

## 1. Objective

Strip story numbers, dated attributions, epic and plan-section references, and self-narration out of
every KDoc and comment in the repository, so a reader learns what the code does and why it must be
that way — and nothing about which ticket produced it.

Then write the rule into `AGENTS.md`, so it does not grow back.

## 2. Context & background

**This is a standing instruction that has been given more than once and is not being followed.**
Documentation states the current behaviour of the software. No editorial, no references to past
versions, no meta-information about which story wrote which line. `docs/` was rewritten to that
standard; the source was not, and every story since has added more.

**The scale, measured rather than estimated:**

| Pattern | Occurrences | Files |
|---|---|---|
| `story NNNN` / `stories NNNN` | 886 | 266 |
| `§N.N` plan-section references | 258 | — |
| `EPIC-NN` references | 32 | — |
| `(Pete, YYYY-MM-DD)` / "found live" | 21 | — |

266 of the repository's 417 Kotlin files carry at least one story reference. The heaviest are
`:core:network` (64 files), `:bridge` (37) and `:app` (28).

**The canonical example**, from `BridgeMageClient`:

> *"Responsibilities in story 0003 are deliberately minimal: … It performs no decoding of callback
> payloads and does no `mage.view.*` mapping — those are stories 0004–0006."*
>
> *"**Thread hand-off (story 0005).** `SessionImpl` invokes these callbacks on JBoss-remoting
> threads…"*

Everything a reader actually needs is in there — the version handshake, the thread hand-off, why
`tryEmit` — wrapped in a frame that says *this class is a phase of a plan*. It is not; it is the
shipping callback sink. A reader has to strip the scaffolding out for themselves before they can
trust a word of it.

**Why it actively costs, beyond being noise:**

- **It goes stale and then it lies.** "Those are stories 0004–0006" describes a future that has since
  happened. Nothing rechecks these sentences, so they drift from true to false in silence.
- **It sends readers to the wrong place.** A `§7.4` reference points into a plan document that is
  itself edited to current state; when the numbering moves, the pointer rots.
- **It is a second copy of history.** Git already records which commit introduced a line, and the
  story documents already record why the work was scheduled. A third copy in the source only rots.

**What must survive, because it is the valuable half.** The *why* — "we do X because upstream does Y",
"`orEmpty()` is load-bearing because upstream only allocates the list here", "the order is meaningless
because `Counters` extends `HashMap`". Every one of those is current fact about the code, and this
story must not strip a single one. The failure mode to avoid is a cleanup that leaves behind KDoc
saying only what the signature already says.

## 3. Scope

**In scope**
- Every `.kt` and `.kts` file: KDoc, block comments and line comments.
- Removing: story numbers, `EPIC-NN`, `§N.N` plan-section pointers, dated attributions, and
  narration about the author's choices ("deliberately", "on purpose", "worth knowing", "this is the
  whole point", "found live", "the first version of this…").
- Rewriting each affected sentence in the present tense so it reads as though it had always been so —
  not deleting the sentence when it carries a fact.
- A new **Documentation** section in `AGENTS.md`'s coding conventions stating the rule.

**Out of scope**
- `docs/`. The story documents are exactly where story numbers belong, and `docs/stories/README.md`
  is a tracker.
- Commit messages and PR descriptions. Chronology is correct there and wanted.
- Test **names**. A test name describes the behaviour under test; where one currently carries a story
  number, drop the number and keep the sentence.
- Any behaviour change. This story changes comments and one markdown file.

## 4. Prerequisites & toolchain

Project baseline; `:bridge` in-container per `docs/build-environment.md`.

## 5. Design & approach

**Rewrite, do not delete.** A blanket regex that strips `(story NNNN)` and leaves everything else
would pass a grep check and destroy nothing — but that is not the job. Most of these sentences are
*framed* by the story reference: "Story 0086: `targets` is what a spell on the stack points at" has
to become "`targets` is what a spell on the stack points at". Some need more than that, because the
frame is doing real work in the sentence ("Responsibilities in story 0003 are deliberately minimal"
has no residue worth keeping and the paragraph must be re-written around it).

So this is a **file-by-file editorial pass**, not a `sed`. Automation's only role is finding the
files and proving none was missed.

**Work module by module, one commit per module.** 266 files is too much for one reviewable diff. The
order below puts the worst first, so if the pass is interrupted the most-read code is already clean:

1. `:bridge` (37 files) — the mapper KDoc is the densest in the repo.
2. `:core:network` (64) — the largest.
3. `:protocol` (9) — the wire contract, read by everyone.
4. `:core:cards`, `:core:decks`, `:core:model` (51).
5. `:app` and `:feature:*` (79).
6. `build-logic` and the `.kts` build files (12).

**The plan-section pointers need a decision, not a deletion.** `§7.4` and friends point at
`ui-modernization-plan.md`, and some carry information the code genuinely depends on. Replace the
pointer with the *fact* it stands for: "§7.4's piling rule" becomes "a permanent carrying an
attachment never piles". Where the reference is decorative, it goes.

**Verification standards references stay.** "verification standard 2 (reachability)" names a
project-wide engineering rule that is current, is documented in `docs/stories/README.md`, and does not
rot — that is a live cross-reference, not history. Keep those, and keep the reachability paragraphs
themselves: they are the most useful documentation in the mapping layer.

**`AGENTS.md` gets the rule**, in *Coding conventions*, next to naming and style:

> **Documentation states current behaviour.** KDoc and comments say what the code does and why it must
> be that way, in the present tense. No story or epic numbers, no plan-section pointers, no dated
> attributions, no narration about how the code came to be. Git and the story documents already hold
> that, and a second copy in the source rots. Keep the rationale; drop the chronology.

## 6. Implementation steps

1. Add the rule to `AGENTS.md` **first**, so the pass has something to check against.
2. Per module, in the order in §5: read each flagged file, rewrite the affected KDoc, commit.
3. After each module, run its `check` task — a comment-only change must not move a single test.
4. Finish with the repo-wide grep in §7 and account for every remaining hit.

## 7. Testing & verification

- **The grep is the acceptance test.** After the pass, these must all return nothing outside `docs/`:

  ```bash
  grep -rEni "story [0-9]{4}|stories [0-9]{4}|EPIC-[0-9]+" --include=*.kt --include=*.kts .
  grep -rEni "\(Pete, 20[0-9]{2}-|found live" --include=*.kt --include=*.kts .
  grep -rEn "§[0-9]" --include=*.kt --include=*.kts .
  ```

  A surviving hit is allowed only with a one-line justification in the PR. "Verification standard N"
  is expected to survive and is not matched by any of the above.
- **Nothing may change but comments.** `git diff --stat` per commit should touch only `.kt`/`.kts`
  files, and `git diff -w --ignore-blank-lines` restricted to non-comment lines must be empty. State
  in the PR how that was checked.
- **The full suite must be identical, not merely green.** Record the executed test count before and
  after and confirm it matches: `./gradlew check` plus `:bridge:check` with `XMAGE_SERVER` set.
- **A sampled read-back.** Pick ten of the rewritten files at random and confirm each still explains
  *why*, not just *what*. A cleanup that turned good KDoc into a restatement of the signature has done
  more damage than the story numbers ever did — this is the check that catches it, and it is the one
  worth doing carefully.
- **Eyes-on:** none. No behaviour changes.

## 8. Acceptance criteria

- [ ] The three greps in §7 return nothing under `.kt`/`.kts`, or every survivor is justified.
- [ ] `AGENTS.md` carries the rule in *Coding conventions*.
- [ ] Every diff hunk is a comment; no production or test statement changed.
- [ ] The executed test count is unchanged, and `./gradlew check` and `:bridge:check` both pass.
- [ ] The sampled read-back confirms the rationale survived the pass.

## 9. References

- `AGENTS.md` — *Coding conventions*, where the rule lands.
- `bridge/src/main/kotlin/magefree/bridge/xmage/BridgeMageClient.kt` — the worked example in §2.
- `docs/stories/README.md` — *Verification standards*, the one kind of cross-reference that stays.
