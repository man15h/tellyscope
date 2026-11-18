#!/usr/bin/env bash
# Drives RELEASE_TESTING.md over ADB: the app on the phone, the checks on the TV.
#
#   TV=<tv-host>:5555 PHONE=<phone-serial> .github/release-test.sh [STEP...]
#
# With no STEP it runs every step in table order. Each step prints one line,
# "ID PASS|FAIL|ASK|MANUAL|SKIP note", and the lines are also appended to
# $OUT as table rows. ASK means a person has to look; with ASK=1 the script
# waits for y/n instead. Z1 (put back) always runs last, even after Ctrl-C.
#
# Needs adb and python3. Nothing about your network lives in this file:
# addresses and serials come only from the environment.
#
# Optional:
#   PKG       package under test (default app.tellyscope.alpha)
#   FIXTURES  folder on this computer with small.apk, older.apk (an older
#             build of the same app), bundle.apks, photo.png, video.mp4 and
#             big.bin (50 MB or more), for I1-I3 and F1-F3
#   SMALL_APP the app name small.apk shows, for A4 and A5
#   OFF_APP, OFF_PKG  name and package of an app A3 may turn off and on
#   DEBLOAT_PKGS      2-3 packages from the Debloat list that D3 may turn
#             off and back on, space-separated
#   SKIP      step IDs to skip, space-separated (for example "C6 X2")
#   ASK=1     stop and ask at the steps a person has to see

set -u
: "${TV:?set TV to the TV's adb address, host:port}"
: "${PHONE:?set PHONE to the phone's adb serial}"
PKG=${PKG:-app.tellyscope.alpha}
OUT=${OUT:-release-test-results.md}
W=$(mktemp -d)
SKIP=" ${SKIP:-} "

p() { adb -s "$PHONE" "$@"; }
t() { adb -s "$TV" "$@"; }

# ---- results ----
row() { printf '%-4s %-6s %s\n' "$1" "$2" "$3"; printf '| %s | %s | %s |\n' "$1" "$2" "$3" >> "$OUT"; }
pass() { row "$ID" PASS "$*"; }
fail() { row "$ID" FAIL "$*"; }
manual() { row "$ID" MANUAL "$*"; }
ask() {
  if [ "${ASK:-0}" = 1 ]; then
    read -r -p "$ID: $* [y/n] " a; [ "$a" = y ] && pass "$*" || fail "$*"
  else row "$ID" ASK "$*"; fi
}
check() { local note=$1; shift; if "$@"; then pass "$note"; else fail "$note"; fi; }

