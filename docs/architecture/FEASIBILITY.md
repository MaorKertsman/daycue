# Android feasibility matrix (API 26 - 37)

Owner: Android architect. Compiled 2026-10-04 for DayCue with `minSdk 26`, `targetSdk 37`.
Because DayCue always targets the latest SDK, every "apps targeting X" change applies on devices
running X or newer.

Status legend:
- **Doc (read)** - the cited official page was read on 2026-10-04 and states this.
- **Doc (not re-read)** - from Android documentation known to the author; URL given but the page
  was not re-read in this pass. Re-check before relying on the detail.
- **Unverified** - no official statement found, or the behaviour depends on the device/OEM.
  Needs an emulator or physical-device test.

Nothing in this file is emulator- or device-tested yet.

## 1. Matrix

Columns: 8 = API 26-27, 9 = 28, 10 = 29, 11 = 30, 12 = 31-32, 13 = 33, 14 = 34, 15 = 35,
16 = 36, 17 = 37. "-" = no change from the previous column.

| Topic | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 |
|---|---|---|---|---|---|---|---|---|---|---|
| **POST_NOTIFICATIONS** | Not a permission; on by default, user can block app/channels | - | - | - | - | Runtime permission; off until granted. FGS notices go to Task Manager only if denied | - | - | - | - |
| **Exact alarms** | No permission; `setExact*`, `setAlarmClock` free | - | - | - | `SCHEDULE_EXACT_ALARM` required, pre-granted, user-revocable; revoke kills app + cancels exact alarms | `USE_EXACT_ALARM` added: auto-granted, not user-revocable | `SCHEDULE_EXACT_ALARM` denied by default on fresh installs | - | - | - |
| **Doze / while-idle alarms** | Doze defers normal alarms; `*AllowWhileIdle` throttled (once / 9 min per app; current power page: 7 / hour while dozing); `setAlarmClock` exits Doze | Standby buckets: alarm limits Working set 10/h, Frequent 2/h, Rare 1/h | - | - | Restricted bucket: 1 alarm/day (after 45 days without interaction) | Restricted after 8 days without interaction; `USE_EXACT_ALARM` holders exempt from Restricted | - | - | Job runtime quotas depend on bucket / top state / FGS | - |
| **Unused-app hibernation** | (Play services backport: permissions reset only) | - | - | Permissions auto-reset after months unused | + app hibernated: no background jobs/alarms, no FCM, cache cleared | Setting renamed "Pause app activity if unused" | - | - | - | - |
| **Background location** | Fine/coarse covers background; background location updates limited to a few per hour | - | `ACCESS_BACKGROUND_LOCATION` separate; dialog offers "Allow all the time" | Must request background separately; user grants on a settings page | User may grant approximate only; background then also approximate | - | - | - | - | - |
| **Geofencing** | 100 geofences/app; latency typically < 2 min, ~2-3 min avg under background location limits, up to 6 min when stationary; re-register after reboot/data clear/`GEOFENCE_NOT_AVAILABLE`; needs fine location and Wi-Fi/network location | - | Needs background location for transitions while app not visible | - | Approximate-only grant: geofencing likely fails (Unverified) | - | - | - | - | - |
| **FGS start / types** | `startForegroundService` + `startForeground` within ~5 s; no `startService` from background | `FOREGROUND_SERVICE` permission | `foregroundServiceType` attribute introduced | - | **No FGS start from background** except exemptions (exact alarm, notification/widget interaction, boot/time broadcasts, high-priority FCM, geofence event, battery-opt exempt, ...) | - | Type **mandatory** + per-type `FOREGROUND_SERVICE_*` permission; WIU permission checks at start; `shortService`, `specialUse`, `systemExempted` (exact-alarm holders eligible) | `BOOT_COMPLETED` may not start `mediaPlayback` (and other) FGS types; `dataSync` 6 h timeout | Jobs running alongside an FGS count against job quota | Background audio needs a non-`shortService` FGS (see TTS row) |
| **Full-screen intent (alarm UI)** | `setFullScreenIntent` without permission | - | `USE_FULL_SCREEN_INTENT` (normal permission) required | - | - | - | Special app access; Play revokes it for non-calling/alarm apps; `canUseFullScreenIntent()` + `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Sideloaded default: Unverified | - | - | - |
| **TTS / audio from background** | TTS binds to engine service; speaking after a receiver returns needs a running FGS to keep the process | - | - | - | - | - | - | - | - | **Background audio hardening**: playback silenced, focus fails, volume ignored unless visible activity or non-short FGS; targeting 37 also needs WIU-capable FGS **or** exact-alarm permission + `USAGE_ALARM`. Whether TTS audio (played by the engine) is attributed to DayCue: Unverified |
| **Direct boot / after reboot** | All alarms cleared on reboot. `LOCKED_BOOT_COMPLETED` to `directBootAware` components; credential storage (Room) unavailable until first unlock; `BOOT_COMPLETED` after unlock | - | - | - | - | - | - | FGS-from-boot restrictions above apply | - | - |
| **Force stop** | App enters stopped state: alarms cancelled, no broadcasts (incl. boot) until the user launches it | - | - | - | - | - | - | All PendingIntents cancelled (widgets greyed); `BOOT_COMPLETED` delivered when user un-stops the app; `ApplicationStartInfo.wasForceStopped()` | - | - |
| **OEM battery restrictions** | OEMs add their own killers/whitelists beyond AOSP; manufacturers set their own standby-bucket criteria | - | - | - | User-set "Restricted" battery mode (12+) blocks background work | - | - | - | - | - |

## 2. Sources and status per row

| Topic | Official source(s) | Status |
|---|---|---|
| POST_NOTIFICATIONS | https://developer.android.com/develop/ui/views/notifications/notification-permission | Doc (read). No exemption for alarm/medication notifications; only media sessions, self-managed calls, FGS (Task Manager). |
| Exact alarms | https://developer.android.com/develop/background-work/services/alarms ; https://developer.android.com/about/versions/14/changes/schedule-exact-alarms ; https://developer.android.com/about/versions/12/behavior-changes-12#exact-alarm-permission | Doc (read) for alarms page and Android 14 default-deny; Android 12 page not re-read. |
| Doze / buckets | https://developer.android.com/training/monitoring-device-state/doze-standby ; https://developer.android.com/topic/performance/power/power-details ; https://developer.android.com/topic/performance/appstandby ; https://developer.android.com/about/versions/16/behavior-changes-all | Doc (read). The two pages disagree (9-minute rule vs "7 per hour" while dozing); design for the stricter. Whether `setAlarmClock` is exempt from bucket alarm limits is **not stated** on these pages: Unverified. Restricted-bucket exemption for `USE_EXACT_ALARM` is stated on the appstandby page. |
| Hibernation | https://developer.android.com/topic/performance/app-hibernation | Doc (read). Alarms and jobs do **not** count as usage; interacting with a notification does (dismissal does not). |
| Background location | https://developer.android.com/develop/sensors-and-location/location/permissions/background ; https://developer.android.com/about/versions/oreo/background-location-limits | Doc (read) for the permissions page; Oreo limits page not re-read. |
| Geofencing | https://developer.android.com/develop/sensors-and-location/location/geofencing | Doc (read). Approximate-only behaviour: Unverified. |
| FGS | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/develop/background-work/services/fgs/service-types ; https://developer.android.com/about/versions/14/behavior-changes-14 ; https://developer.android.com/about/versions/15/behavior-changes-15 ; https://developer.android.com/about/versions/oreo/background | Doc (read) for bg-start, service types, Android 14. Android 15 boot-type list: `mediaPlayback` confirmed on the service-types page; the full list and `dataSync` timeout from the Android 15 page are Doc (not re-read). Oreo 5 s rule: Doc (not re-read). |
| Full-screen intent | https://developer.android.com/about/versions/14/behavior-changes-14 ; https://developer.android.com/about/versions/10/behavior-changes-10 | Android 14: Doc (read). API 29 introduction: Doc (not re-read) - the Android 14 page words it as "With Android 11". Grant state for sideloaded installs on 14+: Unverified (expected granted; check `canUseFullScreenIntent()` on device). |
| TTS / audio | https://developer.android.com/about/versions/17/changes/bg-audio ; https://developer.android.com/about/versions/17/behavior-changes-all ; https://developer.android.com/about/versions/17/behavior-changes-17 ; https://developer.android.com/reference/android/speech/tts/TextToSpeech | Android 17 hardening: Doc (read). The page does not mention `TextToSpeech`: **Unverified**, highest-priority emulator test. |
| Direct boot | https://developer.android.com/privacy-and-security/direct-boot ; https://developer.android.com/develop/background-work/services/alarms | Doc (read). Alarm-clock use case explicitly listed for direct boot. |
| Force stop | https://developer.android.com/about/versions/15/behavior-changes-all | Doc (read) for Android 15. Pre-15 stopped-state semantics: Doc (not re-read). |
| OEM | https://developer.android.com/topic/performance/appstandby (manufacturer bucket criteria) | Doc (read) for that sentence only. Concrete OEM killers (Samsung "sleeping apps", Xiaomi autostart, etc.) are not documented by Google: **Unverified**; community reference https://dontkillmyapp.com (not official). Must be tested on the owner's actual phone. |

## 3. Consequences for DayCue

1. **Alarms and medication**: `setAlarmClock` + `USE_EXACT_ALARM` (see ANDROID.md 5.2). This is the
   most robust combination available: not deferred by Doze, not user-revocable, exempt from the
   Restricted bucket. Remaining failure modes: force stop, hibernation, OEM killers, reboot before
   first unlock (mitigated by the direct-boot snapshot), user-blocked channel.
2. **Soft cues** (habit, posture): `setExactAndAllowWhileIdle`, coalesced by the engine; expect
   minutes of lateness in deep Doze on bucket-throttled devices. Documented as acceptable.
3. **Spoken cues**: speak only inside a foreground service that the user started (routine) or the
   alarm service with `USAGE_ALARM`. A spoken cue fired purely from a background alarm, with
   ordinary speech attributes, is at risk of being silenced on Android 17. Until tested, background
   cues are notifications (with sound) and speech is limited to routines/alarms.
4. **Hibernation**: alarms do not keep DayCue "used". The readiness screen must offer the
   unused-app exemption; tapping notification actions (Done/Snooze) does count as use.
5. **Geofencing**: minutes of latency, so place context uses dwell + hysteresis and expires to
   `Unknown`; no reminder may depend on sub-minute place detection.
6. **Force stop**: nothing can be done until the user opens the app; on next launch the engine
   replays `BootCompleted` and the readiness screen reports "DayCue was force-stopped; some
   reminders may have been missed" (`ApplicationStartInfo.wasForceStopped()` on 15+).
7. **POST_NOTIFICATIONS denied**: reminders cannot be shown at all (alarms still ring through the
   FGS/full-screen path, but their notification is only in Task Manager). Readiness must make this
   loud.

## 4. Verification plan (emulator `daycue_test`, then physical phone)

| Check | How |
|---|---|
| Alarm in Doze | `adb shell dumpsys deviceidle force-idle`, arm alarm-clock and while-idle wakes, observe delivery |
| Bucket limits | `adb shell am set-standby-bucket app.daycue rare|restricted` |
| Android 17 audio hardening | `adb shell cmd audio set-enable-hardening enable`; test TTS from routine FGS, from alarm FGS (`USAGE_ALARM`), and from a bare receiver |
| Force stop | `adb shell am force-stop app.daycue`, then relaunch and confirm replay |
| Reboot before unlock | Set a PIN on the AVD, `adb reboot`, do not unlock, wait for an armed alarm |
| Full-screen intent | Lock screen, fire alarm; check `canUseFullScreenIntent()` |
| Hibernation | `adb shell cmd app_hibernation set-state app.daycue true` (Unverified command name; check `adb shell cmd app_hibernation help`) |
