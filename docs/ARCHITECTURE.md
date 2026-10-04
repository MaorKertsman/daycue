# DayCue architecture

Status: baseline set by the delivery lead on 2026-10-04. Details are elaborated in `docs/architecture/` by the owning engineers. Changes to anything in "Contracts" need the delivery lead.

## 1. Principles

1. **The phone owns its schedule.** Core reminders need no network, desktop, server or LLM.
2. **Deterministic rules engine.** Pure Kotlin, driven by an injected `Clock`, fully testable on the JVM.
3. **One edit path.** UI, MCP and import all produce `ConfigOp`s that pass the same validator.
4. **Unknown beats wrong.** Stale or missing signals degrade to `Unknown`, never to a confident guess.
5. **Acknowledgement is explicit.** Posting or dismissing a notification is never completion.
6. **Minimal infrastructure.** Everything remote is optional, free-tier, and holds as little as possible.

## 2. Layers (and where they live)

| # | Layer | Module / package | Notes |
|---|---|---|---|
| 1 | Definitions (habits, routines, places, cues, calendar rules, alarms) | `domain/.../config` | One versioned `DayCueConfig` document (kotlinx.serialization JSON) |
| 2 | Observed signals (geofence, manual override, companion activity, calendar busy) | `domain/.../signal` | Each signal has `observedAt` + `expiresAt` |
| 3 | Inferred context (place, environment, activity) | `domain/.../context` | Confidence, dwell, hysteresis; expiry -> `Unknown` |
| 4 | Scheduling decisions | `domain/.../engine` | `Engine.reduce(state, event, now) -> (state', effects)` |
| 5 | Delivery (notification, sound, vibration, speech, alarm UI) | `app/.../delivery` | Executes `Effect`s; collision/quiet-hours policy decided in domain |
| 6 | Completion / snooze / pause actions | `domain` events, `app/.../actions` receivers | Notification actions map to `Event`s |
| 7 | External integrations (calendar, Spotify, companion, MCP relay) | `app/.../integrations`, `companion/`, `mcp/` | Only produce signals or `ConfigOp` commands |

## 3. Contracts

### 3.1 Engine

```kotlin
interface Clock { fun now(): Instant; fun zone(): ZoneId; fun elapsedRealtime(): Duration }

// Pure function. No I/O. All persistence is "store EngineState after each reduce".
fun reduce(config: DayCueConfig, state: EngineState, event: Event, clock: Clock): Reduction
data class Reduction(val state: EngineState, val effects: List<Effect>)
```

- `Event`: `Tick` (alarm fired), `Ack(habitId)`, `Snooze`, `Pause/Resume`, `SignalObserved`, `ManualOverride`, `ConfigChanged`, `BootCompleted`, `TimeChanged`, `TimezoneChanged`, routine/posture/alarm controls.
- The lists here are indicative; `docs/PRODUCT.md` is authoritative for behavior. Also required: `LeavingNow`, `CalendarSynced(events)`, and delivery feedback (`SpeechFinished/Failed`) as events; config ops for places, cue profiles, quiet hours/settings, per-event and per-series calendar overrides, and alarms.
- `Effect`: `ScheduleWake(at, exact)`, `CancelWake`, `Deliver(Cue)`, `DismissCue`, `Speak`, `StartAlarm`, `RecordHistory`.
- After every reduce the app persists `EngineState` (Room, single transaction) and re-arms **one** next-wake alarm from `state.nextWakeAt`. On process start / boot / time change the app replays `BootCompleted`/`TimeChanged` and the engine recomputes; nothing depends on in-memory timers.
- Time semantics: interval habits store **UTC instants** (`lastAckAt`, `dueAt`), unaffected by timezone. Fixed-clock schedules (medication, alarms, routines) store **local time + days** and resolve to instants through `Clock.zone()`, with an explicit per-item travel policy (`FollowLocalTime` | `KeepHomeTimezone`).

### 3.2 Config and edits

