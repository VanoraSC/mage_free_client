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
  STEP_NO=$((STEP_NO + 1))
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
