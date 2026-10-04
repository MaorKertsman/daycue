# DayCue behavior spec

Status: v1 baseline by the product lead, 2026-10-04. This is the contract for **what** the engine does; `docs/ARCHITECTURE.md` says **how**. Rule IDs (`SUN-3`, `MED-7`, ...) are stable: tests and bugs should cite them. Every default below is reversible configuration, not a hard-coded constant.

Principles in force: configure once; the right cue at the right time; minimal interaction. Unknown beats wrong. Dismissal is never acknowledgement. Medication is never suppressed by context. No medical advice anywhere (no intake targets, no dosing or missed-dose guidance).

---

## 0. Shared definitions

| Term | Meaning |
|---|---|
| Cue | One user-facing delivery: notification and/or sound, vibration, speech. Has a type, priority and its own actions. |
| Ack | An explicit user action that completes a cue (`Applied`, `Drank`, `Taken`, `Done`, `Switched`, `Got it`). Only an ack moves completion state. |
| Unanswered | Cue delivered, repeats exhausted, no ack. Recorded in history as `unanswered`. Never treated as done. |
| Dismiss | Swipe/clear of a notification. Logged; changes no state other than hiding the notification. |
| Snooze | Re-deliver the same cue N minutes after the tap. Survives reboot. |
| Pause | Stop one item (or all non-exempt items) until a chosen instant. No cues, no catch-up on resume. |
| Day boundary | `settings.dayStartsAt`, default `04:00` local. "Today", "rest of today" and history days use it. |
| Active hours | Per-item local window in which the item may cue. Cues due outside it wait (rule GEN-6). |
| Workdays | `settings.workDays`, default from locale weekend (he-IL: Sun–Thu; otherwise Mon–Fri). |

### 0.1 Generic cue rules (apply to every type unless the type says otherwise)

| ID | Rule |
|---|---|
| GEN-1 | Posting, showing, timing out or dismissing a notification never acks it. |
| GEN-2 | Each item has at most **one** pending cue at a time. Re-evaluating context or re-entering a context never creates a second timer or a duplicate cue for the same item. |
| GEN-3 | Snooze re-delivers at `tapAt + snoozeMinutes`. If at that instant the item's conditions no longer hold, the item follows its own "conditions lost" rule instead (e.g. SUN-9). |
| GEN-4 | Repeat policy `RepeatPolicy(everyMin, maxRepeats)`: an unacked cue re-alerts every `everyMin` up to `maxRepeats` times, then becomes `unanswered`. A re-alert updates the same notification (no stacking). |
| GEN-5 | After `unanswered`, interval items follow `UnansweredPolicy` (§2). |
| GEN-6 | A cue due outside its active hours or while the item is paused is not delivered. At the next allowed instant it is delivered **once** if its conditions still hold; missed occurrences are never replayed one by one. |
| GEN-7 | On `BootCompleted` / `TimeChanged` the engine recomputes from persisted state. Anything that became due while the phone was off is delivered at most once per item, merged per §8.3. |
| GEN-8 | Every delivery, ack, snooze, pause, dismissal and unanswered transition is written to history with its instant and the cause (rule ID). |
| GEN-9 | Every cue notification has a "Why now?" detail listing the rule and context values that triggered it. |
| GEN-10 | Test actions (cue preview, routine test, alarm test) never change completion state or history counts; they are logged as `test`. |

---

## 1. Context inference

Three independent dimensions. Each has a value, a confidence (`High | Medium | Low`), a source and an `expiresAt`. Item conditions require at least `Medium` unless stated.

| Dimension | Values |
|---|---|
| Place | `Saved(placeId)` · `Elsewhere` (confidently outside every saved place) · `Unknown` |
| Environment | `Indoor` · `Outdoor` · `Unknown` |
| Activity | `Working` · `Studying` · `Meeting` · `RoutineRunning` · `Inactive` · `Unknown` |

Note: `Elsewhere` is added to the owner's list because "outside every geofence" is a known state, distinct from "no location data". It never implies `Outdoor` (CTX-6).

### 1.1 Signal sources

| Source | Feeds | Confidence | Default expiry (stale -> ignored) |
|---|---|---|---|
| Manual override | any dimension | High | per override duration (§1.4) |
| Geofence enter/exit/dwell (OS) | Place | High after dwell, Medium before | until a contrary event; `Unknown` immediately if location permission/service is off; after boot `Unknown` until the OS initial trigger |
| Saved place `typicalEnvironment` | Environment | Medium | while Place = that place |
| Activity recognition (optional, on-foot) | Environment | Medium | 10 min after last update |
| Windows companion (active/idle/locked/asleep) | Activity | Medium | `observedAt + 3 min` |
| Calendar busy event (meeting rule match, CAL-4) | Activity = Meeting | Medium | event end; cache older than 24 h is not used for Activity |
| Routine playback | Activity = RoutineRunning | High | while the run is active |

### 1.2 Saved places

| Field | Default | Range / options |
|---|---|---|
| name | — | 1–40 chars, user text |
| center, radius | unset (place inactive until set) | radius 50–1000 m, default 150 m |
| typicalEnvironment | Indoor | `Indoor / Outdoor / Mixed` (Mixed contributes nothing -> Unknown) |
| allowedRoutines / allowedActivities | per template (§12) | subset of habit/routine ids; `Working`, `Studying` |
| sessionStart | per template | `AutoStart / Suggest / Off` (§1.5) |
| defaultSessionKind | Working | `Working / Studying` |
| bottleReminderOnLeave | per template | bool |

