# Changelog

What changed in each WakeCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/WakeCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.1.1 (2026-11-12)

### Changed

- **No more "Display over other apps".** WakeCtrl keeps the screen on with a screen wake lock only, like Wakey, so it no longer asks for that permission, no longer shows a card about it, and has no overlay window at all. If you allowed it before, you can turn it off again in Android's settings.

## v0.1.0 (2026-11-12)

The first release of WakeCtrl: keep your screen on, by hand or automatically, privately and offline.

### New

- **Keep the screen on** with one tap, with no limit or for 5, 15 or 30 minutes, 1 or 2 hours, and a live countdown.
- **AppWake:** the screen stays on while apps you choose are open.
- **ChargeWake:** the screen stays on while the phone charges.
- **BTWake:** the screen stays on while a paired Bluetooth device you choose is connected.
- **DevWake:** the screen stays on while a debug build of any app is on screen.
- **Brightness while on** (restored afterwards) and **Allow dimming when idle**.
- **Stop at low battery** at a level you pick; turning it on again after a stop overrides it until you charge.
- **Long-session reminder** after 3 hours on battery.
- **Resume after a restart.**
- **Quick Settings tile, home-screen widget, launcher shortcut** and an on/off button in the notification.
- **Other apps** (Tasker, Home Assistant, MacroDroid…) can turn it on, off or toggle it with a broadcast, only when you allow it.
- **Activity log** of what WakeCtrl did and why, kept on the phone.
- **Reliability settings** for phones that stop apps in the background.
- **Privacy:** no internet permission, app lock with its own PIN and fingerprint, Hide in screenshots and Hide in recent apps.
