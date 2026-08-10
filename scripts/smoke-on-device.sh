#!/usr/bin/env bash
# Story 0048 — on-device smoke for the mage-free client.
#
# Drives the **real debug APK** on a connected emulator/device against the **real bridge** and a real
# XMage server, through the playable path a user actually walks:
#
#   1. cold launch -> add a server -> sign in                      (:feature:connect)
#   2. lobby, populated from the live server                       (:feature:lobby)
#   3. decks WITH THE DEVICE OFFLINE: create, search, add, legality(:feature:decks + :core:cards)
#   4. host a table -> seats fill -> start -> match starting       (:feature:tables)
#   5. sign out -> back to the Servers screen                      (0046 + 0047)
#
# It asserts on-screen content at every step and fails loudly and specifically: what it expected, what
# was actually on screen, plus a screenshot and the raw UI dump of the failing moment.
#
# See docs/build-environment.md ("On-device smoke") for prerequisites and how to read a run.
#
# Usage (from the repo root):
#   ./scripts/smoke-on-device.sh                       # build nothing, install app-debug.apk, run all
#   ./scripts/smoke-on-device.sh --serial emulator-5554 --out build/smoke
#   ./scripts/smoke-on-device.sh --host 10.0.2.2 --port 8080
#   ./scripts/smoke-on-device.sh --apk path/to/app-debug.apk --keep-app
#
# Exit code 0 = every step passed. Non-zero = a named step failed (see the message and $OUT).

set -uo pipefail

# Git Bash (MSYS) rewrites arguments that look like Unix paths — `/sdcard/ui.xml` becomes
# `C:/Program Files/Git/sdcard/ui.xml` and every `adb shell` command silently targets the wrong file.
# Turn that off; harmless on Linux/macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------------------------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------------------------------