### 1.3 Inference rules

| ID | Rule |
|---|---|
| CTX-1 | Precedence per dimension: Manual override > fresh automatic sources (table order) > `Unknown`. |
| CTX-2 | Place enter is confirmed after `placeEnterDwell` = **3 min** (1–15) inside. Before that, Place stays at its previous value. |
| CTX-3 | Place exit is confirmed after `placeExitDwell` = **5 min** (0–20) outside. Re-entry before confirmation cancels the exit (hysteresis). A confirmed place change is a **meaningful transition**. |
| CTX-4 | Environment `Outdoor` from an automatic source requires it to hold for `outdoorEnterDwell` = **5 min** (0–20). |
| CTX-5 | Environment leaves `Outdoor` only after a non-Outdoor value holds for `outdoorExitDwell` = **10 min** (0–60). Shorter indoor visits do not change Environment. |
| CTX-6 | `AwayEnvironmentPolicy` (when Place is `Elsewhere`/`Unknown`): `OutdoorWhenOnFoot` (default; walking/running/cycling sustained 5 min -> Outdoor/Medium, held up to 45 min after the last on-foot reading, then Unknown) · `Unknown` · `AssumeOutdoor` (Elsewhere -> Outdoor/Low; items needing Medium will not fire unless the owner lowers their threshold). Without activity-recognition permission `OutdoorWhenOnFoot` behaves as `Unknown`. In-vehicle is never Outdoor. |
| CTX-7 | Activity precedence: RoutineRunning > Meeting > Working/Studying (session active) > Inactive (fresh companion idle/locked/asleep and no session) > Unknown. |
| CTX-8 | Being at Home never implies Working; Place never implies Activity on its own. |
| CTX-9 | Low-value guesses are never confirmed with prompts. The only context prompt the engine may emit is the session suggestion (WRK-2), at most once per place per `suggestCooldown`. |
| CTX-10 | Context changes are evaluated immediately for all items; items re-arm from their persisted state (GEN-2). |

### 1.4 Manual controls and overrides

| Control | Sets | Default duration | Options |
|---|---|---|---|
| I'm outdoors | Environment = Outdoor | `UntilTransition` (cap 8 h) | `UntilChanged`, `For(30m/1h/2h/4h/custom)`, `UntilTransition` |
| I'm indoors | Environment = Indoor | `UntilTransition` (cap 8 h) | same |
| Start working / Start studying | Activity session (manual) | until ended (cap 10 h) | `UntilChanged`, `For(...)` |
| End session | ends session | — | — |
| Pause automatic detection | all automatic inference -> Unknown (manual overrides still apply) | `For(2h)` | `For(30m/1h/2h/4h)`, `UntilChanged`, rest of today |
| Leaving now | departure event (§5) | instantaneous | — |

`OverrideDuration` semantics: `UntilChanged` = until the user picks another value or clears it (still capped where a cap is listed). `For(d)` = until `setAt + d`. `UntilTransition` = until the next meaningful transition (CTX-3) or the cap, whichever first. Override expiry returns the dimension to automatic inference; it does not itself emit cues except via normal re-evaluation.

### 1.5 Work / study sessions (Windows companion optional)

| Setting | Default | Range / options |
|---|---|---|
| sustainedActiveToStart | 5 min | 1–30 min |
| sessionStart (per place) | Office: `AutoStart`; Home: `Suggest`; others `Off` | `AutoStart / Suggest / Off` |
| permittedHours | workDays 08:00–19:00 | any days, any window |
| idleToPause | 10 min | 2–60 min |
| lockedToPause | 2 min | 0–30 min |
| pausedToEnd | 60 min | 15–240 min |
| companionStaleToSuspend | 10 min | 3–60 min |
| suggestCooldown | 2 h | 30 min–1 day |
| meetingKeepsSessionActive | true | bool |

| ID | Rule |
|---|---|
| WRK-1 | Auto session candidate: Place is a saved place with Working/Studying allowed, within permitted hours, and companion `active` (fresh) for `sustainedActiveToStart`. |
| WRK-2 | Candidate + `AutoStart`: session starts silently; a low-priority notification offers "Not working" (undo, ends it and suppresses auto-start at this place for `suggestCooldown`). Candidate + `Suggest`: one quiet notification "Start working?" with `Start` / `Not now` (Not now = suppress for `suggestCooldown`). No reply = nothing starts. |
| WRK-3 | Session pauses on: companion idle >= `idleToPause`, locked/asleep >= `lockedToPause`. Not while a calendar meeting is in progress if `meetingKeepsSessionActive`. Resumes on fresh `active`. |
| WRK-4 | Session ends on: paused >= `pausedToEnd`; confirmed exit from the place; end of permitted hours (auto sessions only); `End session`; cap (manual). Ending emits no cue. |
| WRK-5 | Companion stale (no signal for `companionStaleToSuspend`): auto session becomes `Suspended` (Activity = Unknown; dependents freeze, e.g. posture). It ends by WRK-4 timing if not refreshed. Manual sessions ignore companion staleness. |
| WRK-6 | Computer activity alone never starts a session outside an enabled place or outside permitted hours. |
| WRK-7 | Manual sessions: idle/lock still pause (they freeze posture), but only `End session`, leaving the place, or the cap ends them. |
| WRK-8 | Without the companion, sessions are manual-only (plus calendar Meeting). This is a supported configuration, not an error. |

