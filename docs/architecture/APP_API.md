# `:app` engine host — API for the UI

Owner: scheduling engineer. Code: `android/app/src/main/kotlin/app/daycue/` (everything except `ui/**`).
Status labels: **unit** (JVM test), **instr** (instrumented test on the emulator), **emu** (observed on the
`daycue_test` API 37 emulator), **unverified**.

## 1. How to get it

```kotlin
val facade = (application as DayCueApplication).container.facade   // app.daycue.facade.DayCueFacade
```

`AppContainer` is manual DI (ANDROID.md §2). The UI should use **only** `facade` (plus `DeepLinks` for routing
and `AlarmRingingService.ringing` for the alarm screen). Everything is main-safe.

## 2. Observe

| Member | Type | Notes |
|---|---|---|
| `snapshot` | `StateFlow<HostSnapshot?>` | `(config, state)`; null only until the first load (ms). |
| `config` | `Flow<DayCueConfig>` | Current document. Its `version` is the `baseVersion` for edits. |
| `engineState` | `Flow<EngineState>` | Raw engine state (rarely needed). |
| `today(refreshMs = 30_000)` | `Flow<TodayView>` | `Queries.todayView`, recomputed on every change and every `refreshMs`. |
| `history(limit)` / `historyFor(subjectType, subjectId, limit)` | `Flow<List<HistoryEventEntity>>` | GEN-8 rows, newest first. `subjectType`: `habit`, `medication`, `routine`, `alarm`, `posture`, `calendar`, `session`, `system`, `test`. `isTest` rows never count (GEN-10). `payloadJson` = `{detail, itemKey}`. |
| `audit(limit)` | `Flow<List<AuditLogEntity>>` | Config changes with sensitivity + diff summary. |
| `canUndo` | `Flow<Boolean>` | |
| `readiness` | `StateFlow<ReadinessReport?>` | Filled by `refreshReadiness()` (call from `onResume`). |
| `voices` | `StateFlow<VoiceStatus>` | TTS engine + per-language availability (§6). |

## 3. Act

| Function | What it does |
|---|---|
| `dispatch(event)` / `dispatchAsync(event)` | Any domain `Event`. In-app controls pass `cueId = null`. |
| `apply(ops, baseVersion?, source = "ui")` | The single edit path → `ApplyOutcome.Applied(config, preview)` / `Invalid(errors)` / `Conflict(current, base)`. Defaults `baseVersion` to the current version; pass the version the screen was built from to detect concurrent edits. |
| `preview(ops)` | `Preview(lines, sensitivity, errors)` without applying (MED-9 review). |
| `undo()` | Restores the previous document as a **new** version (`NothingToUndo` if none). Repeated undo walks back. |
| `setEnvironment(value, duration)`, `clearEnvironmentOverride()`, `startSession(kind, duration)`, `endSession()`, `pauseAutoDetection(duration)`, `resumeAutoDetection()` | Manual context controls (PRODUCT §1.4). |
| `leavingNow()` | BTL-1 (tile, widget, chip). |
| `startRoutine(activityContext, routineId, replaceCurrent)` | **Call from a visible Activity.** Starts `RoutinePlaybackService` (`mediaPlayback` FGS), which dispatches the start. Required for audible speech on Android 17 (§6). |
| `testRoutine(activityContext, routineId, RoutineTestMode)` | RTN-8 test run, same path. |
| `testAlarm(alarmId)` | ALM-5. Note: a background-started FGS needs the exact-alarm exemption; call it from the foreground UI. |
| `sendTestReminder(type)` | Readiness "Send a test reminder": real notification labeled "Test" (domain `PreviewCue`). |
| `previewCueProfile(profileId)` | Cue profile "Preview": plays sound + vibration + phrase locally; posts nothing, records nothing. |
| `exportConfig(includeHistory = false)` | `ExportResult(json, containsMedication, includesHistory)`; `*.daycue-backup.json`. Show the medication warning when `containsMedication`. |
| `planImport(text)` | `(ImportParse, ImportPlan?)`. `ImportParse.NotDayCue(reason)` → "This file isn't a DayCue setup"; `UnsupportedSchema`. `ImportPlan(ops, preview, baseVersion, containsMedication)`; `valid`, `noChanges`. Nothing is applied. |
| `applyImport(plan)` | Applies the reviewed plan through `applyOps` (Conflict if the config changed meanwhile). Imported history is ignored. |
| `refreshReadiness()` / `fixIntent(ReadinessId)` | Rows + the exact system screen to open (`startActivity`). |
| `setSpeechRate(Float)`, `setVoice(Language, name?)` | App-local speech preferences (not part of the synced config). |

