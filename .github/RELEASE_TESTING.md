# Release testing

Run this on real hardware before every release, meaning before the
`dev` → `main` pull request is merged. It walks through the app the way
someone using it would, on one phone against two TVs. Plan on about two
hours for both TVs (a guess; time your first run). Before each step,
read the expected result; after it, mark the step in the results table.

## What you need

- **Phone:** a Pixel 2 XL (Android 11).
- **Fire TV:** any Fire TV stick or Cube on Fire OS 7 or newer. It has
  no Play Store and runs its own launcher.
- **Android TV:** any Android TV or Google TV device on Android 11 or
  newer, with the Play Store.
- **The build under test:** the `-alpha` APK from the release candidate's
  pre-release. It installs as `app.tellyscope.alpha` next to the regular
  app, so the regular app's TVs and History stay untouched. Debug builds
  and emulators don't count, because a debug build is far too slow to
  judge timing.
- **Optional, for whoever drives the test over ADB:** a computer with
  `adb` connected to the phone and to both TVs. It is used only to check
  what the TV did, never to replace a step in the app. The checks are
  listed under each section.

## Before you start

1. On both TVs, turn on ADB debugging and network or wireless debugging.
   Wake them and leave them on their home screen.
2. On the phone, open Developer options and turn on **Stay awake**, then
   plug it in. A locked phone stops the run.
3. Write down what you'll put back at the end: each TV's Home app, its
   Remote dock, its Private DNS setting and its animation speed.
4. Copy the results table below into the release pull request. Then add
   one row for each line under `## Unreleased` in `CHANGELOG.md`, and
   test those rows in the TV columns they apply to.

Steps marked ⚠ change the TV. Undo each one straight after, as the step
says.

## Running it from a computer

`.github/release-test.sh` drives the steps over ADB: it taps through the
app on the phone and checks the result on the TV. Run it with the TV's
ADB address and the phone's serial, which it reads only from the
environment:

```sh
TV=<tv-address>:5555 PHONE=<phone-serial> .github/release-test.sh        # every step
TV=… PHONE=… ASK=1 .github/release-test.sh R2 R6                          # ask at the steps a person must see
```

It prints one line per step ID and appends them to
`release-test-results.md`. MANUAL means the step needs hands on the TV
or the phone, and ASK means someone has to look. Those are C5, C6, D2,
L1, L3, O2, R2–R6, S1, S2, W2, X1, X2, the press on the TV in C4, and
the last look in L2. A3–A5, D3, I1–I3 and F1–F3 run by themselves once
you give them something safe to work on; without it they print SKIP or
MANUAL:

- `FIXTURES`: a folder holding `small.apk`, `older.apk` (an older build
  of the same app), `bundle.apks`, `photo.png`, `video.mp4` and
  `big.bin` (50 MB or more).
- `SMALL_APP`: the name `small.apk` shows in Apps.
- `OFF_APP` and `OFF_PKG`: an app A3 may turn off and on.
- `DEBLOAT_PKGS`: two or three packages from the Debloat list that D3 may
  turn off. It turns them back on with Undo, never Restore everything,
  so earlier debloats stay.

Z1 runs last even if you stop the run: it puts back
Private DNS, the animation speed and Stay awake, and lists any Home
change, leftover files or apps still off.

C1 clears the alpha app's storage: its saved TVs, Remote buttons, History
and settings are gone, and the phone has a new key, so the TV asks to
allow it again. Have someone at the TV for C4. Run C1 on a test phone or
accept losing that History; `SKIP="C1 C2 C3 C4"` keeps it.

## Results

Mark each cell ✅ pass, ❌ fail (open an issue and link it), or – not run,
with the reason in Notes. "Not run" is never a pass.

