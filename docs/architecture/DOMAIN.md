# `:domain` — rules engine, config, edits

Owner: scheduling engineer. Pure Kotlin/JVM (`android/domain`, package `app.daycue.domain`), no Android imports.
Behaviour contract: `docs/PRODUCT.md` (rule IDs cited in code and test names). Verification: JVM unit tests only
(`.\gradlew.bat :domain:test`); nothing here is emulator- or device-verified.

## 1. Public API for `:app`

| Area | Entry points |
|---|---|
| Time | `Clock` (`now`, `zone`, `elapsedRealtime`), `FixedClock`. `time.TimeMath` (DST-explicit `resolveLocal`, windows, day boundary). |
| Config | `config.DayCueConfig` (+ all item types), `config.Defaults.config(language)` (PRODUCT §12 first-run doc, medication list empty), `config.ConfigCodec.encode/decode`, `config.DayCueJson` (the one `Json` instance for config, state, ops). |
| Edits | `edit.ConfigOp` (sealed, serializable), `edit.ConfigEditor.applyOps(config, ops, baseVersion): ApplyResult` (`Applied(config, previous)` / `Invalid(errors: path+code+message)` / `Conflict`), `ConfigEditor.preview(config, ops, redactMedicationLabels)` → `Preview(lines, sensitivity, errors)` (**on-phone only**, raw values), `edit.ConfigValidator.validate`, `edit.ConfigHistory` (bounded undo; undo yields a *new* version). |
| Remote | `ConfigEditor.previewForRemote(config, ops, RemoteRedaction(allowMedication))` → `RemotePreview(lines, sensitivity, errors)` + `summary(maxChars, prefix)` — the only diff/summary that may leave the phone (§6). `ConfigEditor.sensitivity(config, ops)`, `edit.ConfigSensitivity.of(config, op)` (§5). `edit.ConfigOpCodec.decodeList(json)` → `Decoded(ops, errors)` for op lists from outside the process (§4). |
| Engine | `engine.Engine.reduce(config, state, event, clock): Reduction(state, effects)`; `engine.EngineState` (`encode`/`decode`), `Event`, `Effect`, `Cue`. |
| Signals | `signal.GeofenceTransition`, `GeofenceSnapshot` (result of (re)registration or a location-fix check: inside set; empty = Elsewhere), `LocationAvailability`, `MotionActivity(kind, observedAt, expiresAt, transition = Sample\|Enter\|Exit)`, `MotionAvailability`, `CompanionActivity`, `CompanionGone` (explicit retraction). Each has `observedAt`/`expiresAt`; stale-on-arrival signals are dropped. |
| Queries | `query.Queries.todayView(config, state, clock)` → context (value/confidence/source/since per dimension), active items, next cues with `at` or `WaitingReason`, today's doses with status words; `Queries.calendarPreview` (CAL-1: decision + deciding step/rule per event); `engine.CalendarRules.decide` ("why matched"). |

### Events (all processed at `clock.now()`)
Lifecycle `Tick`, `BootCompleted` (send on boot **and** on every process start), `TimeChanged`, `TimezoneChanged`, `ConfigChanged`,
`NotificationsObserved(shown)` (the notification keys the system really shows; send after `BootCompleted` on every process start).
Context `SignalObserved`, `OverrideEnvironment(value, OverrideDuration)`, `ClearEnvironmentOverride`, `OverridePlace(placeId?, OverrideDuration)`
(null = "not at a saved place" = Elsewhere), `ClearPlaceOverride`, `StartSession`, `EndSession`,
`SessionPromptAnswer(Start|NotNow|NotWorking)`, `PauseAutoDetection`, `ResumeAutoDetection`, `LeavingNow`.
Items `HabitAck`, `HabitSnooze`, `Pause(target, choice)`, `Resume(target)`, `BottleAck`, `MedicationTaken(SlotRef, cueId?, takenAt?)`,
`MedicationSnooze/Skip(SlotRef)`, `MedicationCorrect(SlotRef, DoseCorrection.Taken(at)|Skipped|Undo)`,
`PostureControl(PostureAction)`, `RoutineControl(RoutineAction)`, `AlarmControl(Stop|Snooze|Test|SpotifyFellBack)`, `CalendarSynced(events, syncedAt)`,
`CalendarAck`, `CalendarSnooze`. Delivery feedback `CueDismissed`, `SpeechFinished`, `SpeechFailed`, `PreviewCue`.
Notification actions pass the `Cue.id` as `cueId`; a stale/duplicate `cueId` is ignored (idempotent re-delivery).