# ---- phone UI ----
# Dumps the phone's screen; each line is "text|description|x y" (the centre).
ui() {
  p shell uiautomator dump /sdcard/tellyscope-ui.xml >/dev/null 2>&1
  p shell cat /sdcard/tellyscope-ui.xml > "$W/ui.xml"
  python3 - "$W/ui.xml" > "$W/ui.txt" <<'PY'
import re, sys, xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
    tx, ds = n.get('text') or '', n.get('content-desc') or ''
    if tx or ds:
        a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds')))
        print(f"{tx}|{ds}|{(a+c)//2} {(b+d)//2}")
PY
  cat "$W/ui.txt"
}
has() { ui | grep -qE "$1"; }
# Waits up to $2 seconds (default 15) for text matching $1.
wait_for() { local i; for i in $(seq 1 "${2:-15}"); do has "$1" && return 0; sleep 1; done; return 1; }
# Taps the Nth (default first) node whose text or description matches $1.
tap() {
  local xy; xy=$(ui | awk -F'|' -v r="$1" -v k="${2:-0}" '($1 ~ r || $2 ~ r) && k-- == 0 {print $3; exit}')
  [ -n "$xy" ] || { echo "  (no '$1' on screen)" >&2; return 1; }
  p shell input tap $xy; sleep 1
}
key() { p shell input keyevent "$@"; }
type_text() { p shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; }
scroll() { p shell input swipe 540 1800 540 700 300; sleep 0.8; }
# Every text on a scrolling list, top to bottom.
alltext() { local i; for i in $(seq 1 15); do ui | cut -d'|' -f1; scroll; done | awk 'NF && !s[$0]++'; }
# Some controls (the Remote button on Overview) aren't in the dump; tap a screen fraction.
tap_frac() { local s; s=$(p shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
  p shell input tap $(( ${s%x*} * $1 / 100 )) $(( ${s#*x} * $2 / 100 )); sleep 1; }

launch() { p shell monkey -p "$PKG" --pct-syskeys 0 1 >/dev/null 2>&1; sleep 4; }
tab() { tap "^$1\$"; sleep 1; }
menu() { tap '^More options$' && tap "^$1\$"; sleep 1.5; }
home_page() { key 4 4 4 >/dev/null; launch; tab Overview; }
open_remote() { home_page; tap '^Remote$' 2>/dev/null || tap_frac 83 87; sleep 2; }

# ---- TV ----
tv_wake() { t shell input keyevent 224; }
tv_focus() { t shell dumpsys window | grep -m1 mCurrentFocus; }
tv_setting() { t shell settings get global "$1" | tr -d '\r'; }

# ---- before ----
# Z1 puts back exactly what this records.
snapshot() {
  {
    echo "dns_mode=$(tv_setting private_dns_mode)"
    echo "dns_spec=$(tv_setting private_dns_specifier)"
    echo "anim=$(tv_setting animator_duration_scale)"
    echo "home=$(t shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | tr -d '\r')"
    echo "stay_on=$(p shell settings get global stay_on_while_plugged_in | tr -d '\r')"
    echo "since=$(p shell date +'%m-%d %H:%M:%S.000' | tr -d '\r')"
  } > "$W/before"
  t shell pm list packages -d | sort > "$W/off_before"
  t shell ls /sdcard/Download /sdcard/Movies 2>/dev/null | sort > "$W/tv_files_before"
  p shell ls /sdcard/Download | sort > "$W/phone_files_before"
  p shell settings put global stay_on_while_plugged_in 7
}
before() { grep "^$1=" "$W/before" | cut -d= -f2-; }

# ======================================================================
# Steps. One function per ID in RELEASE_TESTING.md, in table order.

step_C1() {
  p shell pm clear "$PKG" >/dev/null; launch
  wait_for '^Welcome to Tellyscope' || { fail "no welcome screen"; return; }
  tap '^Continue$'
  check "welcome, then Add your TV" wait_for '^Add your TV' 10
}
step_C2() {
  tap '^Fire TV$|^Android TV$' >/dev/null 2>&1
  local ip=${TV%:*}
  check "scan lists the TV, with Scan again" eval 'wait_for "^${ip//./\\.}\|" 30 && has "^Scan again"'
}
step_C3() {
  tap '^Add by IP address$' || { fail "no Add by IP"; return; }
  p shell input tap $(awk -F'|' '/^Find it on the TV/ {split($3,a," "); print a[1], a[2]+250; exit}' "$W/ui.txt")
  type_text "${TV%:*}"; tap '^Connect$'
  check "Connect shows This phone's key" wait_for "This phone's key" 15
}
step_C4() {
  # The press on the TV is a person's job; the script checks both ends.
  tv_wake
  local fp; fp=$(ui | awk -F'|' 'f {print $1; exit} /^This phone.s key/ {f=1}' | tr -d '\n')
  echo "  Press Allow on the TV (key ${fp:0:8}…). Waiting 120 s."
  check "connected, lands on Overview" wait_for '^Overview\|' 120
}
step_C5() { manual "wireless-debugging TVs only: pair with a wrong code, then the right one"; }
step_C6() { manual "turn the phone's Wi-Fi off 10 s and on; the TV should reconnect untouched"; }

step_O1() {
  home_page; sleep 10; ui > "$W/o1"
  local dashes; dashes=$(grep -A1 -E '^(CPU|RAM|Temp|Network)\|' "$W/o1" | grep -c '^—|')
  [ "$dashes" = 0 ] && pass "CPU, RAM, Temp, Network filled" || fail "$dashes number(s) stuck at —"
}
step_O2() { ask "list card names a list for model $(t shell getprop ro.product.model | tr -d '\r')"; }

step_A1() {
  tab Apps; has '^Running' && has '^Installed' || { fail "tabs missing"; return; }
  # A package or class name where the app's name should be (N13).
  local t ids=""; for t in Installed Built-in; do tap "^$t\$"; sleep 2
    ids="$ids$(alltext | grep -E '^[a-z][a-z0-9_]*(\.[A-Za-z0-9_]+){2,}$' | tr '\n' ' ')"; done
  [ -z "$ids" ] && pass "Installed and Built-in show names" || fail "shown as names: $ids"
}
step_A2() {
  local all n; all=$(ui | grep -c '|')
  tap 'Search apps' && type_text "set"; sleep 2; n=$(ui | grep -c '|')
  tap 'Clear search'; sleep 1.5
  [ "$n" -lt "$all" ] || { fail "search didn't narrow the list ($all, then $n)"; return; }
  [ "$(ui | grep -c '|')" -gt "$n" ] && pass "narrows ($all to $n), Clear search restores" || fail "Clear search left the list short"
}
# Finds an app in Apps › Installed by name and opens its row.
app_row() { home_page; tab Apps; tap '^Installed$'; tap 'Search apps' && type_text "$1"; sleep 2; tap "^$1\$"; sleep 1.5; }
is_off() { t shell pm list packages -d | tr -d '\r' | grep -qx "package:$1"; }
step_A3() {
  [ -n "${OFF_APP:-}" ] && [ -n "${OFF_PKG:-}" ] || { manual "set OFF_APP and OFF_PKG, or turn one unused app off and on by hand"; return; }
  app_row "$OFF_APP"; tap '^Disable$' && tap '^Yes, disable it$'; sleep 4
  is_off "$OFF_PKG" || { fail "$OFF_PKG not off after Disable"; return; }
  tap '^Turn on$' && tap '^Yes, turn it on$' 2>/dev/null; sleep 4
  is_off "$OFF_PKG" && fail "$OFF_PKG still off after Turn on" || pass "off, then on (pm list packages -d)"
}
step_A4() {
  local pkg; pkg=$(cat "$W/i1_pkg" 2>/dev/null)
  [ -n "$pkg" ] && [ -n "${SMALL_APP:-}" ] || { row "$ID" SKIP "needs I1 and SMALL_APP"; return; }
  app_row "$SMALL_APP"; tap '^Uninstall$' && tap '^Yes, uninstall it$'; sleep 6
  t shell pm list packages | tr -d '\r' | grep -qx "package:$pkg" && fail "$pkg still installed" || pass "confirmed, then gone"
}
step_A5() {
  [ -n "${SMALL_APP:-}" ] || { row "$ID" SKIP "needs SMALL_APP"; return; }
  app_row "$SMALL_APP"
  tap '^Open$' || { fail "no Open on the app's row (#259)"; return; }
  tap '^Yes, open it$' 2>/dev/null; sleep 4
  check "comes up on the TV" eval 'tv_focus | grep -q "$(cat "$W/i1_pkg")"'
}

step_D1() {
  home_page; tap '^Debloat$' || { fail "no Debloat on the list card"; return; }
  sleep 3; check "review lists apps before any change" has '^Disable [0-9]+ apps?\||Everything on the list is off'
}
step_D2() { ask "untick one app, then a whole group from its heading; the count follows"; key 4; }
# Ticks exactly the rows whose package is in $1 on the Debloat review, scrolling through it.
tick_only() {
  local i; for i in $(seq 1 12); do
    ui >/dev/null
    python3 - "$W/ui.xml" "$1" <<'PY2' | while read -r xy; do p shell input tap $xy; sleep 0.4; done
import re, sys, xml.etree.ElementTree as E
want = set(sys.argv[2].split())
for n in E.parse(sys.argv[1]).iter('node'):
    if n.get('checkable') != 'true': continue
    texts = [c.get('text') or '' for c in n.iter('node')]
    pkg = next((t for t in texts if re.fullmatch(r'[a-z][\w]*(\.[\w]+)+', t)), None)
    if pkg and (n.get('checked') == 'true') != (pkg in want):
        a, b, c, d = map(int, re.findall(r'\d+', n.get('bounds')))
        print((a + c) // 2, (b + d) // 2)
PY2
    has '^Disable [0-9]+ apps?\|' && break; scroll
  done
}
step_D3() {
  [ -n "${DEBLOAT_PKGS:-}" ] || { manual "set DEBLOAT_PKGS, or run Debloat with 2-3 harmless apps and Undo them in History"; return; }
  home_page; tap '^Debloat$' || { fail "no Debloat"; return; }; sleep 3
  tick_only "$DEBLOAT_PKGS"
  local n; n=$(wc -w <<<"$DEBLOAT_PKGS")
  tap "^Disable $n apps?\$" || { fail "couldn't tick exactly $n"; return; }
  tap '^Yes, disable them$'; wait_for '^Debloat (partly )?done' 60 || { fail "no Debloat done"; return; }
  local q off=0; for q in $DEBLOAT_PKGS; do is_off "$q" && off=$((off+1)); done
  # Not Restore everything: History also holds earlier changes the owner wants kept.
  # This run's are the newest rows, so Undo the top $off of them.
  home_page; menu History; local i
  for i in $(seq 1 "$off"); do tap '^Undo$' 0 && { tap '^Yes, turn it on$' 2>/dev/null; sleep 4; }; done
  t shell pm list packages -d | sort | comm -13 "$W/off_before" - > "$W/d3_left"
  [ -s "$W/d3_left" ] && { fail "$off/$n turned off; still off after Undo: $(tr '\n' ' ' < "$W/d3_left")"; return; }
  comm -23 "$W/off_before" <(t shell pm list packages -d | sort) | grep -q . && { fail "an app that was off before the run is now on"; return; }
  pass "$off/$n turned off, Undo in History turned them back on"
}
step_H1() {
  home_page; menu History
  check "History opens" wait_for '^History' 5
  key 4
}

step_R1() {
  open_remote; tv_wake; t shell am start -a android.settings.SETTINGS >/dev/null; sleep 2
  tap '^Down$'; tap '^OK$'; tap '^Back$'; tap '^Home$'; sleep 3
  check "Home on the Remote leaves Settings" eval '! tv_focus | grep -qi settings'
  ask "D-pad, OK, Back and Menu each moved the TV"
}
step_R2() { ask "volume up, down, mute move the TV's volume bar (fixed-volume HDMI shows none)"; }
step_R3() { ask "open a search box on the TV, type and delete one letter from the phone"; }
step_R4() { ask "while typing, tap empty space: the keyboard closes"; }
step_R5() { ask "⋮ › Remote buttons: only installed apps, a 4th refused, each opens, hold edits"; }
step_R6() { ask "⋮ › Screen off darkens the TV, any key wakes it, app stays connected"; }
step_R7() {
  open_remote; menu 'Restart TV'; tap '^Restart$' || { fail "no confirm"; return; }
  adb disconnect "$TV" >/dev/null   # a second connection hides the phone's from the reconnect check
  wait_for '^Restarting' 10 || { fail "no Restarting…"; adb connect "$TV" >/dev/null; return; }
  if wait_for 'reconnected|Running fine' 180; then pass "rebooted and reconnected"; else fail "no reconnect in 180 s"; fi
  adb connect "$TV" >/dev/null; sleep 2
  t shell uptime | grep -q 'up 0 min\|up [1-4] min' || fail "TV uptime says it never restarted"
}

# Opens Apps › Install app and picks $1 from Downloads. The phone's file
# picker ignores injected taps on files, so search, then D-pad to the hit.
pick_file() {
  tab Apps; tap '^Install app$' || return 1; sleep 3
  tap '^Search$' || return 1; type_text "$1"; key 66; sleep 2.5; key 20 20 23; sleep 3
}
fixture() { [ -n "${FIXTURES:-}" ] && [ -f "$FIXTURES/$1" ] || { row "$ID" SKIP "no \$FIXTURES/$1"; return 1; }
  p push "$FIXTURES/$1" "/sdcard/Download/$1" >/dev/null; echo "$1" >> "$W/pushed"; }
install_step() {  # $1 file in FIXTURES, $2 wording expected before the install
  fixture "$1" || return; home_page
  pick_file "${1%.*}" || { fail "file picker"; return; }
  wait_for "$2" 20 || { fail "no '$2' before install"; return; }
  t shell pm list packages -3 | sort > "$W/pk_before"
  tap '^(Install|Reinstall|Update)$|^Install the older version anyway$'
  check "$1: '$2', then installed" wait_for '^Open on TV' 120
  # Only what this install added; Z1 uninstalls exactly these.
  t shell pm list packages -3 | sort | comm -13 "$W/pk_before" - | sed 's/^package://' | tr -d '\r' | tee -a "$W/installed" > "$W/new_pkg"
}
step_I1() {
  install_step small.apk 'Not on this TV yet'
  cp "$W/new_pkg" "$W/i1_pkg"
  # N12: Open on TV after an install.
  ID=N12 check "Open on TV opens the installed app" eval 'tap "^Open on TV\$" && sleep 4 && tv_focus | grep -q "$(cat "$W/i1_pkg")"'
}
step_I2() { install_step bundle.apks 'Not on this TV yet'; }
step_I3() { install_step small.apk 'Reinstall'; install_step older.apk 'older version'; }

# Files › folder $1, then Upload (not in the dump: bottom-right) and pick $2.
upload() {
  home_page; tab Files; tap "^$1\$"; tap_frac 83 87; sleep 3
  tap '^Search$' || return 1; type_text "${2%.*}"; key 66; sleep 2.5; key 20 20 23
}
step_F1() {
  fixture photo.png && fixture video.mp4 || return
  upload Download photo.png && wait_for '^photo\.png\|' 60 || { fail "photo not listed in Download"; return; }
  upload Movies video.mp4 && wait_for '^video\.mp4\|' 120 || { fail "video not listed in Movies"; return; }
  check "photo in Download, video in Movies" eval 't shell ls /sdcard/Download/photo.png /sdcard/Movies/video.mp4 >/dev/null'
}
step_F2() {
  home_page; tab Files; tap '^Download$'; tap '^photo\.png$' || { row "$ID" SKIP "no photo.png from F1"; return; }
  local n; n=$(p shell ls /sdcard/Download | grep -c '^photo')
  tap '^Save' ; sleep 3; key 66; sleep 4   # the phone's save screen: Enter saves under the offered name
  local m; m=$(p shell ls /sdcard/Download | grep -c '^photo')
  [ "$m" -gt "$n" ] || { fail "nothing new in the phone's Download"; return; }
  local f; f=$(p shell ls -t /sdcard/Download | head -1 | tr -d '\r')
  echo "$f" >> "$W/pushed"   # this run saved it, so Z1 deletes it
  pass "saved to the phone's Download: $f"
}
step_F3() {
  fixture big.bin || return
  upload Download big.bin; sleep 3; tap '^Cancel$'
  wait_for 'Cancelled\. Nothing was left on the TV\.' 20 || { fail "no 'Cancelled' message"; return; }
  t shell ls /sdcard/Download | grep -q big.bin && fail "big.bin left on the TV" || pass "cancelled, nothing left on the TV"
}

step_T1() {
  home_page; tab Tweaks; tap '^Block ads with Private DNS$' || { fail "no Private DNS"; return; }
  sleep 1; type_text 1.1.1.1; tap '^Turn on$'
  has "IP address won't work" || { fail "IP accepted"; return; }
  p shell input keyevent 123; for _ in $(seq 1 10); do key 67; done
  type_text dns.adguard-dns.com; tap '^Turn on$'; sleep 3
  [ "$(tv_setting private_dns_specifier)" = dns.adguard-dns.com ] || { fail "TV setting not written"; return; }
  tap '^Block ads with Private DNS$'; sleep 3
  check "IP refused, hostname set, then off" test "$(tv_setting private_dns_mode)" != hostname
}
step_T2() {
  tab Tweaks; tap '^Faster animations$'; sleep 2; local on; on=$(tv_setting animator_duration_scale)
  tap '^Faster animations$'; sleep 2
  check "on ($on), then back" test "$on" != "$(tv_setting animator_duration_scale)"
}
step_T3() { tab Tweaks; tap '^Clear caches$'; check "caches cleared" wait_for '^Cleared app caches' 60; }
step_T4() {
  tv_wake; tab Tweaks; tap '^Take screenshot$'
  check "saved to the phone" wait_for 'Screenshot saved to your phone' 20
}

step_L1() {
  tab Tweaks; local cur; cur=$(t shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | cut -d/ -f1 | tr -d '\r')
  ask "Home screen card marks Current on the app for $cur"
}
home_pkg() { t shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | cut -d/ -f1 | tr -d '\r'; }
step_L2() {
  local was now svc; was=$(home_pkg); svc=$(t shell settings get secure enabled_accessibility_services | tr -d '\r')
  home_page; tab Tweaks
  tap '^Make Home$' 0 && tap '^Make Home$' || { fail "no Make Home"; return; }; sleep 8
  now=$(home_pkg); [ "$now" != "$was" ] || { fail "Home still $was"; return; }
  # Back: the first Make Home on the card is the launcher we left, when it's added.
  tap '^Make Home$' 0 && tap '^Make Home$'; sleep 4
  if has '^Switch without it\|'; then tap '^Turn it on$'; sleep 6; fi
  [ "$(t shell settings get secure enabled_accessibility_services | tr -d '\r')" = "$svc" ] || { fail "accessibility services differ from before"; return; }
  ask "Home switched to $now and back; press Home on the TV's own remote: is it the launcher from before?"
}
step_L3() { manual "only while no extra launcher is installed; say in Notes if one was uninstalled"; }

step_W1() { home_page; menu 'Who has access'; check "this phone is marked" wait_for '^This phone' 15; key 4; }
step_W2() { manual "adb connect from a computer with a fresh key; phone shows the TV's prompt only while it's focused"; }
step_M1() {
  home_page; menu 'Advanced monitor'; sleep 12
  check "fills in" test "$(ui | grep -c '%|')" -gt 3; key 4
}
step_S1() { manual "Settings › Advanced › own lists: refused for an address with no index"; }
step_S2() { ask "Check for updates, Report a problem (no serial, IP, account), Share open what they say"; }

step_X1() { manual "needs two saved TVs"; }
step_X2() { manual "unplug one TV: TV offline, no endless spinner, reconnects by itself"; }
step_X3() {
  local i; for i in 1 2; do p shell am force-stop "$PKG"; launch
    wait_for 'Running fine' 20 || { fail "cold start $i not connected"; return; }; done
  pass "two cold starts land connected"
}
step_X4() {
  local n; n=$(p logcat -b crash -d -T "$(before since)" | grep -c "$PKG")
  [ "$n" = 0 ] && pass "no crash since the run started" || fail "$n crash line(s): adb logcat -b crash -d -T '$(before since)'"
}

step_Z1() {
  [ -f "$W/before" ] || return
  local m s a
  m=$(before dns_mode); s=$(before dns_spec); a=$(before anim)
  if [ "$m" = null ]; then t shell settings delete global private_dns_mode >/dev/null; else t shell settings put global private_dns_mode "$m"; fi
  if [ "$s" = null ]; then t shell settings delete global private_dns_specifier >/dev/null; else t shell settings put global private_dns_specifier "$s"; fi
  if [ "$a" = null ]; then t shell settings delete global animator_duration_scale >/dev/null; else t shell settings put global animator_duration_scale "$a"; fi
  p shell settings put global stay_on_while_plugged_in "$(before stay_on)"
  # What the run added: the pushed fixtures, the files F1 sent, the apps I1-I3 installed.
  local f q; while read -r f; do p shell rm -f "/sdcard/Download/$f"; done < "$W/pushed" 2>/dev/null
  for f in Download/photo.png Movies/video.mp4; do
    grep -qx "${f#*/}" "$W/tv_files_before" || t shell rm -f "/sdcard/$f"; done
  for q in $(sort -u "$W/installed" 2>/dev/null); do t shell pm uninstall "$q" >/dev/null; done
  # Anything else left behind on either side.
  t shell ls /sdcard/Download /sdcard/Movies 2>/dev/null | sort | comm -13 "$W/tv_files_before" - > "$W/tv_new"
  p shell ls /sdcard/Download | sort | comm -13 "$W/phone_files_before" - > "$W/phone_new"
  t shell pm list packages -d | sort | comm -13 "$W/off_before" - > "$W/off_new"
  local home; home=$(t shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1 | tr -d '\r')
  local left=""
  [ "$home" = "$(before home)" ] || left="$left Home is $home;"
  [ -s "$W/off_new" ] && left="$left still off: $(tr '\n' ' ' < "$W/off_new");"
  [ -s "$W/tv_new" ] && left="$left TV files: $(tr '\n' ' ' < "$W/tv_new");"
  [ -s "$W/phone_new" ] && left="$left phone files: $(tr '\n' ' ' < "$W/phone_new");"
  ID=Z1
  [ -z "$left" ] && pass "settings put back, nothing left over" || fail "settings put back; check:$left"
}

# ======================================================================
# Table order, except I1-I3 run before A3-A5, which use the app I1 installs.
STEPS="C1 C2 C3 C4 C5 C6 O1 O2 A1 A2 I1 I2 I3 A3 A4 A5 D1 D2 D3 H1 R1 R2 R3 R4 R5 R6 R7
F1 F2 F3 T1 T2 T3 T4 L1 L2 L3 W1 W2 M1 S1 S2 X1 X2 X3 X4"

adb connect "$TV" >/dev/null; p get-state >/dev/null || { echo "phone $PHONE not reachable"; exit 2; }
t get-state >/dev/null || { echo "TV not reachable"; exit 2; }
[ -s "$OUT" ] || printf '| ID | Result | Notes |\n|---|---|---|\n' > "$OUT"
snapshot
trap 'step_Z1; rm -rf "$W"' EXIT
trap 'exit 130' INT TERM

for ID in ${*:-$STEPS}; do
  case "$SKIP" in *" $ID "*) row "$ID" SKIP "skipped by SKIP"; continue ;; esac
  type "step_$ID" >/dev/null 2>&1 || { echo "no step $ID"; continue; }
  "step_$ID"
done
