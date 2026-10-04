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
- Spotify: implemented behind `AlarmMusicPlayer` (section 11) but **unverified on a live account**, and Spotify's
  Developer Policy prohibits alarm functionality without written approval (docs/setup/SPOTIFY.md). Off unless the
  App Remote AAR is added locally; the local tone always rings first. Relay and companion producers: section 10.
- Calendar, geofence, companion and relay producers are not implemented (tables `signal`, `calendar_event_cache`,
  `command_log` exist; `SignalObserved` rows are stored).

## 10. Remote access (relay) — `facade.remote`

Owner: integrations engineer. Code: `integrations/relay/**`, `facade/RelayFacade.kt`. Protocol: `docs/architecture/RELAY.md`;
setup: `docs/setup/MCP.md`. Labels: **unit** = JVM test, **emu** = instrumented on an emulator, **unverified** = not run.
Everything is optional: with no relay paired nothing here runs and no network call is made.

```kotlin
val remote: RelayFacade = facade.remote
```

| Member | Notes |
|---|---|
| `paired: StateFlow<Boolean>`, `relayHost(): String?` | Host name only; the credential never leaves encrypted storage. |
| `parsePairing(text)` | Accepts `https://relay`, `daycue://pair?relay=<url>&code=ABCDE-FGHJK`, or a bare code. No QR scanner is built in (the UI can scan and pass the text). |
| `pair(relayUrl, code, deviceLabel)` -> `PairResult` | `Ok`, `InvalidUrl`, `CleartextNotAllowed` (http only in debuggable builds), `Rejected(code, message)` (e.g. `invalid_pair_code`, `locked`), `Offline`. Generates a non-exportable ECDSA P-256 Keystore key (StrongBox if present), registers it, stores the credential AES-GCM encrypted. Allow up to ~100 s on a cold relay. |
| `unpair()` | Stops all triggers, deletes the key and credential. The relay keeps the device record until the owner revokes it. |
| `setEnabled(Boolean)` | Kill switch ("Disable remote access"): off = nothing is pulled, applied, published or polled; periodic work and alarms are cancelled. Credentials stay so it can be re-enabled. |
| `settings: StateFlow<RelaySettings>` + setters | `setConfigPolicy(Auto \| AlwaysConfirm \| Deny)` (Auto: ordinary changes apply, sensitive/destructive always ask), `setSessionPolicy(Allow \| Deny)`, `setAllowMedication`, `setUseCompanionActivity`, `setFrequentCheck(enabled, minutes)`, `setPushWake` + `pushAvailable`. Held on the phone only. |
| `status: StateFlow<RelayStatus>` | `paired`, `lastSyncAtMs`, `lastResult` (`Ok / Disabled / NotPaired / Offline / Unauthorized / Failed`), `lastError`, `lastPublishedVersion`, `companion` (e.g. `active (fresh)`, `unknown (stale)`, `ignored: bad signature`). `Unauthorized` = the relay revoked this phone: show "re-pair". |
| `syncNow()`, `syncNowAwait()` | Pull + apply + ack + publish + companion check. |
| `pending: StateFlow<List<PendingRemote>>` | Remote commands waiting for the owner: `commandId`, `clientLabel` (untrusted text), `kind` (`ConfigChange`, `Undo`, `RoutineStart`), `sensitivity`, `lines` (unredacted diff lines, owner is the audience), `expiresAtMs`, `needsVisibleStart`, `routineId`. |
| `confirm(activityContext, commandId)` / `decline(commandId)` -> `DecisionResult` | Call `confirm` from a visible Activity: a routine start begins playback through `RoutinePlaybackService` (Android 17 needs a user action for audio). `Failed` = could not start from this screen; the command stays pending. A change that became stale (version moved on) is rejected with a conflict and reported to the relay. |
| `createCompanionCode()` | 10-minute code for pairing the Windows companion. |
| `facade.audit(limit)` | Every remote command has `remote.<type>.<outcome>` rows with actor `mcp:<client label>` (plus the engine's own `config.apply` row for applied changes). |

**UI needs (UI engineer):** (1) a Remote access settings screen over the members above; (2) a confirmation screen showing
`pending` (diff lines, who asked, sensitivity, expiry) with Approve / Decline; notification taps open
`daycue://open/remote?item=<commandId>` (target `remote`, not yet routed in `DeepLinks.targetFor`; the UI should handle the
target in `MainActivity`); (3) strings: `dc_remote_*` exist in `strings_engine.xml` (en + iw) for the notifications only.

Behavior facts (all **unit** unless noted): command ids are applied at most once (`command_log` row written before any
effect, redelivery is ignored, an interrupted command is acked `failed`); expired commands (phone clock) are rejected, never
applied; `baseVersion` mismatch is `rejected` with `conflict.currentVersion`; sensitivity comes from the domain `preview`
(`destructive`/`sensitive` always wait for the owner; medication edits additionally need the grant's `medication` scope and
the local `allowMedication`); undo is only valid while `targetVersion` is the current version and the previous document is the
one before it; acks are signed exactly as RELAY.md section 4.3 and an unsent ack is retried at the next sync; the snapshot is
republished after every applied change (local or remote) and on app start; coordinates, medication (unless the relay wants it
**and** the owner allows it) and sensitive audit summaries are redacted on the phone. Session control: `work_session` `start`/`stop`
map to `StartSession`/`EndSession`; `pause`/`resume` are **rejected** (sessions pause from companion activity only); routine
`pause`/`resume`/`stop` map to `RoutineControl`; routine `start` is always `awaiting_confirmation` (needs a visible tap).
Signature/format interop with the real relay: **emu** (Keystore DER signatures verified by the relay) and **unit** (real relay
child process with a software key). Real FCM, real Windows companion signals, real phone hardware, Render: **unverified**.

Triggers: app open, after every config change (debounced 1.5 s), WorkManager periodic (15 min minimum, best effort), opt-in
frequent check (inexact alarm chain every 3-30 min only at a work/study place inside permitted hours and days; Doze can
stretch it to 9+ minutes), FCM wake via `PushProvider` (no-op by default; `fcm-template/`).

## 11. Alarm music (Spotify) — `facade.alarmMusic`

Code: `integrations/spotify/**`; the ringing service calls `AlarmMusicPlayer.startAsync` (added to the existing interface;
the synchronous `start` path is unchanged). Contract: the **local tone rings from the first moment** and is silenced only
after `SpotifyPlayback` confirmed playback from player state (requested URI as track or context, not paused, position
advancing between two samples). If playback fails or stops later the tone is (back) on at full volume and the engine gets
`AlarmControl(SpotifyFellBack)`.

| Member | Notes |
|---|---|
| `alarmMusic: StateFlow<AlarmMusicState>` | `Idle`, `Connecting`, `Playing`, `FellBack(failure, recovery, stoppedAfterPlaying)`. |
| `SpotifyFailure` | `NotInstalled`, `NotAuthorized` (not signed in / not authorized / expired), `NoNetwork`, `RemoteUnavailable` (connect failed or dropped: Spotify not running or not startable from the background), `AccountRestriction`, `Timeout` (default 10 s = `MorningAlarm.spotifyStartTimeoutSec`), `SdkNotBundled`, `Unknown`. Strings `dc_spotify_fail_*` / `dc_spotify_stopped` (en + iw). |
| `RecoveryAction` | `InstallSpotify`, `AuthorizeSpotify`, `CheckNetwork`, `OpenSpotify`, `CheckAccount`, `Retry`, `None`. |
| `alarmMusicRecoveryIntent(action)` | System intent to start from the alarm screen (Play Store page, Spotify launch, network settings). |
| `retryAlarmMusic(interactive)` | "Try again" / "Authorize" from the visible alarm screen; `interactive = true` lets the SDK show its auth view. |

Verification: failure reasons, confirmation rule, watcher and fallback callbacks **unit** (fake remote); the AAR-based adapter
compiles (**build**); live playback, locked/sleeping phone, auth expiry, Premium rules: **unverified** (needs the owner's
account and a physical phone; list in docs/setup/SPOTIFY.md). Service-side tone handling (silence on confirm, resume on loss)
is code-reviewed only; not exercised on an emulator because it needs a Spotify app.
