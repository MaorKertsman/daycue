# DayCue Android architecture

Owner: Android architect. Elaborates `docs/ARCHITECTURE.md` (the contract); where this file
proposes a contract change it says so explicitly. Platform facts and their sources are in
[`FEASIBILITY.md`](FEASIBILITY.md).

## 1. Toolchain and versions (verified 2026-10-04)

Latest **stable** versions, read from Google Maven `maven-metadata.xml`, Maven Central
`maven-metadata.xml`, `services.gradle.org/versions/current` and developer.android.com.
They live in `android/gradle/libs.versions.toml`.

| Item | Version | Note |
|---|---|---|
| Gradle (wrapper) | 9.8.0 | AGP 9.4 needs >= 9.6.0; distribution SHA-256 pinned in the wrapper |
| Android Gradle Plugin | 9.4.1 | Max supported API 37. **Built-in Kotlin**: `org.jetbrains.kotlin.android` is not applied |
| Kotlin (KGP, Compose compiler, serialization plugin) | 2.4.20 | Declared at the root so AGP's built-in Kotlin uses it |
| KSP | 2.3.12 | KSP2; independent version line; supports AGP 9 built-in Kotlin |
| compileSdk / targetSdk | 37 (Android 17) | Platform package `platforms;android-37.0`; minor SDKs 37.1/37.2 not used |
| minSdk | 26 (Android 8.0) | `java.time` native, notification channels exist, no desugaring needed |
| Compose BOM | 2026.09.00 | Material 3 from the BOM |
| Room | 2.8.5 | KSP + `androidx.room` Gradle plugin (schema export to `app/schemas`) |
| WorkManager | 2.12.0 | |
| Lifecycle | 2.11.0 | |
| Navigation Compose | 2.10.2 | |
| DataStore | 1.2.1 | |
| Play Services Location | 21.4.0 | Catalogued; added to `:app` with the geofencing feature |
| kotlinx-serialization | 1.11.0 | |
| kotlinx-coroutines | 1.11.0 | |
| activity-compose / core-ktx / appcompat | 1.13.0 / 1.19.1 / 1.8.0 | appcompat only for the per-app-language backport |
| JDK | 21 (build), bytecode 17 | |

Build: `cd android; .\gradlew.bat :domain:test :app:assembleDebug` (JAVA_HOME, ANDROID_HOME set).

## 2. Modules

- `:domain` - Kotlin/JVM. Layers 1-4 (config, signal, context, engine) plus `ConfigOp`
  validation. No Android imports, ever. Owned by the scheduling engineer.
- `:app` - `app.daycue` (namespace = applicationId, debug and release alike). Hosts the
  engine, persists state, and executes effects.

Dependency injection is manual (`AppGraph` built in `DayCueApplication`). The graph is small;
Hilt would add KSP/kapt surface and build time for little gain. Revisit if it grows.

## 3. Package structure of `:app`

| Package (`app.daycue.`) | Layer (ARCHITECTURE §2) | Contents |
|---|---|---|
| `ui.*` (`ui.home`, `ui.habits`, `ui.routines`, `ui.alarms`, `ui.settings`, `ui.readiness`, `ui.theme`) | 1 (editing definitions) | Compose screens + ViewModels. Edits produce `ConfigOp`s only, never write config directly. |
| `data.db` | persistence | Room database, entities, DAOs (section 4). |
| `data.repo` | persistence | `ConfigRepository` (`applyOps` -> transaction: history row + current row + audit), `HistoryRepository`. |
| `data.boot` | persistence | Device-protected "boot snapshot" (section 5.4). |
| `engine` | 4 (host side) | `EngineHost`: load config + state, call `reduce`, persist `EngineState` in one transaction, hand `Effect`s to executors. Serialised through one `Mutex`; the only caller of `reduce`. |
| `signal` | 2 (producers) | Android sources that emit `SignalObserved`: `GeofenceSignalSource` + `GeofenceReceiver`, manual override. |
| `scheduling` | 4 -> platform | `WakeScheduler` (executes `ScheduleWake`/`CancelWake` on `AlarmManager`), `WakeAlarmReceiver` (feeds `Tick`), `ExactAlarmAccess`. |
| `delivery` | 5 | `ChannelRegistry`, `NotificationDelivery`, `SpeechDelivery` (TTS), `AlarmRingingService` + `AlarmActivity`, `RoutinePlaybackService`. Executes effects; makes no policy decisions. |
| `actions` | 6 | `CueActionReceiver` (Done/Snooze/Pause notification actions -> `Event`), `CueDismissedReceiver` (records dismissal, never `Ack`). |
| `integrations` (`.calendar`, `.spotify`, `.companion`, `.relay`) | 7 | Produce signals or `ConfigOp` commands only. WorkManager workers for sync. |
| `system` | lifecycle | `SystemEventsReceiver` (boot/time/package/permission broadcasts), `Readiness` (permission + channel + battery state for the readiness screen). |

