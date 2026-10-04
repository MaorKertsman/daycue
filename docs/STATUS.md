# Project status — durable notes

Resume from this file after any interruption. Update it at every checkpoint.

_Last updated: 2026-10-04 (session 1)_

## Environment facts

- Lead model verified: Claude Fable 5.1 (`claude-fable-5-1`), the latest GA Fable per the official model page (released 2026-09-01).
- Team models: `opus` = Opus 5.5, `sonnet` = Sonnet 5.5, `haiku` = Haiku 4.5 (aliases in `.claude/agents/*.md`).
- Session 1 caveat: `.claude/agents/` was created mid-session, so custom agent types were not loadable. Roles ran as general-purpose agents with the assigned model override and the role file as instructions. From a fresh Claude Code session started in this directory, the named agent types load natively.
- Machine: Windows 11, 16 cores, 31 GB RAM. Nothing was preinstalled except git, gh, node 20. Installed user-locally (no admin, not on PATH) under `%USERPROFILE%\dev-tools`: `jdk21` (Temurin 21.0.12), `android-sdk` (cmdline-tools), `dotnet` (LTS SDK).
- GitHub: `gh` authenticated as `MaorKertsman` (scopes: repo, workflow). Repo-local git identity uses the GitHub noreply email so no personal email enters the public history.
- Owner asked (2026-10-04) for all communication in English. The app still supports Hebrew + English.

## Decisions (see `docs/ARCHITECTURE.md`)

- Repo root is the `daycue` folder; public GitHub repo name `daycue`.
- Pure-Kotlin `:domain` reducer + versioned JSON config + `ConfigOp` edit path; one next-wake exact alarm.
- Remote path = serverless relay + phone ack; FCM optional wake. All remote parts optional.

- Relay hosting: the owner offered their Render account (2026-10-04). Render is the deploy target; the lead deploys after the relay passes local tests and the cost/free-tier limits are written down.

## Assumptions recorded (reversible)

- minSdk 26; owner's phone model unknown — everything device-specific stays configurable.
- Work/study detection defaults follow the brief (5 min sustained activity at an enabled place) until the owner describes their patterns.

## Done (evidence in agent reports; see git log)

- Specs: PRODUCT.md, design/VISUAL.md (Cut Paper Day), design/UX.md, ACCEPTANCE.md, design/REVIEW-1.md.
- :domain engine — 107 JVM tests green (lead re-ran). Scenarios 1-5, 7-12, 17 at engine level.
- mcp/ relay + MCP server — 47 tests green (lead re-ran). Local only, not deployed.
- companion/ — 40 tests green, incl. 3 against the real local relay. Tray UI not hand-tested.
- Android platform layer — emulator-verified (API 37): on-time cue after process kill, ack reschedules, Doze, airplane mode, timezone change, reboot re-arm, medication via setAlarmClock, ringing alarm. Background speech: focus refused on Android 17 but track not muted; audibility unverified. Locked-boot fallback unverified.
- Design system + debug gallery; CI green on GitHub (3 jobs).

## Decisions since baseline

- Calendar = Android Calendar Provider (READ_CALENDAR), not the REST API (ADR-0004).
- Posture notification actions: Switched / Snooze / +5 min; Skip in-app only.
- Scheduled routines ask before starting (Android 17 audio rule). Alarm missed <= 30 min while off -> notification.
- Global pause does not pause calendar cues (owner told; may change).
- Relay hosting question open with owner: (A) Render free + Supabase free Postgres, $0, cold starts; (B) Render Starter + disk ~$7/mo. Lead recommends A. Do not deploy before the owner chooses.
- One early commit (8141492) contains the local path with the Windows username in docs/setup/MCP.md; fixed forward; owner told, history not rewritten.

## Session 1 ended (owner went offline, 2026-10-04)

Committed and pushed through 93602b4: phone relay client, companion signal intake, Spotify alarm path (off by default), relay follow-ups (60 relay tests).

Two agents were still running when the session stopped; their work is UNCOMMITTED in the working tree and may be half-finished:
- UI engineer applying docs/design/REVIEW-1.md in android/app ui/** and src/debug/**; screenshots go to ../daycue-review/design-system-2 (emulator-5556, AVD daycue_ui).
- Scheduling engineer adding integrations/location/** and integrations/calendar/** (+ facade, manifest, ADR-0004, FEASIBILITY updates) (emulator-5554, AVD daycue_test).

To resume:
1. `git status`; run `.gradlew.bat :domain:test :app:testDebugUnitTest :app:assembleDebug`. If green, review and commit; if not, hand the failing area back to the owning role with the error.
2. Check for leftover headless emulators (`adb devices`) and stop them with `adb -s <id> emu kill` if not needed.
3. Re-run whichever of the two tasks did not finish (task briefs are summarized above; specs are in docs/).

Open decisions with the owner (none blocks other work):
- Relay hosting: (A) Render free + Supabase free Postgres, $0, cold starts, PgStore untested; (B) Render Starter + disk, ~$7/mo unconfirmed. Lead recommends A.
- Spotify alarms: Spotify Developer Policy forbids alarm functionality without written approval; shipped off. Owner chooses: keep off / enable for self / ask Spotify.
- Global pause currently does not pause calendar cues.

## Next steps

1. Finish and commit the two in-flight tasks above.
2. UI engineer: real screens wired to the facade (docs/architecture/APP_API.md): Today, Cues (habits, medication, posture, routines, alarms), Setup (places, calendar, cues, integrations incl. remote-access confirmation screen and daycue://open/remote deep link, readiness, settings, export/import), onboarding (ask full-screen-intent access when the first alarm is created), styled alarm screen reading facade.alarmMusic. Add android:icon/roundIcon to the main manifest.
3. Designers review real screens (second review); QA runs docs/ACCEPTANCE.md and writes docs/VALIDATION.md; security reviewer audits repo, relay, pairing, permissions.
4. Signed release APK (owner keystore, docs/setup/BUILD.md), GitHub release, Hebrew quick start, final docs, limitations list.
5. Owner bundle: hosting choice, Spotify choice, Firebase (optional push wake), phone model + USB debugging for physical-device tests (speech audibility, geofence latency, lock-screen alarm, companion end to end).
