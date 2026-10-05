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
| `setPlace(placeId?, duration = UntilTransition)`, `clearPlaceOverride()` | CTX-1 "I'm at <place>" / "Not at a saved place" (null). Durations as `setEnvironment` (cap 8 h); Today shows the place with source `Manual`. **unit** |
| `medicationTaken(slot, takenAt?)` | MED-2 for today's slot; `takenAt` = "I took it at" (null = now; clamped to `[dueAt - 24 h, now]`). **unit** |
| `correctDose(slot, DoseCorrection.Taken(at) | Skipped | Undo)` | MED-5 history correction (today and the previous day). Undo = not confirmed again, never re-cued (no implied advice). History row `Corrected` (`from`, `to`, `takenAt`). **unit** |
| `setAppLanguage(tag?, fromOnboarding = false)`, `appLanguageTag()` | **The one language call** (D8): `settings.language` (notifications, speech) first, then the per-app locale (UI), serialized with the start-up / system reconcile and retried on a version conflict, in the app scope (leaving the screen cannot stop it half-way). `"he"` / `"en"` / null = follow the phone. `fromOnboarding = true` also re-localizes untouched first-run names (D10b). A language chosen in system settings reaches the config at the next start (and at once while running); a config language set by MCP or an import moves the UI. **unit** (policy) + **emu** |
| `spotify: StateFlow<SpotifyAvailability>`, `refreshSpotify()` | `available` (SDK bundled + client id), `enabled` (available + Spotify installed), `connection` (`Unavailable`, `NotInstalled`, `Idle`, `Connecting`, `Playing`, `FellBack`) and `lastFailure`. **unit** |
| `startRoutine(activityContext, routineId, replaceCurrent)` | **Call from a visible Activity.** Starts `RoutinePlaybackService` (`mediaPlayback` FGS), which dispatches the start. Required for audible speech on Android 17 (§6). |
| `testRoutine(activityContext, routineId, RoutineTestMode)` | RTN-8 test run, same path. |
| `testAlarm(alarmId)` | ALM-5. Note: a background-started FGS needs the exact-alarm exemption; call it from the foreground UI. |
| `sendTestReminder(type)` | Readiness "Send a test reminder": real notification labeled "Test" (domain `PreviewCue`). |
| `previewCueProfile(profileId)` | Cue profile "Preview": plays sound + vibration + phrase locally; posts nothing, records nothing. |
| `exportConfig(includeHistory = false)` | `ExportResult(json, containsMedication, includesHistory)`; `*.daycue-backup.json`. Show the medication warning when `containsMedication`. |
| `planImport(text)` | `(ImportParse, ImportPlan?)`. `ImportParse.NotDayCue(reason)` → "This file isn't a DayCue setup" (reasons include `unknown_format_version`); `UnsupportedSchema(schemaVersion, formatVersion?)` → "from a newer DayCue" (D4: `formatVersion` set when the backup wrapper is newer). `ImportPlan(ops, preview, baseVersion, containsMedication)`; `valid`, `noChanges`. Nothing is applied. |
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
| `item` (D11) | `med:<id>\|<date>\|<HH:mm>`, `cal:<key>`, `posture`, `routine-prompt:<id>` | "Why now?" from the delivered cue's `why` facts: dose = schedule ("Scheduled 08:00 · Every day", "Reminder 2 of 4"; no context sources, no advice), calendar = matched rule + lead, posture = mode ended + next, routine prompt = its trigger, bottle = Leaving now / place left / scheduled departure. The dose sheet shows the same dose lines. |
| `dose` | `med:<medId>\|<yyyy-mm-dd>\|<HH:mm>` (`ActionMapper.slot()` parses it) | Dose detail |
| `medication` | `med:merged`, `med:policy` | Today medication section |
| `routine` | `routine:<id>`, `routine-prompt:<id>` | Playback (pre-start / recovery state) |
| `posture` | `posture` | Posture live control |
| `calendar` | `cal:<eventKey>` | Calendar preview at that event |
| `context` | `session:<placeId>` | Today + context sheet |
| `alarm` | `alarm:<id>` | Ringing screen |
| `today`, `readiness` | — | |

Remote approvals (`remote` target) are not routed through `MainActivity`: see section 10 (`RemoteConfirmActivity`, non-exported).

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
- D7: while any DayCue Activity is visible the ringing service starts `AlarmActivity` itself (the system shows only a
  heads-up for a full-screen intent when the app is in front); otherwise the full-screen intent opens it. **emu**

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