### Effects (execute in order)
`ScheduleWake(at, precision: AlarmClock|Exact|Inexact, reason)` / `CancelWake` (emitted only when the single next wake changes),
`Deliver(Cue)`, `DismissCue(notificationKey)`, `Speak` (deferred speech after an alarm, COL-4), `StartAlarm` (source incl. Spotify item
+ fallback tone, timeout, ramp), `StopAlarm`, `RecordHistory(HistoryEntry)` (instant, itemKey, kind, rule ID, test flag),
`ApplyConfigOps(ops)` (engine asks the app to run ops through `applyOps`, then send `ConfigChanged`; used for Pause/Resume).

`Cue` fully describes a delivery: `notificationKey` (stable per item → re-alerts update, never stack), channel id, priority, `title`/`body`
as `Text(key, args)` (user text travels as args: `phrase`, `label`, `title`, `name`), `actions`, lock-screen visibility + `publicTitle`,
`soundId`/`vibrationId` (from the cue profile; only the group lead), `silent`, `speech` (`SpeechRequest`: language, lead text, "also" list,
`moreCount`, over-media/output policy, `dropAfter`), `groupKey`/`groupLead`, `repeatIndex`, `isTest`, `ongoing`, `why` (rule + facts).
Key families: `cue.<type>.title`, `cue.habit.body`, `cue.medication.{title,body,generic.title,merged.*,policy.*}`, `cue.calendar.*`,
`cue.routine.{step,nudge,recover,prompt}.*`, `cue.posture.{switch,keep_going}.title`, `cue.session.{started,suggest}.*`,
`cue.alarm.missed_boot.*`, `cue.test.title`, `action.*`, `short.*`, `speech.*`, `why.*`.

## 2. Invariants (tested)

- `reduce` is pure and deterministic; after every reduce `nextWakeAt` is null or strictly after `now` (asserted on every step of every test, plus a random-stream property test).
- Replaying `BootCompleted` on an up-to-date state is a no-op (same state, no deliver/dismiss/wake effects). A real reboot is detected only when `elapsedRealtime` went backwards (on whichever event is reduced first).
- After a reboot or a `NotificationsObserved`, `delivery.visible` equals what was (re-)posted (`NotificationRecoveryTest`).
- `EngineState` and `DayCueConfig` JSON round-trips are lossless; process death between any two reduces doesn't change behaviour.
- Dismissal is never an ack (GEN-1, MED-2); medication is never held by context, quiet hours, pause, routines or collisions (MED-3); at most one current cue per item (GEN-2); missed occurrences are never replayed (GEN-6/7).
- Interval habits store UTC instants; fixed-clock items resolve via `Clock.zone()` + travel policy; DST gap → transition instant, overlap → first occurrence.
- Wake precision: alarms + medication `AlarmClock`; user-facing cues and dwell confirmations `Exact`; expiries/housekeeping `Inexact`. An Inexact wake within 5 min before a stronger one is dropped; an Inexact earliest wake is upgraded to Exact if a stronger one follows within 60 min.

## 3. Interpretations and deviations from PRODUCT.md

