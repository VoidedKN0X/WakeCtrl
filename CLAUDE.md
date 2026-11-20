# WakeCtrl

Keep-screen-on app in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings.

## Layout

- `wake/WakeService.kt`: foreground service (special use). Works out the reasons to hold the screen (manual + timer, AppWake, ChargeWake, BTWake, DevWake), applies the low-battery stop and its override, holds the screen with short timed screen wake locks renewed every 10 s (ACQUIRE_CAUSES_WAKEUP, only while interactive and unlocked; bright, or dim for "Allow dimming") because ColorOS drops long-held background screen locks; no overlay, no SYSTEM_ALERT_WINDOW, sets and restores brightness, posts the status and alert notifications. Stops itself when nothing is held and no automation is on (except after a low-battery stop, until the phone charges).
- `wake/Schedule.kt`: ScheduleWake windows and their maths (inside/outside, next change; wall-clock times, midnight, DST), unit-tested in `app/src/test`. `wake/ScheduleAlarm.kt`: the alarm at the next start or end (exact with `SCHEDULE_EXACT_ALARM` when allowed, else `setAndAllowWhileIdle`) and `ScheduleReceiver`; `BootReceiver` also handles time, time zone and exact-alarm permission changes.
- `wake/WakeState.kt`: process-wide `WakeState` flow for the UI and tile; `ActivityLog` (last 300 lines in `filesDir/activity.log`, encrypted at rest; all file and key work on its one background thread).
- `wake/WakeRules.kt`: pure, unit-tested logic: `AppWakeRule` (per-app "Only while charging").
- `widget/Widgets.kt`: Quick Settings tile (follows `WakeState` live while listening), launcher shortcut activity, `AutomationReceiver` (exported but `enabled="false"` in the manifest; `AppContainer` enables the component only while "Allow other apps" is on, and the receiver checks the setting again; ON/OFF/TOGGLE only), `BootReceiver` (boot and package replaced).
- `ui/wake`: Wake and Automations tabs, app and device pickers. `ui/settings`: Settings and the activity log.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`).
- Settings open only from the profile picture. No settings tab, menu or gear icon, no You tab.
- Must work as a normal user app on any ROM (Android 15+), not only when built into the system.
- No overlay windows and no `SYSTEM_ALERT_WINDOW`: the screen is held with a wake lock only (removed in 0.1.1 on request).
- Usage access is only for the foreground package name while the screen is on; never store it beyond the activity log.
- Other apps can only control WakeCtrl through `AutomationReceiver`, and only when the user allowed it.

## Encryption at rest

- `activity.log` is sealed with AES-256-GCM (`data/crypto/SealedFile`, marker `WAKECTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `wakectrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication, so the service can write the log in the background). It is the only sealed file.
- Settings (DataStore `wakectrl`), the `brightness` preferences and the app lock vault are NOT sealed and must stay that way: the service, tile, `BootReceiver`, `ScheduleReceiver` and `AutomationReceiver` read them, possibly right after boot. Nothing on those paths may wait for or depend on the data key. `ActivityLog.add` only queues work on the log thread.
- Readers accept both sealed and ordinary files (`SealedFile.isSealed`). Without a data key and with no sealed file the log is an ordinary file as before.
- Never delete or overwrite a log that can't be opened, never replace an existing `data.key`, and never create a Keystore key on the decrypt path (`KeystoreAes.existing`). An ordinary file only becomes a sealed one through `SealedFile.sealInPlace`. A sealed log that can't be opened puts `ActivityLog.unreadable` on: lines stay in memory, nothing is written, and the log screen offers "Try again" and "Start a new log" (renames the file to `activity.log.unreadable-<time>`; an unusable `data.key` becomes `data.key.unreadable`). Only "Forgot PIN?" (`ActivityLog.wipe`) deletes them.
- `SealedFileTest` covers the format, the migration step and the key rules (`./gradlew :app:testDebugUnitTest`; CI does not run unit tests).

## Dependency verification

Gradle checks every library and plugin download against the sha256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency, plugin or the Gradle version changes, start the `verification-metadata.yml` workflow, take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Never turn verification off or make it lenient.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "WakeCtrl X.Y.Z". Never add a build number.
- Push straight to `main`; no branches or pull requests.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