---

## 2. Interval habit model (shared by sunscreen, hydration)

| Enum | Values (default **bold**) | Semantics |
|---|---|---|
| `IntervalAnchor` | **`FromAck`** · `FromDue` | Next due = last ack + interval, or previous due + interval (fixed cadence). |
| `UnansweredPolicy` | **`RollForward`** · `StayDue` | RollForward: next due = last delivery + interval. StayDue: no further cue until ack or a new context entry (SUN-8). |
| `DuringMeeting` | per item · `Defer` · `DeliverSilently` · `Deliver` | Defer: hold until Activity leaves Meeting, then GEN-6. |
| `DayStartPolicy` | **`IntervalAfterStart`** · `AtStart` | First due of the day is active-start + interval, or active-start itself. |

---

## 3. Sunscreen

### 3.1 Settings

| Setting | Default | Range / options |
|---|---|---|
| enabled | false (enabled in onboarding) | bool |
| interval | 2 h | 30 min–6 h |
| condition | Environment = Outdoor, confidence >= Medium | any context condition |
| activeHours | 07:00–19:00 | any window |
| anchor | `FromAck` | `IntervalAnchor` |
| firstReminder | `OnOutdoorStart` | `FirstReminderPolicy` |
| reentry | `RemindOnReentry` (grace 0) | `OutdoorReentryPolicy` |
| onLeaveOutdoor | `RetractAndHold` | `LeaveOutdoorPolicy` |
| repeat | every 20 min, 1 repeat | 0–5 repeats, 5–60 min |
| unanswered | `RollForward` | `UnansweredPolicy` |
| snooze | 15 min | 5–120 min |
| duringMeeting | `DeliverSilently` | `DuringMeeting` |
| actions | Applied · Snooze · Pause | fixed set |

### 3.2 Policy enums

| Enum | Values (default **bold**) | Semantics |
|---|---|---|
| `FirstReminderPolicy` | **`OnOutdoorStart`** · `AfterDelay(min)` · `AfterFullInterval` · `OnlyAfterApplied` | Applies when Outdoor begins and the user is **not covered** (no `Applied` within `interval`). OnOutdoorStart: cue when Outdoor is confirmed. AfterDelay: confirmed + N min (0–120). AfterFullInterval: first cue one interval after Outdoor began. OnlyAfterApplied: never a first cue; cueing starts after the first manual `Applied`. |
| `OutdoorReentryPolicy` | **`RemindOnReentry(graceMin=0)`** · `TreatAsFirst` · `WaitNextInterval` | Applies when Outdoor begins and the item is already due (became due indoors, or snoozed/held). RemindOnReentry: cue at confirmation + grace (0–30). TreatAsFirst: apply `FirstReminderPolicy`. WaitNextInterval: due moves to now + interval. |
| `LeaveOutdoorPolicy` | **`RetractAndHold`** · `KeepVisible` · `RemindAnyway` | When Outdoor ends with a visible or pending cue. RetractAndHold: remove the notification, keep the due state, no cue until Outdoor resumes. KeepVisible: leave it, stop repeats. RemindAnyway: ignore environment for this cue. |

### 3.3 Rules

| ID | Rule |
|---|---|
| SUN-1 | State: `lastAppliedAt` (UTC instant or null), `dueAt`, `snoozedUntil`, `pausedUntil`. One instance; transitions never create a second timer. |
| SUN-2 | `Applied` sets `lastAppliedAt = tapAt`; `dueAt = tapAt + interval` (FromAck). Applied is accepted anywhere, any time (e.g. at home before leaving). |
| SUN-3 | Covered = `now < lastAppliedAt + interval`. A covered user never gets a first reminder; the next cue is at `dueAt` if Outdoor then. |
| SUN-4 | Cues are delivered only while the condition holds and within active hours (GEN-6). |
| SUN-5 | Delivered, dismissed, timed-out or unanswered cues never change `lastAppliedAt`. |
| SUN-6 | `lastAppliedAt` is preserved across place transitions, indoor visits, overrides, reboot and timezone change. Only a newer `Applied` or a history edit changes it. |
| SUN-7 | Indoor visits shorter than `outdoorExitDwell` are invisible to sunscreen (CTX-5). Longer visits: the interval keeps running indoors; `onLeaveOutdoor` and `reentry` decide delivery. |
| SUN-8 | On Outdoor start: if not covered and no first cue was given in this outdoor stretch -> `firstReminder`; if `dueAt <= now` -> `reentry`; otherwise wait for `dueAt`. |
| SUN-9 | Snooze ending while not Outdoor: handled as held due (re-entry rule applies later). |
| SUN-10 | Pause options: 1 h · 2 h · until Outdoor ends · rest of today · custom. Resume never replays missed cues. |
| SUN-11 | Day boundary does not reset `lastAppliedAt`; it simply ages past the interval. |