| Rule | What the engine does |
|---|---|
| §3.2 | Outdoor policies are generic over the item `condition`: `FirstReminderPolicy.OnConditionStart` (= `OnOutdoorStart`), `ReentryPolicy`, `LeaveConditionPolicy`. |
| SUN-8 | "Re-entry" applies when a due state was carried over from an earlier stretch (`heldDue`: became due / retracted / snoozed while not in condition, or `StayDue`); otherwise `firstReminder`. |
| HYD-3 | `dayStart` applies only to habits whose condition is "any" (no condition stretch). |
| GEN-6 vs §0 Pause | On pause end the item is delivered **once** if still due and conditions hold; nothing is replayed. |
| Pause | Pauses live in config (`pause` on habits/posture, `settings.pauseAll`); the `Pause`/`Resume` events return `ApplyConfigOps`. Global pause exempts medication, alarms, running routines and calendar cues (PRODUCT doesn't list exemptions; calendar kept on). |
| CTX-2 | Before the enter dwell Place keeps its previous value (the "Medium before dwell" row is not used); a confirmed geofence place is High. `Elsewhere` needs a confirmed exit or a `GeofenceSnapshot` (new signal). |
| CTX-6 / CTX-4 | On-foot "sustained 5 min" and the Outdoor enter dwell are not additive (dwell = max of both). After leaving a saved place the on-foot Outdoor run counts only from the place change, giving PRODUCT §3.4's "~12:40" exactly. |
| COL-1 | Grouping = cues due now plus *soft* cues (P5–P8, `pullable`) due within `mergeWindow`, pulled forward. Medication/alarm/routine/calendar are never pulled early. |
| COL-3 | A whole soft group is delayed to `lastAudibleAt + minAudibleGap`; groups containing P1–P2 are never delayed. |
| §8.5 | `NotificationOnly` and `VibrateOnly` both drop sound + speech and keep vibration (the app may make `VibrateOnly` non-heads-up). |
| MED-1/4 | At the day boundary a still-Due slot becomes "Not confirmed" (history row); its notification is left as is. |
| GEN-7 / D1 (2026-10-05) | Every delivered cue is kept in `delivery.visible` with its content (`VisibleCue.cue`). A real reboot (detected on **any** first event after boot: `elapsedRealtime` went backwards) treats every visible notification as gone: non-medication cues are re-posted **quietly** (same cue id and buttons; no sound, vibration, speech or full-screen; history `Reposted`), medication notifications are replaced by the MED-8 path, and the merged state is reset so MED-8 always runs. `NotificationsObserved(shown)` does the same quiet re-post for every visible key the system no longer shows (force stop, notification reset), medication included. A re-post superseded by a new delivery or a dismissal in the same reduce is dropped. Visible entries without stored content (older states) are dropped from the visible set. |
| MED-8 | Reboot: today's Due doses except those whose snooze is still running (§0: snooze survives reboot) → ≥2 one merged cue, 1 normal re-delivery. |
| MED-11 (owner decision 2026-10-05) | Clock jumps forward (reboot after a long power-off, `TimeChanged`, time zone change): doses of **earlier days** that were never cued (`cue == null`, not covered by a merged cue) and are due within the last **48 h** (`MedicationModule.CATCH_UP_HOURS`) join the same **one** merged cue as today's ("N medication reminders not confirmed", Private on the lock screen, action Open, no repeats, no advice, no "missed" wording). Even one such dose gets the merged form (never a per-dose "take it now" cue). They stay Not confirmed; older doses appear only in history. Taken from the merged notice goes through the dose screen (`MedicationTaken` for today, `MedicationCorrect` for earlier days). |
| MED-11 / D9 (2026-10-05) | A merged cue already recorded (e.g. from a reboot) never blocks recovery. On every recovery event the candidate set is: today's never-cued due doses, earlier-day never-cued due doses within 48 h, **plus** the still-Due doses of the current merged cue within 48 h. If that set differs from what the current merged cue shows, ONE merged cue replaces it (same notification key `med:merged`, new cue id; doses that fell out of 48 h are released from it); if it is the same set (e.g. a small clock correction) nothing is re-alerted. A merged cue whose doses are all resolved or dropped (older than two days) is dismissed. Test: `NotificationRecoveryTest` "D9 ...". |
| MED-2 / GEN-1 dismissal | Dose and merged medication cues are `ongoing = false`: the user can swipe them away; the slot stays `Due` and repeats continue (MED-4). Dismissal is never a confirmation. |
| MED-5 | `MedicationTaken.takenAt` (null = now) is clamped to `[dueAt - 24 h, now]`. `MedicationCorrect` works on tracked slots of today or earlier: `Taken(at)` (sets or changes the time), `Skipped`, `Undo` (Taken/Skipped → not confirmed; `Upcoming` again if its time is ahead, otherwise Due with `noCueReason = corrected`, i.e. never cued again: no implied advice, MED-10). History row `Corrected` with `from`, `to`, `previousTakenAt`, `takenAt`. |
| CTX-1 place | `OverridePlace` is a manual Place (High, source Manual) on top of the automatic track, which keeps running. Same duration kinds and cap as the environment override (`environmentOverrideCapMin`); `UntilTransition` ends at the next meaningful automatic place change; a deleted place ends it. It applies while detection is paused, drives the typical environment, session start/end ("left place") and the bottle's Leaving now / scheduled departure place. Unknown place ids are ignored (history row). Survives reboot. |
| POS-4 / POS-6 (2026-10-05) | POS-6 ("Resume after **freeze**") governs automatic interruptions only (POS-2 freezes: session pause/end, Inactive, quiet hours, meeting Freeze). A **manual** Pause keeps mode and remaining time for as long as it lasts (acceptance 3 "pauses and resumes at the correct position"); resuming while the cycle may not run becomes a freeze that starts at the resume. PRODUCT's `PostureInterruptionPolicy` text says "after a pause", which reads as the automatic case because the rule it implements, POS-6, is about freezes; open question 6 stays with the owner. Extend while Running counts from the planned end, or from **now** when that end is already past. |
| D8 / first run | `Defaults.config(FirstRunSeed(language, region, use24Hour, text))`: language from the app locale, `workDays` from the region, `settings.use24Hour` from the device, and template display names resolved once from `template.*` text keys in that language (built-in English fallback). `Defaults.config(language)` is unchanged. |
| Work days rule (D10c) | **The device REGION decides, independent of the UI language**: Fri-Sat weekend regions such as IL -> Sunday-Thursday; IR/AF -> Saturday-Wednesday; all others Monday-Friday. The per-app locale's region counts only when the device locale has none; with no region at all the language decides (he -> Sunday-Thursday, en -> Monday-Friday). Table: he-IL Sun-Thu, en-IL Sun-Thu, he-US Mon-Fri, en-US Mon-Fri, he (no region) Sun-Thu. The owner changes it in Settings > General > Work days. Code: `Defaults.workDaysFor(region, language)`, `LanguagePolicy.region`; tests `DefaultsRelocalizeTest`, `PlatformFixesTest` "D10c ...". |
| D10b onboarding language | `Defaults.relocalizeOps(config, target, known, from, to, region)`: when the user picks a language in onboarding, first-run names the user has not edited (equal to a known default in any language: habits, places, morning routine and its steps, morning alarm, calendar rules) are renamed to the chosen language through normal `ConfigOp`s; edited names are never touched. Work days follow the language only when the region is unknown and they still equal the old language's default. |
| MED-7/8 | Also applied on `TimeChanged`. TZ/time change: merged cue covers slots never delivered; reboot: all of today's Due slots. ≥2 slots → one merged cue (no repeats, action Open); 1 slot after reboot → normal re-delivery. Slots whose time had already passed when a medication was created/edited are not cued (`noCueReason = created_after_time`). |
| §6 | Quiet hours freeze the posture cycle (P7 "frozen"); `Activity = Inactive` freezes it for every `activeWhen`. `Skip` (in-app only, product decision): when a switch is pending → mode after the pending one; while running → next mode now. `Snooze` under `AtCue` reverts to the previous mode for the snooze time. Notification actions: Switched / Snooze / +5 min. |
| RTN-5/6 | Interruption = time since the engine last ran; a running routine keeps a 60 s Inexact heartbeat. A user pause longer than the threshold: `Resume` is taken as the answer (restart the step with full duration); policy `Cancel` cancels. |
| RTN-9 / §10.1 | Android 17: routine audio must start from a user action (ANDROID.md). Scheduled routines therefore always prompt ("Start / Skip today"), even with `AutoStart`. `followOnRoutine` starts on alarm **Stop** (a user action) unless the routine's `startMode` is explicitly `AskToStart`. |
| ALM-6 | Reboot with an alarm passed by ≤ 30 min: a P1 notification ("alarm time passed while the phone was off") instead of ringing, since the ringing FGS can't start from `BOOT_COMPLETED`. A late wake without reboot (≤ 30 min) rings normally. |
| BTL-4 | Cooldown applies to automatic triggers only; `Leaving now` is subject to dedup (BTL-3) only. Scheduled departures more than 30 min late are skipped. |
| BTL-2 snapshot | A departure is also detected when a `GeofenceSnapshot` no longer lists a place we were raw-inside (OS exit missed). It takes the same pending path as a raw exit (trigger `GeofenceExit`, rule BTL-2, BTL-3/4/5 apply). A place whose departure was already seen (raw exit recorded, not re-entered) never yields a second pending departure, so exit + snapshot in either order cue once. Stale-on-arrival signals are ignored here too. After boot `rawInside` is empty, so the first snapshot is never a departure. |
| CTX-6 continuous walk | `MotionActivity.transition`: `Enter` (activity-transition ENTER) makes an on-foot walk *ongoing*: it needs no further readings and lasts until a contrary signal (`Still`/`Other`, an on-foot `Exit`, `InVehicle`) or until `contextRules.onFootOngoingMaxMin` (default 180, range 30–720, never below `onFootHoldMin`) after the last supporting on-foot signal, whichever is first; then the usual exit dwell → Unknown ("stale becomes unknown"). A `Sample` refreshes an ongoing walk. `Still`/`Other`/`Exit` end it at that instant and the 45-min `onFootHoldMin` hold counts from there; `InVehicle` ends Outdoor at once. Plain samples without a transition keep the PRODUCT 45-min hold. |
| WRK-5 gone | `CompanionGone(observedAt)` retracts any companion report observed at or before it: Activity = Unknown immediately and an automatic Active session is `Suspended` at `observedAt` (not after `companionStaleToSuspendMin`). Older-than-last reports are ignored; a newer `CompanionActivity` starts a new track. Manual sessions are unaffected. |
| CAL text | A calendar cue whose deciding rule has no kind text (default policy, override, or blank kind) carries no `kind` argument. It uses the key variants `cue.calendar.title.event`, `cue.calendar.generic.title.event`, `speech.calendar.generic.event` and the argument `kindKey = "calendar.kind.event"` (`CalendarRules.UNMATCHED_KIND_KEY`) for templates that reference `{kind}` (the user's phrase template in the body). |
| WRK-2 | Answering "Start" to a suggestion creates an automatic (not manual) session. |
| CAL-4 | Meeting = in progress, busy, not excluded at step 0, and any enabled `isMeeting` rule matches (not only the deciding rule), or a step-1 `Always(asMeeting)`. `maxCacheAge` gates both calendar cues and Activity = Meeting. `tentative = Exclude` is a step-0 exclusion. |

## 4. Not done / open

- Forward tolerance covers unknown keys and unknown enum values. For op lists use `ConfigOpCodec.decodeList`: each op is decoded separately and an unknown op type becomes `ValidationError("ops[i]", "unsupported_op")`, an unknown nested polymorphic subtype (habit, pause, trigger, …) `unsupported_type`, anything else malformed `bad_op`; list-level `bad_ops`/`too_many`. It never throws. Callers must reject the whole list when `errors` is non-empty. Not covered: a whole **config document** (`ConfigCodec.decode`) or `EngineState`/`Event` JSON containing an unknown sealed subtype still throws (kotlinx closed polymorphism); this only happens on downgrade or a newer export, and import callers should catch it.
- History retention (`historyRetentionDays`), audit of history edits (MED-5) and the speech queue (SPK-1) are app/Room concerns; the domain only emits `RecordHistory` and `SpeechRequest.dropAfter`.
- `respectSystemDnd`, during-phone-call silence, headphones-only output and Spotify fallback are executed by the app from the fields provided.
- Calendar "Edit rule" / "Never for this calendar" corrections exist as ops (`UpsertCalendarRule`, `SetCalendarPreference`); there is no single "correction" helper.
- Not covered by tests: `AlarmAction.Test` (ALM-5) and `SpotifyFellBack` beyond compilation; `AssumeOutdoor` away policy; `KeepHomeTimezone` for routines.

## 5. Sensitivity of config ops (security review M-5, MED-9)

`sensitive` and `destructive` changes need on-phone confirmation when they come from a remote caller (the app's `ConfigPolicy`
may additionally confirm everything). `ConfigSensitivity.of(config, op)` classifies **every** `ConfigOp` variant explicitly (an
exhaustive `when` with no `else`: a new variant does not compile until classified). A list takes the maximum, each op classified
against the document it is applied to; an invalid list still reports the class of all its ops. `preview` / `previewForRemote`
additionally escalate: any removed id-keyed element → `destructive`, any medication line → at least `sensitive`.
Tested exhaustively in `SensitivityTableTest` (a sample per variant, coverage checked against the serializer's variant list).

| Op | Class | Why |
|---|---|---|
| `upsertHabit`, `setHabitEnabled` (true or false), `setHabitInterval`, `setHabitActiveHours` | ordinary | interval / hours / a single non-medication habit |
| `deleteHabit` | destructive | deletion |
| `setPause` target `habit` or `posture` (any spec, or resume) | ordinary | pausing a single non-medication item |
| `setPause` target `all` with a pause | sensitive | pauses all reminders |
| `setPause` target `all`, `pause = null` (resume) | ordinary | only restores alerting |
| `setPostureCycle`, `setPostureModes`, `setPostureEnabled` | ordinary | posture durations (removing a mode escalates to destructive) |
| `upsertMedication`, `setMedicationTimes`, `setMedicationTravelPolicy`, `setMedicationEndDate` | sensitive | medication schedule (MED-9) |
| `deleteMedication` | destructive | deletion |
| `upsertRoutine`, `duplicateRoutine`, `upsertRoutineStep`, `reorderRoutineSteps` | ordinary | routine / step edits (dropping steps escalates) |
| `deleteRoutine`, `deleteRoutineStep` | destructive | deletion |
| `upsertAlarm` of an **existing** alarm | sensitive | can disable, move or skip it |
| `upsertAlarm` of a new id | ordinary | only adds alerting |
| `setAlarmEnabled(false)` / `(true)` | sensitive / ordinary | disabling an alarm |
| `skipNextAlarm(date)` / `(null)` | sensitive / ordinary | skipping an alarm |
| `deleteAlarm` | destructive | deletion |
| `upsertPlace` of an existing place with a different `center` or `radiusM` | sensitive | relocates geofences |
| `upsertPlace` otherwise (new place, rename, flags) | ordinary | |
| `setPlaceLocation` | sensitive | relocates / clears a place |
| `deletePlace` | destructive | deletion |
| `upsertCueProfile` of type Medication or Alarm (before or after) | sensitive | medication-adjacent / alarm delivery |
| `upsertCueProfile` that turns off sound, vibration or speech, or changes sound, vibration or type | sensitive | can silence cues |
| `upsertCueProfile` otherwise (phrase, new non-critical profile) | ordinary | |
| `deleteCueProfile` | destructive | deletion |
| `setCalendarConfig` | sensitive | whole calendar policy incl. default policy, lock-screen and spoken titles |
| `upsertCalendarRule`, `reorderCalendarRules`, `setCalendarPreference`, `setEventOverride` | ordinary | calendar lead times / one-tap corrections |
| `deleteCalendarRule`, `removeCalendarPreference` | destructive | deletion |
| `setContextRules`, `setSessionRules` | sensitive | context-detection settings |
| `setQuietHours`, `setSpeechSettings`, `setCollisionSettings`, `setGlobalSettings` | sensitive | can silence or delay cues (global settings include quiet hours and pause-all) |
| `setLanguage` | ordinary | |

Remote-access settings (relay pairing, `ConfigPolicy`, `allowMedication`) are app settings, not `ConfigOp`s; the app must keep
them changeable on the phone only.

## 6. Remote redaction (security review H-1, M-8)

`ConfigEditor.previewForRemote(config, ops, RemoteRedaction(allowMedication))` is the only diff that may leave the phone
(relay `config.preview` results, `awaiting_confirmation` / applied summaries, undo summaries, audit rows). It redacts **values**:

- Place coordinates never appear, whatever the policy. Places are diffed with `center` replaced by `location: set|none`; a moved
  place adds `places[<id>].location: set -> moved`; a whole added/removed place is `"<name> (location set|removed)"` or
  `"<name> (no location)"`. Any JSON value is additionally stripped of `center`/`lat`/`lng`/`latitude`/`longitude` keys.
- Without `allowMedication` (the grant's `medication` scope **and** the owner's phone setting), medications and medication cue
  profiles are removed from both documents and replaced by one line `medications: details withheld -> changed (details withheld)`;
  validation errors under `medications` are generic.
- Sensitivity is computed from the unredacted change (equal to the on-phone `preview`).
- `RemotePreview.summary(maxChars, prefix)` builds confirmation / undo text from the already-redacted lines, so truncation cannot
  expose anything. For an undo, pass the restoring ops (e.g. the app's `ConfigDiff.ops(current, previous)`).

`RemotePreview` is a separate type from `Preview` so raw on-phone lines cannot be passed where redacted ones are expected.
Tested in `RemoteRedactionTest`: for every op variant (and the undo direction, and 400 random op sequences) no coordinate digit
fragment and — under `Strict` — no medication label, time, date or phrase appears in any line, error, text, summary or JSON.
