#!/usr/bin/env bash
#
# Deterministic on-device verification driver for the Android OmaTube port.
#
# It installs nothing and builds nothing: it drives the already-built debug
# APKs on an isolated emulator using only adb, launch extras and uiautomator.
# Every app launch uses the debug automation extras so the in-memory fixture
# and fake backend are used: no network, no images, no user database.
#
# Usage:
#   docs/verification/run-device-checks.sh [output-dir]
#
# Environment overrides:
#   DEVICE=emulator-5580
#   ADB=/path/to/adb
#   SETTLE=2.5   seconds to wait after a normal launch
#
# Screenshots land in the output directory (default docs/verification/android)
# and a machine-readable summary is written to results.txt in the same place.
#
# NOTE: the app consumes window insets. MainActivity wraps every non-player
# route in safeDrawingPadding(), and the player pads the display cutout, so the
# 128 px top cutout/status region no longer overlaps header controls. Header
# controls are tapped at their node centers; the old lower-edge workaround is
# gone, and no touch is masked by a system region. Verified: the Config close X
# center (993, 210) closes settings and the feed nav centers (y 236) navigate.
# The player quality picker is located by its mixed-case label text rather than
# a fixed coordinate.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEV="${DEVICE:-emulator-5580}"
ADB="${ADB:-$REPO/.tooling/android-sdk/platform-tools/adb}"
PKG="dev.omatube.app"
ACT="$PKG/.MainActivity"
OUT="${1:-$REPO/docs/verification/android}"
TMP="${TMPDIR:-/tmp}/oma-device-checks"
SETTLE="${SETTLE:-2.5}"

mkdir -p "$OUT" "$TMP"
RESULT="$OUT/results.txt"
: > "$RESULT"

say() { printf '%s\n' "$*" | tee -a "$RESULT"; }

adb() { "$ADB" -s "$DEV" "$@"; }

require_device() {
  adb wait-for-device
  local ok
  ok="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  if [ -z "$ok" ]; then
    echo "No device $DEV" >&2
    exit 1
  fi
  say "device=$DEV sdk=$ok size=$(adb shell wm size | tr -d '\r') density=$(adb shell wm density | tr -d '\r')"
}

snap() {
  adb exec-out screencap -p > "$OUT/$1.png"
  say "shot=$1.png $(file -b "$OUT/$1.png" | cut -d, -f1-2)"
}

dump_ui() {
  local attempt
  for attempt in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
    if adb pull /sdcard/window_dump.xml "$TMP/window.xml" >/dev/null 2>&1 && [ -s "$TMP/window.xml" ]; then
      return 0
    fi
    sleep 0.6
  done
  return 1
}

# node_geom <attribute> <value> -> prints "cx cy x1 y1 x2 y2".
node_geom() {
  python3 - "$TMP/window.xml" "$1" "$2" <<'PY'
import re, sys
path, attr, val = sys.argv[1], sys.argv[2], sys.argv[3]
data = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r"<node[^>]*>", data):
    n = m.group(0)
    def a(k):
        mm = re.search(k + r'="([^"]*)"', n)
        return mm.group(1) if mm else ""
    if a(attr) == val:
        b = a("bounds")
        mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if mm:
            x1, y1, x2, y2 = map(int, mm.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2)
            sys.exit(0)
sys.exit(1)
PY
}

node_center() {
  local geom
  geom="$(node_geom "$1" "$2")" || return 1
  echo "$geom" | awk '{print $1, $2}'
}

node_present() { node_geom "$1" "$2" >/dev/null 2>&1; }

tap_text() {
  dump_ui || true
  local xy
  if ! xy="$(node_center text "$1")"; then
    say "FAIL tap_text '$1' not found"
    return 1
  fi
  adb shell input tap $xy
}

tap_desc() {
  dump_ui || true
  local xy
  if ! xy="$(node_center content-desc "$1")"; then
    say "FAIL tap_desc '$1' not found"
    return 1
  fi
  adb shell input tap $xy
}

# Prints the first mm:ss / h:mm:ss label (the current playback position).
node_time() {
  dump_ui || return 1
  python3 - "$TMP/window.xml" <<'PY'
import re, sys
data = open(sys.argv[1], encoding="utf-8", errors="replace").read()
for t in re.findall(r'text="([^"]*)"', data):
    if re.match(r"^\d+:\d+$", t):
        print(t)
        sys.exit(0)
sys.exit(1)
PY
}

assert_text() {
  dump_ui
  if node_present text "$1"; then say "PASS text '$1' present"; else say "FAIL text '$1' missing"; fi
}