### 3.4 Edge cases

| Case | Decided behavior |
|---|---|
| Apply at home 09:00, outside 09:30–14:00 | No first cue (covered). Cue 11:00. |
| Outside 10:00 not covered | Cue ~10:05 (after dwell). Applied 10:07 -> next 12:07. |
| Due at 12:07 while in a shop 12:00–12:30 | Cue retracted/held; out at 12:30 -> cue at ~12:40 (exit dwell + enter dwell, grace 0). |
| 3-min doorway pass during outdoor stretch | No change, no duplicate cue. |
| Cue ignored outdoors | One repeat at +20 min, then unanswered; next cue at last delivery + 2 h. |
| Environment Unknown while due | Treated as not Outdoor (hold). |
| Applied twice within minutes | Latest wins; history keeps both. |

---

## 4. Hydration

| Setting | Default | Range / options |
|---|---|---|
| enabled | false (onboarding) | bool |
| interval | 60 min | 15–240 min |
| activeHours | 09:00–21:00 | any window |
| days | every day | any subset |
| condition | any context; Unknown counts as match | any context condition |
| anchor / dayStart | `FromAck` / `IntervalAfterStart` | enums §2 |
| repeat | none (0) | 0–3 repeats |
| unanswered | `RollForward` | `UnansweredPolicy` |
| snooze | 15 min | 5–120 min |
| duringMeeting | `Defer` | `DuringMeeting` |
| actions | Drank · Snooze · Pause | fixed |
| phrase | "Time for some water" / "הגיע הזמן לשתות מים" | user text |

| ID | Rule |
|---|---|
| HYD-1 | `Drank` sets `lastAckAt`; next due = ack + interval. No amounts, targets or totals are recommended or computed. |
| HYD-2 | Unacked cue: no repeat by default; next cue = delivery + interval. |
| HYD-3 | First cue of each active window follows `dayStart`; acks from the previous day do not shift it. |
| HYD-4 | Pause options: 1 h · 2 h · rest of today · custom. |
| HYD-5 | Optional per-context intervals: a list of `(condition, interval)` evaluated in order; first match wins; fallback = base interval. Switching interval recomputes `dueAt = lastAckAt + newInterval` (never earlier than now + 1 min). |

---

## 5. Water bottle (departure cue)

| Setting | Default | Range / options |
|---|---|---|
| enabled | false (onboarding) | bool |
| places | places with `bottleReminderOnLeave` (Home, Office, Gym templates) | subset |
| triggers | `LeavingNow`, `GeofenceExit` | + `ScheduledDeparture(days, time)` |
| cooldownPerPlace | 60 min | 0–480 min |
| dedupWindow | 30 min | 5–120 min |
| actions | Got it · Not needed | fixed |
| repeat | none | 0–1 |

| ID | Rule |
|---|---|
| BTL-1 | `Leaving now` (quick tile, widget, notification shortcut, app) cues immediately and records a departure from the current place (or Unknown). This is the dependable path. |
| BTL-2 | A raw OS geofence exit from an enabled place cues immediately (no exit dwell, since exits already arrive late), unless suppressed by BTL-3/4. |
| BTL-3 | Dedup: one cue per departure. Any trigger within `dedupWindow` after a delivered bottle cue is suppressed (e.g. Leaving now then geofence exit). |
| BTL-4 | Cooldown: no bottle cue for the same place within `cooldownPerPlace` of the previous one (boundary flapping, stepping out briefly). |
| BTL-5 | If an exit is processed after the user already entered another saved place, skip it (too late to help); log `skipped_late`. |
| BTL-6 | `ScheduledDeparture`: cue at the configured time if Place = the enabled place; it counts as the departure cue for dedup. |
| BTL-7 | `Got it` / `Not needed` are equivalent acks; dismiss is not an ack but there is nothing further to remind. |

Signal assessment (honest): geofence exit typically arrives minutes after leaving and can be later in power-saving modes, so it is a fallback, not the primary path. Candidate earlier signals, not in v1 defaults: Wi-Fi disconnect from a chosen network (early but noisy; needs location permission), car Bluetooth connect (reliable but you are already in the car), NFC tag at the door (reliable, needs a tag), activity-recognition walking start (noisy at home). The scheduled-departure trigger is the dependable automatic option.

---

## 6. Posture cycle

| Setting | Default | Range / options |
|---|---|---|
| modes (ordered) | Sitting 30 min · Standing 30 min · Walking (treadmill) 30 min | reorder; each 5–120 min; each enable/disable; >= 1 enabled |
| activeWhen | `DuringSessions` | `DuringSessions / ActiveHours(window) / ManualOnly` |
| timerStart | `AtConfirmation` | `PostureTimerStartPolicy` |
| confirmRepeat | every 5 min, 2 repeats | 0–5, 2–30 min |
| duringMeeting | `DeferCue` | `PostureMeetingPolicy` |
| shortInterruption | <= 15 min: continue remaining | 0–60 min |
| longInterruption | `ResetToFirst` | `PostureInterruptionPolicy` |
| extendOptions | +5 / +10 / +15 min | any |
| snooze (of switch) | 5 min | 1–30 min |
| phrases | "Time to stand" · "Time to walk" · "Time to sit" | user text, per mode |