`app.daycue.devtools.DevToolsReceiver` lives in the `debug` source set (class and manifest entry), so release APKs
contain neither (D2, checked with the merged release manifest and a dex listing). Sender must hold `DUMP` (adb shell).
Output in logcat tag `DayCueDebug`. A device that ran an older release build may have it explicitly disabled: reinstall
the debug build (`adb uninstall app.daycue`).

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
- Geofence, activity-recognition and calendar producers: sections 12 and 13 (FEASIBILITY.md §5-§6 for what is verified).

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
| `unpair(): Boolean` | Calls `DELETE /v1/phone/self` best effort (10 s limit, so the credential stops working at once), then always stops all triggers, deletes the key and credential and forgets grants. `true` = the relay confirmed; `false` (offline) = the credential stays valid on the relay until the owner revokes the device, so tell the owner to do that. |
| `setEnabled(Boolean)` | Kill switch ("Disable remote access"): off = nothing is pulled, applied, published or polled; periodic work and alarms are cancelled. Credentials stay so it can be re-enabled. |
| `settings: StateFlow<RelaySettings>` + setters | `setConfigPolicy(Auto \| AlwaysConfirm \| Deny)` (Auto: ordinary changes apply, sensitive/destructive always ask), `setSessionPolicy(Allow \| Deny)`, `setAllowMedication`, `setUseCompanionActivity`, `setFrequentCheck(enabled, minutes)`, `setPushWake` + `pushAvailable`. Held on the phone only. |
| `status: StateFlow<RelayStatus>` | `paired`, `lastSyncAtMs`, `lastResult` (`Ok / Disabled / NotPaired / Offline / Unauthorized / Failed`), `lastError`, `lastPublishedVersion`, `companion` (e.g. `active (fresh)`, `unknown (stale)`, `paused (companion went away)`, `ignored: bad signature`), `unpairedByRelay`. `Unauthorized` / `unpairedByRelay` = the relay revoked this phone: a notification was posted once (`dc_remote_revoked_*`) and background polling stopped; show "This phone was unpaired from the relay" with a Pair again action. `unpairedByRelay: Flow<Boolean>` is the same flag. |
| `grants: StateFlow<List<RemoteGrant>>`, `pendingGrants: Flow<List<RemoteGrant>>`, `refreshGrants()` | The client connections the relay knows (RELAY.md 4.6; Claude.ai, ChatGPT, Claude Code), persisted across runs. `RemoteGrant`: `id`, `label` (client-supplied, **unverified**: show it as such), `kind` (`oauth` / `token`), `scopes`, `activeScopes`, `approval` (`Pending`, `Approved`, `NotRequired`), `createdAtMs`, `lastUsedAtMs`; helpers `awaitsApproval`, `holdsMedication`, `canWrite`, `gatedScopes`. A connection that holds `config:write`, `sessions:control` or `medication` is `Pending` (those scopes inactive) until approved here. A notification (`dc_remote_grant_notif_*`) is posted once per new pending connection. |
| `approveGrant(id, approvedScopes? = null)`, `declineGrant(id)`, `revokeGrant(id)` -> `GrantDecisionResult` | Signed with the Keystore device key (`daycue.grant.v1`), so a stolen device token alone cannot approve. `approvedScopes` narrows (never widens). Results: `Done`, `NotPaired`, `NotFound`, `Rejected`, `Offline`, `Unauthorized`, `Failed`. Call **only after a deliberate gesture** (the confirmation Activity uses press-and-hold); a biometric prompt for connections with `holdsMedication` is recommended (no BiometricPrompt dependency is added in `:app`; the UI engineer owns that). Offer Revoke for every active connection. |
| `confirmationIntent(context, commandId?, grantId?)` | Explicit intent for `RemoteConfirmActivity`, the **non-exported** screen that lists pending changes and connections (see below). |
| `companions: StateFlow<List<PairedCompanion>>`, `refreshCompanions(): CompanionListResult`, `revokeCompanion(id): CompanionRevokeResult` | Paired Windows companions from `GET /v1/phone/activity` (`id`, `label` unverified, `fingerprint` = first 10 base32 chars of SHA-256 of the key, RELAY.md 4.4.1). Revoke calls `DELETE /v1/phone/companions/:id`, **which the relay does not implement yet** (only the owner-secret `DELETE /v1/owner/devices/:id` and the companion's own `DELETE /v1/companion/self` exist): until the integrations owner adds it the result is `NotSupportedByRelay` - tell the owner to unpair on the PC. Other results `Done`, `NotPaired`, `NotFound`, `Offline`, `Unauthorized`, `Failed`. **unit** |
| `syncNow()`, `syncNowAwait()` | Pull + apply + ack + publish + companion check. |
| `pending: StateFlow<List<PendingRemote>>` | Remote commands waiting for the owner: `commandId`, `clientLabel` (untrusted text), `kind` (`ConfigChange`, `Undo`, `RoutineStart`), `sensitivity`, `lines` (unredacted diff lines, owner is the audience), `expiresAtMs`, `needsVisibleStart`, `routineId`. |
| `confirm(activityContext, commandId)` / `decline(commandId)` -> `DecisionResult` | Call `confirm` from a visible Activity: a routine start begins playback through `RoutinePlaybackService` (Android 17 needs a user action for audio). `Failed` = could not start from this screen; the command stays pending. A change that became stale (version moved on) is rejected with a conflict and reported to the relay. |
| `createCompanionCode()` | 10-minute code for pairing the Windows companion. |
| `facade.audit(limit)` | Every remote command has `remote.<type>.<outcome>` rows with actor `mcp:<client label>` (plus the engine's own `config.apply` row for applied changes). |

**Approvals (security review L-13, M-7).** Approving a remote change or a connection happens in exactly one place:
`integrations/relay/RemoteConfirmActivity`, declared `android:exported="false"`. Notifications open it through explicit,
immutable PendingIntents (the `remote` target is **not** routed to `MainActivity`, and no `daycue://` URI can approve
anything); intent extras only choose which item is shown first. The notification action can only **decline**. The Activity
lists `pending` and the pending connections, shows the owner's on-phone (unredacted) lines, and approves only after a
**press-and-hold** (a tap just explains; the long press is also exposed as a TalkBack action), with
`filterTouchesWhenObscured` and `setHideOverlayWindows(true)` (`HIDE_OVERLAY_WINDOWS` permission). It is a plain Material3
screen so the feature works now; **UI needs (UI engineer):** (1) restyle or replace its content in the app design system
(keep the manifest entry, `RemoteConfirmActivity.intent(...)`, the hold gesture and the window flags); (2) a Remote access
settings screen over the members above, including the connections list (label shown as unverified, scopes in plain words,
last used, Revoke), Pair again when `unpairedByRelay`, and entry points that start `facade.remote.confirmationIntent(...)`;
(3) a biometric/device-credential step for `holdsMedication` connections before `approveGrant`; (4) strings: `dc_remote_*`
exist in `strings_engine.xml` (en + iw) for the notifications and this Activity.

Behavior facts (all **unit** unless noted): command ids are applied at most once (`command_log` row written before any
effect, redelivery is ignored, an interrupted command is acked `failed`); expired commands (phone clock) are rejected, never
applied; `baseVersion` mismatch is `rejected` with `conflict.currentVersion`; sensitivity comes from the domain `ConfigSensitivity`
(DOMAIN.md section 5: `destructive`/`sensitive` always wait for the owner, which now includes changing or disabling or skipping an
existing alarm, pause-all, quiet hours, speech/collision/global settings, context and session rules, relocating a place,
silencing cue profiles and the whole calendar policy; adding things and re-enabling stay ordinary; `ConfigPolicy.Auto` is still
the default); medication edits additionally need the grant's `medication` scope and the local `allowMedication`; undo is only valid while `targetVersion` is the current version and the previous document is the
one before it; acks are signed with **ack v2** (`signatureVersion: 2`, the signature also covers `sha256(canonicalJson(result))`, RELAY.md 4.6) and an unsent ack is retried at the next sync; the command `payloadHash` is recomputed on the phone and a mismatch is rejected (`payload_hash_mismatch`) instead of applied; op lists are decoded with `ConfigOpCodec.decodeList` (an unknown op or nested type rejects the whole list with `unsupported_op` / `unsupported_type` / `bad_op` / `bad_ops` / `too_many`, never a partial apply); the snapshot is
republished after every applied change (local or remote) and on app start; **everything that leaves the phone is built with `ConfigEditor.previewForRemote`** (DOMAIN.md section 6; the on-phone `preview` is used only for the owner's confirmation screen and the needs-confirmation decision): preview diffs, apply and undo summaries (`"Undo of version N: "` prefix), rejection errors use `RemoteRedaction(allowMedication = grant has medication && owner allows)`; audit rows (the engine's `config.*` rows too), the "applied" notice, `status.recentChanges` and `nextCues` use `RemoteRedaction.Strict`, medication-related entries carry `"medication": true` (and no time or text without the owner's allowance), and coordinates never appear anywhere (test: no digit of a synthetic place in any outbound payload across preview/apply/confirm/undo). Calendar override keys are published as short hashes and medication cue profiles only with the medication allowance. Retention: finished, acknowledged `command_log` rows are deleted after 30 days and `audit_log` rows after 400 days (`AppContainer.housekeeping`). Session control: `work_session` `start`/`stop`
map to `StartSession`/`EndSession`; `pause`/`resume` are **rejected** (sessions pause from companion activity only); routine
`pause`/`resume`/`stop` map to `RoutineControl`; routine `start` is always `awaiting_confirmation` (needs a visible tap).
Signature/format interop with the real relay: **emu** (Keystore DER signatures verified by the relay) and **unit** (real relay
child process with a software key). Real FCM, real Windows companion signals, real phone hardware, Render: **unverified**.

Triggers: app open, after every config change (debounced 1.5 s), WorkManager periodic (15 min minimum, best effort), opt-in
frequent check (inexact alarm chain every 3-30 min only at a work/study place inside permitted hours and days; Doze can
stretch it to 9+ minutes), FCM wake via `PushProvider` (no-op by default; `fcm-template/`).

### 10.1 Platform hardening (security review, Android side)

- **Backup and device transfer.** `allowBackup="false"` plus `res/xml/data_extraction_rules.xml` (cloud backup **and**
  device-to-device transfer, API 31+) and `res/xml/backup_rules.xml` (`fullBackupContent`, API 26-30) exclude every domain:
  the Room database (medication history, config with place coordinates, command and audit logs), relay credentials,
  remote-access settings, grants and the locked-boot snapshot. **Decision:** the supported way to back up or move a setup is
  the in-app export/import (`*.daycue-backup.json`, owner-controlled, history optional); a new phone is paired again.
  Not verified on a device (no device-to-device transfer was run).
- **Logging.** `proguard-rules.pro` strips `Log.v/d/i` in release (R8 `-assumenosideeffects`); `Log.w/e` stay and carry
  exception types, not config. Item keys, relay sync reasons and geofence notes therefore appear in debug builds only.
- **PendingIntents.** All are `FLAG_IMMUTABLE` and explicit (component set) except the two Play services targets
  (`GeofenceBroadcastReceiver`, `MotionTransitionReceiver`): Play services must add event extras, so they are
  `FLAG_MUTABLE` and explicit, and both receivers are `exported="false"`. Notification action identity includes the cue
  id (`CueActionReceiver.identitySegments`), so two live notifications for one item never share a PendingIntent.
- **Launcher icon.** `android:icon` / `android:roundIcon` (`@mipmap/ic_launcher`, `ic_launcher_round`) are on the main
  manifest `<application>`.
- **Companion keys (review L-4)** are still taken from the relay's list without pinning (needs UI + a fingerprint check
  on both devices, RELAY.md 4.4.1). A paused/gone companion marker (`ttlSeconds <= 30`) is fed as `CompanionGone`.

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

## 12. Places and location — `facade.places`

Code: `integrations/location/**`. Geofences and activity transitions follow the config and permissions automatically
(`AppContainer.startIntegrations()` at process start; boot / package / location-switch receivers; app open). The UI never
registers anything itself. Behaviour and limits: FEASIBILITY.md §5.

| Member | Notes |
|---|---|
| `places: Flow<List<Place>>` | From config. |
| `upsertPlace(place, baseVersion?)`, `setPlaceLocation(id, center, radiusM?)`, `deletePlace(id)` | `ConfigOp.UpsertPlace` / `SetPlaceLocation` / `DeletePlace` through `apply` (same validation, undo, audit). `center = null` = inactive (no geofence). |
| `currentLocation(): CurrentLocationResult` | "Use current location": `Ok(fix(lat, lng, accuracyM, at), precise)` / `NoPermission` / `PlayServicesMissing` / `LocationOff` / `Unavailable` / `Failed(reason)`. One fix, no updates. Show accuracy; with `precise = false` say the fix is too coarse for a place. Coordinates are personal data: keep them out of logs/screenshots. |
| `access: StateFlow<LocationAccessState>` | `foreground` (`None/Approximate/Precise`), `background`, `locationEnabled`, `playServices`, `activityRecognition`; derived `mode` (`Automatic/Paused/Off`), `nextStep`, `degradations` (`NoPlayServices`, `NoLocationPermission`, `ApproximateOnly`, `ForegroundOnly`, `LocationServicesOff`, `NoActivityRecognition` — exact consequences in the KDoc and FEASIBILITY §5.2). |
| `nextPermissionStep(): LocationPermissionStep` | `Foreground` -> `Precise` -> `Background` -> `Done`; `.permissions` is the array for `RequestMultiplePermissions`. Never request background together with foreground (Android rejects it). |
| `backgroundOptionLabel()` | Localized "Allow all the time" (API 30+) for the explanation before the background step. |
| `activityRecognitionPermissions()` | `ACTIVITY_RECOGNITION` on API 29+ (optional, CTX-6 on-foot Outdoor). |
| `onPermissionsChanged()` | Call after every location / activity-recognition permission result (forces re-registration). `refreshAccess()` from `onResume`. |
| `geofenceStatus: StateFlow<GeofenceStatus?>` | `mode`, `registered`, `detail` (`ok`, `unchanged`, `no_places`, `no_permission`, `approximate_only`, `no_background`, `location_off`, `no_play_services`, `gms_error:<code>`), `skippedPlaceIds` (> 100 places). |
| `fixIntent(LocationFix)` | `AppSettings`, `LocationSettings`, `PlayServices`. |

Leaving now: `facade.leavingNow()` (BTL-1, unchanged) is the dependable departure path; the geofence exit is a fallback.

## 13. Calendar — `facade.calendar`

Code: `integrations/calendar/**`. Read-only Calendar Provider (ADR-0004); `READ_CALENDAR` only; never writes.

| Member | Notes |
|---|---|
| `permission`, `hasPermission()`, `onPermissionChanged()` | Request `READ_CALENDAR` in context (when the user opens Calendar setup), then call `onPermissionChanged()`. |
| `listCalendars(): List<CalendarChoice>` | Every provider calendar (`calendar: DeviceCalendar(id, displayName, accountName, accountType, ownerAccount, color, visible, syncEvents, accessLevel, isPrimary)`) + `selected`, `mode`, `leadsMin` from config. Empty without permission. Calendars whose sync is off on the phone are not listed (they don't exist on the device). |
| `selectCalendar(id, mode = Rules, leadsMin = [10])`, `deselectCalendar(id)`, `neverForCalendar(calendarId)` | `SetCalendarPreference` / `RemoveCalendarPreference`. A sync follows automatically. |
| `preview(): Flow<List<CalendarPreviewRow>>` | CAL-1: `event` (domain `CalendarEvent`; `title` is untrusted text: display only, respect `showTitlesOnLockScreen`), `decision` (`CalendarRules.decide`: step 0-5, `ruleId`, `reason`, `matched`, `leadsMin`), `nextCueAt`, `calendarName` (after `listCalendars()`), `why` (localized "why matched", `dc_calmatch_*`), current `instanceOverride` / `seriesOverride`. |
| `always(row, scope = Instance, leadsMin?)`, `never(row, scope)`, `clearOverride(row, scope)` | One-tap corrections via `SetEventOverride`; `Series` uses `event.seriesId` (falls back to the instance when the event isn't recurring). |
| `refresh(): CalendarSyncResult`, `lastSync: StateFlow<CalendarSyncResult?>` | `status` (`Ok`, `NoPermission`, `NothingSelected`, `Failed`), `events`, `added/changed/removed`, `duplicatesDropped`, `error`. |
| `whyText(config, decision, lang)` | Same wording as `why`, for other screens. |

Sync triggers (no UI action needed): WorkManager periodic 30 min, content-URI trigger 5-60 s after a provider change,
`ContentObserver` while the process lives, app open (throttled 60 s), selection/horizon change.

## 14. Context readiness — `facade.contextReadiness`

`refreshContextReadiness(): ContextReadinessReport` (call from the readiness screen's `onResume` next to
`refreshReadiness()`). Rows (`ContextReadinessId`): `PlaceDetection` (detail `mode=..;registered=..;detail=..`),
`PlayServices`, `PreciseLocation`, `BackgroundLocation`, `LocationServices`, `ActivityRecognition`, `CalendarAccess`,
`CalendarSync` (Limited when the last sync is older than `maxCacheAgeHours` — the engine then gives no calendar cues).
Status reuses `ReadinessStatus` (`NotNeeded` when there are no active places / no selected calendars). `fix`
(`ContextFix`) says what the button does: `Request*` = the UI runs the runtime-permission request (`nextPermissionStep()`
for location), `LocationSettings` / `PlayServices` / `AppSettings` = `places.fixIntent(...)`, `SyncNow` =
`calendar.refresh()`. These rows are separate from `ReadinessReport` (optional integrations never count as problems for
core reminders).
