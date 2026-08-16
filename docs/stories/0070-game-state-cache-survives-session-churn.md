# 0070 — The game-state cache must survive a bridge-session change, not just a park

- **Epic:** EPIC-11 — In-Game Play
- **Depends on:** 0054 (bridge game-state cache), 0069 (rejoin a table whose game already started)
- **Status:** ready

## 1. Objective

Fix a defect found live (Pete, 2026-08-16, on-device, on a clean rebuild after 0069 landed): rejoining
an in-progress game sometimes lands on a **wrong-but-plausible** board — the priority banner said
"waiting on opponent" against an AI that had visibly already acted and was doing nothing, which is only
possible if it was actually the viewer's own priority. Not a crash, not an empty board — a board that
looks completely normal and confidently states something false.

## 2. Root cause — confirmed against the pinned XMage source, not assumed

Story 0054 built the bridge's answer to "XMage has no get-game verb": cache the latest snapshot the
bridge relays and serve it back on `GetGameState`. That design is sound for what it targets — a
**parked** session (story 0023), where the pump keeps running and the cache keeps advancing while the
app is merely disconnected. It was never designed to survive the session itself ending.

But `GameStateCache` (bridge/.../session/GameStateCache.kt) is owned one-per-`LiveSession`
(bridge/.../session/SessionRegistry.kt), and `LiveSession.close()` — called by
`SessionRegistry.evict()` — **unconditionally clears it**, in two places: the pump's own `finally` and
`close()` itself ("the cache must not outlive the session it describes"). A session gets evicted for
several reasons that have nothing to do with the game being over: the resume TTL expiring, an explicit
sign-out, or — what actually happened live — the bridge's own upstream keepalive ping failing
(`SessionRegistry.startKeepalive`) and evicting on a transient network hiccup between the bridge and
`xmage-server`. Bridge log from the live run:

```
Registered resumable session b11cdcd8-...
WARN  XMage server error: Server error: Ping failed
WARN  XMage server error: Network error. Can't connect to xmage-server
INFO  XMage session disconnected (askToReconnect=true, keepMySessionActive=true)
INFO  Upstream 4bcff880-... is no longer alive; evicting
INFO  Evicted session 4bcff880-...
```

Whatever the trigger, eviction wipes the cache, and **XMage itself gives a rejoining player no way to
ask for a fresh state** — verified directly against `Mage.Server/src/main/java/mage/server/game/
GameController.java` at the pinned ref (`e0fe4b6f6a`), not inferred from the earlier story's summary:

- `join(userId)`'s rejoin path (`gameSession != null` → `joinType = "rejoined"`) does nothing but log
  and broadcast a chat line. `startGame()`/`init()` — the only code path that pushes a full snapshot —
  only runs once, the very first time a seat is taken.
- `watch(userId)` **explicitly refuses a seated player**: `if (userPlayerMap.containsKey(userId)) { //
  You can't watch a game if you already a player in it \n return false; }`. It is not usable as a
  resync trick for a player.

So a brand-new `LiveSession` for the same real person, after any eviction, starts with a genuinely empty
cache and has no way to fill it except waiting for the *next* organic game event — which, if it is
already the viewer's own priority (exactly the observed case), may never come from the server on its
own. The board is left holding whatever it happened to have — here, a plausible-looking but stale
snapshot from before the disconnect.

## 3. Scope

**In scope**
- **`:bridge`** — move the per-game snapshot cache from being owned by each ephemeral `LiveSession` to
  being held by `SessionRegistry`, keyed by **username** (`Credentials.username`, the identity already
  captured at `Login`), so a new `LiveSession` for the same username is handed the *same* `GameStateCache`
  instance instead of a fresh empty one. `LiveSession.close()`/the pump's `finally` stop clearing it —
  cleanup relies entirely on the cache's own existing per-game `GameOver` eviction (unchanged, already
  correct), not on session lifecycle.
- Preserve the **per-player isolation guarantee** story 0054 exists for: two different usernames watching
  the same game must never see each other's cache, including across this change. (`GameStateCache`
  itself is unchanged; only *which instance* a `LiveSession` is given changes.)
- Update `SessionGameStateCacheTest`'s eviction test to assert the new, intended behaviour (cache
  survives eviction for the same username) instead of the old one (cache dies with the session) — the
  test must change, not just stop failing.

**Out of scope**
- Any change to `GameStateCache`'s own rules (still the latest snapshot verbatim, still dropped on
  `GameOver`, still bounded at `MAX_GAMES`).
- Any attempt to get XMage to resync a rejoining player — confirmed above that no such verb exists.
- The lobby's "Watch" mislabeling for a seated player's own table (0069, already flagged out of scope
  there; unaffected by this story).