# Quality popup: Compose's focusable Popup is dismissed by a uiautomator dump, so
# the row geometry is measured from a screenshot instead. Prints "cx cy" for the
# option at the given 0-based index in PlaybackQuality.OPTIONS order
# (Default, Auto, 2160p, 1440p, 1080p, 720p, 480p, 360p).
quality_option_xy() {
  local index="$1"
  adb exec-out screencap -p > "$TMP/quality.png" 2>/dev/null || return 1
  python3 - "$TMP/quality.png" "$index" <<'PY'
import sys
from PIL import Image
path, idx = sys.argv[1], int(sys.argv[2])
im = Image.open(path).convert("RGB"); px = im.load(); W, H = im.size
def cream(p):
    r, g, b = p
    return r > 200 and g > 195 and b > 185
colcount = [sum(1 for y in range(150, min(H, 1600)) if cream(px[x, y])) for x in range(W)]
xs = [x for x, c in enumerate(colcount) if c > 100]
if not xs:
    sys.exit(1)
minx, maxx = xs[0], xs[-1]
rowcount = [sum(1 for x in range(minx, maxx + 1) if cream(px[x, y])) for y in range(H)]
ys = [y for y, c in enumerate(rowcount) if c > 100]
if not ys:
    sys.exit(1)
runs = []; start = prev = ys[0]
for y in ys[1:]:
    if y == prev + 1:
        prev = y
    else:
        runs.append((start, prev)); start = prev = y
runs.append((start, prev))
miny, maxy = max(runs, key=lambda r: r[1] - r[0])
x0, x1 = minx + 5, maxx - 5
rows = []; cur = None
for y in range(miny, maxy + 1):
    dark = sum(1 for x in range(x0, x1) if sum(px[x, y]) < 260)
    if dark > 4:
        if cur is None: cur = [y, y]
        else: cur[1] = y
    else:
        if cur is not None: rows.append(tuple(cur)); cur = None
if cur: rows.append(tuple(cur))
if idx >= len(rows):
    sys.exit(1)
r = rows[idx]
print((minx + maxx) // 2, (r[0] + r[1]) // 2)
PY
}

# Visual check that the desktop seek handle is square. The player chrome is
# paused when this runs. The band matches the current compact portrait chrome
# (seek row ~2110-2210 with the 16 dp bottom lift). It excludes the bottom
# bar's top accent border, which would otherwise merge with the track into a
# full-width detection, and spans the bar's full width so the handle is found
# at any playback position. (There is no in-app volume slider; volume is left
# to the phone buttons, so only the seek handle is checked.) A layout change
# surfaces as a failed detection rather than being silently skipped.
assert_square_handles() {
  local png="$1" out name w h
  if ! python3 -c "import PIL" >/dev/null 2>&1; then
    say "SKIP square-handle visual check (PIL unavailable)"
    return 0
  fi
  out="$(python3 - "$png" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); px = im.load()
def ink(p): return sum(p) > 45
def probe(x1, x2, y1, y2):
    tall = []
    for x in range(x1, x2 + 1):
        ys = [y for y in range(y1, y2 + 1) if ink(px[x, y])]
        if ys and (max(ys) - min(ys) + 1) >= 20:
            tall.append((x, min(ys), max(ys)))
    if not tall:
        return None
    w = tall[-1][0] - tall[0][0] + 1
    h = max(a[2] for a in tall) - min(a[1] for a in tall) + 1
    return w, h
for name, box in (("seek", (21, 1059, 2110, 2210)),):
    r = probe(*box)
    print(name, "none" if r is None else f"{r[0]} {r[1]}")
PY
)"
  while read -r name w h; do
    [ -z "$name" ] && continue
    if [ "$w" = "none" ]; then
      say "FAIL $name handle not detected in $(basename "$png")"
      continue
    fi
    if [ "$w" -ge 18 ] && [ "$w" -le 40 ] && [ "$h" -ge 18 ] && [ "$h" -le 40 ] \
       && [ $((w - h)) -le 6 ] && [ $((h - w)) -le 6 ]; then
      say "PASS $name handle square ${w}x${h}px"
    else
      say "FAIL $name handle not square ${w}x${h}px"
    fi
  done <<< "$out"
}

# start <full|simple> <route> <theme> [settle]
start() {
  local ui="$1" route="$2" theme="$3" settle="${4:-$SETTLE}"
  adb shell am force-stop "$PKG"
  adb shell am start -n "$ACT" \
    --ez automation true \
    --es automation_ui "$ui" \
    --es automation_route "$route" \
    --es automation_theme "$theme" >/dev/null 2>&1
  sleep "$settle"
  say "start ui=$ui route=$route theme=$theme settle=$settle"
}

