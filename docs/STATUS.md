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

## In progress

- A3 product spec, A4 visual direction, A5 Android scaffold (three agents running).

## Next steps

1. Review A3/A4/A5 outputs; commit; create the public GitHub repo (after a staged-content check).
2. Launch: scheduling-engineer (B1–B5 domain), ux-designer (A6), release-engineer (A8 CI).
3. Then UI engineer on design system + screens; integrations research prototype for relay/MCP.