SERIAL="${ANDROID_SERIAL:-}"
OUT="$ROOT/build/smoke"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
BRIDGE_HOST="10.0.2.2"   # the emulator's alias for the host machine, where the bridge publishes :8080
BRIDGE_PORT="8080"
SKIP_INSTALL=0
KEEP_APP=0
PACKAGE="magefree.app"
ACTIVITY="$PACKAGE/.MainActivity"

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --host) BRIDGE_HOST="$2"; shift 2 ;;
    --port) BRIDGE_PORT="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --keep-app) KEEP_APP=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB="adb"
  elif [ -n "${LOCALAPPDATA:-}" ] && [ -x "$(cygpath -u "${LOCALAPPDATA}" 2>/dev/null)/Android/Sdk/platform-tools/adb.exe" ]; then
    ADB="$(cygpath -u "${LOCALAPPDATA}")/Android/Sdk/platform-tools/adb.exe"
  elif [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  else
    echo "adb not found: put it on PATH or set ADB=/path/to/adb" >&2
    exit 2
  fi
fi

mkdir -p "$OUT"
LOG="$OUT/smoke.log"
: > "$LOG"

# ---------------------------------------------------------------------------------------------------
# Plumbing
# ---------------------------------------------------------------------------------------------------

STEP_NO=0
STEP_NAME="startup"
UI="$OUT/.ui.xml"

log() { printf '%s\n' "$*" | tee -a "$LOG"; }
note() { printf '    %s\n' "$*" | tee -a "$LOG"; }

adbx() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

# A path the adb *binary* can open. On Git Bash the binary is a Windows program, and MSYS_NO_PATHCONV
# (which we need so on-device paths survive) also stops host paths being translated for it.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

step() {
  STEP_NO="$1"
  STEP_NAME="$2"
  log ""
  log "== step $1 · $2"
}

pass() { log "   PASS: $*"; }

# Fail loudly and specifically: name the step, what was expected, and dump what was actually on screen
# next to a screenshot of it. Nothing about a failure should require a re-run to diagnose.
fail() {
  local expected="$1"
  log ""
  log "!! FAILED at step $STEP_NO ($STEP_NAME)"
  log "   expected: $expected"
  log "   actually on screen:"
  local actual
  actual="$(screen_labels)"
  if [ -z "$actual" ]; then
    log "     (no text on screen — the UI dump was empty)"
  else
    printf '%s\n' "$actual" | sed 's/^/     · /' | tee -a "$LOG"
  fi
  shot "FAILED-$STEP_NAME"
  cp "$UI" "$OUT/$(printf '%02d' "$STEP_NO")-FAILED-$STEP_NAME.xml" 2>/dev/null || true
  log ""
  log "   evidence: $OUT"
  exit 1
}

# A failed expectation that does NOT stop the run: record it, keep the evidence, keep going, and make
# the whole run exit non-zero at the end. Used where the remaining steps are still worth measuring —
# a smoke that aborts on the first defect hides every defect behind it. It never softens an
# expectation: a recorded defect fails the run exactly as `fail` does.
DEFECTS=0
DEFECT_LIST=""
defect() {
  local what="$1" expected="$2"
  DEFECTS=$((DEFECTS + 1))
  log ""
  log "!! DEFECT $DEFECTS at step $STEP_NO ($STEP_NAME): $what"
  log "   expected: $expected"
  log "   actually on screen:"
  screen_labels | sed 's/^/     · /' | tee -a "$LOG"
  shot "DEFECT-$STEP_NAME"
  cp "$UI" "$OUT/$(printf '%02d' "$SHOT_NO")-DEFECT-$STEP_NAME.xml" 2>/dev/null || true
  DEFECT_LIST="$DEFECT_LIST
  $DEFECTS. [step $STEP_NO · $STEP_NAME] $what
       expected: $expected"
  log ""
}

# Assert without aborting: PASS, or record a defect and carry on.
check() {
  local pattern="$1" what="$2" timeout="${3:-15}"
  local deadline=$((SECONDS + timeout))
  while [ $SECONDS -lt $deadline ]; do
    refresh_ui || { sleep 1; continue; }
    if screen_has "$pattern"; then pass "$what"; return 0; fi
    sleep 1
  done
  defect "$what" "a screen matching /$pattern/ within ${timeout}s"
  return 1
}

SHOT_NO=0
shot() {
  SHOT_NO=$((SHOT_NO + 1))
  local name
  name="$(printf '%02d' "$SHOT_NO")-$1"
  adbx exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  note "screenshot: $name.png"
}

# ---------------------------------------------------------------------------------------------------
# Reading the screen
#
# uiautomator's dump is the accessibility tree, which is what Compose publishes its semantics into.
# Nodes are located by **visible text**, never by content description: Material3's NavigationBarItem
# and the lobby's icon-only actions publish no contentDescription on their merged node, so a
# description-based lookup matches nothing (story 0048 §3a).
# ---------------------------------------------------------------------------------------------------

refresh_ui() {
  local attempt
  for attempt in 1 2 3; do
    if adbx shell uiautomator dump /sdcard/mage-smoke-ui.xml >/dev/null 2>&1 &&
      adbx exec-out cat /sdcard/mage-smoke-ui.xml 2>/dev/null | tr -d '\r' | sed 's/></>\n</g' > "$UI" &&
      [ -s "$UI" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Every visible label on screen, one per line (text first, then content descriptions, de-duplicated).
screen_labels() {
  {
    grep -o 'text="[^"]*"' "$UI" 2>/dev/null | sed 's/^text="//; s/"$//'
    grep -o 'content-desc="[^"]*"' "$UI" 2>/dev/null | sed 's/^content-desc="//; s/"$//'
  } | grep -v '^$' | sed 's/&amp;/\&/g; s/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g' | awk '!seen[$0]++'
}

screen_has() { screen_labels | grep -Eq -- "$1"; }

# Wait until $1 (an extended regex over the visible labels) appears, or fail after $2 seconds.
wait_for() {
  local pattern="$1" timeout="${2:-20}" what="${3:-$1}"
  local deadline=$((SECONDS + timeout))
  while [ $SECONDS -lt $deadline ]; do
    refresh_ui || { sleep 1; continue; }
    if screen_has "$pattern"; then return 0; fi
    sleep 1
  done
  refresh_ui || true
  fail "$what (waited ${timeout}s; matched /$pattern/ nowhere on screen)"
}

expect() {
  refresh_ui || fail "a readable UI dump"
  screen_has "$1" || fail "${2:-a screen matching /$1/}"
  pass "${2:-/$1/}"
}

# ---------------------------------------------------------------------------------------------------
# Touching the screen
# ---------------------------------------------------------------------------------------------------

# Bounds of the Nth node whose visible text is exactly $1 (default: the first).
bounds_of_text() {
  local text="$1" nth="${2:-1}"
  grep -F "text=\"$text\"" "$UI" | sed -n "${nth}p" |
    grep -o 'bounds="\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]"' | head -1
}

# Bounds of the Nth node whose visible text *contains* $1 (an extended regex).
bounds_matching() {
  local pattern="$1" nth="${2:-1}"
  grep -E "text=\"[^\"]*$pattern[^\"]*\"" "$UI" | sed -n "${nth}p" |
    grep -o 'bounds="\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]"' | head -1
}

center_of() {
  printf '%s' "$1" |
    sed 's/bounds="\[\([0-9-]*\),\([0-9-]*\)\]\[\([0-9-]*\),\([0-9-]*\)\]"/\1 \2 \3 \4/' |
    awk '{ printf "%d %d", ($1 + $3) / 2, ($2 + $4) / 2 }'
}

tap_at() { adbx shell input tap "$1" "$2" >/dev/null; sleep 1; }

# Tap the node with exactly this visible text. Always re-reads the screen first: Compose relayouts
# (a floating label rising, an error line disappearing) move things between one action and the next,
# and a stale coordinate is how a smoke test silently taps the wrong control.
tap_text() {
  local text="$1" nth="${2:-1}"
  refresh_ui || fail "a readable UI dump before tapping '$text'"
  local b
  b="$(bounds_of_text "$text" "$nth")"
  [ -n "$b" ] || fail "a tappable '$text' (occurrence $nth) on screen"
  tap_at $(center_of "$b")
}

tap_matching() {
  local pattern="$1" nth="${2:-1}"
  refresh_ui || fail "a readable UI dump before tapping /$pattern/"
  local b
  b="$(bounds_matching "$pattern" "$nth")"
  [ -n "$b" ] || fail "a tappable control matching /$pattern/ on screen"
  tap_at $(center_of "$b")
}

# True when a node with exactly this text is on screen (no failure if absent).
has_text() { refresh_ui || return 1; [ -n "$(bounds_of_text "$1")" ]; }

# Tap the *lowest* node with this text. The four primary-navigation tabs repeat the labels the Home
# hub uses for its secondary entries ("Decks", "Settings", …), and the tab is always the bottom one.
# Matched by position and text rather than by the icon's content description, which Material3 does not
# publish on the merged NavigationBarItem node (story 0048 §3a).
tap_tab() {
  local label="$1"
  refresh_ui || fail "a readable UI dump before selecting the $label tab"
  local b
  b="$(grep -F "text=\"$label\"" "$UI" |
    grep -o 'bounds="\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]"' | tail -1)"
  [ -n "$b" ] || fail "a '$label' primary-navigation tab on screen"
  tap_at $(center_of "$b")
}

# Bounds of the node carrying this content description. Reserved for icon-only controls that publish
# no text at all: unlike Compose's own semantics tree, uiautomator's accessibility tree does surface
# contentDescription for these, and there is no other handle on them.
tap_described() {
  local desc="$1"
  refresh_ui || fail "a readable UI dump before tapping '$desc'"
  local b
  b="$(grep -F "content-desc=\"$desc\"" "$UI" |
    grep -o 'bounds="\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]"' | head -1)"
  [ -n "$b" ] || fail "a control described '$desc' on screen"
  tap_at $(center_of "$b")
}

# Scroll the current screen up until this exact text is on it. Long forms (the host form) run well past
# one viewport, and how far depends on the device, so scroll until the target is found rather than
# guessing an offset.
scroll_to_text() {
  local text="$1" attempts="${2:-8}"
  local i=0
  while [ $i -lt "$attempts" ]; do
    has_text "$text" && return 0
    adbx shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 72 / 100)) \
      $((SCREEN_W / 2)) $((SCREEN_H * 32 / 100)) 350 >/dev/null
    sleep 1
    i=$((i + 1))
  done
  has_text "$text"
}