| Enum | Values (default **bold**) | Semantics |
|---|---|---|
| `PostureTimerStartPolicy` | **`AtConfirmation`** · `AtCue` | AtConfirmation: next mode's timer starts when the user taps `Switched`; until then the cycle is `SwitchPending` and repeats per `confirmRepeat`, then waits silently. AtCue: next timer starts at the cue instant; `Switched` is optional. |
| `PostureMeetingPolicy` | **`DeferCue`** · `Freeze` · `Ignore` | DeferCue: timer runs; a cue due in a meeting is delivered when it ends. Freeze: timer pauses during meetings. Ignore: cue as normal (speech still per §8.5). |
| `PostureInterruptionPolicy` | **`ResetToFirst`** · `RestartCurrent` · `ContinueRemaining` | After a pause longer than `shortInterruption`: start the first enabled mode with full time, restart the current mode, or continue its remaining time. |

| ID | Rule |
|---|---|
| POS-1 | Persisted state: `modeIndex`, `modeStartedAt`, `remaining` (when frozen), `phase` (`Running / SwitchPending / Paused / Frozen / Off`). Survives reboot. |
| POS-2 | The cycle runs only while `activeWhen` holds; otherwise `Frozen` with remaining time kept. Session pause / companion stale / Inactive freeze it (WRK-3, WRK-5). |
| POS-3 | Cue at mode end names the next enabled mode. Actions: `Switched` · `Snooze` · `Skip` (go to the mode after next) · `Extend`. |
| POS-4 | Controls anytime: pause, resume, skip to next, extend current, switch now, reset. |
| POS-5 | Disabled modes are skipped; with one enabled mode the cue becomes a "keep going / take a break" reminder after each duration. |
| POS-6 | Resume after freeze: <= `shortInterruption` -> continue remaining; longer -> `longInterruption`. |
| POS-7 | The app never controls the treadmill or any device; the walking cue is text/sound only. |
| POS-8 | Unconfirmed switch (AtConfirmation): after repeats, no further cues; the pending switch is shown in-app until acted on or the session ends. |

---

## 7. Medication

| Setting | Default | Range / options |
|---|---|---|
| items | **empty** | 0–30 items |
| label | — (required) | 1–40 chars, user text, never shipped or synced unless `medication` scope |
| times | — (required) | 1–12 local times per day |
| days | every day | any subset; optional start/end date |
| travelPolicy | must be chosen at creation; prefilled `FollowLocalTime` | `MedicationTravelPolicy` |
| repeat | every 10 min, 3 repeats | 0–12 repeats, 5–60 min |
| snooze | 10 min | 5–60 min |
| lockScreen | `Generic` | `LockScreenPresentation` |
| speakLabel | false | bool (false speaks "Medication reminder") |
| quietHours | `DeliverNormally` | `DeliverNormally / DeliverSilently` |
| historyRetention | 90 days (local only) | 30–730 days |
| actions | Taken · Snooze | fixed; `Skip` only from the dose detail screen |

| Enum | Values (default **bold**) | Semantics |
|---|---|---|
| `MedicationTravelPolicy` | **`FollowLocalTime`** · `KeepHomeTimezone(zone)` | Local time at the current zone, or the instant of that local time in a fixed zone. |
| `LockScreenPresentation` | **`Generic`** · `Full` · `Hidden` | Generic: "Medication reminder" without label; Full: label shown; Hidden: no content on lock screen. |

| ID | Rule |
|---|---|
| MED-1 | Dose slot identity = `(itemId, localDate, localTime)` in the policy's zone. Slot states: `Upcoming -> Due -> Taken(takenAt) / Skipped`; a slot still `Due` after its day boundary is shown as **Not confirmed**. No "missed" judgement wording. |
| MED-2 | Only `Taken` (or an explicit history edit) marks taken. Dismissal, timeout, unanswered, screen lock or app open never do. |
| MED-3 | Medication cues are never suppressed, deferred or paused by context, place, activity, routines, meetings, quiet hours, global pause or collisions. Meetings may only turn speech off (§8.5). |
| MED-4 | Unacked cue repeats per `repeat`; after the last repeat the notification remains (not auto-cancelled) and the slot stays `Due`; the home screen shows it until confirmed or the day boundary. |
| MED-5 | `Taken` accepted for any `Due`/`Upcoming` slot of today; history allows correcting `takenAt` and state (logged in audit). |
| MED-6 | DST: a nonexistent local time fires at the first valid instant after it; a repeated local time fires once at the first occurrence. |
| MED-7 | Timezone change: slots are recomputed under the item's policy. A slot already `Taken` is never re-cued. Slots whose new instant is already past and not confirmed get **one** merged cue ("2 medication reminders not confirmed"), never one per slot. A one-time informational notice states which policy each item follows. No advice on what to do. |
| MED-8 | Boot recovery: same as MED-7 merged cue for today's `Due` slots. |
| MED-9 | Edits to schedules, deletion and policy changes are `sensitive`/`destructive` and need on-phone confirmation (ARCHITECTURE §3.2). Pausing an item is a schedule edit (end date), not a pause action. |
| MED-10 | The app provides no dosage, interaction, timing or missed-dose guidance anywhere, including speech and MCP responses. |

