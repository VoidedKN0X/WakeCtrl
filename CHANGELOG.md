# Changelog

What changed in each WakeCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/WakeCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Built and checked by CI only: nothing here had been run on a phone when it was written.

### Battery

- **Fewer checks while the screen is kept on by hand, by charging, by Bluetooth or by the schedule.** While WakeCtrl was holding the screen it looked at all its automations every 2 seconds, for as long as the screen stayed on, even when nothing it watches can change that fast. The 2-second check is now used only when AppWake (with apps chosen) or DevWake is on and the screen is on, because only those have to notice which app is in front. Otherwise WakeCtrl looks every 15 seconds; charging, Bluetooth devices, the schedule alarm, the screen switching on or off and anything you change in the app still take effect at once, as before.
- What this means in practice: the low-battery stop and the 3-hour reminder can come up to 15 seconds later than before (they used to be noticed within 2 seconds while the screen was held).

### Fixed

- **The timer and a ScheduleWake window end on time.** WakeCtrl now wakes up exactly when the timer or the current schedule window ends, instead of noticing at the next check (up to 2 seconds late with the screen on, up to 15 seconds with it off, and later still for a schedule window when "Alarms and reminders" is not allowed and Android delivers the alarm late).

## v0.5.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Privacy

- **The activity log is now encrypted on the phone.** The log (what WakeCtrl did and why, including the line that names the app that kept the screen on) is stored in `activity.log` in WakeCtrl's private storage. That file is now sealed with AES-256-GCM under a random 256-bit data key. The data key is stored next to it in `data.key`, itself encrypted by a key in the phone's secure hardware (Android Keystore, StrongBox when the phone has it) that can't be exported and needs no fingerprint or PIN, so the service keeps writing the log in the background. This protects the log in a copy of WakeCtrl's files taken off the phone; it does not protect against someone who can run code as WakeCtrl on the unlocked phone, and it is independent of the app lock.
- **Your existing log is kept.** The first time this version starts, the old log is read as before and then replaced, in one step, by an encrypted copy that was first written in full, read back from storage, decrypted and compared byte for byte. If anything in that fails, the old file stays in use and WakeCtrl tries again at the next start. If the phone has no usable secure hardware key, the log stays an ordinary file as before.
- **If the encrypted log can't be opened** (for example the secure hardware key is gone), WakeCtrl does not delete or overwrite it. The activity log screen says so and offers **Try again** and **Start a new log**; starting a new log renames the old file to `activity.log.unreadable-…` instead of deleting it. Until then, new lines are shown but not saved. Keeping the screen on, the tile, the schedule and the automations don't depend on the log and work as before.
- **Not encrypted, on purpose:** your settings, chosen apps, Bluetooth devices, schedule and the saved brightness (DataStore and preferences). The service, the Quick Settings tile, the restart receiver, the schedule alarm and the automation receiver all read them, some right after the phone restarts, and keeping the screen on must never depend on a key being available. The app lock's own storage was already protected and is unchanged.
- **No going back to an older version with the log intact:** WakeCtrl 0.4.0 and older can't read the encrypted log. Installing an older version over this one shows an unreadable log and overwrites it with the next line. WakeCtrl has no export for the log.
- "Forgot PIN?" still deletes the log, now also when it can't be opened, together with any log set aside earlier.

### Changed

- The log is read on a background thread when WakeCtrl starts, instead of on the main thread. The activity log screen stays blank for the moment that takes instead of showing "Nothing yet".
- The log file is now replaced in one step when a line is added (written to a temporary file first), so a crash or power cut can't leave half a file.

## v0.4.0 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Removed

- **The home-screen widget is gone.** The Ctrl apps no longer have home-screen widgets. A WakeCtrl widget that is still on your home screen stops working after the update; remove it by hand. The **Quick Settings tile stays** and works as before, and so do the launcher shortcut, the on/off button in the notification and the broadcasts for automation apps. With the widget, its once-a-minute alarm for the countdown is gone too.

### Battery

