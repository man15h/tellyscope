# Contributing to Tellyscope

Thanks for helping! Tellyscope is young, so the code and its structure are still moving.

## Ground rules

- **Read the [safety rules](README.md#safety-rules) first.** A pull request that uninstalls apps that came with the TV, bypasses the protected list or skips recording what was already off won't be merged.
- **Open pull requests against `dev`.** `main` is the release branch and only takes `dev`.
- **One change per pull request.** Smaller is easier to review.
- **Don't edit [CHANGELOG.md](CHANGELOG.md).** Put your change's line in the pull request description instead, as `Changelog: **New:** …`, `**Improved:**` or `**Fixed:**`, in the format the changelog's header gives. Leave it out when users won't notice the change. The release pull requests add the lines.
- **`./gradlew build` must pass** before you open a PR. CI runs the same command.
- **New files start with an SPDX header:** `// SPDX-License-Identifier: GPL-3.0-only`.
- **Licensing.** By contributing you agree that your work is licensed under GPL-3.0. If you adapt code or lists from another project, check that its license is compatible and add it to [NOTICE](NOTICE) in the same PR.

## Releases

- **Alpha:** to publish an alpha, put `[release]` anywhere in the title of the pull request into `dev` (any case), for example `Changelog for 0.16 alpha 1 [release]`. That pull request changes only CHANGELOG.md: it copies the `Changelog:` lines of the pull requests merged since the last release under `## Unreleased`. Merges without `[release]` only run CI. On a `[release]` merge, CI builds `app.tellyscope.alpha`, which installs next to the release, and once the build passes it tags the commit `vX.Y.Z-alpha.N` and publishes a GitHub pre-release under that tag, with the APK named `Tellyscope-vX.Y.Z-alpha.N.apk`. `X.Y.Z` is the current `versionName`, and `N` is one past the highest alpha of that version. A failed build tags nothing. You can still put out an alpha by hand: tag a commit that is on `dev` and push the tag, for example `git tag v0.1.0-alpha.1 origin/dev && git push origin v0.1.0-alpha.1`. The tag's `X.Y.Z` must equal `versionName` in `app/build.gradle.kts`, and its commit must be on `dev`; otherwise the build fails and publishes nothing.
- **Shipping:** a release is a pull request from `dev` into `main`, merged with a merge commit. It must raise `versionName` and `versionCode` in `app/build.gradle.kts` past the latest `v*` tag and turn the `## Unreleased` section of [CHANGELOG.md](CHANGELOG.md) into that version's section; CI refuses anything else. Merging it tags `vX.Y.Z` and publishes a GitHub release with the signed APK, notes from the changelog section and the commits since the previous tag. A second job then builds the same tag as the `play` flavor, an app bundle for Google Play, signed with the same release key. It is not attached to the release: download it from the release run's artifacts as `Tellyscope-X.Y.Z-play` (a zip holding `Tellyscope-X.Y.Z-play.aab`, kept 90 days) and upload it to Play Console.
- **Signing:** alphas and releases are signed with two different keys, so an alpha can never install over the release app. Each key lives in its own GitHub Environment: `ALPHA_KEYSTORE_BASE64`, `ALPHA_KEY_ALIAS` and `ALPHA_KEY_PASSWORD` in `alpha`, which deploys only from `dev` and `v*-alpha.*` tags; `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` in `release`, which deploys only from `main`. `*_KEYSTORE_BASE64` is the keystore, base64-encoded, and `*_KEY_PASSWORD` is both the store and the key password (keytool's PKCS12 default uses one for both). A job with its secrets missing fails; it never falls back to the other key. Only the maintainer holds the keys; never commit a keystore. Local release builds are unsigned.

## Debloat lists

The per-TV lists behind **Debloat** and the Overview's list card live in the [tellyscope-devices](../../../tellyscope-devices) repository, not here; add or fix a list there. When you add a package to a list:

- Say which TV models you tested on, and what changed after disabling it.
- If disabling it broke anything (launcher, updates, remote, input, HDMI-CEC), put it in the list's keep entries. If it must never be stopped or disabled, add it to tellyscope-devices' `lists/protected.json` when only one maker's TVs have it, or propose it for the built-in protected list (`adb/src/main/kotlin/app/tellyscope/adb/Protected.kt`) here when every Android TV, Google TV or Fire TV can have it.

### Using your own lists

You don't have to trust ours. Fork tellyscope-devices, edit the lists in your fork, and keep `index.json` on schema 1 with each file's sha256 updated. Then in the app open **Settings** (in the ⋮ menu at the top right), open **Advanced** at the bottom, tap **Use your own debloat lists**, and paste the raw address of the folder that holds `index.json`, for example `https://raw.githubusercontent.com/<you>/tellyscope-devices/main/`. Only HTTPS is accepted, and a source that doesn't serve a schema 1 index is refused without changing anything. Switching drops the cached lists. While your source is in use, the review step says where the list came from. Your source supplies only debloat lists, extra protected apps and the Remote's logos (a service your source has no logo for shows its name): launchers, app names and streaming services always come from this repository's `data/`. The built-in protected list still wins over any list, and **Reset to default** goes back to tellyscope-devices.

## Launchers, app names and streaming services

These lists live in this repository, in [`data/`](data/), and the app downloads them from `main`: change one here with a pull request, and it reaches people once it's on `main`, without an app release. Keep `data/index.json` in step: it gives each file's sha256, and the app uses only files that match. [`DataListsTest`](app/src/test/java/app/tellyscope/DataListsTest.kt) checks the lists and the index, and says which sha256 changed. tellyscope-devices still has older copies that earlier versions of the app download; they're frozen until they're retired, so don't edit them there. File formats are in that repo's README.

- `data/launchers.json`: the third-party launchers Tellyscope knows. One with an `"about"` line is offered in **Install a home screen**, under **Editor's choice**, in the file's order. The app names no launcher itself: what it needs to install one from an APK comes from its entry (`signers`, `max_mb`, `releases`, `apk`, `fire_os`; `DataListsTest` checks their formats). `apk` names the asset to take when a release carries more than one APK; without it, a release must carry exactly one. On Fire OS, which has no Play Store, only the `fire_os` launchers are offered, those on the list with `signers`, in the file's order: Tellyscope downloads the chosen one from its `releases` page and installs only an APK signed with one of its `signers` ([`LauncherDownload.kt`](app/src/main/java/app/tellyscope/data/LauncherDownload.kt)). On Android TV the same download is offered as **Download directly**, under the Play Store and after a warning, for any launcher with both `signers` and `releases` (`downloadable` in [`LauncherFlowUi.kt`](app/src/main/java/app/tellyscope/ui/LauncherFlowUi.kt)). The Play build downloads neither: on Fire OS it asks you to download the launcher in your browser and pick the file, which it checks the same way. When `data/` fails its signature check ([below](#signing-data)), every one of those installs first shows the list's address and the signer, and waits for a tick saying you trust them: Tellyscope can then only check the APK matches the list.
- Pictures for the picker: an entry's `"icon"` (its app icon) and `"screenshot"` (its home screen) are `https` addresses on the launcher developer's own page, such as its GitHub repository, and `"credit"` says where they come from ("Image from Klevico's GitHub page"), shown under the screenshot. Tellyscope keeps no copy of their art in this repository: the phone fetches each image once, from that address, and caches it, only for launchers on this list and only while `data/` passes its signature check. It takes a PNG, JPEG or WebP of at most 2 MB and 4096 px a side; the picker shows the icon on every row and the screenshot on the chosen one, and goes without either when it can't be had.
- `data/labels.json`: names shown for common TV apps when the TV can't tell us.
- `data/streaming.json`: the streaming services the Remote can have a button for. Each names a logo, which stays in tellyscope-devices' `logos/` with its source and licence in `SOURCES.md`.

A launcher that keeps Home with its own accessibility service carries `"accessibility": "<version>"` in `data/launchers.json`, and only those may have that service turned on. An accessibility service can read the screen, so the field counts only while `data/` passes its signature check: while it fails, Tellyscope turns no launcher's service on, even when you've ticked that you trust the list. Turning one off, when you switch away from that launcher, Undo or Reset, works either way. Its value is the minimum version that has the service: Tellyscope offers it, and turns it on, only when the TV's copy of the launcher is that version or newer, and not when its version can't be read. Add the field only after checking that version's manifest for a service with `BIND_ACCESSIBILITY_SERVICE`. `DataListsTest` names every such launcher on purpose, so add yours there too and a reviewer sees it.

The tests use small fixtures in [`app/src/test/resources/fixtures/`](app/src/test/resources/fixtures/) that only check the code: change the real debloat lists in tellyscope-devices and the others in `data/`, not there. The app itself bundles none.

### Signing `data/`

The app trusts `data/` only when `data/index.json` is signed. It holds the sha256 of every file in
`data/`, and its line 2 holds the signatures:

```json
{
  "sigs": ["p256:<base64>"],
  "schema": 1,
  …
```

Each entry is an ECDSA P-256 / SHA-256 signature (DER, base64) over the exact bytes of the file with
line 2 cut out. Nothing parses and rewrites the JSON to check it, so a file reformatted after signing
fails, and `index` puts it right. A checker skips tags it doesn't know and passes if any `p256` entry
verifies against any key, so a new key or algorithm can sign next to the old one. The public keys
are built into the app and kept in [`tools/data-keys/`](tools/data-keys/) for the `Data` check: a primary,
used for every update, and a backup, so losing or leaking the primary doesn't lock apps out.

**Nobody signs locally.** The `Data` workflow signs after merge, on every push to `dev` that changes the
index:

1. Contributors run `python3 tools/data.py index` and `python3 tools/data.py check --unsigned`, which is
   also what `Data` checks on a pull request into `dev`.
2. After the merge, `Data`'s `needs-sign` job runs `index` and `check` without secrets. When the index
   needs a signature, the `sign` job waits for the maintainer's approval in the `data-signing`
   environment, signs with its `DATA_SIGNING_KEY` secret, force-pushes the signed `data/index.json` to
   the `data/sign` branch, and opens a pull request from it into `dev` (or updates the open one).
3. The maintainer runs `Data` by hand on `data/sign` (Actions → Data → Run workflow, no secrets): a
   pull request opened by a workflow triggers no checks itself, and a run by hand checks the signature
   too. Once it passes, merge the pull request. Alpha builds read the signed index from `dev`, and the
   merge doesn't ask for another signature, because the index on `dev` is now signed.
4. The `dev` → `main` release carries the signed index; `Data` runs the full `check` on pull requests
   into `main` and on `main` itself.

Signing only after merge matters: once a signed `data/index.json` is on GitHub it stays fetchable by its
commit forever, and anyone serving it gets it treated as signed.

The keys are made once, by the maintainer:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out tellyscope-data-primary.pem
openssl pkey -in tellyscope-data-primary.pem -pubout -outform DER | openssl base64 -A > tools/data-keys/primary.pub
# the same with tellyscope-data-backup.pem > tools/data-keys/backup.pub
```

- The primary `.pem` goes, whole, into the secret `DATA_SIGNING_KEY` of the `data-signing` environment
  (deployment branches `dev` and `main`, the maintainer as required reviewer), and no copy stays anywhere
  else.
- The backup `.pem` stays offline and is used only to rotate after the primary is lost or leaked.
- Neither ever goes in this repository.

Changing a public key needs an app release. To rotate, the `sign` job signs with both keys
(`sign --key old.pem --key new.pem`) until no app that knows only the old key is in use.

`index` leaves `data/index.json` alone, signature included, while nothing changes. Otherwise it raises
`version` (`YYYYMMDDNN`, UTC) and writes it unsigned; the app refuses an index older than the one it has.

**The format is fixed once an app ships with it**, so it changes only by adding:

- A new field, in any file, keeps `schema: 1`. Apps ignore keys they don't know, so add a field only
  when an app that ignores it still behaves correctly.
- A breaking change goes in a new folder, `data/v2/`, with its own signed `index.json`, read by the app
  versions that understand it. `data/` stays as it is and keeps serving older apps until they're gone.
- A new list is a new entry in `lists`, and older apps skip it.

## README screenshots

They come from the screenshot tests, so they always show the current UI with sample data. To refresh them, render the tests, then put them in phone frames (needs Python 3 with Pillow):

```sh
./gradlew :app:testGithubDebugUnitTest --tests '*ScreenshotTest' -Proborazzi.test.record=true
python3 docs/screenshots/frame.py
```

`frame.py` lists which test image becomes which README image. Keep the README at 12 images or fewer.

## Style

- Kotlin official code style, as set up in `.editorconfig`.
- Logic that doesn't need Android goes in `:adb`, with unit tests.
- UI uses Material 3 Expressive components and the app theme. No hard-coded colors.
- New UI text goes in `app/src/main/res/values/strings.xml`, never as a literal in Kotlin. Compose code reads it with `stringResource`; the ViewModel and data layer return a `UiText` the screen resolves. Counts use `<plurals>`, and values go in as format arguments, not by joining strings.
- A translation is `app/src/main/res/values-xx/strings.xml` plus its code in `localeFilters` in `app/build.gradle.kts`; the release APK keeps only the languages listed there. `TranslationsTest` checks each one covers every string and keeps its format arguments; a new language adds a one-line subclass there.

## Reporting a TV

Open a [device report](../../issues/new?template=device_report.yml). Leave out serial numbers, IP addresses and account names.