## 4. Room schema

Database `daycue.db`, version 1 until the first install that holds real data; after that every
change ships a `Migration`, the exported schema JSON in `app/schemas/` is committed, and
`fallbackToDestructiveMigration` is never enabled for release builds. All instants are
`INTEGER` epoch milliseconds UTC (`*_ms`); where the local interpretation matters the zone id is
stored next to it. Booleans are `INTEGER` 0/1.

Only `config_current` exists in the scaffold; the rest is the agreed design.

### config_current (single row)
| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | Always 1 |
| `schema_version` | INTEGER | `DayCueConfig.schemaVersion` |
| `version` | INTEGER | `DayCueConfig.version` (monotonic) |
| `json` | TEXT | Full `DayCueConfig` (kotlinx.serialization) |
| `updated_at_ms` | INTEGER | |

### config_history (bounded, newest N = 100 kept; trimmed in the same transaction)
| Column | Type | Notes |
|---|---|---|
| `version` | INTEGER PK | Version of the document **being replaced** |
| `schema_version` | INTEGER | |
| `json` | TEXT | Previous document (undo source) |
| `replaced_at_ms` | INTEGER | Index |
| `source` | TEXT | `ui` / `mcp` / `import` / `undo` / `migration` |
| `command_id` | TEXT NULL | FK-like reference to `command_log.command_id` |

### engine_state (single row)
| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK | Always 1 |
| `json` | TEXT | Full `EngineState` |
| `config_version` | INTEGER | Config version the state was reduced against |
| `next_wake_at_ms` | INTEGER NULL | Typed copy of `state.nextWakeAt` so receivers can re-arm without decoding JSON |
| `next_wake_precision` | TEXT NULL | `alarm_clock` / `exact` / `inexact` (see 5.1) |
| `updated_at_ms` | INTEGER | |

### history_event
| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | |
| `occurred_at_ms` | INTEGER | Index |
| `zone_id` | TEXT | Zone at the time, for "taken at 08:02 local" |
| `kind` | TEXT | `delivered`, `ack`, `snooze`, `dismissed`, `missed`, `skipped`, `alarm_dismissed`, `alarm_snoozed`, `routine_started`, `routine_completed`, ... |
| `subject_type` | TEXT | `habit` / `medication` / `routine` / `alarm` / `posture` |
| `subject_id` | TEXT | Stable id from config |
| `cue_id` | TEXT NULL | Delivery instance id (links delivered -> ack) |
| `scheduled_for_ms` | INTEGER NULL | Due instant, for lateness stats and the medication log |
| `payload_json` | TEXT NULL | Small, kind-specific |

Indexes: `(subject_type, subject_id, occurred_at_ms)`, `(occurred_at_ms)`, `(cue_id)`.

### signal (latest per source)
| Column | Type | Notes |
|---|---|---|
| `source` | TEXT PK | `geofence`, `manual`, `companion`, `calendar` |
| `value_json` | TEXT | Domain `Signal` payload |
| `observed_at_ms` | INTEGER | |
| `expires_at_ms` | INTEGER | Expired rows are treated as `Unknown` by the domain, not deleted eagerly |

