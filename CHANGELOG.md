# Changelog

What changed in each Tellyscope release, newest first. Each entry is written
for the people using the app. Headings are `## <version> — <date>`, where
`<version>` is the release's `versionName`. One line per change:
**New:**, **Improved:** or **Fixed:**, then a plain line of at most about
twelve words, ending with a link to its PR.

Changes merged into `dev` collect under `## Unreleased`. A release renames
that heading to its version and date. A pull request into `main` fails CI
until this file has a section for its version.

## Unreleased

- **New:** An Other chip in the pairing guide, for TVs not listed. [#100](../../pull/100)

## 0.6.2 — 2026-10-25

- **Fixed:** App buttons and Undo wait while another change is running. [#92](../../pull/92)
- **Fixed:** Turning off a launcher mid-switch no longer crashes the Apps tab. [#92](../../pull/92)
- **Fixed:** A TV connecting mid-copy no longer hides the copy's dialog. [#93](../../pull/93)
- **Fixed:** Undo can't run twice on the same change. [#93](../../pull/93)
- **Fixed:** Fire TV and Philips Settings and the media storage service are protected. [#93](../../pull/93)
- **Improved:** The README names the scan's lookup of each TV's name. [#93](../../pull/93)
- **Fixed:** Debloat no longer crashes on a list that repeats an app. [#91](../../pull/91)
- **Fixed:** One bad entry no longer hides every debloat list. [#91](../../pull/91)
- **Fixed:** Sending a file never overwrites a hidden file on the TV. [#91](../../pull/91)
- **Fixed:** A failed Private DNS put-back is reported, with Undo in History. [#91](../../pull/91)
- **Fixed:** An empty scan now retries every 15 seconds. [#91](../../pull/91)

## 0.6.1 — 2026-10-25

- **Fixed:** Set as default launcher now works when Android TV Home is Home. [#84](../../pull/84)

## 0.6.0 — 2026-10-24

- **Improved:** The README now shows a real TV home screen after Tellyscope. [#79](../../pull/79)
- **Improved:** Debloat finds your TV's list itself, or its maker's, or lets you pick. [#70](../../pull/70)
- **Improved:** The TV tops every tab and folds into the bar as you scroll. [#71](../../pull/71)
- **Improved:** Apps splits into Running, Installed and Built-in tabs, with the apps the TV needs folded under System. [#72](../../pull/72)
- **Improved:** The TV's icon and name glide into the bar, Apps and Files pin their buttons, and empty pages say so. [#74](../../pull/74)
- **Improved:** Protected apps drop the lock icon; the line saying why stays. [#74](../../pull/74)
- **Fixed:** The upload icon draws properly again. [#74](../../pull/74)
- **New:** Switch home back to the TV's own launcher in one tap; list keeps only warn. [#76](../../pull/76)
- **Improved:** Search apps sits under the Apps buttons and opens to every matching app, full screen. [#78](../../pull/78)
- **Improved:** The ⋮ menu names its TV and groups its items; Settings folds the list source under Advanced. [#78](../../pull/78)
- **Fixed:** Welcome and pairing buttons no longer hide under the phone's navigation buttons. [#69](../../pull/69)
- **Improved:** Messages at the bottom of the screen clear after 4 seconds, not 10. [#77](../../pull/77)

## 0.5.0 — 2026-10-24

- **Improved:** Add your TV step by step, with a screen per TV showing this phone's key. [#66](../../pull/66)
- **New:** The app opens on its logo, then a one-time welcome for first-time users. [#65](../../pull/65)
- **Improved:** Overview puts the TV first, with chips to switch and debloat counts. [#64](../../pull/64)
- **Improved:** An offline TV keeps its maker and Android version after the app restarts. [#63](../../pull/63)

## 0.4.0 — 2026-10-24

- **Improved:** Each TV's page has four tabs: Overview, Apps, Files and Tweaks. [#60](../../pull/60)

## 0.3.0 — 2026-10-24

- **Improved:** Picking your TV: your match on its own, or search by maker. [#49](../../pull/49)
- **New:** Off Wi-Fi, the scan screen shows how to connect instead of an empty list. [#55](../../pull/55)

## 0.2.2 — 2026-10-23

- **Improved:** Checks for a new version every 30 minutes, not 6 hours. [#53](../../pull/53)
- **Fixed:** Pairing finds the port the TV shows now, after the dialog reopens. [#54](../../pull/54)

## 0.2.1 — 2026-10-23

- **Fixed:** Wireless-debugging TVs ask for the pairing code instead of looping on Allow. [#50](../../pull/50)

## 0.2.0 — 2026-10-23

- **New:** Uninstall apps you installed yourself; apps that came with the TV can only be disabled. [#33](../../pull/33)
- **Improved:** Apps that can't be changed fold into their own section below the list. [#33](../../pull/33)
- **Fixed:** App icons that failed to load once now come back, with a fallback to the banner. [#34](../../pull/34)
- **New:** Block ads on the whole TV with Private DNS, AdGuard by default. [#35](../../pull/35)
- **New:** Faster animations: menus move at twice the speed. [#35](../../pull/35)
- **New:** Clear every app's cache in one tap; app data stays. [#35](../../pull/35)
- **New:** Save a screenshot of the TV to your phone. [#35](../../pull/35)
- **New:** Allow or deny another device's connection request from your phone. [#36](../../pull/36)
- **New:** Set another launcher, like Projectivy, as default; Undo restores yours. [#37](../../pull/37)
- **New:** Use your own fork of the debloat lists, set in Settings. [#40](../../pull/40)
- **New:** Hear when a new version is out; turn it off in Settings. [#43](../../pull/43)

## 0.1.0 — 2026-10-23

- **New:** A new app icon: a telescope with a TV screen for a lens. [#28](../../pull/28)
- **New:** Install apps on the TV from an APK, split APKs or a bundle. [#9](../../pull/9)
- **New:** Send files to the TV's Download or Movies folder, and save them back. [#9](../../pull/9)
- **New:** Suggested apps to disable, from a tested list for your TV. [#11](../../pull/11)
- **Fixed:** Processes, screen state, connections and data used now show on real TVs. [#17](../../pull/17)
- **Fixed:** A TV on Wi-Fi no longer shows its connection as Ethernet. [#17](../../pull/17)
- **New:** An icon shows whether each device is a TV, projector, Fire TV or stick. [#18](../../pull/18)
- **Improved:** The confirm before disabling suggested apps names every app. [#19](../../pull/19)
- **Improved:** System updaters, Wi-Fi resources and Chrome's library are now protected. [#19](../../pull/19)
- **Fixed:** Done and error messages stay on screen long enough to read. [#20](../../pull/20)
- **Fixed:** Undo and results always go to the TV the action ran on. [#20](../../pull/20)
- **New:** Debloat in two steps: pick your TV, then review every app it turns off. [#23](../../pull/23)
- **Improved:** The TV page leads with the TV, its vitals and big action buttons. [#23](../../pull/23)
- **Fixed:** Adding a TV with a port above 65535 shows a message, not a crash. [#25](../../pull/25)
- **Fixed:** Losing Wi-Fi while pairing shows a message, not a crash. [#25](../../pull/25)
- **Fixed:** A broken suggestions list can no longer crash the app. [#25](../../pull/25)
- **Improved:** Undoing a Turn on in History asks before disabling the app. [#25](../../pull/25)
