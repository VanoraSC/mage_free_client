# 0102 — The cast intent contract

- **Epic:** EPIC-20 — Declared Cast Intent
- **Story:** #177
- **Depends on:** Phase 2 step 2a, written up in
  [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md). Read it first; this story does not
  repeat the trace, only what follows from it.

## 1. Objective

`CastIntent` in `:protocol`, and the bridge-side player that plays one back against the real prompt
sequence — tested headlessly against the reference server, **including the bail-out path, which is the
part that matters**.

## 2. Context & background

**This is the only phase whose failure mode is submitting a wrong action to a live game** rather than
looking bad. That is why the contract is built and tested before any UI exists to produce one.

**A cast today is a conversation, not a command.** The trace records the whole of it: one
`GAME_ASK` per optional cost, a `GAME_GET_AMOUNT` for X, a `GAME_SELECT`/target prompt per target,
and then **one `GAME_PLAY_MANA` per mana source tapped**, each carrying only the remaining unpaid
cost. A player casting a kicked spell with X and three lands answers six or seven separate prompts,
each of which the client must recognise from context it does not have.

**The intent inverts that.** The player declares the whole cast once; the bridge holds the declaration
and answers the prompts as they arrive. The client stops needing to know what a mid-cast prompt means,
because it is no longer the thing being asked.

**Three findings from 2a shape the contract, and each is a test:**

- **Engine order is not UI order.** X is announced before targets are chosen; mana is paid before
  sacrifices. An intent recorded in the order a player thinks must be replayed in the order the engine
  asks (§1 of the trace).
- **Special mana payment is one-way.** Once convoke or improvise is used, normal mana abilities are
  locked out for the rest of that cast, and upstream's own remedy is "cancel and recast" (§2.4). An
  intent that mixes them in the wrong order is unplayable, and that must be caught before it is sent.
- **Bailing out is not free.** Cancelling rolls the game back to a bookmark, but mana already produced
  is explicitly excluded — upstream's own comment says so (§2.5). A bail-out therefore has a defined
  end state: tapped permanents stay tapped and floating mana stays floating. That is the assertion the
  bail-out test makes.

**`PlayXManaPrompt` is dead.** `GAME_PLAY_XMANA` is declared, implemented and wired upstream, and
nothing calls it (§2.1). Whatever handles X handles `GetAmountPrompt`.

## 3. Scope

**In scope**
- `CastIntent` in `:protocol`: the object being cast, the alternative and additional costs accepted,
  the X value, the chosen modes and targets, and the payment — mana sources in order, special mana
  actions marked as such.
- The bridge-side player that receives an intent and answers the real prompt sequence from it.
- Replay in **engine order**, whatever order the intent's fields were filled in.
- Rejecting an unplayable intent **before** submitting anything, with the reason, rather than
  discovering it half way through a live cast.
- The bail-out path: what the bridge does when a prompt arrives that the intent has no answer for,
  and what state the game is left in.
- Disconnect mid-playback, defined against 0074's reconnect.

**Out of scope**
- Any UI. 0103 builds the surface that produces an intent; this story is the contract it produces one
  against, and it is finished when a hand-written intent plays a real spell on a real server.
- Computing a *proposed* payment. 2a established that nothing upstream proposes one (§2.3); deciding
  whether the bridge derives one belongs to 0103, where it has a consumer.
- Split, fused and spliced spells, and alternative casting methods that replace the whole cost —
  explicitly not traced in 2a, so explicitly not designed here.

## 4. Prerequisites & toolchain

`:protocol`, `:bridge`. `:bridge` builds and is verified in the container only; the live tests run
against the reference server exactly as `GameRelayIT` does.

## 5. Design & approach

**The contract is a list of answers, not a script of actions.** Every field exists because a prompt in
the trace asks for it, and the mapping between them is one to one. That is what makes "does the intent
cover this cast" answerable before anything is submitted.

**Ordering belongs to the bridge, not to the intent.** The client is free to record decisions in
whatever order it collected them; the bridge replays them in the order §1 of the trace establishes.
Putting the ordering in the wire format would make every client responsible for a rule only the server
knows, which is the mistake this whole phase exists to stop.

**The bail-out is the design, not the error handler.** A prompt arriving that the intent cannot answer
is the expected case for anything the trace did not cover, and it has to leave the game somewhere a
player can carry on from. It is tested first and hardest.

## 6. Implementation steps

1. Re-read `docs/upstream-cast-sequence.md` §1 and §2; the field list comes from the prompts there.
2. `CastIntent` in `:protocol`, with the tolerant-enum discipline the rest of the schema uses.
3. The bridge-side player: hold an intent, answer prompts from it, in engine order.
4. Validate an intent against the special-mana-ordering rule before submitting.
5. The bail-out path and its end state.
6. Live tests against the reference server, cheapest case first.

## 7. Testing & verification

- **Proven failing first (standard 1):** the ordering test — an intent whose fields were filled in UI
  order must still cast correctly — has to fail against a player that replays them in the order they
  were recorded, then pass.
- **Unit (`:protocol`):** an intent round-trips; an unknown field decodes tolerantly.
- **Unit (the bridge player, hermetic):** every prompt in the trace is answered from the right field;
  an intent that uses a special mana action after a land is rejected with its reason.
- **Live against the reference server (standard 5):** a plain creature; a spell with X; a spell with a
  target; a spell with an optional additional cost. Then **the bail-out**: an intent that runs out of
  answers mid-payment, asserting the game is left with the taps and floating mana the trace says it
  will be, and that the session is still usable afterwards.
- **Disconnect mid-playback**, against 0074.
- No eyes-on: this story renders nothing.

## 8. Acceptance criteria

- [ ] `CastIntent` crosses `:protocol` with one field per prompt the trace records.
- [ ] The bridge replays an intent in engine order regardless of the order it was recorded in.
- [ ] An intent that violates the special-mana ordering rule is rejected before anything is submitted.
- [ ] The bail-out path has a defined end state, asserted against a live server.
- [ ] Disconnect mid-playback is defined against 0074.
- [ ] `./gradlew check` passes, and `:bridge` passes in the container.

## 9. References

- [`docs/upstream-cast-sequence.md`](../upstream-cast-sequence.md) — the traced sequence this is built
  against.
- `docs/ui-modernization-plan.md` §7.6, §11 Phase 2.
- `bridge/src/test/kotlin/magefree/bridge/mapping/GameRelayIT.kt` — the live-test harness to extend.
