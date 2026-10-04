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

- **Emu** - observed on the `daycue_test` emulator (API 37 `google_apis` x86_64, Google Play services
  26.11.36). Emulator results are recorded in §4-§7; nothing here is physical-device-tested yet.

§1-§3 are the architect's original compilation; where an emulator result now exists the cell says
"see §n".

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
| **Geofencing** | 100 geofences/app; latency typically < 2 min, ~2-3 min avg under background location limits, up to 6 min when stationary; re-register after reboot/data clear/`GEOFENCE_NOT_AVAILABLE`; needs fine location and Wi-Fi/network location | - | Needs background location for transitions while app not visible | - | Approximate-only grant: geofencing needs fine location, DayCue does not register (see §5) | - | - | - | - | - |
| **FGS start / types** | `startForegroundService` + `startForeground` within ~5 s; no `startService` from background | `FOREGROUND_SERVICE` permission | `foregroundServiceType` attribute introduced | - | **No FGS start from background** except exemptions (exact alarm, notification/widget interaction, boot/time broadcasts, high-priority FCM, geofence event, battery-opt exempt, ...) | - | Type **mandatory** + per-type `FOREGROUND_SERVICE_*` permission; WIU permission checks at start; `shortService`, `specialUse`, `systemExempted` (exact-alarm holders eligible) | `BOOT_COMPLETED` may not start `mediaPlayback` (and other) FGS types; `dataSync` 6 h timeout | Jobs running alongside an FGS count against job quota | Background audio needs a non-`shortService` FGS (see TTS row) |
| **Full-screen intent (alarm UI)** | `setFullScreenIntent` without permission | - | `USE_FULL_SCREEN_INTENT` (normal permission) required | - | - | - | Special app access; Play revokes it for non-calling/alarm apps; `canUseFullScreenIntent()` + `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Sideloaded (adb) default on API 37: **denied** (Emu, see §7) | - | - | - |
| **TTS / audio from background** | TTS binds to engine service; speaking after a receiver returns needs a running FGS to keep the process | - | - | - | - | - | - | - | - | **Background audio hardening**: playback silenced, focus fails, volume ignored unless visible activity or non-short FGS; targeting 37 also needs WIU-capable FGS **or** exact-alarm permission + `USAGE_ALARM`. Emu: focus refused for a bare receiver, granted inside the user-started FGS; TTS still plays (see §7) |
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
| Full-screen intent | https://developer.android.com/about/versions/14/behavior-changes-14 ; https://developer.android.com/about/versions/10/behavior-changes-10 | Android 14: Doc (read). API 29 introduction: Doc (not re-read) - the Android 14 page words it as "With Android 11". Grant state for sideloaded installs: **Emu: denied by default** on API 37 (§7); expected the same on device, check `canUseFullScreenIntent()`. |
| TTS / audio | https://developer.android.com/about/versions/17/changes/bg-audio ; https://developer.android.com/about/versions/17/behavior-changes-all ; https://developer.android.com/about/versions/17/behavior-changes-17 ; https://developer.android.com/reference/android/speech/tts/TextToSpeech | Android 17 hardening: Doc (read). The page does not mention `TextToSpeech`; emulator result in §7 (audibility itself still needs a physical device). |
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
| Geofence enter/exit | `adb emu geo fix <lng> <lat>` + an active location request (see §5.3) |
| Calendar | Local calendar via `adb shell content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=daycue-test&account_type=LOCAL' ...`, events/attendees via `content insert/update` (§6) |

## 5. Places, geofencing and the water-bottle cue (scheduling engineer, 2026-10-04)

Code: `android/app/src/main/kotlin/app/daycue/integrations/location/`. API for the UI: APP_API.md §12.

### 5.1 What is implemented

