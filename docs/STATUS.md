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

## State at 2026-10-05 (session continued)

Committed through c59b66a; CI green at 9584927 (re-check after each push — it was red for several commits on lint errors and the lead missed it).

Done since the earlier notes: places/geofencing/activity recognition/calendar provider; security review 1 and its fixes (relay hardening 103 tests, domain value-level redaction + explicit op sensitivity, app adoption, grant approval, backup exclusion); real screens for all three tabs + onboarding/readiness/alarm; design reviews 1 and 2 with fix rounds; QA report docs/VALIDATION.md (10 pass / 7 partial / 1 fail before fixes); engine/platform fixes for QA defects D1, D2, D4, D6 (analysis), D7, D8, posture extend/pause, place override, dose correction, clock-jump medication catch-up (owner decision: one merged notice, 48 h window).

Owner decisions so far: communicate in English; Render offered for hosting; medication catch-up notice after clock jumps (yes).
Still open with owner: relay hosting A (Render free + Supabase free Postgres) vs B (Render Starter + disk); Spotify alarms (shipped off; policy forbids alarm use without approval); whether global pause should also pause calendar cues; posture after long manual pause now keeps position (owner may object).

## In progress

- UI engineer (single owner of ui/**): adopting new facade APIs, remaining REVIEW-2 shell/shared items, QA D3/D5; captures to ../daycue-review/app-3 (emulator-5556).
- Integrations engineer: relay DELETE /v1/phone/companions/:id (signed) — app request shape may need adjusting afterwards.
- Docs writer: README.md, docs/QUICKSTART.he.md, docs/setup/INSTALL.md, docs/LIMITATIONS.md.

## Next steps

1. Build (incl. lint) + commit each result; confirm CI.
2. If the relay endpoint requires a signature the app does not send, have the integrations engineer adjust integrations/relay (small).
3. QA re-verification of fixed defects (D1 reboot, D2 release manifest, D3, D4, D5, D7, D8, posture) and scenarios 8/9 with the real companion against a local relay; update VALIDATION.md.
4. Final designer pass on ../daycue-review/app-3 if time allows.
5. Release: owner must create a keystore (docs/setup/BUILD.md) for a signed APK; until then attach a debug-signed APK to a GitHub pre-release and say so. Handle Dependabot PRs (#1 close; #2-#4 rebase/merge).
6. Deploy relay only after the owner picks hosting; then live checks with Claude Code / Claude.ai.
7. Owner bundle: hosting, Spotify, Firebase (optional), keystore, phone model + USB debugging for the physical checks in VALIDATION.md section 5.
