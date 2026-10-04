# `:domain` — rules engine, config, edits

Owner: scheduling engineer. Pure Kotlin/JVM (`android/domain`, package `app.daycue.domain`), no Android imports.
Behaviour contract: `docs/PRODUCT.md` (rule IDs cited in code and test names). Verification: JVM unit tests only
(`.\gradlew.bat :domain:test`); nothing here is emulator- or device-verified.

## 1. Public API for `:app`

| Area | Entry points |
|---|---|
| Time | `Clock` (`now`, `zone`, `elapsedRealtime`), `FixedClock`. `time.TimeMath` (DST-explicit `resolveLocal`, windows, day boundary). |
| Config | `config.DayCueConfig` (+ all item types), `config.Defaults.config(language)` (PRODUCT §12 first-run doc, medication list empty), `config.ConfigCodec.encode/decode`, `config.DayCueJson` (the one `Json` instance for config, state, ops). |
| Edits | `edit.ConfigOp` (sealed, serializable), `edit.ConfigEditor.applyOps(config, ops, baseVersion): ApplyResult` (`Applied(config, previous)` / `Invalid(errors: path+code+message)` / `Conflict`), `ConfigEditor.preview(config, ops, redactMedicationLabels)` → `Preview(lines, sensitivity, errors)`, `edit.ConfigValidator.validate`, `edit.ConfigHistory` (bounded undo; undo yields a *new* version). |
| Engine | `engine.Engine.reduce(config, state, event, clock): Reduction(state, effects)`; `engine.EngineState` (`encode`/`decode`), `Event`, `Effect`, `Cue`. |
| Signals | `signal.GeofenceTransition`, `GeofenceSnapshot` (result of (re)registration: inside set; empty = Elsewhere), `LocationAvailability`, `MotionActivity`, `MotionAvailability`, `CompanionActivity`. Each has `observedAt`/`expiresAt`; stale-on-arrival signals are dropped. |
| Queries | `query.Queries.todayView(config, state, clock)` → context (value/confidence/source/since per dimension), active items, next cues with `at` or `WaitingReason`, today's doses with status words; `Queries.calendarPreview` (CAL-1: decision + deciding step/rule per event); `engine.CalendarRules.decide` ("why matched"). |

### Events (all processed at `clock.now()`)
Lifecycle `Tick`, `BootCompleted` (send on boot **and** on every process start), `TimeChanged`, `TimezoneChanged`, `ConfigChanged`.
Context `SignalObserved`, `OverrideEnvironment(value, OverrideDuration)`, `ClearEnvironmentOverride`, `StartSession`, `EndSession`,
`SessionPromptAnswer(Start|NotNow|NotWorking)`, `PauseAutoDetection`, `ResumeAutoDetection`, `LeavingNow`.
Items `HabitAck`, `HabitSnooze`, `Pause(target, choice)`, `Resume(target)`, `BottleAck`, `MedicationTaken/Snooze/Skip(SlotRef)`,
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
- Replaying `BootCompleted` on an up-to-date state is a no-op (same state, no deliver/dismiss/wake effects). A real reboot is detected only when `elapsedRealtime` went backwards.
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
| MED-7/8 | Also applied on `TimeChanged`. TZ/time change: merged cue covers slots never delivered; reboot: all of today's Due slots. ≥2 slots → one merged cue (no repeats, action Open); 1 slot after reboot → normal re-delivery. Slots whose time had already passed when a medication was created/edited are not cued (`noCueReason = created_after_time`). |
| §6 | Quiet hours freeze the posture cycle (P7 "frozen"); `Activity = Inactive` freezes it for every `activeWhen`. `Skip` (in-app only, product decision): when a switch is pending → mode after the pending one; while running → next mode now. `Snooze` under `AtCue` reverts to the previous mode for the snooze time. Notification actions: Switched / Snooze / +5 min. |
| RTN-5/6 | Interruption = time since the engine last ran; a running routine keeps a 60 s Inexact heartbeat. A user pause longer than the threshold: `Resume` is taken as the answer (restart the step with full duration); policy `Cancel` cancels. |
| RTN-9 / §10.1 | Android 17: routine audio must start from a user action (ANDROID.md). Scheduled routines therefore always prompt ("Start / Skip today"), even with `AutoStart`. `followOnRoutine` starts on alarm **Stop** (a user action) unless the routine's `startMode` is explicitly `AskToStart`. |
| ALM-6 | Reboot with an alarm passed by ≤ 30 min: a P1 notification ("alarm time passed while the phone was off") instead of ringing, since the ringing FGS can't start from `BOOT_COMPLETED`. A late wake without reboot (≤ 30 min) rings normally. |
| BTL-4 | Cooldown applies to automatic triggers only; `Leaving now` is subject to dedup (BTL-3) only. Scheduled departures more than 30 min late are skipped. |
| WRK-2 | Answering "Start" to a suggestion creates an automatic (not manual) session. |
| CAL-4 | Meeting = in progress, busy, not excluded at step 0, and any enabled `isMeeting` rule matches (not only the deciding rule), or a step-1 `Always(asMeeting)`. `maxCacheAge` gates both calendar cues and Activity = Meeting. `tentative = Exclude` is a step-0 exclusion. |

## 4. Not done / open

- Forward tolerance covers unknown keys and unknown enum values; an unknown **sealed subtype** (e.g. a future `ConfigOp`) still fails to decode (kotlinx limitation). Callers should reject such commands as "unsupported".
- History retention (`historyRetentionDays`), audit of history edits (MED-5) and the speech queue (SPK-1) are app/Room concerns; the domain only emits `RecordHistory` and `SpeechRequest.dropAfter`.
- `respectSystemDnd`, during-phone-call silence, headphones-only output and Spotify fallback are executed by the app from the fields provided.
- Calendar "Edit rule" / "Never for this calendar" corrections exist as ops (`UpsertCalendarRule`, `SetCalendarPreference`); there is no single "correction" helper.
- Not covered by tests: `AlarmAction.Test` (ALM-5) and `SpotifyFellBack` beyond compilation; `AssumeOutdoor` away policy; `KeepHomeTimezone` for routines.