| Piece | Behaviour | Status |
|---|---|---|
| Registration | One `Geofence` per active place (request id = place id, radius 50-1000 m, `NEVER_EXPIRE`), transitions ENTER, EXIT and DWELL, `loiteringDelay = placeEnterDwellMin` (3 min default) so the OS dwell lands when the domain would confirm (CTX-2). Initial trigger ENTER + DWELL. Max 100 (Play services limit); extra places are reported as `skipped`. | unit (plan); emu (2 registered, ENTER/EXIT received) |
| Re-registration | Process start, config change (center/radius/dwell/away-policy fingerprint), `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `PROVIDERS_CHANGED` / `MODE_CHANGED`, `GEOFENCE_NOT_AVAILABLE`, permission result, app open; plus a PendingIntent liveness check (`FLAG_NO_CREATE`) that detects registrations lost to reboot or force stop. | emu (package replaced; force stop -> "registrations lost: re-registering"); reboot path: code only |
| Signals | ENTER/EXIT/DWELL -> `GeofenceTransition(observedAt = triggering fix time, expiresAt = observedAt + 30 min)`. A transition older than 30 min on arrival is dropped and a fresh snapshot is requested instead (a queued exit must not fire the bottle cue long after the departure). `GEOFENCE_NOT_AVAILABLE` / insufficient permission -> `LocationAvailability(false)` (Place = Unknown immediately). | unit, emu |
| Snapshot | After (re)registration and on app open (at most every 10 min; at most one fix per minute overall): one `getCurrentLocation` (balanced; GPS only if balanced yields nothing) -> `GeofenceSnapshot` **only if unambiguous** (accuracy circle wholly inside or wholly outside every place, accuracy <= 250 m). | unit, emu |
| Hysteresis / jitter | Done by the domain: exit confirmed after `placeExitDwell` (5 min), re-entry cancels it (CTX-3). | unit at integration level (decode -> EngineHost -> domain, 3 jitter cycles, no extra transition); emu: snapshot exit confirmed exactly 5 min later |
| "Use current location" | One high-accuracy fix, cached up to 60 s accepted; returns accuracy and `precise`. | code; same call path produced the emulator snapshots (accuracy 5 m) |
| No continuous sampling, no permanent FGS | Only OS-side registrations plus one-shot fixes. | code review |

### 5.2 Permissions and what degrades

Progressive flow (Android rules): foreground first (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` in one request),
then the precise upgrade if the user picked approximate, then `ACCESS_BACKGROUND_LOCATION` as a separate request (API
30+ opens the settings page; option label `getBackgroundPermissionOptionLabel()`). Source:
https://developer.android.com/develop/sensors-and-location/location/permissions/background (Doc read, §2).

| State | Result |
|---|---|
| No location permission | No geofences; `LocationAvailability(false)` -> Place = Unknown. Manual context, Leaving now and scheduled departures work (scenario 6; **emu**: permissions revoked -> Place Unknown, then `OverrideEnvironment(Outdoor)` applied with source Manual). |
| Approximate only | Geofencing requires fine location (geofencing guide, Doc read): DayCue does not register. "Use current location" works but is km-scale; the UI should say so. |
| Precise, no background (foreground-only) | DayCue does **not** register geofences (API 29+ needs background location for transitions to a closed app) and does **not** poll. Place stays Unknown; place-conditioned items follow `unknownMatches`; geofence-exit bottle cues never fire; sessions never auto-start. "Use current location" works. |
| Location switch off | Place = Unknown; re-registered automatically when switched back on (`PROVIDERS_CHANGED` is on the implicit-broadcast exception list). |
| Play services missing | No geofences, no activity recognition, no current location. Everything else unaffected. |
| `ACTIVITY_RECOGNITION` denied | `OutdoorWhenOnFoot` behaves as Unknown away from saved places (CTX-6). |

### 5.3 Geofence latency, honestly

- Official (geofencing guide, Doc read 2026-10-04): "usually less than 2 minutes", "about 2-3 minutes on average" under
  the Android 8 background location limits, "up to 6 minutes" when the device is stationary. Doze, battery saver and OEM
  power management can add more (unverified; device-specific).