| ID | Step | Fire TV | Android TV | Notes |
|---|---|---|---|---|
| C1 | First launch and welcome | | | |
| C2 | Scan finds the TV | | | |
| C3 | Add by IP | | | |
| C4 | Allow on the TV, key matches | | | |
| C5 | Wireless debugging pairing code | | | |
| C6 | Reconnect after Wi-Fi off and on | | | |
| O1 | Overview numbers | | | |
| O2 | List card matches the TV | | | |
| A1 | Running and Installed tabs | | | |
| A2 | Search apps | | | |
| A3 | ⚠ Turn an app off, then on | | | |
| A4 | ⚠ Uninstall an app you installed | | | |
| A5 | Open an app from Apps | | | |
| D1 | Debloat review screen | | | |
| D2 | Untick a single app and a group | | | |
| D3 | ⚠ Run Debloat, then Restore | | | |
| H1 | History lists and undoes | | | |
| R1 | D-pad, Back, Home, Menu | | | |
| R2 | Volume and mute | | | |
| R3 | Typing on the TV | | | |
| R4 | Keyboard closes on tap outside | | | |
| R5 | Remote buttons: pick, open, edit | | | |
| R6 | Screen off and on | | | |
| R7 | ⚠ Restart TV and reconnect | | | |
| I1 | Install an APK | | | |
| I2 | Install a split bundle | | | |
| I3 | Update and older version warnings | | | |
| F1 | Send a file | | | |
| F2 | Save a file to the phone | | | |
| F3 | Cancel a transfer | | | |
| T1 | ⚠ Private DNS on, then off | | | |
| T2 | ⚠ Faster animations, then back | | | |
| T3 | Clear caches | | | |
| T4 | Take a screenshot | | | |
| L1 | Home screen card shows the right Current | | | |
| L2 | ⚠ Make Home, then back to the TV's own | | | |
| L3 | Install a home screen | | | |
| W1 | Who has access | | | |
| W2 | TV prompt answered from the phone | | | |
| M1 | Advanced monitor | | | |
| S1 | Settings, own lists, refused source | | | |
| S2 | Check for updates, report, share | | | |
| X1 | Switch between the two TVs | | | |
| X2 | One TV offline | | | |
| X3 | Cold start | | | |
| X4 | No crash all run | | | |
| Z1 | TV put back as you found it | | | |

## Steps

### Connect

- **C1 First launch.** Clear the alpha app's storage, then open it.
  *Expect:* Welcome to Tellyscope, with Continue and Skip. Continue goes
  through each page and ends on Add your TV.
- **C2 Scan.** Pick the TV's brand under Add your TV. *Expect:* the scan
  counts up, and the TV shows up with its address and the Connect
  button (Fire TV lists as Android device). Scan again looks once more.
  The phone itself can show up too; that's expected.
- **C3 Add by IP.** Tap Can't find your TV?, then Add by IP address, and
  type the TV's address. *Expect:* it goes to the same Connect screen as
  C2.
- **C4 Allow.** Tap Connect. *Expect:* the phone shows This phone's key.
  The TV shows Allow USB debugging? with the same fingerprint. Tick
  Always allow from this computer and press Allow. The phone lands on the
  TV's Overview within a few seconds. If nobody presses Allow in about a
  minute, the phone says No answer from the TV, with Try again.
- **C5 Pairing code** (wireless debugging only; skip on TVs without it).
  Forget the TV in the ⋮ menu, then add it again, using Pair with the
  6-digit code from the TV. Type one wrong code first. *Expect:* "That
  code didn't work" on the wrong code, then connected with the right one.
- **C6 Reconnect.** Turn the phone's Wi-Fi off for 10 s, then on.
  *Expect:* the TV reconnects by itself, with no tap needed.

### Overview

- **O1 Numbers.** *Expect:* free space, CPU, RAM, temperature and network
  speed fill in and keep moving, with nothing stuck at a dash. Paired and
  plugged-in devices are listed.
- **O2 List card.** *Expect:* the card names a list that fits this TV.
  If no list fits, it says No list for this TV yet and offers the
  maker's lists.

ADB check: `adb shell getprop ro.product.model` and compare it with the
model the card used.

### Apps

- **A1 Tabs.** *Expect:* Running lists what's open on the TV. Installed
  lists everything, with names, not package IDs, for the common apps.
  Built-in lists what came with the TV.
- **A2 Search.** Search for an app by part of its name. *Expect:* the
  list narrows as you type, and Clear search brings it all back.
- **A3 ⚠ Off and on.** Turn off one app you don't use, then turn it back
  on. *Expect:* it's marked off, History has the change, and turning it
  on clears the mark. Protected apps (the launcher, settings, input,
  Wi-Fi) offer no off.
- **A4 ⚠ Uninstall.** Only for an app you installed in I1. *Expect:* a
  confirm first, then it's gone from Installed. Built-in apps never offer
  uninstall.
- **A5 Open.** Open an app from its row. *Expect:* it comes up on the TV
  and appears under Running.

ADB check: `adb shell pm list packages -d` lists what's off;
`adb shell dumpsys window | grep mCurrentFocus` shows what's in front.

### Debloat