- Board-level UI changes. `hasSnapshot`/`PriorityUi.Asked` already exist and are correct (0052/0055) —
  this story is about *what the cache serves*, not how the board renders what it is given.

## 4. Constraints already verified — do not rediscover

- `GameController.join`/`watch` read directly from `Mage.Server/src/main/java/mage/server/game/
  GameController.java` at `e0fe4b6f6a` (§2 above) — neither gives a rejoining seated player a fresh
  push. Do not re-derive this from the bridge side; it was checked at the source.
- The per-username cache is a `GameStateCache` per distinct username ever logged in during the bridge
  process's life, held for the process's lifetime rather than per-session. This is an accepted,
  bounded memory tradeoff (each entry is capped at `GameStateCache.MAX_GAMES` = 8 snapshots) — not a
  leak needing active cleanup, in the same spirit as the project's other "Known issues (accepted, not
  scheduled)" entries. Note it in the PR; do not build session-churn-aware cache eviction to avoid it.
- A `GameStateCache` instance may now be written to by more than one `LiveSession` sequentially (never
  concurrently in the steady state, but story 0026 F4's one-consumer invariant only governs the outbound
  channel, not this). `GameStateCache` is already internally synchronized
  (`Collections.synchronizedMap`) — confirm this remains sufficient; do not add new locking without
  checking it is actually needed.

## 5. Verification

- **Standard 1**, and the test must discriminate the *actual* bug: a session for username X is evicted
  mid-game with a cached snapshot; a **new** `LiveSession` for the same username X must answer
  `GetGameState` with that snapshot, not `GameStateUnavailable`. The existing `evicting the session
  drops its cache` test asserted the opposite and must be rewritten, not deleted (its title changes to
  match what it now proves).
- Also prove: two different usernames never share a cache entry, before and after this change (the
  existing `two sessions in the same game hold their own views` test should still pass unmodified — if
  it does not, the per-username keying is wrong).
- **Standard 2 (reachability):** name what produces the cache instance a `LiveSession` uses —
  `SessionRegistry.createSession`, keyed by `credentials.username`.
- **Hermetic gate**, `bridge/src/test` — extend `SessionGameStateCacheTest`, no live server needed (this
  is bridge-internal wiring, same as the rest of that file).
- **Live**, if practical: reproduce the original symptom — start a match, force the bridge's upstream
  connection to drop (e.g. briefly stop `xmage-server` or block the network path) so the keepalive evicts
  the session, let it reconnect, and confirm the board shows the correct (not stale) priority state on
  reopen. Budget for this being awkward to force reliably; the hermetic test is the primary proof.
- **Eyes-on (standard 3) — hand Pete this checklist.** Do **not** drive the UI programmatically.
  1. Host a table against an AI, start the match, play a turn or two.
  2. Cause a bridge reconnect some way that's convenient to trigger (briefly restarting the bridge
     container is the most reliable; a real network drop works too if one happens naturally).
  3. Reopen the game (via 0069's rejoin path).
  4. Confirm the board's priority banner matches what is actually true server-side — if it is your
     turn, it must say so, not "waiting on opponent".

## 6. Acceptance criteria

- [ ] A new bridge session for a username that already had a cached snapshot for a game answers
      `GetGameState` from that snapshot, not `GameStateUnavailable`.
- [ ] Two different usernames' caches remain fully isolated (existing guarantee, reconfirmed).
- [ ] `LiveSession`/`SessionRegistry` no longer clear the game-state cache on eviction; only `GameOver`
      (per-game, inside `GameStateCache` itself, unchanged) removes an entry.
- [ ] `SessionGameStateCacheTest` reflects the new intended behaviour, not the old one.
- [ ] Pete has completed the eyes-on checklist.

## 7. References

- `bridge/src/main/kotlin/magefree/bridge/session/GameStateCache.kt` — the cache itself, unchanged.
- `bridge/src/main/kotlin/magefree/bridge/session/SessionRegistry.kt` — `LiveSession`, `createSession`,
  `evict`, where the per-session ownership moves to per-username.
- `bridge/src/main/kotlin/magefree/bridge/session/SessionCoordinator.kt` — `Login` handling, the source
  of `Credentials.username`.
- `bridge/src/test/kotlin/magefree/bridge/session/SessionGameStateCacheTest.kt` — the test to extend.
- `mage.server.game.GameController` at `e0fe4b6f6a` — `join`/`watch`, read directly, confirming no
  upstream resync path exists.
- `docs/stories/0054-bridge-game-state-cache.md` — the original design this story extends.