- **Emulator (emu; not representative of latency):** the Play services geofencer on the emulator only evaluated geofences
  while some app had an active location request. `adb emu geo fix` alone produced **no** transitions for 8.5 min outside
  the place (fixes every 5 s). ENTER and EXIT both arrived 15-30 ms after DayCue's own one-shot fix (app open / snapshot):
  the emulator has no network location provider, so nothing else feeds the geofencer. The OS DWELL never arrived (no fixes
  after the loitering delay); the domain confirmed the place from ENTER after 3 min, as designed. On a phone, Wi-Fi/cell
  location and activity changes drive the geofencer; latency there must be measured (§5.6).
- Consequence for the water bottle: a geofence exit (BTL-2) usually arrives after you are out of the door. It is a fallback.
  **Emu:** exit -> bottle cue delivered in the same reduce (`habit:water-bottle`, channel `bottle`). A departure detected
  only through a snapshot (no OS EXIT) **now cues the bottle too** (domain BTL-2 "snapshot departure": a
  `GeofenceSnapshot` that no longer lists a place the engine was raw-inside of takes the same path as a raw exit, with the
  same dedup, cooldown and BTL-5 rules; exit + snapshot in either order cue once). The app takes a one-shot fix at
  (re)registration, on app open, on a stale queued transition **and when a walk or a drive starts** (activity-transition
  ENTER of on-foot or in-vehicle, throttled to one fix per minute), so a missed EXIT is repaired shortly after leaving
  (unit: fix outside -> snapshot -> bottle cue; not measured on a device).

### 5.4 Earlier departure signals - assessment

| Signal | Assessment | Decision |
|---|---|---|
| **Leaving now** (BTL-1) | Instant, dependable, user-initiated. Already wired: `facade.leavingNow()` -> `Event.LeavingNow`. | Implemented (facade); a Quick Settings tile / widget / Today chip is UI work |
| **Scheduled departure** (BTL-6) | Dependable automatic option (exact alarm), in the domain. | Implemented (domain) |
| **Home Wi-Fi disconnect** | The SSID needs `ACCESS_FINE_LOCATION` + location on, and `ACCESS_BACKGROUND_LOCATION` for a closed app; on API 31+ the `NetworkCallback` must be created with `FLAG_INCLUDE_LOCATION_INFO` to see it. Background observation: `CONNECTIVITY_ACTION` is not delivered to manifest receivers since API 24; `WifiManager.NETWORK_STATE_CHANGED_ACTION` is not on the implicit-broadcast exception list; `registerNetworkCallback(request, PendingIntent)` fires when a matching network becomes available, not when one is lost. A disconnect is therefore only observable while DayCue's process is alive (in-process `onLost`), which is not guaranteed without a permanent FGS. It is also noisy (router reboot, Wi-Fi off at night, edge of the flat). Sources (Doc, not re-read): https://developer.android.com/reference/android/net/ConnectivityManager , https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback , https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions | **Not implemented**: no reliable background signal without a permanent FGS |
| **Activity Recognition STILL -> WALKING at a place** | Feasible (`ACTIVITY_RECOGNITION`, transition API, PendingIntent). As a departure trigger it fires constantly at home (walking between rooms). | AR implemented **for Environment only** (§5.5); not a bottle trigger. A future domain rule could combine it with a departure time window. |
| Car Bluetooth / NFC tag | PRODUCT §5: reliable but late (car) or needs hardware (tag). | Not implemented |

### 5.5 Activity Recognition (implemented, optional)