### calendar_event_cache
| Column | Type | Notes |
|---|---|---|
| `instance_key` | TEXT PK | `<calendarId>:<eventId>:<beginMs>` from `CalendarContract.Instances` |
| `calendar_id` | INTEGER | |
| `begin_ms`, `end_ms` | INTEGER | Index `(begin_ms, end_ms)` |
| `all_day` | INTEGER | |
| `busy` | INTEGER | From `AVAILABILITY` |
| `title` | TEXT NULL | On-device only; never exported, never sent remote unless a scope allows |
| `fetched_at_ms` | INTEGER | |

### command_log (MCP commands, at-most-once apply)
| Column | Type | Notes |
|---|---|---|
| `command_id` | TEXT PK | Idempotency key from the relay |
| `received_at_ms` | INTEGER | |
| `origin` | TEXT | `relay` / `local` |
| `base_version` | INTEGER | |
| `ops_json` | TEXT | Serialized `List<ConfigOp>` |
| `state` | TEXT | `received` / `awaiting_confirmation` / `applied` / `rejected` / `failed` / `expired`. Index |
| `result_json` | TEXT NULL | Validation errors / conflict / applied version |
| `applied_version` | INTEGER NULL | |
| `acked_at_ms` | INTEGER NULL | When the signed ack reached the relay |

### audit_log
| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | |
| `at_ms` | INTEGER | Index |
| `actor` | TEXT | `user`, `mcp:<client-label>`, `system` |
| `action` | TEXT | e.g. `config.apply`, `config.undo`, `permission.changed`, `pairing.added` |
| `sensitivity` | TEXT | `ordinary` / `sensitive` / `destructive` |
| `summary` | TEXT | Human-readable diff summary (from `preview`) |
| `version_before`, `version_after` | INTEGER NULL | |
| `command_id` | TEXT NULL | |

Backups: `android:allowBackup="false"` for now (medication history must not leave the device
via cloud backup by default). Export/import goes through the explicit `*.daycue-backup.json` flow.

## 5. Alarms and scheduling

### 5.1 Which AlarmManager API for which cue

The engine still arms exactly **one** next-wake alarm (ADR-0002). The API used is chosen by the
most critical item due at that instant:

| Cue class at the wake instant | API | Why |
|---|---|---|
| Morning alarm (ringing) | `setAlarmClock(AlarmClockInfo(at, showIntent), pi)` | Never deferred; system leaves Doze shortly before; shows the alarm icon and appears as the system "next alarm". |
| Medication reminder | `setAlarmClock` | Must not be missed or batched. Side effect accepted: it shows as the next alarm in the status bar / clock widgets. |
| Routine start, habit, posture, interval habits | `setExactAndAllowWhileIdle` | Fires in Doze, but while-idle alarms are throttled (see FEASIBILITY: "once per 9 min" / "7 per hour" in Doze). The engine must therefore coalesce soft cues due within a few minutes of each other into one wake. |
| Anything when exact access is missing (should not happen, see 5.2) | `setAndAllowWhileIdle` | Inexact fallback; readiness screen shows "reminders may be late". |

**Contract change proposed** (needs the delivery lead / scheduling engineer):
`Effect.ScheduleWake(at, exact: Boolean)` is not enough to pick the API. Replace it with
`ScheduleWake(at, precision: WakePrecision)` where `WakePrecision = AlarmClock | Exact | Inexact`.
The domain decides precision (it knows the cue class); the app only maps it.

Mechanics: one `PendingIntent.getBroadcast` to `WakeAlarmReceiver` with a fixed request code,
`FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`; re-arming always replaces it. The receiver uses
`goAsync()`, feeds `Tick` through `EngineHost`, executes effects, and finishes well inside the
10 s broadcast budget; long work (ringing, routine) moves into a foreground service started from
the receiver (exact alarms are exempt from FGS background-start restrictions).

### 5.2 Exact-alarm permission decision: `USE_EXACT_ALARM`

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

- API 26-30: no permission exists; exact alarms always allowed.
- API 31-32: `SCHEDULE_EXACT_ALARM`, granted at install but user-revocable.
- API 33+: `USE_EXACT_ALARM`, granted at install, **not revocable by the user**.

