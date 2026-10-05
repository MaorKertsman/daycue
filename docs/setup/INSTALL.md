# Building and installing DayCue on a phone

A signed APK of version 0.1.0 is published as a pre-release at https://github.com/MaorKertsman/daycue/releases: download `daycue-0.1.0.apk` and install it as described in step 2 onward (skip step 1). You can also build a debug APK yourself as described below; a debug build and the signed release cannot be installed over each other (different signing keys). Nothing in this guide has been run on a physical phone: the install, permission and reminder behavior below was observed on an Android 17 emulator or read from the code and the Android documentation, as noted.

## Requirements

- Android 8 (API 26) or newer. The app targets API 37.
- Windows PC with the toolchain from `docs/setup/BUILD.md` section 1 (JDK 21 and Android SDK under `%USERPROFILE%\dev-tools\`).
- Google Play services on the phone for automatic place detection and walking detection. Without it everything else works (`docs/architecture/FEASIBILITY.md` section 5.2).

## 1. Build the debug APK

From the repository root (`<repo>`), in PowerShell:

```powershell
$env:JAVA_HOME    = "$env:USERPROFILE\dev-tools\jdk21"
$env:ANDROID_HOME = "$env:USERPROFILE\dev-tools\android-sdk"
cd android
.\gradlew.bat :app:assembleDebug
```

The APK is written to `android\app\build\outputs\apk\debug\app-debug.apk`. If the build fails, see `docs/setup/BUILD.md`.

## 2. Install it

Option A, USB and adb (the method used on the emulator):

1. On the phone, enable Developer options (Settings, About phone, tap Build number seven times; exact wording varies by manufacturer) and turn on USB debugging.
2. Connect the phone and accept the debugging prompt.
3. On the PC, from `<repo>`:

```powershell
& "$env:USERPROFILE\dev-tools\android-sdk\platform-tools\adb.exe" install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Option B, copy the file: copy `app-debug.apk` to the phone, open it from a file manager and allow installing from that app when Android asks. The menu path for "install unknown apps" differs between manufacturers and was not tested.

## 3. First run and permissions

DayCue asks for permissions in context during onboarding, and again from **Reminder readiness** later. The manifest (`android/app/src/main/AndroidManifest.xml`) declares exactly the following.

Permissions you may be asked to grant:

| Permission | What it is for | If you decline |
|---|---|---|
| `POST_NOTIFICATIONS` (Android 13+) | Every reminder is a notification. | Reminders cannot reach you. The app treats this as the one required permission; the others are optional. |
| Exact alarms: `SCHEDULE_EXACT_ALARM` (declared up to Android 12L) and `USE_EXACT_ALARM` | Reminders and alarms at the minute they are due. | Reminders may arrive up to about 10 minutes late (the app's own wording). |
| `USE_FULL_SCREEN_INTENT` (special access on Android 14+) | The ringing alarm screen opens over the lock screen. | The alarm still rings, but its screen may not open. On the Android 17 emulator a sideloaded install had this denied by default. |
| `READ_CALENDAR` | Cues before meetings and appointments. DayCue only reads; it declares no write permission (`docs/adr/0004-calendar-provider.md`). | No calendar cues. |
| `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` | Setting a place from where you stand and detecting arrival and departure. | No automatic places. Manual Outdoors/Indoors, **Leaving now** and timed reminders still work. Approximate location only is not enough for geofences. |
| `ACCESS_BACKGROUND_LOCATION` (separate step; Android opens a settings page) | Place changes while the app is closed. | Place is only known while the app is open; automatic work sessions and the leaving-a-place bottle cue do not fire. |
| `ACTIVITY_RECOGNITION` | Counting a sustained walk away from saved places as outdoors. | Away from saved places DayCue does not guess indoors or outdoors. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Lets Reminder readiness ask Android to exempt DayCue from battery optimization (the **Battery** row). | The phone may delay reminders to save power. |

Declared in the manifest but not a prompt you answer: `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (alarm and routine playback), `VIBRATE`, `WAKE_LOCK`, `HIDE_OVERLAY_WINDOWS` (the remote-approval screen asks Android to hide overlays while it is shown), `INTERNET` and `ACCESS_NETWORK_STATE` (only used for the optional relay and Spotify). DayCue does not request body-sensor permissions or "display over other apps".

Onboarding order: language, what DayCue should help with, permission cards for what your choices need (Notifications, **Exact timing**, **Full-screen alarms** only if you chose the alarm, **Calendar** only if you chose calendar cues), location (optional), a test reminder, done. Every card has **Not now**. Battery and "App pause when unused" are left to Reminder readiness.

## 4. Reminder readiness

Open it from **Setup**, **Reminder readiness** (the Setup tab is the third tab; the tabs are **Today**, **Cues**, **Setup**). It is also linked from the help sheet on Today. Rows (labels as in the app):

- Reminders: **Notifications**, **Exact timing**, **Full-screen alarms**, **Battery**, **Background use**, **App pause when unused**, **Voices**, **Reminder categories**, **Speech**, **Force stop**.
- Places and calendar: **Place detection**, **Precise location**, **Location in the background**, **Location services**, **Google Play services**, **Physical activity**, **Calendar access**, **Calendar sync**.
- Connections: **Remote access**, **Desktop companion**. These are optional and never counted as problems.

A row that needs action shows a **Fix** button; the **Voices** row shows **Install**. **Send a test reminder** posts one reminder so you can see and hear it. When everything is fine the screen says "Reminders are ready".

## 5. Hebrew voice

Hebrew speech needs an offline Hebrew voice in the phone's text-to-speech engine. If it is missing, Hebrew reminders use sound only and **Voices** says so. To install it, press **Install** on the **Voices** row, or in Android Settings: System, Languages, Text-to-speech, the gear next to Google Text-to-speech, Install voice data, Hebrew (the app's own instructions; menu names vary by phone). Audibility of speech has not been checked on a real phone.

To switch the app language: **Setup**, **Settings**, **Language**.

## 6. Updating

- Installing a newer debug build over an older one signed with the same debug key keeps your data (`adb install -r`).
- A build signed with a different key (a release build, or a debug build from another PC) cannot be installed over the existing app. Android refuses it. You must uninstall first, which deletes all DayCue data. Export your setup before uninstalling.

## 7. Backup and moving to a new phone

Android backup is off for DayCue. `allowBackup` is `false` in the manifest, and `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude all data from both cloud backup and device-to-device transfer. The supported backup is the in-app export:

1. **Setup**, **Settings**, section **Backup**.
2. Optionally turn on **Include history**.
3. Press **Save setup as a file** and choose where to put the `*.daycue-backup.json` file.

The file contains your whole configuration, including place coordinates and medication names if you have any (the app warns about medication). History is included only if you turned on **Include history**. Keep the file private.

To restore: **Import a setup file**, choose the file. DayCue lists what would change and applies nothing until you confirm; you can undo afterwards in **Activity & undo**. History inside a file is ignored on import. A file from a newer DayCue is refused. Relay pairing is not part of the file: pair again on the new phone.

## 8. Troubleshooting

**"App not installed" or a signature error.** The installed app was signed with a different key. Export your setup, uninstall DayCue, install the new APK, import the file.

**Reminders do not arrive.** Open **Setup**, **Reminder readiness**. Fix **Notifications** first, then **Exact timing**, **Battery**, **App pause when unused** and **Background use**. If **Force stop** shows, open DayCue once: reminders stay off after a force stop until the app is opened. **Reminder categories** shows when a reminder type is blocked in Android's notification settings. Use **Send a test reminder** to check. Some manufacturers add their own battery restrictions; these were not tested.

**The alarm rings but its screen does not open.** Grant **Full-screen alarms** in Reminder readiness. If Android shows a special-access page, allow it for DayCue.

**No speech, only sound.** Check **Voices** (see section 5) and **Speech**.

**Places are not detected.** Check the **Place detection**, **Precise location**, **Location in the background**, **Location services** and **Google Play services** rows. A place with no location set is inactive: in **Setup**, **Places and context**, open the place and use **Use my current location** (or **Enter coordinates**). Detection delay on a real phone has not been measured; DayCue confirms arrival after 3 minutes inside and departure after 5 minutes outside (`docs/PRODUCT.md` CTX-2, CTX-3), on top of Android's own delay.

**After a reboot.** Android delivers the boot broadcast a while after startup (about 50 to 90 seconds on the emulator). A fix to re-post pending reminders after a reboot was verified on the emulator only; confirming it on a phone is still open (`docs/LIMITATIONS.md`).

## Next

- `docs/QUICKSTART.he.md` for the Hebrew quick start
- `docs/LIMITATIONS.md` for what is unverified or not built
- `docs/setup/MCP.md`, `docs/setup/COMPANION.md`, `docs/setup/SPOTIFY.md` for the optional parts