---

## 8. Global delivery policy

### 8.1 Priority (1 = highest)

| P | Type | Bypasses quiet hours | Default sound / vibration identity |
|---|---|---|---|
| 1 | Morning alarm | yes (rings) | alarm source (§11) · continuous |
| 2 | Medication | yes (`quietHours` setting) | "Soft bell" · long-short-long |
| 3 | Routine step (running routine) | yes (user-started) | "Wood tap" · single short |
| 4 | Calendar | silent only | "Two-note rise" · double short |
| 5 | Water bottle | Leaving now: yes; geofence: silent | "Pop" · single long |
| 6 | Sunscreen | no (held) | "Bright chime" · triple short |
| 7 | Posture | no (frozen) | "Low marimba" · two long |
| 8 | Hydration | no (skipped) | "Droplet" · single short soft |

Sound names are app-bundled synthetic tones; every type's sound, vibration, speech and phrase is editable per cue profile, and each profile has a "Preview" action (GEN-10).

### 8.2 Quiet hours

| Setting | Default | Range |
|---|---|---|
| quietHours | 22:30–07:00 every day | any window, per-day; off |
| respectSystemDnd | true | bool (alarms always ring; medication bypasses DND only if the owner grants DND access) |

| ID | Rule |
|---|---|
| QH-1 | P6–P8 are not delivered in quiet hours; at quiet end each delivers at most once if still due and conditions hold (GEN-6). |
| QH-2 | Calendar cues in quiet hours: silent notification, no sound/speech/vibration. |
| QH-3 | Medication and alarms are never held by quiet hours; medication may be made silent by its own setting. |
| QH-4 | A running routine is user-initiated and delivers normally; a scheduled routine start prompt in quiet hours is delivered silently. |

### 8.3 Collision rule

| Setting | Default | Range |
|---|---|---|
| mergeWindow | 2 min | 0–10 min |
| minAudibleGap | 30 s | 0–300 s |
| maxSpokenItems | 3 | 1–5 |
| speechMaxAge | 2 min | 30 s–10 min |

| ID | Rule |
|---|---|
| COL-1 | Cues due within `mergeWindow` of each other form one **delivery group**: one sound and one vibration (from the highest-priority member), one spoken utterance listing members in priority order ("Medication reminder. Also: posture, hydration."), up to `maxSpokenItems` then "and N more". |
| COL-2 | Each member keeps its own notification (grouped) and its own actions, state and repeats. Merging never acks, drops or downgrades a member. |
| COL-3 | Two audible deliveries are at least `minAudibleGap` apart; a later one is delayed, never discarded. P1–P2 are never delayed by P3–P8; a lower-priority sound already playing is cut short. |
| COL-4 | An alarm ringing absorbs other cues: they post silently and speak (if allowed) after the alarm is stopped or snoozed. |
| COL-5 | Repeats of different items that fall within `mergeWindow` merge the same way. |

### 8.4 Snooze defaults

| Type | Default | Range | Notes |
|---|---|---|---|
| Alarm | 9 min | 1–30 | max snoozes 3 (0–10) |
| Medication | 10 min | 5–60 | |
| Routine step | — | — | uses `+1 min` / `+5 min` extend instead |
| Calendar | 5 min | 1–15 | never later than event start |
| Water bottle | — | — | none |
| Sunscreen / Hydration | 15 min | 5–120 | |
| Posture | 5 min | 1–30 | |

### 8.5 Speech

| Setting | Default | Options |
|---|---|---|
| language | app language; per cue profile override | `he / en` |
| overMedia | `DuckAndSpeak` | `SpeechOverMediaPolicy`: `DuckAndSpeak` (lower music, speak, restore) · `PauseAndSpeak` · `NotificationOnly` |
| inMeeting | `NotificationOnly` | `SpeechInMeetingPolicy`: `NotificationOnly` (no speech/sound; vibration + notification) · `VibrateOnly` · `SpeakAnyway` |
| duringPhoneCall | never speaks | fixed |
| output | any route | `AnyRoute / HeadphonesOnly` |

| ID | Rule |
|---|---|
| SPK-1 | One utterance at a time, queued by priority then due time; only an alarm interrupts speech. |
| SPK-2 | Utterances older than `speechMaxAge` are dropped; their notifications stay. |
| SPK-3 | If TTS or the language voice is unavailable, the cue falls back to notification + sound and the readiness screen shows it. A failed speech is never a failed cue. |
| SPK-4 | Speech never includes medication labels unless `speakLabel`, nor calendar titles unless `speakTitles` (§9). |

---

## 9. Calendar cues

| Setting | Default | Range / options |
|---|---|---|
| calendars | none selected until access granted | any subset of device calendars |
| syncHorizon | 7 days | 1–30 |
| maxCacheAge | 24 h (older cache: no new calendar cues; readiness warning) | 6–72 h |
| supplement | `SupplementOnMatch` | `CalendarReminderSupplementPolicy` |
| speakTitles | false | bool (false: "You have a meeting in ten minutes") |
| showTitlesOnLockScreen | false | bool |
| tentative | treated as accepted | `Accepted / Exclude` |
| phrase template | "You have a {kind} in {minutes} minutes" / Hebrew equivalent | user text with `{kind}`, `{minutes}`, `{title}` |

