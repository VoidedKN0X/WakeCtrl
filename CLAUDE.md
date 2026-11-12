# WakeCtrl

Keep-screen-on app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings.

## Layout

- `wake/WakeService.kt`: foreground service (special use). Works out the reasons to hold the screen (manual + timer, AppWake, ChargeWake, BTWake, DevWake), applies the low-battery stop and its override, holds the screen (1-pixel overlay with FLAG_KEEP_SCREEN_ON, else a screen wake lock; dim wake lock for "Allow dimming"), sets and restores brightness, posts the status and alert notifications. Stops itself when nothing is held and no automation is on (except after a low-battery stop, until the phone charges).
- `wake/WakeState.kt`: process-wide `WakeState` flow for the UI, tile and widget; `ActivityLog` (last 300 lines in `filesDir/activity.log`).
- `widget/Widgets.kt`: Quick Settings tile, home-screen widget, launcher shortcut activity, `AutomationReceiver` (exported; ON/OFF/TOGGLE only when "Allow other apps" is on), `BootReceiver` (boot and package replaced).
- `ui/wake`: Wake and Automations tabs, app and device pickers. `ui/settings`: Settings and the activity log.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`).
- Settings open only from the profile picture. No settings tab, menu or gear icon, no You tab.
- Must work as a normal user app on any ROM (Android 15+), not only when built into the system.
- The overlay is 1×1, transparent, not focusable and not touchable; never draw or read anything with it.
- Usage access is only for the foreground package name while the screen is on; never store it beyond the activity log.
- Other apps can only control WakeCtrl through `AutomationReceiver`, and only when the user allowed it.

## Dependency verification

Gradle checks every library and plugin download against the sha256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency, plugin or the Gradle version changes, start the `verification-metadata.yml` workflow, take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Never turn verification off or make it lenient.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "WakeCtrl X.Y.Z". Never add a build number.
- Push straight to `main`; no branches or pull requests.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