Justification: DayCue's core function includes a user-set alarm clock and time-critical
medication reminders, which is exactly the use case `USE_EXACT_ALARM` exists for. The app is
personal and sideloaded, so Play's declaration policy does not gate it, but it would also qualify
there. `SCHEDULE_EXACT_ALARM` on API 34+ is denied by default for fresh installs, adding a
mandatory settings trip and a silent-failure mode if revoked (revocation also kills the process and
cancels all exact alarms). Holding an exact-alarm permission additionally:
- makes `systemExempted` FGS type available,
- exempts the app from the Restricted standby bucket (USE_EXACT_ALARM listed explicitly),
- satisfies Android 17's background-audio exemption for `USAGE_ALARM` streams.

The code still checks `AlarmManager.canScheduleExactAlarms()` before every arm (API 31+) and falls
back to `setAndAllowWhileIdle` + readiness warning, and `SystemEventsReceiver` handles
`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31-32 grant) by replaying the engine.

### 5.3 Receivers

| Receiver | Exported | Actions | Behaviour |
|---|---|---|---|
| `WakeAlarmReceiver` | no | explicit (our PendingIntent) | `Tick` -> reduce -> effects |
| `SystemEventsReceiver` | yes (system broadcasts only) | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `LOCALE_CHANGED` (rename channels) | Replays `BootCompleted` / `TimeChanged` / `TimezoneChanged`; re-arms; re-registers geofences after boot |
| `LockedBootReceiver` | yes, `directBootAware="true"` | `LOCKED_BOOT_COMPLETED` | Reads the device-protected boot snapshot only (5.4) and arms it |
| `CueActionReceiver` | no | explicit | Done / Snooze / Pause actions -> `Ack` / `Snooze` / `Pause` |
| `CueDismissedReceiver` | no | explicit (`deleteIntent`) | Records `dismissed`; **never** an `Ack` |
| `GeofenceReceiver` | no | explicit (Play services fills a **mutable** PendingIntent, required by `GeofencingClient`) | `SignalObserved(geofence)` |

All listed system actions are on the implicit-broadcast exception list, so manifest receivers
keep working on API 26+. `RECEIVE_BOOT_COMPLETED` is required. Force-stop disables all of these
until the user opens the app again (see FEASIBILITY).

### 5.4 Reboot before first unlock (direct boot)

All alarms are cleared on reboot and Room lives in credential-encrypted storage, unreadable until
the first unlock. A phone that reboots overnight (OS update) would otherwise miss the morning
alarm. Plan: after each reduce, `EngineHost` also writes a tiny **boot snapshot** to
device-protected storage (`createDeviceProtectedStorageContext()`): next `alarm_clock`-precision
instant, kind (`alarm`/`medication`) and a generic label key - no medication names, no habit
text. `LockedBootReceiver` arms it; `AlarmRingingService`/`AlarmActivity` are direct-boot aware and
can ring with a generic title. After unlock, `BOOT_COMPLETED` replays the full engine and replaces
it. (Proposed; needs emulator verification.)

## 6. Notifications

### 6.1 Channels

Created once in `DayCueApplication.onCreate()` with `createNotificationChannels` (re-creating with
the same values is a no-op). Never deleted and recreated to override user choices; the user's
system settings are authoritative and surfaced on the readiness screen
(`getNotificationChannel(id).importance == IMPORTANCE_NONE`, etc.). Names/descriptions are the
only things we update (on locale change).

| Channel id | Importance | Purpose |
|---|---|---|
| `medication` | HIGH | Medication reminders. Default sound + vibration. Never suppressed by context. |
| `alarm` | HIGH | Ringing-alarm FGS notification with full-screen intent. **Silent channel**: the service plays audio itself (`USAGE_ALARM`). |
| `habit` | DEFAULT | Interval/fixed habits. |
| `posture` | LOW (silent) by default | Frequent gentle cues; user can raise it. |
| `routine` | DEFAULT | "Routine is due - Start" prompts. |
| `routine_playback` | LOW | Ongoing routine FGS notification (media-style controls). |
| `system` | LOW | Readiness problems, sync results, missed-alarm notices. |

### 6.2 Custom sound per cue, given channel immutability

A channel's sound is fixed at creation. Two mechanisms:

1. **Notification cues (habit, medication, routine, posture):** sound-variant channels. When the
   user picks a non-default sound for a cue type, the app creates (once) a channel
   `<type>.s.<soundKey>` in a channel group named after the type, with that sound, and posts the
   cue there. Changing the sound moves the cue to another variant channel; variants are never
   rewritten. `soundKey` comes from a small bundled catalogue (`res/raw`, accessed via
   `android.resource://` URIs) to keep the channel count bounded. A user-chosen system ringtone URI
   is hashed into the key.