### 9.1 Evaluation order (first decisive rule wins)

| Step | Rule | Outcome |
|---|---|---|
| 0 | Hard exclusions: canceled; self declined; all-day events (unless step 1 says always) | no cue |
| 1 | Per-event override (`Always(leads)` / `Never`), per instance or whole series | decisive |
| 2 | Calendar preference (`Always` / `Never` / `Rules`) | `Always`/`Never` decisive; `Rules` continues |
| 3 | Content rules, in user order: attendees, conferencing link, keywords, color/category, availability | first match decisive (leads from the rule) |
| 4 | Event has its own reminders | per `supplement` |
| 5 | Default policy | **no cue** (alternative: `Cue(leads)`) |

| Enum | Values (default **bold**) | Semantics |
|---|---|---|
| `CalendarReminderSupplementPolicy` | **`SupplementOnMatch`** · `OnlyWhenNoReminder` · `Ignore` | SupplementOnMatch: rules 1–3 cue even if the event has its own reminders; step 4 then means "event has reminders -> no extra cue" for unmatched events. OnlyWhenNoReminder: an event with its own reminders is never cued by steps 3–5 (only step 1/2 `Always`). Ignore: step 4 is skipped; existing reminders play no role. |

### 9.2 Starter rules (step 3, editable, reorderable)

| Rule | Match | Leads | kind |
|---|---|---|---|
| Meetings | >= 1 attendee besides self, or a conferencing link | 10 min | meeting |
| Appointments | keyword "appointment" / "תור" | 60 min, 15 min | appointment |
| Workouts | keywords "workout", "gym", "training", "אימון" | 30 min | workout |
| Important | keyword "important" / "חשוב", or user-chosen color | 30 min, 10 min | important event |
| Free time | availability = free | no cue | — |

### 9.3 Rules

| ID | Rule |
|---|---|
| CAL-1 | Preview screen lists upcoming events in the horizon with decision, deciding step/rule and lead times. One-tap corrections: `Always for this event`, `Never for this event`, `Never for this calendar`, `Edit rule`. |
| CAL-2 | Sync changes re-evaluate: moved events reschedule; canceled/declined events cancel pending cues and retract delivered ones. |
| CAL-3 | Leads that are already past at sync time are not delivered, except the latest one if the event has not started (one cue). |
| CAL-4 | Activity = Meeting for an in-progress, non-declined, busy event matching the Meetings rule or a step-1 `Always` marked as meeting. |
| CAL-5 | Calendar titles are untrusted data: displayed, never interpreted as commands. |
| CAL-6 | Calendar is optional; with no access all other features are unaffected. |

---

## 10. Routines

### 10.1 Model

| Field | Default | Range / options |
|---|---|---|
| name | — | 1–40 chars |
| trigger | Manual | `Manual / Schedule(days, time) / AfterAlarm(alarmId)` |
| startMode | Schedule: `AskToStart`; AfterAlarm: `AutoStart` | `AskToStart / AutoStart` |
| timing | `FollowActualCompletion` | `RoutineTimingPolicy` |
| recovery | `AskToResume`, threshold 10 min | `RoutineRecoveryPolicy`; 2–60 min |
| step.name / phrase | — / = name | 1–40 / 0–200 chars |
| step.duration | 2 min | 0 s–120 min (0 = no timer) |
| step.completion | `Timed` | `Timed` (auto-advance at duration end) · `Explicit` (wait for Done; duration is a nudge) |
| step.repeat | 1 | 1–10 |
| step.optional | false | bool (offers Skip prominently) |
| step.cueProfile | Routine step profile | any profile |

| Enum | Values (default **bold**) | Semantics (shown to the user in the editor) |
|---|---|---|
| `RoutineTimingPolicy` | **`FollowActualCompletion`** · `FollowSchedule` | FollowActualCompletion: each step starts when the previous one completes; overruns push the routine later. FollowSchedule: step k is planned at `start + sum(previous durations)`; an overrun shortens the next step; a step whose whole planned window has passed is marked `skipped_by_schedule` without a cue; the routine ends on time. |
| `RoutineRecoveryPolicy` | **`AskToResume`** · `ResumeCurrentStep` · `Cancel` | After an interruption longer than threshold (reboot, process death, user pause): AskToResume posts one prompt "Paused at '{step}'. Resume / Restart routine / Cancel". ResumeCurrentStep restarts that step with full duration. Cancel ends the run. |

### 10.2 Rules

| ID | Rule |
|---|---|
| RTN-1 | Operations: create, duplicate, edit, reorder steps, delete, pause, resume, cancel, skip step, back one step, Done, extend +1/+5 min. |
| RTN-2 | Step start cue: sound/vibration + speak phrase. Timed step ending = next step's start cue (one cue, not two). |
| RTN-3 | At most one run at a time. Starting another asks to cancel the current run. |
| RTN-4 | Run state persisted: routineId, config version, step index, repeat index, stepStartedAt, paused remaining, planned schedule (FollowSchedule). |
| RTN-5 | Interruption <= threshold: continue with real elapsed time; if the current Timed step ended during the gap, jump to the correct step and give **one** cue for it. Never cue intermediate steps. |
| RTN-6 | Interruption > threshold: apply `recovery`. Never rapid-fire catch-up. |
| RTN-7 | Editing a routine during a run affects the next run only (run keeps its config version). |
| RTN-8 | Test mode: `x1` or `fast` (each step 10 s); cues delivered even in quiet hours, labeled "Test", no history counts (GEN-10). |
| RTN-9 | Scheduled trigger with `AskToStart`: one prompt "Start / Skip today"; no answer within 30 min = skipped today (logged). |
| RTN-10 | Activity = RoutineRunning while a run is active and not paused; P6–P8 cues during a run are deferred until it ends (medication and calendar are not). |