SCREEN_W=1080
SCREEN_H=2400
read_screen_size() {
  local size
  size="$(adbx shell wm size 2>/dev/null | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1)"
  if [ -n "$size" ]; then
    SCREEN_W="${size%x*}"
    SCREEN_H="${size#*x}"
  fi
  note "screen: ${SCREEN_W}x${SCREEN_H}"
}

# Tap the one clickable node inside a rectangle, given as screen fractions.
#
# Needed for the deck builder's "Add cards" button: it is an extended FAB whose label is plainly
# visible but which publishes **no text and no content description** in the accessibility tree, so
# there is nothing to match on (recorded as an observation in the run summary — the fix belongs to the
# control, not to this script). Requiring *exactly one* candidate keeps it honest: if the layout ever
# changes, this fails loudly with the list of candidates instead of tapping something arbitrary.
tap_only_clickable_in() {
  local x1f="$1" y1f="$2" x2f="$3" y2f="$4" what="$5"
  refresh_ui || fail "a readable UI dump before tapping $what"
  local x1 y1 x2 y2
  x1=$((SCREEN_W * x1f / 100)); y1=$((SCREEN_H * y1f / 100))
  x2=$((SCREEN_W * x2f / 100)); y2=$((SCREEN_H * y2f / 100))
  local candidates
  candidates="$(grep 'clickable="true"' "$UI" |
    grep -o 'bounds="\[[0-9-]*,[0-9-]*\]\[[0-9-]*,[0-9-]*\]"' |
    sed 's/bounds="\[\([0-9-]*\),\([0-9-]*\)\]\[\([0-9-]*\),\([0-9-]*\)\]"/\1 \2 \3 \4/' |
    awk -v x1="$x1" -v y1="$y1" -v x2="$x2" -v y2="$y2" \
      '$1 >= x1 && $2 >= y1 && $3 <= x2 && $4 <= y2 { print }')"
  local count
  count="$(printf '%s\n' "$candidates" | grep -c '[0-9]')"
  if [ "$count" != "1" ]; then
    note "candidates in region [$x1,$y1][$x2,$y2]: ${candidates:-<none>}"
    fail "exactly one clickable control in the region holding $what, found $count"
  fi
  tap_at $(printf '%s' "$candidates" | awk '{ printf "%d %d", ($1 + $3) / 2, ($2 + $4) / 2 }')
}

