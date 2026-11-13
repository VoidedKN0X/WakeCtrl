# WakeCtrl

**Keep your screen on, by hand or automatically, privately and offline.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/WakeCtrl?label=download)](https://github.com/VoidedKN0X/WakeCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Offline](https://img.shields.io/badge/internet-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

WakeCtrl stops your screen from turning off while you read, cook, follow a map or present. Turn it on yourself, with an optional timer. Or let it switch on by itself while certain apps are open, while the phone charges, while your car or speaker is connected, or while a debug build is on screen. It never goes online.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/WakeCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **Works fully offline.** WakeCtrl doesn't have the internet permission and never goes online. No ads, no account, no in-app purchases
- **Keep the screen on** with one tap, until you turn it off or for 5, 15 or 30 minutes, 1 or 2 hours, with a live countdown
- **Automations**, each switched on separately:
  - **AppWake:** while apps you choose are on screen (reading, recipes, maps, presentations)
  - **ChargeWake:** while the phone is charging
  - **BTWake:** while a paired Bluetooth device you choose is connected (your car, a speaker)
  - **DevWake:** while a debug build of any app is on screen, for developers
- **Screen:** set the brightness while it's on (restored afterwards), or let the screen dim when idle instead of staying bright
- **Battery care:** stop at a low battery level you pick (10 to 30%), and a reminder after 3 hours on battery. Turn it on again after a low-battery stop to override it until you charge
- **Resume after a restart** if you had it on when the phone went down
- **Everywhere you need it:** a Quick Settings tile, a home-screen widget, a launcher shortcut, and an on/off button in the notification
- **Works with automation apps:** Tasker, Home Assistant, MacroDroid and others can turn it on, off or toggle it with a broadcast, only if you allow it (off by default)
- **Activity log:** what WakeCtrl did and why, kept on the phone
- **Keeps the screen on with a renewed screen wake lock** (a fresh short lock every 10 seconds, only while the screen is on and the phone unlocked): it works on strict phones like ColorOS, with no overlay and no "Display over other apps" permission. Settings shows what still helps on strict phones
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, following your phone's dark theme setting, high contrast, one violet accent, Space Grotesk and JetBrains Mono; landscape, tablet and foldable layouts

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/WakeCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `WakeCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds WakeCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/WakeCtrl` in Obtainium by hand.

## Getting started

- **Wake:** tap **Keep screen on**. Pick a time under **Timer** first if you want it to stop by itself.
- **Automations:** switch on AppWake, ChargeWake, BTWake or DevWake, then choose the apps or devices. AppWake and DevWake ask you to allow Usage access in Android's settings.
- **Reliability:** on phones that close apps in the background (OPPO, OnePlus, Samsung, Xiaomi…), allow **Unrestricted battery use**. WakeCtrl shows a card when it's missing; Settings shows it too.
- **Quick Settings tile:** edit your Quick Settings and drag in the WakeCtrl tile. Long-press the home screen to add the widget, or long-press WakeCtrl's icon for the shortcut.
- **Settings** (profile picture): brightness, dimming, low battery, reminder, resume after restart, other apps, activity log and privacy.

## Is it safe to install?

- **Open source.** All of WakeCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks that the app has no network permission and no Google libraries.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over WakeCtrl. The SHA-256 fingerprint of WakeCtrl's signing certificate is:

  ```
  A0:17:21:6D:05:E7:51:1B:E3:12:9F:1A:89:62:8B:B7:2C:31:F9:3F:BF:2E:D2:FE:DD:A4:7A:04:51:A0:44:A0
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum WakeCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify WakeCtrl-vX.Y.Z.apk -R VoidedKN0X/WakeCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.** WakeCtrl has **no internet permission**: it can't go online at all. The ones marked *you allow it* are off until you switch them on in Android's settings:

| Permission | What WakeCtrl uses it for |
| --- | --- |
| Keep the device awake | The fallback method, and "Allow dimming". |
| Foreground service (special use), notifications | Running while it holds the screen or watches your automations, with the status notification and its on/off button. |
| Usage access (*you allow it*) | AppWake and DevWake: which app is on screen. Only the app's name, only while the screen is on, never stored or sent. |
| Nearby devices (Bluetooth) | BTWake: which of your paired devices is connected. |
| Modify system settings (*you allow it*) | Only for "Brightness while on"; your brightness is restored afterwards. |
| Run at start-up | Resume after a restart, and keep your automations running. |
| Ignore battery optimisations (*you allow it*) | So the phone doesn't stop WakeCtrl in the background. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock or PIN screen is showing, other apps' floating windows are hidden over it, so nothing can cover or imitate it. |
| App-internal broadcasts (`com.vknxworks.wakectrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only WakeCtrl itself can send to its own internal receivers. |

**App list.** The AppWake list shows apps with a launcher icon (Android's package-visibility rules); WakeCtrl can't see other installed packages.

See [Privacy](#privacy) for what happens to your data.

## App lock

Turn it on under **Settings > Privacy and security > App lock** (tap your profile picture at the top right, then **Settings**). It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Lock again:** as soon as you leave the app (the default), after 1, 5 or 15 minutes or 1 hour away, or only when the app restarts.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset WakeCtrl's settings and automations, clear the activity log and turn the lock off.
- **Keeping the screen on carries on** while the app is locked; the tile, widget and notification still work.

**Hide in screenshots** and **Hide in recent apps** (both off by default): the first keeps WakeCtrl out of screenshots and screen recordings, the second shows a blank card in the recent-apps view while screenshots still work. With the app lock on, the recent-apps view always shows a blank card.

## Privacy

**Telemetry-free.** WakeCtrl has no analytics, crash reporting, usage stats or tracking of any kind. It has **no internet permission**, works fully offline and never connects to the internet. Every build checks that the app has no network permission and no Google libraries.

- Your settings, chosen apps and devices, and the activity log are stored in WakeCtrl's private storage and can't be read by other apps.
- Usage access is used only to see which app is on screen while AppWake or DevWake is on; nothing is recorded except the activity log line "app on screen".
- Other apps can control WakeCtrl only when you switch on **Allow other apps**. While it's off, the part of WakeCtrl that listens for them is switched off in Android itself, so other apps can't reach it at all; it can only turn the screen-on hold on, off or toggle it, and reads nothing else from the request.
- Android backups don't include WakeCtrl's data, and WakeCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How WakeCtrl is made

WakeCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/WakeCtrl.git
   cd WakeCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

Or open the folder in Android Studio and press **Run** with your phone connected.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

WakeCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). In the app, tap your profile picture: the You page has **Support WakeCtrl** and **Report a bug**, which opens the [issue tracker](https://github.com/VoidedKN0X/WakeCtrl/issues). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/WakeCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| **WakeCtrl** (this app) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

WakeCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