## 4. Notifications → UI (deep links)

Body taps open `MainActivity` with `ACTION_VIEW` and data `daycue://open/<target>?item=<itemKey>&cue=<cueId>`
(flags `NEW_TASK | SINGLE_TOP`; handle in `onCreate` **and** `onNewIntent`; build the back stack Today → target
per UX §1.4). Targets (`DeepLinks.targetFor`):

| target | itemKey examples | Screen |
|---|---|---|
| `item` | `habit:sunscreen`, `habit:water-bottle` | Item detail, "Why now?" expanded |
| `dose` | `med:<medId>\|<yyyy-mm-dd>\|<HH:mm>` (`ActionMapper.slot()` parses it) | Dose detail |
| `medication` | `med:merged`, `med:policy` | Today medication section |
| `routine` | `routine:<id>`, `routine-prompt:<id>` | Playback (pre-start / recovery state) |
| `posture` | `posture` | Posture live control |
| `calendar` | `cal:<eventKey>` | Calendar preview at that event |
| `context` | `session:<placeId>` | Today + context sheet |
| `alarm` | `alarm:<id>` | Ringing screen |
| `today`, `readiness` | — | |

Notification **buttons** never open the UI; they go to `CueActionReceiver` → `ActionMapper` → engine
(at most 3 per notification; dismissal = `CueDismissed`, never an ack). The notification "Pause" button pauses
for 60 min (`ActionMapper.NOTIFICATION_PAUSE_MIN`); the full SUN-10/HYD-4 option list is in-app
(`Event.Pause(target, choice)`).

## 5. Alarm screen

`delivery/AlarmActivity.kt` is a **functional placeholder** (Compose, `MaterialTheme`, unstyled). The UI engineer
can restyle it in place or move the composable to `ui.alarms` and call it from `AlarmActivity`:
- state: `AlarmRingingService.ringing: StateFlow<RingingAlarm?>` (`alarmId, title, time, canSnooze, locked, isTest`);
  finish when it becomes null;
- Stop / Snooze: `dispatch(Event.AlarmControl(id, AlarmAction.Stop|Snooze))`; when `locked` (before first unlock)
  send `AlarmRingingService.ACTION_LOCKED_STOP|ACTION_LOCKED_SNOOZE` to the service instead;
- back does nothing (UX §1.3). Manifest: `showWhenLocked`, `turnScreenOn`, `directBootAware`, own task.

## 6. Speech, sound and channels (what actually happens)

- Channels = `CueType.channelId` (`alarm, medication, routine, calendar, bottle, sunscreen, posture, hydration,
  habit, notice`) + `routine_playback`, `system`, one group per type. Sound/vibration variants
  `<type>.s.<sound>.v.<vibration>` are created on first use when a profile differs from the type default.
  Channels are never deleted/recreated. **Deviation:** `posture` is `IMPORTANCE_DEFAULT` (PRODUCT §8.1 gives it a
  sound); ANDROID.md proposed LOW. `bottle`/`calendar`/`notice` channels exist because the domain uses them.
- Sounds: `res/raw/cue_*.wav`, original, synthesized by `src/test/.../CueSoundSynth.kt` (checked byte-for-byte by
  `CueSoundAssetsTest`; regenerate with `DAYCUE_REGEN_SOUNDS=1`). Channel URIs use the resource **name**.
- Speech is queued after the notification sound (1.2 s), one utterance at a time (SPK-1), dropped after
  `dropAfter` (SPK-2), skipped during calls, with `HeadphonesOnly` and no headset, with `NotificationOnly` while
  music plays, and under system DND when `respectSystemDnd`. Failures → `SpeechFailed` → readiness row.