ime_shown() {
  adbx shell dumpsys input_method 2>/dev/null | tr -d '\r' |
    grep -o 'mInputShown=[a-z]*' | head -1 | grep -q 'true'
}

# Dismiss the soft keyboard before tapping anything bottom-anchored (story 0048 §3a): the sign-in and
# server screens do NOT resize for the IME, so a bottom button keeps reporting bounds that are behind
# the keyboard, and the tap lands on the IME instead — no UI change, no logs, no bridge contact, and a
# very convincing false "dead button".
#
# BACK, not ESCAPE: in Compose, ESCAPE dismisses the *dialog*, so using it here silently throws away
# the form you just filled in.
hide_ime() {
  local attempt
  for attempt in 1 2 3; do
    ime_shown || return 0
    adbx shell input keyevent 4 >/dev/null
    sleep 1
  done
  ime_shown && note "warning: the soft keyboard is still up after three attempts to dismiss it"
  return 0
}

# Focus the text field carrying this floating label, then type. The label is rendered *inside* the
# field's bounds (Material3 outlined fields), so tapping the label focuses the field.
type_into() {
  local label="$1" value="$2"
  tap_text "$label"
  adbx shell input text "$value" >/dev/null
  sleep 1
}

# ---------------------------------------------------------------------------------------------------
# Device state
# ---------------------------------------------------------------------------------------------------

