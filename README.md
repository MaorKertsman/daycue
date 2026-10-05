# DayCue

DayCue is a personal Android assistant for recurring habits and routines: sunscreen, water, a water-bottle check when leaving a place, posture cycles, medication reminders, a morning routine, a morning alarm, and cues before calendar events. It is a personal-use project in a public repository. The app has English and Hebrew (right-to-left) interfaces.

**Status: nothing has been verified on a physical phone.** Everything below was checked with JVM unit tests and an Android 17 emulator only. There is no signed release APK yet.

## What the phone does on its own

The core reminders run on the phone with no network, no PC and no account. Each one is off until you turn it on (onboarding or the Cues tab). Defaults come from `docs/PRODUCT.md`.

- **Sunscreen**: every 2 hours between 07:00 and 19:00 while DayCue believes you are outdoors.
- **Hydration**: every 60 minutes between 09:00 and 21:00.
- **Water bottle**: a cue when you leave a place, or when you tap **Leaving now**.
- **Posture cycle**: sitting, standing and walking, 30 minutes each, with switch, snooze and extend controls.
- **Medication**: the list starts empty. You set the times, days and a travel-time policy. Reminders repeat until you confirm; dismissing a notification never counts as taking a dose. DayCue gives no medical advice.
- **Morning routine**: a step-by-step routine (shower, face cleanser, brush teeth, get dressed) that you edit, test and start by hand.
- **Morning alarm**: 07:00 on your work days. The local tone always rings.
- **Today tab**: what is due, what is running, what is next, plus quick controls (Outdoors, Indoors, Start working, Leaving now, pause automatic detection).
- **History**: each reminder, confirmation, snooze and dismissal is recorded and shown per item.
- **Backup**: Setup, Settings, **Save setup as a file** writes a `*.daycue-backup.json` file; **Import a setup file** shows every change before applying it.

## Optional integrations and their real state

| Component | State | How verified |
|---|---|---|
| Calendar cues (reads the phone's calendars, read-only; no sign-in) | Built | Unit tests; on the emulator with a local test calendar (cue at the lead time, move, cancel). Not tried with a real synced account |
| Place detection (Android geofences) | Built; latency not measured | Unit tests; emulator receives enter/exit events. Real latency on a phone is unverified |
| Walking detection (activity recognition) | Built | Unit tests; registration seen on the emulator. Walking cannot be simulated there |
| Windows companion (reports active, idle, locked or asleep) | Built | Unit tests (42 in the last QA run); not run live with a phone |
| Remote access relay and MCP server (for Claude or ChatGPT) | Built, **not deployed**; hosting is the owner's pending decision | 112 relay tests (`npm test` in `mcp/`, run for this document); QA drove a local relay from the emulator. No real Claude.ai, ChatGPT, Render or FCM run |
| Spotify alarm | Off by default; Spotify's developer policy forbids alarm functionality without written approval | Local tone fallback shown on the emulator. The Spotify SDK binary is not in the repo; never run against a real Spotify account |
| Hebrew interface and speech | Built | Emulator screens in Hebrew at 2.0 font scale. Hebrew speech needs a voice installed (see `docs/setup/INSTALL.md`). TalkBack not run |

Remote access details: a new remote client stays read-only until you approve it on the phone with a press-and-hold; sensitive changes also wait for press-and-hold confirmation. Nothing is applied remotely without the phone.

## Install and build

Requirements: Android 8 (API 26) or newer, a Windows PC with the toolchain in `docs/setup/BUILD.md`. To build a debug APK:

```powershell
$env:JAVA_HOME    = "$env:USERPROFILE\dev-tools\jdk21"
$env:ANDROID_HOME = "$env:USERPROFILE\dev-tools\android-sdk"
cd android
.\gradlew.bat :app:assembleDebug
```

Then follow `docs/setup/INSTALL.md`. If you later install a build signed with a different key, Android requires uninstalling first, which deletes app data: export your setup first.

## Privacy

- No account, no analytics, no ads. Places, coordinates, calendar titles, medication names, history and settings stay on the phone.
- Android cloud backup and device-to-device transfer are disabled (`allowBackup="false"` plus explicit exclusion rules in `res/xml/`).
- Coordinates never leave the phone through the relay (value-level redaction in the domain, covered by tests).
- Medication details leave the phone only when a remote connection holds the medication scope **and** you switched the medication setting on.
- A manual export file contains your whole configuration, including place coordinates and medication names. History is added only if you tick **Include history**. Store the file privately.
- The companion sends only a signed state (`active`, `idle`, `locked`, `asleep`) with timing, to your relay.

## Repository layout

```
android/domain/     Pure Kotlin rules engine and configuration model (no Android imports)
android/app/        Compose UI, Room, alarms, notifications, speech, location, integrations
mcp/                TypeScript MCP server and relay (optional)
companion/          .NET Windows tray app (optional)
docs/               Specs, status, validation, architecture, setup guides
.claude/agents/     Team role definitions
.github/workflows/  ci.yml, release.yml
```

## Documents

- `docs/setup/INSTALL.md`: build, install, permissions, backup, troubleshooting
- `docs/QUICKSTART.he.md`: Hebrew quick start
- `docs/LIMITATIONS.md`: platform limits, unverified items, features not built, open decisions
- `docs/setup/BUILD.md`: builds, tests, CI, release signing
- `docs/setup/MCP.md`, `docs/setup/COMPANION.md`, `docs/setup/SPOTIFY.md`: optional integrations
- `docs/PRODUCT.md` (behavior spec), `docs/STATUS.md`, `docs/VALIDATION.md`
- `docs/architecture/` and `docs/security/REVIEW-1.md`

## Fonts and licenses

The app bundles the Rubik and Frank Ruhl Libre fonts under the SIL Open Font License 1.1. License texts: `android/app/src/main/assets/licenses/` (`OFL-Rubik.txt`, `OFL-FrankRuhlLibre.txt`). The app's About screen lists these and the Apache 2.0 libraries it uses. Cue sounds are original and made for DayCue.
