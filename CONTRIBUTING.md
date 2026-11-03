# Contributing to Tellyscope

Thanks for helping! Tellyscope is young, so the code and its structure are still moving.

## Ground rules

- **Read the [safety rules](README.md#safety-rules) first.** A pull request that uninstalls apps that came with the TV, bypasses the protected list or skips recording what was already off won't be merged.
- **Open pull requests against `dev`.** `main` is the release branch and only takes `dev`.
- **One change per pull request.** Smaller is easier to review.
- **`./gradlew build` must pass** before you open a PR. CI runs the same command.
- **New files start with an SPDX header:** `// SPDX-License-Identifier: GPL-3.0-only`.
- **Licensing.** By contributing you agree that your work is licensed under GPL-3.0. If you adapt code or lists from another project, check that its license is compatible and add it to [NOTICE](NOTICE) in the same PR.

## Releases

- **Alpha:** to publish an alpha, put `[release]` anywhere in the title of the pull request into `dev` (any case), for example `Fix the remote [release]`. Merges without it only run CI. On a `[release]` merge, CI builds `app.tellyscope.alpha`, which installs next to the release, and once the build passes it tags the commit `vX.Y.Z-alpha.N` and publishes a GitHub pre-release under that tag, with the APK named `Tellyscope-vX.Y.Z-alpha.N.apk`. `X.Y.Z` is the current `versionName`, and `N` is one past the highest alpha of that version. A failed build tags nothing. You can still put out an alpha by hand: tag a commit that is on `dev` and push the tag, for example `git tag v0.1.0-alpha.1 origin/dev && git push origin v0.1.0-alpha.1`. The tag's `X.Y.Z` must equal `versionName` in `app/build.gradle.kts`, and its commit must be on `dev`; otherwise the build fails and publishes nothing.
- **Shipping:** a release is a pull request from `dev` into `main`, merged with a merge commit. It must raise `versionName` and `versionCode` in `app/build.gradle.kts` past the latest `v*` tag and turn the `## Unreleased` section of [CHANGELOG.md](CHANGELOG.md) into that version's section; CI refuses anything else. Merging it tags `vX.Y.Z` and publishes a GitHub release with the signed APK, notes from the changelog section and the commits since the previous tag.
- **Signing:** alphas and releases are signed with two different keys, so an alpha can never install over the release app. Each key lives in its own GitHub Environment: `ALPHA_KEYSTORE_BASE64`, `ALPHA_KEY_ALIAS` and `ALPHA_KEY_PASSWORD` in `alpha`, which deploys only from `dev` and `v*-alpha.*` tags; `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` in `release`, which deploys only from `main`. `*_KEYSTORE_BASE64` is the keystore, base64-encoded, and `*_KEY_PASSWORD` is both the store and the key password (keytool's PKCS12 default uses one for both). A job with its secrets missing fails; it never falls back to the other key. Only the maintainer holds the keys; never commit a keystore. Local release builds are unsigned.

## Debloat lists

The per-TV lists behind **Debloat** and the Overview's list card live in the [tellyscope-devices](../../../tellyscope-devices) repository, not here; add or fix a list there. When you add a package to a list:

- Say which TV models you tested on, and what changed after disabling it.
- If disabling it broke anything (launcher, updates, remote, input, HDMI-CEC), put it in the list's keep entries. If it must never be stopped or disabled, add it to tellyscope-devices' `lists/protected.json` when only one maker's TVs have it, or propose it for the built-in protected list (`adb/src/main/kotlin/app/tellyscope/adb/Protected.kt`) here when every Android TV, Google TV or Fire TV can have it.

### Using your own lists

You don't have to trust ours. Fork tellyscope-devices, edit the lists in your fork, and keep `index.json` on schema 1 with each file's sha256 updated. Then in the app open **Settings** (in the ⋮ menu at the top right), open **Advanced** at the bottom, tap **Use your own debloat lists**, and paste the raw address of the folder that holds `index.json`, for example `https://raw.githubusercontent.com/<you>/tellyscope-devices/main/`. Only HTTPS is accepted, and a source that doesn't serve a schema 1 index is refused without changing anything. Switching drops the cached lists. While your source is in use, the review step says where the list came from. Your source supplies only debloat lists, extra protected apps and the Remote's logos (a service your source has no logo for shows its name): launchers, app names and streaming services always come from this repository's `data/`. The built-in protected list still wins over any list, and **Reset to default** goes back to tellyscope-devices.

## Launchers, app names and streaming services

These lists live in this repository, in [`data/`](data/), and the app downloads them from `main`: change one here with a pull request, and it reaches people once it's on `main`, without an app release. Keep `data/index.json` in step: it gives each file's sha256, and the app uses only files that match. [`DataListsTest`](app/src/test/java/app/tellyscope/DataListsTest.kt) checks the lists and the index, and says which sha256 changed. tellyscope-devices still has older copies that earlier versions of the app download; they're frozen until they're retired, so don't edit them there. File formats are in that repo's README.

- `data/launchers.json`: the third-party launchers Tellyscope knows. One with an `"about"` line is offered in **Install a home screen**, in the file's order. On Fire OS, which has no Play Store, only Projectivy is offered, while it's on the list: Tellyscope downloads it from its developer's GitHub releases and installs only an APK signed with the certificate pinned in [`LauncherDownload.kt`](app/src/main/java/app/tellyscope/data/LauncherDownload.kt). On Android TV the same download is offered as **Download directly**, under the Play Store and after a warning. Projectivy is the only launcher with a pinned release and signer, so it's the only one offered either way (`downloadable` in [`LauncherFlowUi.kt`](app/src/main/java/app/tellyscope/ui/LauncherFlowUi.kt)).
- `data/labels.json`: names shown for common TV apps when the TV can't tell us.
- `data/streaming.json`: the streaming services the Remote can have a button for. Each names a logo, which stays in tellyscope-devices' `logos/` with its source and licence in `SOURCES.md`.

Which launchers may turn on an accessibility service is not on any list: it's `ACCESSIBILITY_LAUNCHERS` in [`Launchers.kt`](app/src/main/java/app/tellyscope/data/Launchers.kt), because an accessibility service can read the screen. Add one only for a launcher that keeps Home with its own accessibility service: check the APK's manifest for a service with `BIND_ACCESSIBILITY_SERVICE` and put the version you checked next to it. `LaunchersTest` names every such launcher on purpose, so add yours there too and a reviewer sees it.

The tests use small fixtures in [`app/src/test/resources/fixtures/`](app/src/test/resources/fixtures/) that only check the code: change the real debloat lists in tellyscope-devices and the others in `data/`, not there. The app itself bundles none.

### Signing `data/`

The app trusts `data/` only when `data/index.json.sig` verifies: an ECDSA P-256 / SHA-256 signature over
the exact bytes of `data/index.json`, which holds the sha256 of every file in `data/`. The public keys
are built into the app and kept in [`tools/data-keys/`](tools/data-keys/) for the `Data` check: a primary,
used for every update, and a backup kept offline, so losing the primary doesn't lock apps out. The
private keys stay on the maintainer's machine, never in this repo or in CI. Make each one once:

```sh
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out tellyscope-data-primary.pem
openssl pkey -in tellyscope-data-primary.pem -pubout -outform DER | openssl base64 -A > tools/data-keys/primary.pub
# the same with tellyscope-data-backup.pem > tools/data-keys/backup.pub, then store the backup offline
```

Changing a public key needs an app release.

**Sign last, after review, and only a branch you'll merge.** Once a signed `data/index.json` is on
GitHub it stays fetchable by its commit forever, and anyone serving it gets it treated as signed, even
if the pull request was rejected.

Every change to `data/`:

1. Contributors run `python3 tools/data.py index` and `python3 tools/data.py check --unsigned`. The
   `Data` check fails on the signature until the maintainer signs.
2. After review, on the pull request's branch, the maintainer runs
   `python3 tools/data.py sign --key ~/path/to/tellyscope-data-primary.pem`. It refuses a key that
   `tools/data-keys/` doesn't verify.
3. Commit `data/index.json.sig`, let `Data` pass, merge.

`index` keeps `version` (`YYYYMMDDNN`, UTC) while nothing else changes and raises it otherwise; the app
refuses an index older than the one it has.

## README screenshots

They come from the screenshot tests, so they always show the current UI with sample data. To refresh them, render the tests, then put them in phone frames (needs Python 3 with Pillow):

```sh
./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest' -Proborazzi.test.record=true
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
