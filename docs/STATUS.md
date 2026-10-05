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

## State at 2026-10-05, end of day

Code complete for the authorized scope except the items under "Not built". Last code commit 69ee2d6 (full gate green locally: 160 domain + 249 app unit tests, debug + release, lint 0 errors). QA: 13 scenarios pass, 5 partial, 0 fail at 1740ef9 (docs/VALIDATION.md); defects D9-D13 fixed afterwards and verified by the implementer only (VALIDATION section 7).

Signed release: a keystore was generated on the owner's PC at %USERPROFILE%daycue-keys (daycue-release.jks + keystore.properties with the generated password; android/keystore.properties is the git-ignored copy Gradle reads). versionName 0.1.0, versionCode 1. The owner must back this folder up — losing it means future updates cannot install over the app. The CI release workflow has not run (GitHub signing secrets not set).

Process lessons: check CI after every push (it was red for several commits once); never let two agents run Gradle in android/ at the same time (it corrupted the build cache); agents that run long can end without a final report — give bounded-effort rules and audit their work.

Owner decisions made: English communication; Render offered for hosting; one merged medication catch-up notice after clock jumps (48 h window).
Open with owner: relay hosting A (Render free + Supabase free Postgres, $0, cold starts, PgStore only emulation-tested) vs B (Render Starter + disk, about $7/month, unconfirmed price); Spotify alarms (off; Spotify policy forbids alarm use without written approval); posture after a long manual pause now keeps position (spec reading; owner may object); work week comes from device region.

## Not built / not verified (see docs/LIMITATIONS.md)

- Nothing verified on a physical phone: audibility of sounds/speech/alarm on a locked phone, vibration, real geofence latency, deep Doze, OEM battery restrictions, Hebrew TTS voice install, real calendar account sync, companion tray UI and real lock/sleep events.
- Relay not deployed; no live Claude Code / Claude.ai / ChatGPT connector check; FCM push not configured; companion key pinning; biometric step for medication-scope grants.
- UI for "pause everything"; map picking for places; Quick Settings tile/widget for "Leaving now"; TalkBack run; QA re-test of D9-D13.
- Dependabot PRs #1-#4 untouched (recommendation: close #1 @types/node 26; rebase and merge #2-#4).

## Next steps

1. Owner: install the 0.1.0 pre-release APK on the phone (docs/QUICKSTART.he.md), back up %USERPROFILE%daycue-keys, and run the physical checks in docs/VALIDATION.md section 5 — or connect the phone by USB with debugging on so the lead can run them.
2. Owner: choose relay hosting and Spotify handling. Then deploy the relay (mcp/render.yaml, Node 24), pair the phone, and verify Claude Code (stdio) and Claude.ai (remote MCP) end to end.
3. QA re-test of D9-D13 and TalkBack; designer pass on ../daycue-review/app-3, qa-3.
4. Build the small missing pieces if the owner wants them: pause-everything control, Leaving-now tile, companion key pinning.