- Observed on the API 37 emulator (emu), app in background (bare receiver, screen off):
  - DayCue's **audio-focus request is refused by Android 17 audio hardening** (`AS.HardeningEnforcer: AudioHardening
    focus request ... ignored`) for `USAGE_ASSISTANT` **and** `USAGE_ALARM`. Consequence: no ducking/pausing of
    music for background cues.
  - The utterance is still synthesized and played: the `AudioTrack` belongs to `com.google.android.tts`
    (uid of the engine), `mutedState:none`, `onDone` received. Audibility itself could not be heard (headless
    emulator, `-no-audio`): **physical-device check required**.
  - Inside `RoutinePlaybackService` (started from a visible Activity, `allowWiu` granted) focus **is granted**,
    also after the app went to the background.
  - With the app in the foreground focus is granted.
- Hebrew: Google TTS reports Hebrew as available although the offline voice data isn't installed; the first
  Hebrew utterance starts a download and fails offline (`engine_error:-4`). `VoiceStatus.he` therefore checks for
  an installed offline voice and reports `MissingData`; the readiness "Voices" row shows Limited with a fix
  intent (`INSTALL_TTS_DATA`).

## 7. Readiness rows (`ReadinessId`)

`Notifications`, `ExactAlarms`, `FullScreenIntent`, `BatteryOptimization`, `BackgroundRestricted` (only when
restricted), `Hibernation` (auto-revoke/unused-app exemption), `Voices`, `BlockedChannels` (detail = ids),
`SpeechFailure` (detail = reason@instant), `ForceStopped` (API 35+). Status: `Ready / Limited / Off / NotNeeded /
Checking`; `detail` is machine-readable — the UI words it. `ReadinessReport.lastArm` says which AlarmManager API
armed the next wake (`degraded = true` when exact access is missing); `standbyBucket` is informational.

**Full-screen intent (emu):** on a fresh adb install on API 37 `USE_FULL_SCREEN_INTENT` is **denied by default**
(`appops ... USE_FULL_SCREEN_INTENT: default; rejectTime=...`): the alarm rings (FGS + tone) but the alarm screen
doesn't open. After granting (`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) the screen wakes and
`AlarmActivity` is shown. Onboarding should request it when the first alarm is created (UX §8.2).

## 8. Debug driver (debug builds only)

`app.daycue.devtools.DevToolsReceiver`, enabled at runtime only when the app is debuggable, sender must hold
`DUMP` (adb shell). Output in logcat tag `DayCueDebug`. The receiver is enabled the first time the process starts
(open the app once after install).

```
adb shell am broadcast -n app.daycue/.devtools.DevToolsReceiver -a app.daycue.devtools.CMD --es cmd <cmd> [extras]
dump | tick | boot | undo | export | voices
demo [--ei interval 5]        demo_med [--ei inMin 3]        demo_alarm [--ei inMin 2]
ack [--es habit demo]         preview --es type Hydration
event --es json '<Event JSON>'          ops --es json '[<ConfigOp JSON>, ...]'
speak --es lang he|en --es text '...' [--es usage alarm]
routine [--es id morning-routine] [--es test Fast]   (needs an Activity of ours on screen)
```
JSON uses the domain discriminator `type`, e.g. `{"type":"habitAck","habitId":"demo"}`,
`{"type":"alarm","alarmId":"demo-alarm","action":"Stop"}`.

## 9. Known limitations

- **Force stop** cancels every alarm and blocks all broadcasts until the user opens the app (emu: armed wake gone
  after `am force-stop`, re-armed on next launch). Nothing can be done; readiness shows `ForceStopped` on API 35+.
- `BOOT_COMPLETED` arrived ~50 s after `sys.boot_completed=1` on the emulator; cues due in that gap are delivered
  late (once, GEN-7).
- Locked boot: `LockedBootReceiver` arms a device-protected snapshot (next alarms/doses, no user text). Code path
  exists; **not emulator-verified** (needs a PIN on the AVD). After unlock, an alarm that already rang in locked
  mode will additionally produce the engine's "alarm time passed" notice (the engine doesn't know it rang).
- Import/undo are expressed as ops (`ConfigDiff`); list order of pre-existing items is kept from the current
  document (new items are appended).
- Routine step cues show in the playback FGS notification (one notification). The notification itself alerts via
  the `routine` channel; speech plays in-process.
- Spotify: `AlarmMusicPlayer` seam with a no-op implementation; the local tone always rings.
- Calendar, geofence, companion and relay producers are not implemented (tables `signal`, `calendar_event_cache`,
  `command_log` exist; `SignalObserved` rows are stored).
