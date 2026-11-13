# Changelog

What changed in each WakeCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/WakeCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.1.3 (2026-11-13)

A security update for automation apps, and a little less work for your phone.

### Security

- **Automation apps can't reach WakeCtrl at all while "Allow other apps" is off.** Before, WakeCtrl received their requests and ignored them; now the part that listens for them is switched off in Android itself until you allow it, and switched off again when you turn the setting off. With the setting on, Tasker, Home Assistant, MacroDroid and others work exactly as before. Because blocked requests no longer reach WakeCtrl, they're no longer listed in the activity log.
- **Release builds drop debug logging** from WakeCtrl and its libraries.
- **The build tool download is checked** against its published SHA-256 checksum.

### Performance

- The activity log is saved in the background instead of on the main thread.

### Fixed

- **The Quick Settings tile updates as soon as you tap it.** Before, it kept showing the old state until you closed and reopened the Quick Settings panel. While the panel is open, the tile now also follows changes from the app, the notification, the widget or an automation.

## v0.1.2 (2026-11-12)

### Fixed

- **The screen stays on again while WakeCtrl is in the background**, also on phones like OPPO/OnePlus (ColorOS) that drop a long-held screen wake lock. WakeCtrl now takes a fresh, short screen wake lock every 10 seconds instead of holding one forever. It only renews while the screen is on and the phone is unlocked, so pressing the power button still turns the screen off.
- **The Quick Settings tile** works with this too: turning WakeCtrl on from the tile now keeps the screen on.

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