Transition API (Doc read: https://developer.android.com/develop/sensors-and-location/location/transitions): WALKING,
RUNNING, ON_BICYCLE enter and exit; IN_VEHICLE enter; STILL enter. Mapping (unit, `MotionSignals.map`): on-foot enter ->
`MotionActivity(OnFoot, transition = Enter)`; on-foot **exit** -> `OnFoot` with `transition = Exit` at the exit instant
(the walk ended then, so the 45-minute `onFootHoldMin` hold counts from the end of the walk); IN_VEHICLE enter ->
`InVehicle` and STILL enter -> `Still`, both `transition = Enter`. `observedAt` from `elapsedRealtimeNanos`;
`expiresAt = observedAt + activityRecognitionExpiryMin`. Registered only with the permission and
`awayEnvironment = OutdoorWhenOnFoot`; `MotionAvailability` follows.

**Long walks (CTX-6 continuous walk, DOMAIN.md section 3).** The API still reports changes only, never "still walking",
but the domain now treats an on-foot `Enter` as an *ongoing* walk: it needs no further readings and lasts until a contrary
signal (`Still`, `Other`, an on-foot `Exit`, `InVehicle`) or until `contextRules.onFootOngoingMaxMin` (default 180 min,
range 30-720) after the last supporting on-foot signal, whichever comes first, then the usual exit dwell. So a walk
longer than 45 min **keeps Outdoor up to that bounded maximum** (it used to lose Outdoor after `onFootHoldMin` without a
transition). A walk longer than the maximum with no further transition still falls back to Unknown (stale becomes
unknown). Unit-tested in the domain (`ContextEngine`) and in `MotionAndGoneSignalsTest` (the mapping); detection latency
"varies by device" (doc); not measured on a device. Starting a walk or a drive also triggers one location fix (§5.3).
Emu: registration succeeded and a STILL enter arrived right after registering; walking can't be simulated on the emulator.

### 5.6 Physical-device test steps (owner's phone) - not done

1. Install the debug build; grant location "Allow all the time" + precise + Physical activity; set Home with "Use current
   location" (check the reported accuracy <= 30 m); enable the water-bottle cue for Home.
2. `adb logcat -s DayCueLocation DayCue`; note `location sync (...): Automatic ok registered=N`.
3. Walk out with the phone locked in a pocket; note the wall-clock time you cross the radius (a landmark ~150 m away).
   Record when `geofence event: Exit` and `delivered habit:water-bottle` appear. Repeat 5 times at different times of day,
   including once after the phone sat still > 1 h (Doze).
4. Come back and stand still inside; record the `Enter` and `Dwell` times and when Place became Home (3 min after Enter).
5. Boundary jitter: stand at the edge of the circle for 10 min; Place must not flip (no extra `ContextChanged` rows).
6. Reboot; without opening the app check `system broadcast ...BOOT_COMPLETED` and the re-registration; note the delay.
7. Turn location off and on; check Place = Unknown, then re-registration.
8. Revoke background location in Settings; open the app; check `Off no_background` and that manual controls still work.
9. Walk 10+ min outdoors away from saved places; check `activity transitions: ... [OnFoot]` and Environment = Outdoor
   5 min later.

## 6. Calendar (Calendar Provider, read-only) - see ADR-0004

Code: `integrations/calendar/`. API: APP_API.md §13.

| Item | Status |
|---|---|
| Calendar list; selection via `SetCalendarPreference` | emu (local test calendar listed and selected) |
| Mapping: canceled, self declined, all-day (local midnight), attendees excl. self and resources, organizer, own reminders (alert/default/alarm methods; email doesn't count), conferencing host match, color, availability, timezone, recurrence identity (`series@originalStart`) | unit; emu for declined via an attendee row (`selfAttendeeStatus=2` -> `Declined`) and attendee count |
| Dedupe across calendars (UID + start, fallback title + times) | unit |
| `CalendarSynced` -> cue for a matching event, none for excluded ones | unit (fake provider + real engine) and **emu**: "Design review" (1 attendee, Meetings rule) cued exactly at start - 10 min; "Lunch" (no rule) and "Declined sync" (declined) got no cue |
| Moved event reschedules; canceled event retracts | unit and **emu**: "Retro" moved +3 min before its lead -> no cue at the old lead (17:48:34), cue exactly at the new lead (17:51:34) (an earlier run of the same check was cut off by an emulator suspension); "Design review" canceled after delivery -> `Retracted CAL-2`, notification removed. The provider drops canceled single events from `Instances`, so the engine saw a removal. |
| Triggers | emu: `ContentObserver` sync ~3 s after a provider write, content-URI trigger job ~5 s after; periodic 30 min and app open: code, app open seen in logs |
| Event added shortly before start | unit: added 4 min before start -> one cue at the next sync (CAL-3) |
| Permission revoked / nothing selected | unit: empty snapshot dispatched (no cues from stale data) |
| UID column | `Instances.UID_2445` is probed; if a provider rejects it, the query falls back without it (dedupe then uses title + times). emu: accepted. |

**Worst-case latency for a new or changed event** (provider write -> engine): process alive ~3 s (observer debounce);
app closed, device awake 5-60 s (JobScheduler content trigger); device in Doze: jobs and the 30-min periodic sync wait
for a maintenance window (windows get rarer the longer the device is idle - can be hours); opening the app syncs at once.
Before any of this the device's own Google calendar sync must have written the event (push-driven on most phones, but
outside our control; account sync off = never). So an event created on the web 10 minutes before it starts gets its cue
on a phone in use, and possibly **no cue** on a phone deep in Doze; once synced before the start, CAL-3 delivers the
latest due lead once. Physical-device steps:

1. Grant Calendar; select your Google calendar; check `calendar sync (...): Ok events=N`.
2. On the web, create an event with one guest 20 min ahead; record when `calendar sync (observer|provider changed)` logs
   `+1` (screen on, app closed) and when the cue fires (must be start - 10 min).
3. Repeat with the phone idle, screen off for > 1 h (Doze), creating the event 15 min ahead; record whether/when it syncs.
4. Move the event 30 min later on the web; check the cue moves. Decline it; check the cue is removed.
5. Add the same meeting to a second selected account's calendar; check there is one cue only.
6. Turn off account sync for Calendar; after 24 h check readiness `CalendarSync` = Limited (maxCacheAge).

## 7. Earlier emulator findings (platform layer; also in APP_API.md §6, §7, §9)

| Finding | Status |
|---|---|
| **Android 17 background audio:** DayCue's audio-focus request from a bare receiver (app in background, screen off) is refused by audio hardening (`AS.HardeningEnforcer: AudioHardening focus request ... ignored`) for both `USAGE_ASSISTANT` and `USAGE_ALARM`. The TTS utterance is still synthesized and played by the engine's `AudioTrack` (uid of `com.google.android.tts`, not muted, `onDone` received), but music is not ducked. Inside `RoutinePlaybackService` started from a visible Activity focus is granted, also after the app went to the background. Audibility could not be heard (headless emulator, `-no-audio`). | Emu; audibility needs a physical device |
| **Hebrew voice:** Google TTS reports Hebrew as available although no offline voice data is installed; the first Hebrew utterance starts a download and fails offline (`engine_error:-4`). DayCue checks for an installed offline voice, reports `MissingData`, and the readiness Voices row offers `INSTALL_TTS_DATA`. | Emu |
| **Full-screen intent:** on a fresh adb install on API 37 the `USE_FULL_SCREEN_INTENT` app-op is `default` = **denied** for a sideloaded app. The alarm rings (FGS + tone) but the alarm screen doesn't open until the user grants it (`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`). Onboarding must ask when the first alarm is created. | Emu |
| **`BOOT_COMPLETED` delay:** delivered ~50 s after `sys.boot_completed=1`; cues due in that gap are delivered late, once (GEN-7). Geofences and activity transitions are re-registered from the same broadcast, so Place is Unknown at least that long after a reboot. | Emu |
| **Force stop (API 35+):** `BOOT_COMPLETED` is delivered when the user next launches the app; DayCue's PendingIntents (alarm, geofences, activity transitions) were gone and are re-created (liveness check, §5.1). | Emu |
