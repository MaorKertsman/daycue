# Limitations

What DayCue cannot do, what has not been verified, what is not built, and what is waiting on the owner. Evidence labels used here: unit (JVM tests), emulator (Android 17 AVD), physical phone, unverified. **Nothing has been verified on a physical phone.**

## 1. Verification state

- The unit suites and an emulator QA pass exist. The QA report is `docs/VALIDATION.md` (10 scenarios passed, 7 partial, 1 failed at the time of writing).
- QA found eight defects (D1 to D8). The code fixes landed afterwards (commits `c59b66a`, `5c3df55`, `a385cc2`). **VALIDATION.md has not been re-run since**, so a QA re-verification of the fixes is pending and the report still describes the pre-fix state.
- What the fixes are, by the engineers' account:
  - D1, reboot did not re-post pending reminders: fixed and checked on the emulator with a real reboot. Still to confirm on a phone.
  - D2, debug receiver in the release build: removed; checked in the release manifest and dex.
  - D3, raw JSON in the import review: replaced by readable lines.
  - D4, backup `formatVersion` not checked on import: now checked.
  - D5, weekday chips without descriptions and no non-drag reorder: descriptions and reorder alternatives added. TalkBack itself has not been run.
  - D6, Android 17 log line "background playback would be muted" for the alarm: analysed. The alarm path meets the documented exemption (exact-alarm permission plus `USAGE_ALARM`), and the player was not muted on the emulator. Audibility on a physical phone is unverified.
  - D7, alarm screen not opening while DayCue is in the foreground: fixed.
  - D8, language set by import or remote change not applied to the UI: fixed.

## 2. Needs a physical phone

The manual steps are in `docs/VALIDATION.md` section 5. In short, none of these has been observed on a real device:

- Audibility of sounds, speech and the alarm (including the locked, screen-off alarm), vibration patterns, volume ramp, ducking over music.
- Alarm over the lock screen after granting **Full-screen alarms**; alarm with Do Not Disturb and after a reboot.
- Geofence latency. It has not been measured. DayCue's own rules: arrival is confirmed after 3 minutes inside a place, departure after 5 minutes outside, outdoors after 5 minutes of walking signals (`docs/PRODUCT.md` CTX-2 to CTX-4). Android's delay is added on top and is unknown on a real phone.
- Doze and standby over hours or overnight; manufacturer battery restrictions (Samsung, Xiaomi, Oppo and similar).
- Reboot recovery of pending reminders; time zone change with several medications.
- Calendar: a real synced account, event created, moved, declined, canceled, and the sync delay.
- The Windows companion running against a relay with a phone (work sessions starting and pausing).
- A real Claude.ai, ChatGPT or Claude Code connection to a deployed relay.
- Spotify playback (needs the SDK added locally, a Premium account and a phone).
- TalkBack in Hebrew and English across onboarding, Today, editors, the alarm screen and the remote-approval screen.
- Notification tap routing to the matching screen; cold-start Posture control display.

## 3. Platform limits (Android, not bugs)

Sources: `docs/architecture/FEASIBILITY.md`, which separates documentation, emulator observation and unverified claims.

- **Force stop**: Android cancels all alarms and delivers nothing until the app is opened again. Reminder readiness shows **Force stop** when this happened.
- **Reboot**: all alarms are cleared. The boot broadcast arrived roughly 50 to 90 seconds after boot on the emulator, so reminders due in that gap are delivered late, once.
- **Doze and battery saver**: alarm and medication wakes use alarm-clock and exact-alarm paths; habit and posture cues can be minutes late in deep Doze. This is documented in the design; the size of the delay on a phone is unmeasured.
- **App pause when unused (hibernation)**: Android may pause an app that is not opened for weeks. Alarms do not count as use; tapping a notification action does. Reminder readiness has a row for this.
- **Android 17 background audio rules**: a bare background receiver was refused audio focus on the emulator (speech still played, music was not ducked). Speech from the alarm service and routine playback uses a foreground service. Audibility is unverified.
- **Full-screen alarm screen**: on a sideloaded install, Android 14 and later may leave the special access denied until you grant it.
- **Calendar**: DayCue reads the phone's synced calendar copy, so it sees changes only after the phone's own calendar sync. An event added shortly before it starts may be cued late or not at all. DayCue never writes to the calendar.
- **Location**: automatic places need precise location, background location and Google Play services. Approximate location is not enough for geofences.
- **Medication and context**: medication reminders are never held back by context, quiet hours, pause or routines (`docs/PRODUCT.md` MED-3).