2. **Alarms and spoken routines:** the app plays audio itself inside a foreground service, so any
   sound/volume ramp/TTS is possible and channel sound is irrelevant.

Playing the sound ourselves for ordinary notification cues (silent channel + MediaPlayer) is
rejected: it bypasses DND/channel settings the user expects, and on Android 17 audio from the
background without a qualifying FGS is silenced.

### 6.3 Full-screen alarm UI

`AlarmRingingService` posts a `CATEGORY_ALARM` notification with `setFullScreenIntent(AlarmActivity)`.
Needs `USE_FULL_SCREEN_INTENT`; on API 34+ the app checks
`NotificationManager.canUseFullScreenIntent()` and, if false, sends the user to
`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Without it the alarm still rings (heads-up +
sound), just without the lock-screen takeover.

## 7. Foreground services

Only while something user-visible runs (ARCHITECTURE 3.4). Both are `mediaPlayback`:

| Service | `foregroundServiceType` | Manifest permissions | Started from |
|---|---|---|---|
| `RoutinePlaybackService` | `mediaPlayback` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Activity, or the "Start" action of the routine-due notification (user interaction -> while-in-use capability). **Never** auto-started from a background alarm: on Android 17 background audio (TTS) from an FGS without while-in-use capability is silenced unless it is `USAGE_ALARM`. |
| `AlarmRingingService` | `mediaPlayback` | same, plus `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK` | `WakeAlarmReceiver` on an exact alarm (FGS background-start exempt). Audio with `USAGE_ALARM` (Android 17 exemption for exact-alarm holders). Not started from `BOOT_COMPLETED` (forbidden for `mediaPlayback` on Android 15+); a missed alarm after reboot is reported as a notification. |

Why not `systemExempted` for the alarm: it is available to exact-alarm holders, but it is
documented as reserved for system integrations; `mediaPlayback` is the conventional type, has no
timeout and no runtime prerequisites. Keep `systemExempted` as the fallback if device testing shows
a problem. `shortService` is not usable (3-minute cap, and excluded from Android 17 audio).

## 8. Progressive permission plan

Install-time (normal / auto-granted, declared when the feature lands): `RECEIVE_BOOT_COMPLETED`,
`USE_EXACT_ALARM` (+ `SCHEDULE_EXACT_ALARM` max 32), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, later
`INTERNET` (relay/Spotify only).

Asked in context, never at first launch, each with an in-app explanation first:

| Step | Permission / setting | When | If denied |
|---|---|---|---|
| 1 | `POST_NOTIFICATIONS` (API 33+) | User turns on the first reminder | Readiness: "reminders are off"; FGS notices still appear only in Task Manager |
| 2 | Full-screen intent (API 34+ check) | First alarm created | Alarm rings without lock-screen takeover |
| 3 | Exact alarms (API 31-32 only) | Only if `canScheduleExactAlarms()` is false | Inexact fallback + warning |
| 4 | Unused-app hibernation exemption | After first week of use, or with step 1 | Readiness warning: alarms and permissions can be reset after months without opening the app |
| 5 | `ACCESS_FINE_LOCATION` (+ coarse) | User adds the first place | No place context; `Unknown` |
| 6 | `ACCESS_BACKGROUND_LOCATION` | User enables place-based rules; separate request (API 30+: settings page "Allow all the time") | Place context only while the app is open |
| 7 | `READ_CALENDAR` | User enables a calendar rule | Calendar busy = `Unknown` |
| 8 | Battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | Offered on the readiness screen, recommended on OEMs known for aggressive killing | Alarm-clock alarms still fire; soft cues may be late |
| 9 | DND access (`ACCESS_NOTIFICATION_POLICY`) | Only if the user wants medication to bypass DND | Channel-level DND bypass not honoured |

The readiness screen (`ui.readiness`) shows every row above with live state and a deep link.