- `DayCueConfig(schemaVersion, version, habits, postureCycle, medications, routines, alarms, places, cueProfiles, calendarRules, contextRules, settings)`.
- `version` increments on each applied change set. Every applied change stores the previous document in `config_history` (bounded) => undo and audit.
- `ConfigOp` (sealed, serializable): targeted operations such as `UpsertHabit`, `SetHabitInterval`, `UpsertRoutine`, `ReorderRoutineSteps`, `SetPostureModes`, `UpsertMedication`, `DeleteX`, `SetCalendarRule`, `PauseHabitUntil`...
- `applyOps(config, ops, baseVersion) -> Result<DayCueConfig, ValidationErrors | Conflict>`; pure, used by UI, MCP commands and import. `preview(config, ops)` returns a human-readable diff plus a `sensitivity` class (`ordinary | sensitive | destructive`). Medication schedule changes and deletions are `sensitive`/`destructive` and require on-phone confirmation unless the owner has granted that scope.

### 3.3 Persistence (Room)

`config_current`, `config_history`, `engine_state` (single row JSON + typed columns for next wake), `history_event` (acks, snoozes, deliveries, medication log), `signal` (latest per source), `calendar_event_cache`, `command_log` (MCP commands: id, state, result), `audit_log`. Config and engine state are stored as JSON documents to keep migrations cheap; history tables are relational.

### 3.4 Scheduling on Android

- One exact alarm (`setExactAndAllowWhileIdle` / `setAlarmClock` for morning alarms and medication) for the engine's next wake; a `BroadcastReceiver` feeds `Tick`. If exact-alarm access is missing, fall back to inexact `setAndAllowWhileIdle` and show it on the readiness screen.
- `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` receivers re-arm.
- WorkManager only for deferrable sync (calendar, relay polling).
- Foreground service only while something user-visible is running (routine playback, ringing alarm), never permanently.

### 3.5 Remote path (optional)

```
Claude / ChatGPT --(MCP Streamable HTTP + OAuth 2.1)--> relay (serverless, free tier)
Claude Code      --(MCP stdio, device-paired token)-----> relay
Windows companion --(signed activity signal)-----------> relay
relay --(FCM data message = "wake and sync", no payload)--> phone --(HTTPS pull + ack)--> relay
```

- The relay stores: a durable command queue with states `queued -> delivered -> applied | rejected | failed | expired`, the latest phone-published config snapshot (redacted per scope), and expiring companion activity signals. **Relay acceptance is never reported as applied**; only the phone's signed ack moves a command to `applied`.
- Commands carry an idempotency key and `baseVersion`; the phone applies each at most once via `command_log`.
- Wake: FCM high-priority data message when configured (needs the owner's free Firebase project). Without FCM the phone syncs on app open, on session start, and via periodic WorkManager (>= 15 min, not guaranteed) and the MCP tools say so.
- Medication labels are excluded from the remote snapshot unless the owner enables the `medication` scope.
- Companion latency: activity signals go stale after ~3 minutes (`docs/PRODUCT.md`). They only arrive in time with FCM wake, or with the opt-in "frequent check" mode (the phone polls the relay every few minutes only while it is at a work/study-enabled place inside permitted hours). With neither, automatic session detection is off and sessions are manual; the app states this on the readiness screen.
- External text (calendar titles, place names) is returned to MCP clients as clearly delimited data, never interpreted by the phone.

## 4. Repository layout

```
android/        Gradle project: :domain (Kotlin/JVM), :app (Android)
companion/      .NET Windows tray app
mcp/            TypeScript: packages/server (MCP tools, stdio + HTTP), packages/relay
docs/           Product, architecture, design, validation, security
```

## 5. Decision records

See `docs/adr/`. Index:

- ADR-0001 Config as a versioned JSON document + ops (this file, 3.2)
- ADR-0002 Single next-wake exact alarm driven by a pure reducer (3.1, 3.4)
- ADR-0003 Relay with phone acknowledgement; FCM optional wake (3.5)