back() { adb shell input keyevent 4; sleep 1; }

# The tab row sits below the safe top inset (y ~327-372 after the inset fix),
# so the swipe must happen on the tab strip itself, not at the old y=220.
scroll_tabs_to_end() { adb shell input swipe 900 350 300 350 300; sleep 0.6; }

reset_rotation() {
  adb shell settings put system accelerometer_rotation 1 >/dev/null 2>&1
  adb shell settings put system user_rotation 0 >/dev/null 2>&1
}

require_device
trap reset_rotation EXIT

# ---------------------------------------------------------------------------
# Device / inset measurements
# ---------------------------------------------------------------------------
CUTOUT="$(adb shell dumpsys window displays | grep -oE 'DisplayCutout\{insets=Rect\([0-9]+, [0-9]+' | head -1 || true)"
say "measure top_cutout=$CUTOUT statusbar_frame=128x63"

# ---------------------------------------------------------------------------
# Full UI route matrix
# ---------------------------------------------------------------------------
start full feed default
snap full-feed-default
assert_text Feed
dump_ui
if node_present text "Jan 1, 2026"; then say "PASS full feed shows published date"; else say "FAIL full feed published date"; fi
if node_present text "20% watched"; then say "PASS watched card shows progress"; else say "FAIL watched card progress"; fi
if node_present text "0% watched"; then say "FAIL unwatched card shows 0% label"; else say "PASS unwatched card hides 0% label"; fi

start full history default
snap full-history-default
assert_text History

start full watchnext default
snap full-watchnext-default
assert_text Watch\ Next

start full settings default
snap full-settings-channels
assert_text CHANNELS

tap_text CATEGORIES
snap full-settings-categories
assert_text CATEGORIES

tap_text FEED
snap full-settings-feed
assert_text Use\ key

tap_text APPEARANCE
dump_ui
if node_present text Theme; then say "PASS Appearance tab shows Theme"; else say "FAIL Appearance tab"; fi
snap full-settings-appearance

scroll_tabs_to_end
tap_text PLAYBACK
dump_ui
if node_present text SponsorBlock; then say "PASS Playback tab shows SponsorBlock"; else say "FAIL Playback tab"; fi
snap full-settings-playback

dump_ui
if CONFIG="$(node_geom text Config)"; then say "measure Config_title=$CONFIG"; fi
# The first clickable node on the Config screen is the close X. With safe
# insets applied its center is below the 128 px cutout and must close the screen.
if X="$(node_center clickable true)"; then
  say "measure Config_close_center=$X"
  adb shell input tap $X
  sleep 1
  dump_ui
  if node_present text Feed; then say "PASS Config X center tap -> feed"; else say "FAIL Config X center tap"; fi
else
  say "FAIL Config close X not found"
fi

# ---------------------------------------------------------------------------
# Config theme picker interaction
# ---------------------------------------------------------------------------
start full settings default
tap_text APPEARANCE
tap_text Default
sleep 0.5
snap full-settings-theme-open
tap_text Nord
sleep 0.5
snap full-settings-theme-nord

# ---------------------------------------------------------------------------
# Full UI themes on the feed
# ---------------------------------------------------------------------------
start full feed rose-pine
snap full-feed-rose-pine

start full feed nord
snap full-feed-nord

# ---------------------------------------------------------------------------
# Full UI category filter: no visible "All" chip, active chip toggles sentinel
# ---------------------------------------------------------------------------
start full feed default
dump_ui
if node_present text "All"; then say "FAIL visible All category chip present"; else say "PASS no visible All category chip"; fi
if node_present content-desc "Category 2 Automation Tech"; then
  say "PASS user category chip exposed by content description"
else
  say "FAIL user category chip missing"
fi
# The Tech channel's videos are below the fold while unfiltered.
if node_present text "Automation Video 4"; then say "WARN Video 4 already visible unfiltered"; fi
tap_desc "Category 2 Automation Tech"
sleep 0.6
snap full-feed-category-tech
dump_ui
if node_present text "Automation Video 4"; then say "PASS category filter shows Tech channel"; else say "FAIL Tech category filter"; fi
# Tapping the active chip toggles back to the internal unfiltered sentinel.
# The grid keeps the previous first-visible item, so scroll back to the top
# before checking that the unfiltered channel-one videos reappear.
tap_desc "Category 2 Automation Tech"
sleep 0.6
adb shell input swipe 540 900 540 1900 300
sleep 0.8
dump_ui
if node_present text "Automation Video 1"; then say "PASS active category tap resets to unfiltered sentinel"; else say "FAIL active category tap did not reset filter"; fi