## 4. Integrations that are built but not live

- **Relay and MCP server (`mcp/`)**: built and tested locally (112 tests passed in `npm test` when this was written). It is **not deployed**. The hosting choice is pending with the owner (`docs/setup/MCP.md` section 4, `docs/STATUS.md`). A new remote client stays read-only until approved on the phone with a press-and-hold; sensitive changes also need press-and-hold confirmation. Not tried with Claude.ai, ChatGPT or a deployed relay.
- **Push wake (Firebase Cloud Messaging)**: needs the owner's Firebase project and a build with it set up. The Setup screen says "Not available in this build". Without it the phone checks the relay about every 15 minutes or more, or more often if you turn on the opt-in frequent check.
- **Windows companion**: built and unit tested; not run live with a phone. Without it, work sessions are manual, which is a supported setup.
- **Spotify alarm**: off by default. Spotify's Developer Policy says alarm functionality in an app that uses its SDK needs Spotify's written approval; the app does not have it. The SDK binary is not in the repository (`docs/setup/SPOTIFY.md`). The local alarm tone always rings first. Do not rely on Spotify to wake you.
- **Android release build**: no signed release APK exists. See section 6.

## 5. Features not built

From `docs/STATUS.md`, the architecture documents and the code:

- Picking a place on a map. Places are set with **Use my current location** or **Enter coordinates**; the app has no map.
- A Quick Settings tile or home-screen widget for **Leaving now** or other controls. **Leaving now** is available as a button on Today (and under **More**); no tile or widget is declared in the manifest.
- Companion key pinning. The phone learns companion public keys from the relay, so a compromised relay could pair a fake companion and forge activity state (it cannot change configuration). The fix (pin keys on first sight, compare a fingerprint on both screens) is designed in `docs/architecture/RELAY.md` section 4.4.1 and not implemented.
- A biometric or device-credential step before approving a grant that carries the medication scope. Approval today is a press-and-hold.
- A screen for global pause. The configuration model has a global pause, but no screen sets it. Pausing is per item, and **Pause automatic detection** on Today pauses place and outdoor guessing, not reminders.
- Other early departure signals for the water bottle (Wi-Fi disconnect, car Bluetooth, NFC tag): not implemented (`docs/architecture/FEASIBILITY.md` section 5.4).
- TalkBack has not been run, so screen-reader use is unchecked even though descriptions were added.

## 6. Release and install limits

- No signed release APK exists. A debug build can be produced with the commands in `docs/setup/BUILD.md`.
- The release workflow (`.github/workflows/release.yml`) has not run; it needs the keystore secrets and a pushed tag.
- Updating later with a different signing key requires uninstalling, which deletes app data. Export your setup first (**Setup**, **Settings**, **Save setup as a file**).
- Android backup and device transfer are disabled by design. The manifest sets `allowBackup="false"`, and `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml` exclude every data domain. The in-app export is the only backup. An export file includes your configuration, with place coordinates and medication names; history only if you chose it.

## 7. Decisions waiting on the owner

From `docs/STATUS.md` and `docs/PRODUCT.md` section 14:

- Relay hosting: option A (Render free web service plus free Postgres, about $0, cold starts, not durable) or option B (Render Starter plus a disk, about $7 per month, figures unverified). The relay is not deployed until this is decided.
- Spotify alarms: ask Spotify for written approval, or keep them off.
- Whether global pause should also pause calendar cues (currently it does not).
- Posture after a long manual pause: `docs/STATUS.md` records that the cycle now keeps its position after a long pause you set yourself, and that the owner may object (VALIDATION.md describes the earlier behavior, a reset to the first mode).
- Firebase for push wake (optional).
- A release keystore (`docs/setup/BUILD.md` section 4).
- A phone model with USB debugging, for the physical checks above.
- Product defaults still open: sunscreen hours versus daylight, the away-from-places policy default, work days and hours, session start at home, medication repeat count and quiet-hour sound, calendar lead times, a scheduled departure or NFC tag for the bottle cue, morning routine steps.