- **D1 Review.** Tap Debloat on the list card. *Expect:* every app it will
  turn off is listed, grouped, with a reason, before anything changes.
  The TV's home app isn't on it. If everything is already off, you see
  Everything on the list is off, with Go to Apps.
- **D2 Untick.** Untick one app, then tick and untick a whole group from
  its heading. *Expect:* the count follows each tap, and the group goes
  all on, then all off.
- **D3 ⚠ Run and restore.** Run Debloat with two or three harmless apps
  ticked. *Expect:* Disabling…, then Debloat done (or partly done, with
  the reason per app). Then open History and Restore everything. On a
  TV whose earlier Tellyscope changes should stay, Undo this run's rows
  instead; the script always does that.
  *Expect:* those apps are on again, and nothing that was off before
  Tellyscope's first change has been turned on.

### History

- **H1** Open History from the ⋮ menu. *Expect:* every change from A3 and
  D3 is there, newest first, each with Undo. Undo on one row turns that
  app back on and marks the row Undone.

### Remote

- **R1 Keys.** Use the D-pad, OK, Back, Home and Menu. *Expect:* the TV
  follows each press, and Vibrate on key press (Settings) ticks each one.
- **R2 Volume.** Volume up, down and mute. *Expect:* the TV's volume bar
  moves.
- **R3 Typing.** On the TV, open a search box. Type on the phone, then
  delete a letter. *Expect:* the text appears on the TV and the delete
  removes one letter. The Remote always shows "Select a text box on the
  TV, like a search box, then type here."; the app can't tell whether a
  box is selected.
- **R4 Keyboard.** While typing, tap empty space and then a Remote key.
  *Expect:* the keyboard closes, and Back, Home and Menu are back at full
  size.
- **R5 Remote buttons.** On the Remote, open ⋮ › Remote buttons and pick three apps.
  *Expect:* only apps installed on this TV are offered, a fourth can't be
  picked, and each button opens its app on the TV. Hold a button to
  edit it.
- **R6 Screen off.** On the Remote, ⋮ › Screen off, then any key. *Expect:* the picture
  goes dark while the app stays connected, and the key wakes it. On a
  TV that can't, you see "This TV can't turn its picture off".
- **R7 ⚠ Restart.** On the Remote, ⋮ › Restart TV, then confirm. *Expect:* Restarting…,
  and Tellyscope reconnects by itself when the TV is back. If it's slow,
  you see The TV hasn't come back yet, with Retry.

### Install apps

Have these on the phone before you start: a small APK, a split bundle
(`.apks`, `.xapk` or `.apkm`), and an older build of the same small APK.

- **I1 APK.** Apps › Install app, then pick the APK. *Expect:* it says
  whether it's new before it installs, shows Installing on the TV…, and
  the app is in Installed afterwards.
- **I2 Bundle.** Repeat I1 with the bundle. *Expect:* the same result.
- **I3 Versions.** Install the same APK again, then the older one.
  *Expect:* the same version is offered as Reinstall, and the older one
  asks first and needs Install the older version anyway.

### Files

- **F1 Send.** Files › Upload, then send a photo to Download and a video
  to Movies. *Expect:* progress, then each file is listed in its folder.
- **F2 Save.** Save one of them back to the phone. *Expect:* it's in the
  phone's Downloads.
- **F3 Cancel.** Start a large send and cancel it. *Expect:* "Cancelled.
  Nothing was left on the TV.", and no partial file in the folder.

ADB check: `adb shell ls -l /sdcard/Download /sdcard/Movies`.

### Tweaks

- **T1 ⚠ Private DNS.** Turn on Block ads with Private DNS. Type an IP
  address first, then `dns.adguard-dns.com`. *Expect:* the IP is refused,
  and the hostname is accepted. Then turn it off again. On Android 8 or
  older, you see Needs Android 9 or newer instead.
- **T2 ⚠ Faster animations.** Turn it on, check that menus move faster,
  then turn it off.
- **T3 Clear caches.** *Expect:* free space on the Overview goes up or
  stays the same, and an app you're signed in to is still signed in.
- **T4 Screenshot.** *Expect:* Screenshot saved to your phone, and the
  picture shows what the TV shows.

ADB check: `adb shell settings get global private_dns_mode` and
`adb shell settings get global animator_duration_scale`.

### Home screen

- **L1 Current.** Open the Home screen card in Tweaks. *Expect:* Current
  is whatever opens when you press Home on the TV's own remote. On Fire
  TV with Projectivy as Home, that's Projectivy, not the TV's own
  launcher.