- **Fewer checks while the screen is off.** While WakeCtrl was keeping the screen on (or AppWake or DevWake was switched on) it looked at its automations every 2 seconds, also with the screen off, when there is nothing to hold and no app on screen. With the screen off it now looks every 15 seconds, which still ends the timer, applies the low-battery stop and follows the schedule; charging, Bluetooth and schedule changes arrive on their own as before. When the screen comes on, WakeCtrl looks straight away and returns to the 2-second checks, so keeping the screen on works the same as before.
- **The Quick Settings tile is asked to refresh only when on/off changed,** instead of on every check.

### Fixed

- **Brightness is put back after Android closes WakeCtrl.** If Android closed WakeCtrl while it had changed the brightness, the phone stayed at that brightness until the next time WakeCtrl held the screen and let go. WakeCtrl now puts your saved brightness back as soon as it starts again and nothing is keeping the screen on.

### Privacy

- **Two crash reports from a build computer were removed from the repository** (`hs_err_pid18424.log`, `replay_pid18424.log`), and files like them are now ignored. They were never part of the app. They are still in the repository's older history.

## v0.3.1 (2026-11-13)

### New

- **Turn off when the screen goes off** (Settings, off by default). When you switch the screen off with the power button, or it times out, keeping it awake by hand (the tile, the app or the widget) ends too. Next time you want it, turn it on again. Automations (AppWake, ChargeWake, BTWake, ScheduleWake, DevWake) aren't affected and keep working as before. The activity log notes "screen off" as the reason.

## v0.3.0 (2026-11-13)

AppWake can wait for the charger, and the widget tells you why the screen is on and for how long.

### New

- **AppWake: "Only while charging", per app.** In Automations › AppWake › Choose apps, every app you switch on gets its own "Only while charging" switch. With it on, that app keeps the screen on only while the phone is also charging, for example a navigation app on the car charger, while your other AppWake apps keep working as before. Plugging in or unplugging takes effect straight away. The Choose apps row shows how many of your apps are set to it.
- **The home-screen widget shows why and for how long.** While the screen is kept on, the widget names the reason (by you, schedule, app, charging, Bluetooth or debug build, with "+1" when more than one applies) and, for the timer or a ScheduleWake window, the minutes left ("12 min left", "1 h 05 left"). A one-cell widget uses a compact layout; make it taller for a bigger icon and room for two lines. After a low-battery stop it says so.
- **Light on the battery.** The widget is only redrawn when what it shows changes. While a countdown is shown, one alarm at the next minute keeps it current; it never wakes the phone, and nothing runs at all while WakeCtrl is off. With **Alarms and reminders** allowed (ScheduleWake asks for it) the minutes change right on the minute; without it, Android may update them a little later.

## v0.2.0 (2026-11-13)

A new automation: keep the screen on during set hours.

### New

- **ScheduleWake** on the Automations tab keeps the screen on during the hours you set, for example weekdays 08:00–17:30. Add as many time windows as you like (up to 20), each with its own days. A window can run past midnight (22:00–06:00 starts on the days you pick and ends the next morning); the same start and end time means the whole day.
- **Starts and ends on time.** WakeCtrl sets an alarm for the next start or end, so it switches even when the phone has been lying idle. For starts and ends on the minute, allow **Alarms and reminders** for WakeCtrl (a row on the Automations tab takes you there; WakeCtrl asks only when ScheduleWake is on). Without it the schedule still works, but Android may run it a few minutes late.
- **Follows your clock.** Daylight-saving changes, a new time zone, changing the time by hand and restarting the phone are all handled: 08:00 stays 08:00 on the clock.
- **Works like the other automations:** "schedule (until 17:30)" shows as the reason in the app and the notification, the activity log notes when a window starts and ends, the screen is held the same way (a renewed short screen wake lock, no overlay, only while the screen is on and the phone unlocked), and the low-battery stop applies to it too.

### Permissions

- **Alarms and reminders** (`SCHEDULE_EXACT_ALARM`), off until you allow it, only so ScheduleWake starts and ends on the minute.

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