airplane_mode() {
  # `cmd connectivity airplane-mode` is the supported API-30+ path and, unlike `settings put`, actually
  # tells the radio stack. Belt and braces with svc so Wi-Fi/data are down on every image.
  adbx shell cmd connectivity airplane-mode "$1" >/dev/null 2>&1
  if [ "$1" = "enable" ]; then
    adbx shell svc wifi disable >/dev/null 2>&1
    adbx shell svc data disable >/dev/null 2>&1
  else
    adbx shell svc wifi enable >/dev/null 2>&1
    adbx shell svc data enable >/dev/null 2>&1
  fi
  sleep 4
}

restore_network() {
  if [ "${NETWORK_IS_OFF:-0}" = "1" ]; then
    note "restoring connectivity"
    airplane_mode disable
    NETWORK_IS_OFF=0
  fi
}
trap restore_network EXIT

# ---------------------------------------------------------------------------------------------------
# Identity — unique per run, so a run never collides with a previous one's server-side state.
# XMage validates usernames against [a-z0-9_]{3,14}; keep well inside that.
# ---------------------------------------------------------------------------------------------------

RUN_ID="$(date +%H%M%S)$(printf '%02d' $((RANDOM % 100)))"
USERNAME="smoke$RUN_ID"
DECK_NAME="smokedeck$RUN_ID"

# A card name every XMage catalog carries, used to prove name search reaches the bundled catalog.
CARD_SEARCH_TERM="Forest"
# Copies added offline. 40 is the smallest deck the server accepts for the construction below, so the
# same deck that proves the offline builder also gets the host's seat accepted.
DECK_COPIES=40
# The one construction whose legality the app defers to the server ("Format not recognised offline —
# the server checks legality when you sit down"); every other preset gates Create on a tournament-legal
# deck, which cannot be assembled by tapping in a smoke.
HOST_DECK_TYPE="Limited"
HOST_OPPONENT="AI (Mad)"

log "mage-free on-device smoke"
log "  device:   ${SERIAL:-<default>}"
log "  bridge:   $BRIDGE_HOST:$BRIDGE_PORT (insecure ws)"
log "  identity: $USERNAME"
log "  deck:     $DECK_NAME"
log "  evidence: $OUT"

# ---------------------------------------------------------------------------------------------------
# Step 0 — a cold, clean install. Idempotent: every run starts from no app data at all.
# ---------------------------------------------------------------------------------------------------

step 0 "cold-install"
adbx get-state >/dev/null 2>&1 || { log "!! no device: adb get-state failed"; exit 1; }
adbx shell input keyevent 82 >/dev/null 2>&1   # wake + dismiss the keyguard
adbx shell wm dismiss-keyguard >/dev/null 2>&1

if [ "$SKIP_INSTALL" = "1" ]; then
  note "--skip-install: reusing the installed app, clearing its data"
  adbx shell pm clear "$PACKAGE" >/dev/null 2>&1 || true
else
  [ -f "$APK" ] || { log "!! APK not found: $APK (build it with ./gradlew :app:assembleDebug)"; exit 1; }
  note "uninstalling any previous $PACKAGE"
  adbx uninstall "$PACKAGE" >/dev/null 2>&1 || true
  note "installing $APK"
  adbx install -r -g "$(native_path "$APK")" 2>&1 | tail -1 | sed 's/^/    /' | tee -a "$LOG"
  adbx shell pm path "$PACKAGE" >/dev/null 2>&1 || { log "!! install failed"; exit 1; }
fi
pass "clean install of $PACKAGE"

# ---------------------------------------------------------------------------------------------------
# Step 1 — cold launch -> sign in.
#
# The entry policy (story 0047): a launch with no session lands on the connect flow, chrome-free.
# ---------------------------------------------------------------------------------------------------

step 1 "sign-in"
adbx shell am start -W -n "$ACTIVITY" >/dev/null 2>&1
sleep 3