- **L2 ⚠ Switch.** Make Home on another launcher, press Home on the TV,
  then switch back to the TV's own. *Expect:* each switch is checked
  before the old launcher is turned off. If a launcher keeps Home with an
  accessibility service, the service is offered with Turn it on and
  Switch without it, never turned on without asking.
- **L3 Install a home screen.** *Expect:* on Android TV, the recommended
  launchers, with Get it from Play Store first. On Fire TV, only
  Projectivy, with Download and install. This one shows only while no
  extra launcher is installed. Testing it with one installed means
  uninstalling that launcher first, so say in Notes whether you did.

### Who has access

- **W1** ⋮ › Who has access. *Expect:* this phone is marked This phone,
  and any other computer is listed with It's mine and Remove all other
  access…. Don't run the removal on a TV that other test devices use.
- **W2 Prompt.** From the computer, run `adb connect` to the TV with a key
  the TV hasn't seen (`adb kill-server` after moving `~/.android/adbkey`
  aside, then restore it). *Expect:* the phone shows Another device wants
  to control this TV, with the fingerprint. Deny refuses it, and Allow
  lets it in.

### Advanced monitor

- **M1** ⋮ › Advanced monitor. *Expect:* CPU and RAM by app, processes,
  network and data used all fill in within about 10 s and keep updating.

### Settings and the rest

- **S1 Lists.** Settings › Advanced › Use your own debloat lists. Paste an
  HTTPS address that doesn't serve an index. *Expect:* it's refused, and
  the lists still come from cleanyourtv. Reset to default changes
  nothing else.
- **S2 Check, report, share.** Run Check for updates, then Report a
  problem up to the GitHub page without posting, then Share Tellyscope.
  *Expect:* each one opens what it says. The report shows What gets sent,
  and that has no serial number, IP address or account name.

### Across TVs

- **X1 Switch.** Switch from one TV to the other and back, four times.
  *Expect:* the right TV's page each time, with its own apps showing
  straight away and its own Remote buttons.
- **X2 Offline.** Unplug one TV, then open it in the app. *Expect:* TV
  offline, with no crash and no spinner left running forever. Plug it
  back in, and it reconnects by itself.
- **X3 Cold start.** Swipe the app away and open it again, twice.
  *Expect:* it opens on the last TV, connected, not on You were logged
  out.
- **X4 No crash.** *Expect:* the app never closed by itself during the
  run. Check it with `adb logcat -b crash -d`, which should show nothing
  from `app.tellyscope`.

### Put it back

- **Z1** Put back what you wrote down before you started: Home app, Remote
  dock, Private DNS and animations. Uninstall the test APKs from I1–I3,
  and delete the files from F1. Check History has nothing left off that
  you turned off during the run.

## Known TV quirks

These come from earlier runs, so a quirk on this list isn't a new bug.

- **Fire TV, prompt hidden.** The Allow USB debugging? prompt can open
  behind a third-party launcher like Projectivy, where nobody sees it.
  Press Home, or bring it up from ADB: `adb shell am stack list` shows
  its task ID, and `adb shell am stack move-task <id> 4 true` brings it
  to the front.
- **Fire TV, stale prompts.** A prompt from an old computer can be
  waiting ahead of the phone's. Check the fingerprint, and Cancel any
  that aren't the phone's.
- **Fire TV, the Search key** does nothing in most apps. Don't count it
  as a Tellyscope bug.
- **Fire TV, `monkey`** fails without `--pct-syskeys 0`, which affects
  Open and the Remote buttons. Fixed in 0.13.0 (#248). If it comes back,
  that's a regression.
- **The phone in the scan.** With wireless debugging on, the phone shows
  up in its own scan.
- **Fire TV, asleep.** The TV sleeps after 5 minutes. While it sleeps,
  the Allow prompt isn't seen (the phone can't show it in W2) and
  screenshots come out black. Wake it with `adb shell input keyevent 224`.
- **Fire TV, after a restart.** "Unable to Detect Your Remote" can cover
  the Allow prompt. Press OK on it first.
- **Fire TV, volume.** On HDMI the volume is fixed, and Fire OS drops
  volume keys sent over ADB: the log says "ignore volume adjustment on
  fixed volume device". The TV's own remote works because it sends IR.
- **A second ADB connection** from your computer counts as another
  computer. In W2 and R7, disconnect your own `adb` from the TV first.
- **The phone's file picker** ignores taps sent over ADB. Search for the
  file, then press D-pad Down twice and Center (keyevents 20, 20, 23).
