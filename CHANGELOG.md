# Changelog

What changed in each Tellyscope release, newest first. Each entry is written
for the people using the app. Headings are `## <version> — <date>`, where
`<version>` is the release's `versionName`. One line per change:
**New:**, **Improved:** or **Fixed:**, in that order within a section, then a
plain line of at most about twelve words, ending with a link to its PR.

Changes merged into `dev` collect under `## Unreleased`. A release renames
that heading to its version and date. A pull request into `main` fails CI
until this file has a section for its version.

## Unreleased

## 0.13.0 — 2026-10-29

- **New:** Remote buttons open Netflix, YouTube, Plex and 20 more streaming apps. [#224](../../pull/224)
  - Hold a button to pick up to three.
- **New:** On Fire TV, Tellyscope downloads Projectivy, checks its signature and installs it. [#232](../../pull/232)
- **New:** On Android TV, Projectivy can also be downloaded directly, after a warning. [#236](../../pull/236)
- **New:** Who has access shows the TV's Allow prompt, and answers it from the phone. [#253](../../pull/253)
- **Improved:** New launchers and streaming apps arrive without an app update. [#225](../../pull/225)
- **Improved:** On the Remote, tap empty space to close the keyboard. [#222](../../pull/222)
- **Improved:** Debloat ticks a whole group of apps in one tap, from its heading. [#219](../../pull/219)
- **Improved:** Install a home screen offers Projectivy and FLauncher, and marks ones already installed. [#223](../../pull/223)
- **Improved:** While you type on the Remote, the D-pad gets more room. [#229](../../pull/229)
- **Improved:** Find your TV looks again every 10 seconds, and has a Rescan button. [#234](../../pull/234)
- **Improved:** Switching TVs shows each TV's apps straight away. [#242](../../pull/242)
- **Improved:** Install apps opens in Downloads, showing only apps. [#253](../../pull/253)
- **Fixed:** On Fire TV, a debugging prompt hidden behind the remote alert shows on the phone. [#237](../../pull/237)
- **Fixed:** On Fire TV, Apps › Open now opens the app. [#248](../../pull/248)
- **Fixed:** Apps without a name of their own no longer show a class name. [#249](../../pull/249)

## 0.12.0 — 2026-10-28

- **New:** Reset TV, in ⋮: turn your TV back to how it shipped, with one Undo. [#192](../../pull/192)
  - Turns preinstalled apps back on and brings back uninstalled ones; apps the maker shipped off stay off.
  - Makes the TV's own launcher Home again; launchers you installed stay installed.
- **New:** Debloat shows each app as it turns off, then what worked and what didn't. [#214](../../pull/214)
- **Improved:** The Remote opens as a sheet over your TV and never scrolls. [#198](../../pull/198)
  - Screen off and Restart TV sit beside the handle; the header shows the TV's state.
  - Dock apps get equal, well-spaced slots, so a tap can't hit the wrong app.
  - On short phones, mute and volume move beside the D-pad; while typing, the D-pad shrinks to fit.
- **Fixed:** Reset TV's buttons sit above the navigation bar, not under it. [#207](../../pull/207)
- **Fixed:** Turning on an app the TV switched off says why, not a raw error. [#205](../../pull/205)
- **Fixed:** Make home and Reset TV go straight to the new home screen. [#208](../../pull/208)
- **Fixed:** After Reset TV, Debloat counts and lists the apps it turned back on. [#209](../../pull/209)
- **Fixed:** Tapping a dock app on the Remote vibrates, like the keys do. [#213](../../pull/213)

## 0.11.3 — 2026-10-28

- **Fixed:** The TV's page shows the result of a change right away. [#202](../../pull/202)
  - A stopped or turned-off app leaves Running at once, and an opened one joins it.
  - Free storage updates right after clearing caches, installing, uninstalling or uploading.
  - A change that fails partway shows what the TV has now.
  - A device you allow shows under access straight away.

## 0.11.2 — 2026-10-28

- **Improved:** The Share message now says what Tellyscope does, in a friendlier voice. [#197](../../pull/197)
- **Fixed:** An unpaired TV on wireless debugging asks for the pairing code, not "didn't answer". [#200](../../pull/200)

## 0.11.1 — 2026-10-27

- **New:** Share Tellyscope, in ⋮ and in Settings, sends a friend the GitHub link. [#191](../../pull/191)

## 0.11.0 — 2026-10-27

- **New:** A Home screen card in Tweaks: pick your TV's home screen in one place. [#186](../../pull/186)
  - Switch between Projectivy, FLauncher and the TV's own launcher with one tap.
  - Launchers you installed stay on. Only the TV's built-in one is turned off, and Undo brings it back.
- **Improved:** The ⋮ menu is now a bottom sheet with bigger rows. [#188](../../pull/188)
  - History (every change, with Undo and Restore everything) moved here from Tweaks.
  - A dot on ⋮ tells you when there's a new change in History.
  - Advanced is renamed Advanced monitor.
- **Improved:** The Remote's dock moves to the bottom; Screen off and Restart go under ⋮. [#182](../../pull/182)
- **Improved:** Debloat says when the list is all done, and what to try next. [#181](../../pull/181)
- **Improved:** Every dialog uses one style, with the main action filled. [#180](../../pull/180)
- **Fixed:** Undo in History turns off a new home screen's accessibility service, as promised. [#185](../../pull/185)
- **Fixed:** Restore everything always ends on your TV's own home screen. [#184](../../pull/184)
- **Fixed:** The Remote's backspace no longer closes the TV app once the box is empty. [#183](../../pull/183)

## 0.10.1 — 2026-10-27

- **Improved:** Apps shows it's loading while the TV's app list is read. [#176](../../pull/176)
- **Improved:** Opening a TV reads its apps once, in one go, so Apps fills in sooner. [#173](../../pull/173)
- **Improved:** App icons load once per TV and no longer slow the screen down. [#175](../../pull/175)
- **Improved:** Tellyscope stops polling your TV while it's in the background. [#172](../../pull/172)
- **Improved:** The TV's page no longer redraws every app each second. [#174](../../pull/174)

## 0.10.0 — 2026-10-26

- **New:** Pick a better home screen, install it from the Play Store and switch to it. [#152](../../pull/152)
- **New:** Remove the home screen you're using; your TV switches back to its own first. [#158](../../pull/158)
- **Improved:** A launcher's accessibility service only turns on if you say yes. [#150](../../pull/150)
- **Improved:** The Remote's clear key deletes one letter; hold it to keep deleting. [#162](../../pull/162)
- **Improved:** On Fire TV, the Remote's Home button opens your chosen home screen (tester needed). [#153](../../pull/153)
- **Improved:** A USB camera shows up once, with a camera icon, under Input. [#154](../../pull/154)
- **Fixed:** Only the home screen you actually use is protected from removal. [#163](../../pull/163)
- **Fixed:** If your TV takes back this phone's access, you're logged out instead of asked again and again. [#149](../../pull/149)

## 0.9.0 — 2026-10-26

- **New:** A button on the Remote empties the TV's text box. [#144](../../pull/144)
- **New:** Screen off turns off the TV's picture from the Remote; any key wakes it. [#139](../../pull/139)
- **New:** See who has ADB access to your TV, and remove it in one tap. [#137](../../pull/137)
- **New:** Connected devices shows what's paired or plugged into the TV. [#137](../../pull/137)
- **Improved:** The warning about another computer shows without scrolling. [#143](../../pull/143)
- **Improved:** The dock picker leaves out built-in phone-style apps like Camera. [#138](../../pull/138)
- **Improved:** About links to the website, GitHub and the demo video. [#137](../../pull/137)
- **Fixed:** Remove all other access waits for this phone to be allowed again. [#141](../../pull/141)
- **Fixed:** The Remote no longer jumps while you type. [#141](../../pull/141)
- **Fixed:** Apps show their real icon, not a banner strip or letter. [#136](../../pull/136)

## 0.8.1 — 2026-10-25

- **New:** The Remote has icon keys like Google TV's phone remote, with Menu. [#132](../../pull/132)

## 0.8.0 — 2026-10-25

- **New:** Report a problem or help add your TV: GitHub opens with the report filled in. [#127](../../pull/127)
- **New:** About shows the app's version and its GitHub page. [#125](../../pull/125)
- **New:** Dock up to four apps on the Remote, one tap to open. [#120](../../pull/120)
- **New:** Get Tellyscope from Orion Store or Obtainium, with badges in the README. [#123](../../pull/123)
- **Improved:** TV-only apps get a clear letter tile in their banner's colours. [#131](../../pull/131)
- **Improved:** Restart TV sits in the Remote's top bar and waits with you. [#120](../../pull/120)
- **Improved:** Sleep and Menu leave the Remote, making room for the dock. [#120](../../pull/120)
- **Fixed:** Menus and the TV header no longer turn purple without dynamic colour. [#119](../../pull/119)

## 0.7.0 — 2026-10-25

- **New:** The Remote is back, and keys land in about 50 ms. [#111](../../pull/111)
- **New:** Projectivy, AT4K and Dispatch stay Home: Set as default turns on their service. [#108](../../pull/108)
- **Improved:** Typing from the Remote takes a fraction of a second. [#111](../../pull/111)
- **Improved:** The Remote opens from a button at the bottom of the TV page. [#113](../../pull/113)
- **Fixed:** Apps no longer crashes on TVs that list an app twice. [#109](../../pull/109)
- **Fixed:** An older "Switched home back" loses its Undo after a newer switch. [#108](../../pull/108)

## 0.6.3 — 2026-10-25

- **New:** An Other chip in the pairing guide, for TVs not listed. [#103](../../pull/103)
- **Improved:** Each screen reads only what it shows, at its own rate. [#101](../../pull/101)
- **Fixed:** A slow stats read no longer drops the TV mid-change. [#101](../../pull/101)

## 0.6.2 — 2026-10-25

- **Improved:** The README names the scan's lookup of each TV's name. [#93](../../pull/93)
- **Fixed:** App buttons and Undo wait while another change is running. [#92](../../pull/92)
- **Fixed:** Turning off a launcher mid-switch no longer crashes the Apps tab. [#92](../../pull/92)
- **Fixed:** A TV connecting mid-copy no longer hides the copy's dialog. [#93](../../pull/93)
- **Fixed:** Undo can't run twice on the same change. [#93](../../pull/93)
- **Fixed:** Fire TV and Philips Settings and the media storage service are protected. [#93](../../pull/93)
- **Fixed:** Debloat no longer crashes on a list that repeats an app. [#91](../../pull/91)
- **Fixed:** One bad entry no longer hides every debloat list. [#91](../../pull/91)
- **Fixed:** Sending a file never overwrites a hidden file on the TV. [#91](../../pull/91)
- **Fixed:** A failed Private DNS put-back is reported, with Undo in History. [#91](../../pull/91)
- **Fixed:** An empty scan now retries every 15 seconds. [#91](../../pull/91)

## 0.6.1 — 2026-10-25

- **Fixed:** Set as default launcher now works when Android TV Home is Home. [#84](../../pull/84)

## 0.6.0 — 2026-10-24

- **New:** Switch home back to the TV's own launcher in one tap; list keeps only warn. [#76](../../pull/76)
- **Improved:** The README now shows a real TV home screen after Tellyscope. [#79](../../pull/79)
- **Improved:** Debloat finds your TV's list itself, or its maker's, or lets you pick. [#70](../../pull/70)
- **Improved:** The TV tops every tab and folds into the bar as you scroll. [#71](../../pull/71)
- **Improved:** Apps splits into Running, Installed and Built-in tabs, with the apps the TV needs folded under System. [#72](../../pull/72)
- **Improved:** The TV's icon and name glide into the bar, Apps and Files pin their buttons, and empty pages say so. [#74](../../pull/74)
- **Improved:** Protected apps drop the lock icon; the line saying why stays. [#74](../../pull/74)
- **Improved:** Search apps sits under the Apps buttons and opens to every matching app, full screen. [#78](../../pull/78)
- **Improved:** The ⋮ menu names its TV and groups its items; Settings folds the list source under Advanced. [#78](../../pull/78)
- **Improved:** Messages at the bottom of the screen clear after 4 seconds, not 10. [#77](../../pull/77)
- **Fixed:** The upload icon draws properly again. [#74](../../pull/74)
- **Fixed:** Welcome and pairing buttons no longer hide under the phone's navigation buttons. [#69](../../pull/69)

## 0.5.0 — 2026-10-24

- **New:** The app opens on its logo, then a one-time welcome for first-time users. [#65](../../pull/65)
- **Improved:** Add your TV step by step, with a screen per TV showing this phone's key. [#66](../../pull/66)
- **Improved:** Overview puts the TV first, with chips to switch and debloat counts. [#64](../../pull/64)
- **Improved:** An offline TV keeps its maker and Android version after the app restarts. [#63](../../pull/63)

## 0.4.0 — 2026-10-24

- **Improved:** Each TV's page has four tabs: Overview, Apps, Files and Tweaks. [#60](../../pull/60)

## 0.3.0 — 2026-10-24

- **New:** Off Wi-Fi, the scan screen shows how to connect instead of an empty list. [#55](../../pull/55)
- **Improved:** Picking your TV: your match on its own, or search by maker. [#49](../../pull/49)

## 0.2.2 — 2026-10-23

- **Improved:** Checks for a new version every 30 minutes, not 6 hours. [#53](../../pull/53)
- **Fixed:** Pairing finds the port the TV shows now, after the dialog reopens. [#54](../../pull/54)

## 0.2.1 — 2026-10-23

- **Fixed:** Wireless-debugging TVs ask for the pairing code instead of looping on Allow. [#50](../../pull/50)

## 0.2.0 — 2026-10-23

- **New:** Uninstall apps you installed yourself; apps that came with the TV can only be disabled. [#33](../../pull/33)
- **New:** Block ads on the whole TV with Private DNS, AdGuard by default. [#35](../../pull/35)
- **New:** Faster animations: menus move at twice the speed. [#35](../../pull/35)
- **New:** Clear every app's cache in one tap; app data stays. [#35](../../pull/35)
- **New:** Save a screenshot of the TV to your phone. [#35](../../pull/35)
- **New:** Allow or deny another device's connection request from your phone. [#36](../../pull/36)
- **New:** Set another launcher, like Projectivy, as default; Undo restores yours. [#37](../../pull/37)
- **New:** Use your own fork of the debloat lists, set in Settings. [#40](../../pull/40)
- **New:** Hear when a new version is out; turn it off in Settings. [#43](../../pull/43)
- **Improved:** Apps that can't be changed fold into their own section below the list. [#33](../../pull/33)
- **Fixed:** App icons that failed to load once now come back, with a fallback to the banner. [#34](../../pull/34)

## 0.1.0 — 2026-10-23

- **New:** A new app icon: a telescope with a TV screen for a lens. [#28](../../pull/28)
- **New:** Install apps on the TV from an APK, split APKs or a bundle. [#9](../../pull/9)
- **New:** Send files to the TV's Download or Movies folder, and save them back. [#9](../../pull/9)
- **New:** Suggested apps to disable, from a tested list for your TV. [#11](../../pull/11)
- **New:** An icon shows whether each device is a TV, projector, Fire TV or stick. [#18](../../pull/18)
- **New:** Debloat in two steps: pick your TV, then review every app it turns off. [#23](../../pull/23)
- **Improved:** The confirm before disabling suggested apps names every app. [#19](../../pull/19)
- **Improved:** System updaters, Wi-Fi resources and Chrome's library are now protected. [#19](../../pull/19)
- **Improved:** The TV page leads with the TV, its vitals and big action buttons. [#23](../../pull/23)
- **Improved:** Undoing a Turn on in History asks before disabling the app. [#25](../../pull/25)
- **Fixed:** Processes, screen state, connections and data used now show on real TVs. [#17](../../pull/17)
- **Fixed:** A TV on Wi-Fi no longer shows its connection as Ethernet. [#17](../../pull/17)
- **Fixed:** Done and error messages stay on screen long enough to read. [#20](../../pull/20)
- **Fixed:** Undo and results always go to the TV the action ran on. [#20](../../pull/20)
- **Fixed:** Adding a TV with a port above 65535 shows a message, not a crash. [#25](../../pull/25)
- **Fixed:** Losing Wi-Fi while pairing shows a message, not a crash. [#25](../../pull/25)
- **Fixed:** A broken suggestions list can no longer crash the app. [#25](../../pull/25)
