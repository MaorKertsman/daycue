# DayCue

Calm personal Android assistant for recurring habits and routines (working name). Personal-use product, **public repository**.

## Read first

- `docs/STATUS.md` — durable project notes: what is done, current decisions, exact next steps. **Resume from here after any interruption.**
- `docs/ARCHITECTURE.md` — architecture, module boundaries, contracts, decision records (`docs/adr/`).
- `docs/PRODUCT.md` — behavior spec (reminder semantics, defaults, policies).
- `docs/BACKLOG.md` — prioritized work. `docs/VALIDATION.md` — what was verified and how.
- `docs/design/` — UX and visual specs.

## Layout

| Path | What | Stack |
|---|---|---|
| `android/domain` | Pure rules engine, config model, validation. No Android imports. | Kotlin/JVM |
| `android/app` | App: Compose UI, Room, alarms, notifications, TTS, location, integrations | Kotlin, Compose |
| `companion/` | Windows tray app reporting active/idle/locked only | .NET |
| `mcp/` | MCP server (stdio + remote HTTP) and relay | TypeScript |
| `.claude/agents/` | Team role definitions | |

## Rules

- Communicate with the owner in English; code, docs and agent instructions are in English. The app itself supports Hebrew (RTL) and English.
- **Never commit personal data**: medication names, places/coordinates, calendar contents, history, tokens, pairing secrets, keystores, screenshots of real data. Synthetic demo data only. Check `git diff --cached` before every push.
- The phone owns its schedule. Core reminders must work with no network, no desktop, no LLM.
- Deterministic rules engine; all time logic goes through the `Clock` abstraction and is unit-tested on the JVM.
- Notification dismissal is never an acknowledgement. Medication reminders are never suppressed by context.
- All config edits (UI, MCP, import) go through the same `ConfigOp` validation path in `android/domain`.
- Never claim something works without evidence; label it unit / emulator / physical-device / unverified.

## Toolchain (this machine, not on PATH)

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\dev-tools\jdk21"
$env:ANDROID_HOME = "$env:USERPROFILE\dev-tools\android-sdk"
cd android; .\gradlew.bat :domain:test :app:assembleDebug
& "$env:USERPROFILE\dev-tools\dotnet\dotnet.exe" build companion
cd mcp; npm test
```