wait_for '^Servers$' 30 "the chrome-free Servers screen on cold launch (story 0047's entry policy)"
if screen_has '^(Home|Decks|Profile|Settings)$'; then
  fail "sign-in to render WITHOUT the shell's tab chrome, but a top-level tab is visible"
fi
pass "cold launch lands on Servers, chrome-free"
shot "servers-empty"

log "   adding $BRIDGE_HOST:$BRIDGE_PORT"
tap_text "Add server"
wait_for '^Host$' 10 "the add-server form"
type_into "Host" "$BRIDGE_HOST"
type_into "Port" "$BRIDGE_PORT"
hide_ime
shot "add-server"
tap_text "Save"

wait_for "$BRIDGE_HOST:$BRIDGE_PORT" 10 "the saved server listed on the Servers screen"
pass "server $BRIDGE_HOST:$BRIDGE_PORT saved and listed"
shot "servers-saved"

tap_matching "$BRIDGE_HOST:$BRIDGE_PORT"
wait_for '^Sign in$' 10 "the sign-in screen for the selected server"
type_into "Username" "$USERNAME"
hide_ime
shot "sign-in-form"

tap_text "Connect"
# The bridge has to reach XMage, authenticate, and fetch the room; be generous but bounded.
wait_for '^Connected to ' 90 "'Connected to …' after Connect (the bridge accepted the session)"
pass "signed in as $USERNAME"
shot "connected"

tap_text "Continue"
wait_for '^Play$' 20 "the app shell's Home hub after continuing from a live session"
if screen_has '^Retry$'; then
  fail "the shell to show a live connection, but the status strip is offering Retry"
fi
expect '^Connected$' "the shell's connection strip reads Connected (no Retry)"
pass "the shell is up with a live session"
shot "shell-home"
read_screen_size

# ---------------------------------------------------------------------------------------------------
# Step 2 — the lobby, populated by the live server.
#
# The assertions deliberately name *server-sourced* facts: a player count only the room can supply, and
# the format filters, which are the game types this server actually offers. Static chrome would pass a
# weaker test with no server attached at all.
# ---------------------------------------------------------------------------------------------------

step 2 "lobby"
tap_text "Play"
wait_for '^Lobby$' 20 "the lobby browser behind Home's Play entry"
check '[0-9]+ players in lobby' "the lobby reports a live player count from the server room" 25
check '^Two Player Duel$' "the server's game types are offered as format filters" 10
check '^Showing [0-9]+ of [0-9]+$' "the lobby's table list reports its size" 10
note "lobby says: $(screen_labels | grep -E 'players in lobby|Showing [0-9]+ of' | paste -sd' · ' -)"
shot "lobby"
tap_described "Back"
wait_for '^Play$' 15 "Home again after leaving the lobby"

# ---------------------------------------------------------------------------------------------------
# Step 3 — decks, WITH THE DEVICE OFFLINE.
#
# The deck feature's core promise is that everything except art is served locally. Nothing has ever
# tested it against a real device with no network, which is exactly what this step does: airplane mode
# on, build a deck, read the catalog, watch legality recompute, airplane mode off.
# ---------------------------------------------------------------------------------------------------

step 3 "decks-offline"
note "putting the device offline"
airplane_mode enable
NETWORK_IS_OFF=1
if [ "$(adbx shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r\n ')" != "1" ]; then
  fail "the device to be in airplane mode before the offline deck step"
fi
if adbx shell ping -c 1 -W 2 "$BRIDGE_HOST" >/dev/null 2>&1; then
  fail "the bridge host $BRIDGE_HOST to be unreachable from the device while offline"
fi
pass "device is offline: airplane mode on, $BRIDGE_HOST unreachable"
shot "offline"

tap_tab "Decks"
wait_for '^New deck$' 20 "the deck library on the Decks tab (offline)"
pass "the deck library opens with no network"

tap_text "New deck"
wait_for '^Deck name$' 10 "the new-deck form"
type_into "Deck name" "$DECK_NAME"
tap_text "Standard"
hide_ime
tap_text "Create"