# ---------------------------------------------------------------------------
# Full UI player: chrome, quality picker, back
# ---------------------------------------------------------------------------
# Burn the one-time immersive education overlay on a throwaway launch.
start full player default 1.4
dump_ui || true
if node_present text "Got it"; then tap_text "Got it"; sleep 0.4; say "dismissed immersive education overlay"; fi

# Open the player and pause it. Chrome auto-hides 3 s after playback starts, so
# pausing holds the chrome up for deterministic captures and interactions.
# The play/pause button is located by its content description (it sits below
# the seek bar in the compact layout). Poll briefly because the automation
# engine may still be loading on the first dump; a stable "Play" means the
# player is already paused with the chrome held. The fixed coordinate is only
# a last resort and matches the current compact play-button center.
player_open_paused() {
  local ui="$1" attempt
  start "$ui" player default 1.4
  for attempt in 1 2 3 4 5 6; do
    sleep 1.0
    dump_ui || continue
    if PLAY_XY="$(node_center content-desc "Pause")"; then
      adb shell input tap $PLAY_XY
      sleep 0.6
      say "paused player ui=$ui (chrome held)"
      return 0
    fi
    if node_present content-desc "Play"; then
      sleep 1.5
      dump_ui || continue
      if PLAY_XY="$(node_center content-desc "Pause")"; then
        adb shell input tap $PLAY_XY
        sleep 0.6
      fi
      say "paused player ui=$ui (chrome held)"
      return 0
    fi
  done
  adb shell input tap 73 2284
  sleep 0.6
  say "paused player ui=$ui (chrome held, fallback tap)"
}

player_open_paused full
snap full-player-chrome
dump_ui
if node_present text "&lt; BACK"; then say "PASS player top bar BACK present"; else say "FAIL player top bar"; fi

# Seek: the progress bar is exposed to accessibility as a SeekBar, so derive
# the tap point from its bounds (70% across) instead of a fixed pixel. The
# fixture duration is 10:00.
SEEK_GEOM="$(node_geom class android.widget.SeekBar || true)"
if [ -n "$SEEK_GEOM" ]; then
  SEEK_CY="$(echo "$SEEK_GEOM" | awk '{print $2}')"
  SEEK_X1="$(echo "$SEEK_GEOM" | awk '{print $3}')"
  SEEK_X2="$(echo "$SEEK_GEOM" | awk '{print $5}')"
  SEEK_TAPX=$(( SEEK_X1 + 7 * (SEEK_X2 - SEEK_X1) / 10 ))
  say "measure player_seek_bounds=$SEEK_GEOM tap=$SEEK_TAPX,$SEEK_CY"
  SEEK_BEFORE="$(node_time || true)"
  if [ -n "$SEEK_BEFORE" ]; then
    adb shell input tap "$SEEK_TAPX" "$SEEK_CY"
    sleep 0.5
    SEEK_AFTER="$(node_time || true)"
    say "seek before=$SEEK_BEFORE after=$SEEK_AFTER"
    if [ -n "$SEEK_AFTER" ] && [ "$SEEK_AFTER" != "$SEEK_BEFORE" ]; then
      say "PASS player seek moves position"
    else
      say "FAIL player seek did not move position"
    fi
  else
    say "FAIL player position label not found"
  fi
else
  say "FAIL player seek bar not exposed to accessibility"
fi
snap full-player-seek
assert_square_handles "$OUT/full-player-chrome.png"

# Quality picker: natural casing and dynamic geometry. The selector is located
# by its mixed-case label; the popup row is measured from a screenshot because a
# uiautomator dump dismisses the focusable Compose Popup.
dump_ui
if node_present text "Default"; then say "PASS quality selector shows mixed-case Default"; else say "FAIL quality selector Default label"; fi
if node_present text "DEFAULT"; then say "FAIL uppercase DEFAULT label present"; else say "PASS no uppercase DEFAULT label"; fi
if QBTN="$(node_center text "Default")"; then
  adb shell input tap $QBTN
  sleep 0.5
  snap full-player-quality-open
  if QXY="$(quality_option_xy 5)"; then
    adb shell input tap $QXY
    sleep 0.5
    snap full-player-quality-720p
    dump_ui
    if node_present text "720p"; then say "PASS quality selector selects mixed-case 720p"; else say "FAIL quality 720p selection"; fi
    if node_present text "720P"; then say "FAIL uppercase 720P label present"; else say "PASS no uppercase 720P label"; fi
  else
    say "FAIL quality popup 720p row not found"
  fi
else
  say "FAIL quality selector not found"
fi