---

## 11. Morning alarms

| Setting | Default | Range / options |
|---|---|---|
| time / days | 07:00 / workDays | any time / any subset; one-off allowed |
| source | `LocalTone("Morning")` | `SpotifyItem(uri) + LocalFallback(tone)` · `LocalTone` |
| spotifyStartTimeout | 10 s | 5–30 s |
| volumeRamp | 30 s | 0–120 s |
| snooze | 9 min, max 3 | 1–30 min, 0–10 |
| ringTimeout | 10 min (then auto-snooze while snoozes remain, else stop) | 1–30 min |
| vibrate | true | bool |
| followOnRoutine | none | routineId (starts on Stop, not on Snooze) |
| timezone | follows local time | fixed |

| ID | Rule |
|---|---|
| ALM-1 | Alarm rings at the scheduled local time with full-screen UI, independent of quiet hours, context and DND. |
| ALM-2 | Spotify: if playback has not started within `spotifyStartTimeout` (app missing, signed out, offline, locked-phone restriction), the local fallback tone plays. An alarm is never silent. |
| ALM-3 | Actions: `Stop` · `Snooze`. Stop ends today's occurrence and starts `followOnRoutine` if set. |
| ALM-4 | "Skip next" disables only the next occurrence. |
| ALM-5 | Test action rings immediately with the real config and reports which source played (Spotify or fallback) and why. |
| ALM-6 | DST rules as MED-6. Reboot: an alarm whose time passed during power-off by <= 30 min rings once on boot; older ones are logged as `missed_power_off` only. |

---

## 12. Default templates (first run)

All synthetic; no locations, no personal data. Everything is editable or deletable.

| Template | Shipped state | Contents |
|---|---|---|
| Places | inactive until location set | Home (Indoor, sessionStart Suggest, bottle on), Office (Indoor, AutoStart, bottle on), Gym (Indoor, Off, bottle on) |
| Sunscreen | disabled (onboarding toggle) | §3 defaults |
| Hydration | disabled | §4 defaults |
| Water bottle | disabled | §5 defaults |
| Posture cycle | disabled | §6 defaults |
| Medication | **empty list** | — |
| Morning routine (provisional) | disabled, Manual trigger | 1 Shower — Timed 5 min, phrase "Shower" · 2 Face cleanser — Timed 2 min · 3 Brush teeth — Timed 2 min · 4 Get dressed — Explicit |
| Morning alarm | disabled | 07:00 workDays, LocalTone, no follow-on |
| Calendar rules | starter rules §9.2 on; no calendars selected | — |
| Cue profiles | one per type, §8.1 | he + en phrases |
| Quiet hours | on, 22:30–07:00 | — |

Onboarding asks only: language, which templates to enable, permissions needed by the enabled ones (each optional), and medication travel policy when the first medication is added.

---

## 13. Policy enum index (for the engine)

`IntervalAnchor` · `UnansweredPolicy` · `DuringMeeting` · `DayStartPolicy` · `FirstReminderPolicy` · `OutdoorReentryPolicy` · `LeaveOutdoorPolicy` · `AwayEnvironmentPolicy` · `OverrideDuration` · `SessionStart` · `PostureTimerStartPolicy` · `PostureMeetingPolicy` · `PostureInterruptionPolicy` · `MedicationTravelPolicy` · `LockScreenPresentation` · `CalendarReminderSupplementPolicy` · `RoutineTimingPolicy` · `RoutineRecoveryPolicy` · `SpeechOverMediaPolicy` · `SpeechInMeetingPolicy`. Semantics are defined where each first appears.

---

## 14. Open questions for the owner (none block implementation)

1. Sunscreen active hours: fixed 07:00–19:00, or compute daylight on-device from the current location (offline)?
2. Should `AwayEnvironmentPolicy` default to `OutdoorWhenOnFoot` (needs activity-recognition permission) or stay `Unknown` until you tap "I'm outdoors"?
3. Workdays and work hours: is the he-IL Sun–Thu, 08:00–19:00 default right for you?
4. Session start at home: `Suggest` (one quiet prompt) or `AutoStart` with undo, like the office?
5. Medication repeats: is 3 repeats at 10 min about right, and should medication make sound during quiet hours?
6. Posture: should the cycle reset to sitting after a long break, or resume where it stopped?
7. Calendar default lead times (meeting 10 min, appointment 60 + 15 min): adjust?
8. Water bottle: would you use a scheduled departure time, or an NFC tag at the door?
9. Morning routine steps beyond "Brush teeth": what follows, and which steps should wait for an explicit Done?