wait_for "^$DECK_NAME$" 20 "the builder open on the new deck '$DECK_NAME' (created offline)"
check '^• Deck has 0 cards; needs at least 60\.$' "offline legality already reports the empty deck as illegal for Standard"
shot "deck-created-offline"

log "   opening the card catalog (offline)"
tap_only_clickable_in 55 75 100 92 "the builder's 'Add cards' button"
wait_for '^Search cards to add$' 20 "the offline card catalog's add-cards surface"

# Search the catalog by name — the way a player finds a card they have in mind.
log "   searching the catalog for '$CARD_SEARCH_TERM'"
type_into "Search cards to add" "$CARD_SEARCH_TERM"
sleep 2
if ! check "^Showing [1-9][0-9]*$" "searching the catalog for '$CARD_SEARCH_TERM' returns matches" 12; then
  note "the search field kept the placeholder: no character reached it (IME shown=$(ime_shown && echo yes || echo no))"
fi
hide_ime

# Browsing by filter is the other half of the catalog contract, and it is what this run uses to fill
# the deck when name search does not answer.
log "   browsing the catalog by filter (offline)"
tap_text "Green"
check '^Showing [1-9][0-9]*$' "filtering the offline catalog returns cards" 20
shot "catalog-offline"

log "   adding $DECK_COPIES copies to the deck (offline)"
refresh_ui || fail "a readable UI dump before adding cards"
ADD_BOUNDS="$(bounds_of_text "Add to main")"
[ -n "$ADD_BOUNDS" ] || fail "an 'Add to main' action on the first catalog result"
ADD_XY="$(center_of "$ADD_BOUNDS")"
i=0
while [ $i -lt "$DECK_COPIES" ]; do
  adbx shell input tap $ADD_XY >/dev/null
  i=$((i + 1))
done
sleep 2

tap_described "Back to deck"
wait_for "^$DECK_COPIES main · 0 sideboard$" 20 "the deck to hold $DECK_COPIES cards after adding them offline"
check "^• Deck has $DECK_COPIES cards; needs at least 60\.$" "offline legality recomputed against the new card count"
check '^Legality$' "the legality panel is present"
pass "deck built, catalog read and legality evaluated with no network at all"
shot "deck-built-offline"

note "restoring connectivity"
airplane_mode disable
NETWORK_IS_OFF=0
adbx shell ping -c 1 -W 4 "$BRIDGE_HOST" >/dev/null 2>&1 ||
  note "warning: $BRIDGE_HOST still not answering ping right after airplane mode off"

# ---------------------------------------------------------------------------------------------------
# Step 4 — host a table, all the way to match start.
#
# First the session is *probed*, not trusted: the connection strip is a client-side view and the lobby
# is the cheapest real server round-trip there is. A strip that says Connected over a session the
# server has dropped is exactly the failure this probe is here to name.
# ---------------------------------------------------------------------------------------------------

step 4 "host-a-table"
tap_tab "Home"
wait_for '^Play$' 20 "Home after coming back online"
tap_text "Play"
wait_for '^Lobby$' 20 "the lobby, to probe the session against the server"

if ! check '[0-9]+ players in lobby' "the session still reaches the server after the offline step" 30; then
  note "the session did not survive; signing in again so the hosting path can still be measured"
  tap_described "Back"
  wait_for '^Play$' 15 "Home"
  tap_tab "Settings"
  wait_for '^Sign out$' 15 "the Settings destination"
  tap_text "Sign out"
  wait_for '^Servers$' 20 "the Servers screen after signing out"
  USERNAME="${USERNAME}b"
  tap_matching "$BRIDGE_HOST:$BRIDGE_PORT"
  wait_for '^Sign in$' 10 "the sign-in screen"
  type_into "Username" "$USERNAME"
  hide_ime
  tap_text "Connect"
  wait_for '^Connected to ' 90 "'Connected to …' on the recovery sign-in as $USERNAME"
  tap_text "Continue"
  wait_for '^Play$' 20 "the shell after the recovery sign-in"
  tap_text "Play"
  wait_for '[0-9]+ players in lobby' 30 "a live lobby after the recovery sign-in"