back
snap full-player-back-feed
dump_ui
if node_present text Feed; then say "PASS back from player -> feed"; else say "FAIL back from player"; fi

# ---------------------------------------------------------------------------
# Full UI Watch Next reorder + remove
# ---------------------------------------------------------------------------
start full watchnext default
dump_ui
if node_present text "WATCH NEXT (2/25)"; then say "PASS watch next starts at 2/25"; else say "FAIL watch next count"; fi
tap_desc "Move down AUTO0000002"
sleep 0.8
snap full-watchnext-moved
tap_desc "Remove from Watch Next AUTO0000002"
sleep 0.8
snap full-watchnext-removed
dump_ui
if node_present text "WATCH NEXT (1/25)"; then say "PASS watch next remove -> 1/25"; else say "FAIL watch next remove count"; fi

# ---------------------------------------------------------------------------
# Back from history, then long-press add to Watch Next
# ---------------------------------------------------------------------------
start full history default
back
snap full-history-back-feed
dump_ui
if node_present text Feed; then say "PASS back from history -> feed"; else say "FAIL back from history"; fi

start full feed default
dump_ui
THIRD="$(node_center content-desc "Video AUTO0000003 Automation Video 3" || true)"
if [ -n "$THIRD" ]; then
  adb shell input swipe $THIRD $THIRD 1200
  sleep 0.8
  say "INFO long-pressed AUTO0000003 for Watch Next add"
  tap_desc "Show Watch Next"
  sleep 0.8
  snap full-watchnext-after-add
  dump_ui
  if node_present text "WATCH NEXT (3/25)"; then say "PASS long-press add -> 3/25"; else say "FAIL long-press add count"; fi
else
  say "FAIL feed card AUTO0000003 not found"
fi

# ---------------------------------------------------------------------------
# Simple UI route matrix
# ---------------------------------------------------------------------------
start simple feed default
snap simple-feed-default

start simple history default
snap simple-history-default

start simple watchnext default
snap simple-watchnext-default

start simple settings default
snap simple-settings-channels

player_open_paused simple
snap simple-player-chrome
back

# ---------------------------------------------------------------------------
# Simple UI toggle from Config -> Appearance, then back
# ---------------------------------------------------------------------------
start full settings default
tap_text APPEARANCE
sleep 0.5
tap_text "Use simple UI"
sleep 0.8
snap full-settings-simple-toggle-on
say "INFO toggled Use simple UI from full Config"
back
sleep 0.8
snap full-toggle-back-simple-feed
dump_ui
if node_present text "Automation Video 1"; then say "PASS simple feed reachable after toggle+back"; else say "FAIL simple toggle"; fi

# ---------------------------------------------------------------------------
# Rotation while the player is open
# ---------------------------------------------------------------------------
start full player default 1.4
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
adb shell settings put system user_rotation 1 >/dev/null 2>&1
sleep 2
snap full-player-landscape
if file -b "$OUT/full-player-landscape.png" | grep -q "2400 x 1080"; then
  say "PASS player rotation portrait -> landscape"
else
  say "FAIL player landscape size"
fi
adb shell settings put system user_rotation 0 >/dev/null 2>&1
sleep 1.5
snap full-player-portrait-again
if file -b "$OUT/full-player-portrait-again.png" | grep -q "1080 x 2400"; then
  say "PASS player rotation landscape -> portrait"
else
  say "FAIL player portrait size"
fi

# ---------------------------------------------------------------------------
# Network / production-leak audit (app process only)
# ---------------------------------------------------------------------------
PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
if [ -n "$PID" ]; then
  adb logcat -d --pid="$PID" > "$TMP/logcat.txt" 2>/dev/null
  say "app_pid=$PID app_logcat_lines=$(wc -l < "$TMP/logcat.txt")"
  if grep -Eiq "newpipe|okhttp|exoplayer|http://|https://|SocketException|UnknownHost" "$TMP/logcat.txt"; then
    say "WARN network/extractor tokens in app logcat:"
    grep -Eih "newpipe|okhttp|exoplayer|http://|https://" "$TMP/logcat.txt" | head -20 | tee -a "$RESULT"
  else
    say "PASS no network/extractor tokens in app logcat"
  fi
  if grep -Eq "FATAL EXCEPTION|AndroidRuntime" "$TMP/logcat.txt"; then
    say "WARN crash tokens in app logcat:"
    grep -E "FATAL EXCEPTION|AndroidRuntime" "$TMP/logcat.txt" | head -20 | tee -a "$RESULT"
  else
    say "PASS no fatal exceptions in app logcat"
  fi
else
  say "WARN app process not running for logcat audit"
fi

say "done results=$RESULT"