fi

tap_described "Host a table"
wait_for '^Table name$' 20 "the host-a-table form"
shot "host-form"

# Deck type: the presets are a horizontally scrolling chip row and the smoke's deck is not tournament
# legal, so it picks the one construction the app itself treats as "the server decides" — anything else
# is gated client-side on a legal deck, which no scripted run can build by tapping.
refresh_ui || fail "a readable UI dump on the host form"
DECK_TYPE_BOUNDS="$(bounds_of_text 'Constructed - Standard')"
[ -n "$DECK_TYPE_BOUNDS" ] || fail "the deck-type chip row on the host form"
DECK_TYPE_Y="$(printf '%s' "$DECK_TYPE_BOUNDS" |
  sed 's/bounds="\[[0-9-]*,\([0-9-]*\)\]\[[0-9-]*,\([0-9-]*\)\]"/\1 \2/' | awk '{ print int(($1 + $2) / 2) }')"
i=0
while [ $i -lt 3 ] && ! has_text "$HOST_DECK_TYPE"; do
  adbx shell input swipe $((SCREEN_W * 85 / 100)) "$DECK_TYPE_Y" $((SCREEN_W * 18 / 100)) "$DECK_TYPE_Y" 300 >/dev/null
  sleep 1
  i=$((i + 1))
done
has_text "$HOST_DECK_TYPE" || fail "the '$HOST_DECK_TYPE' deck type to be reachable in the chip row"
tap_text "$HOST_DECK_TYPE"
tap_text "$HOST_OPPONENT"
tap_text "$DECK_NAME"
pass "table configured: $HOST_DECK_TYPE, one $HOST_OPPONENT opponent, deck '$DECK_NAME'"

scroll_to_text "Create table" 8 || fail "the Create action at the foot of the host form"
hide_ime
tap_text "Create table"

wait_for '^Table room$' 30 "the table room after creating the table"
check '^Seats \(2/2\)$' "both seats are occupied — the AI took its seat" 30
check 'ComputerMad|AI' "seat 2 is the AI the form asked for" 10
check '^Table: Ready to start$' "the server reports the table ready to start" 30
pass "table created and seated from the app"
shot "table-room"

tap_text "Start match"
wait_for '^Match starting…$' 45 "the match-starting hand-off after Start"
check 'The match has started on the server' "the room reports the match started server-side" 15
pass "reached match start"
shot "match-starting"

# ---------------------------------------------------------------------------------------------------
# Step 5 — sign out.
#
# 0047's entry policy says sign-out returns to a place sign-in is reachable from; 0046 says it is a
# deliberate exit, so the bridge should *evict* the session rather than park it for resume.
# ---------------------------------------------------------------------------------------------------

step 5 "sign-out"
tap_tab "Settings"
wait_for '^Sign out$' 20 "the Settings destination, which owns sign-out"
tap_text "Sign out"

wait_for '^Servers$' 25 "the Servers screen after signing out (sign-in reachable again)"
if screen_has '^(Home|Play|Connected)$'; then
  fail "the signed-out app to leave the shell entirely, but shell chrome is still on screen"
fi
check "$BRIDGE_HOST" "the saved server survives sign-out, so signing back in is one tap away"
pass "sign-out returned to the Servers screen"
shot "signed-out"
note "check the bridge for the matching teardown:  docker logs docker-bridge-1 --tail 20"
note "story 0046 expects 'Evicted session …' for a deliberate sign-out, not 'Parked session …'"

# ---------------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------------

log ""
log "======================================================================"
if [ "$DEFECTS" = "0" ]; then
  log "SMOKE PASSED — all 5 steps, signed in as $USERNAME"
  log "evidence: $OUT"
  log "======================================================================"
  exit 0
fi
log "SMOKE FAILED — $DEFECTS expectation(s) not met (every other step passed)"
log "$DEFECT_LIST"
log ""
log "evidence: $OUT"
log "======================================================================"
exit 1
