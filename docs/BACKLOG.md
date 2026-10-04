# Backlog (prioritized)

Status keys: `[ ]` todo, `[~]` in progress, `[x]` done (evidence in `docs/VALIDATION.md`).
Owner = agent role in `.claude/agents/`. The delivery lead integrates and commits.

## Milestone A — foundations and feasibility

- [x] A1 Verify model, environment, GitHub auth; install toolchain (lead)
- [x] A2 Agent definitions, repo skeleton, architecture baseline (lead)
- [~] A3 Behavior spec `docs/PRODUCT.md` (product-lead)
- [~] A4 Visual direction `docs/design/VISUAL.md` (visual-designer)
- [~] A5 Android scaffold builds; version verification; feasibility matrix (android-architect)
- [ ] A6 UX spec `docs/design/UX.md`: navigation, onboarding, daily screen, editors (ux-designer; needs A3, A4)
- [ ] A7 Risk prototypes: exact alarm + background TTS; geofence latency notes; Spotify locked-phone; relay delivery (scheduling-engineer, integrations-engineer)
- [ ] A8 Public GitHub repo + CI (release-engineer; needs A5)

## Milestone B — usable offline application (must produce an APK)

- [ ] B1 Domain: config model, `ConfigOp` validation, undo history (scheduling-engineer; needs A3)
- [ ] B2 Domain: engine reducer — interval habits (sunscreen, hydration), snooze/pause, quiet hours, collision policy
- [ ] B3 Domain: medication schedules, history, travel policy
- [ ] B4 Domain: posture cycle; routine runs with timing + recovery policies
- [ ] B5 Domain: context inference from manual overrides (signals model ready for C)
- [ ] B6 App: Room persistence, alarm adapter, receivers (boot/time/tz), notification channels + actions
- [ ] B7 App: TTS speech queue with audio focus, sounds, vibration, cue preview
- [ ] B8 App: design system + screens — daily, habits, medication, posture, routines (editor + playback), cues, readiness, onboarding, settings (android-ui-engineer; needs A4, A6)
- [ ] B9 Export/import/backup of configuration
- [ ] B10 QA pass on scenarios 1–7, 12, 13, 17, 18; designer screenshot review

## Milestone C — context and integrations

- [ ] C1 Saved places, geofencing, automatic context rules, "Leaving now"
- [ ] C2 Windows companion (tray, idle/lock/sleep signal, pairing)
- [ ] C3 Relay + MCP server (stdio and remote HTTP with OAuth), phone sync + ack, audit, undo
- [ ] C4 Google Calendar read-only sync, rules, preview, cues
- [ ] C5 Morning alarms with Spotify attempt + local fallback
- [ ] C6 QA pass on scenarios 8–11, 14–16

- [ ] C7 Relay follow-ups: per-companion signal slot (today one `signal/latest`), companion self-revoke endpoint, add `awaiting_confirmation` to the architecture state list, align calendar op names with `DOMAIN.md`, verify `PgStore`
- [ ] C8 Phone side of the relay: pairing, Keystore-signed acks, command dedupe, snapshot publishing, companion signal verification

## Milestone D — complete delivery

- [ ] D1 Integrated behavior testing (emulator; physical-device checklist)
- [ ] D2 Design review of real screenshots; corrections
- [ ] D3 Security and privacy review; fixes
- [ ] D4 Release signing, signed APK, GitHub release, CI green
- [ ] D5 Docs: setup, integrations, Hebrew quick start, validation report, limitations

## Needs the owner (non-blocking; bundled)

- Firebase project (free) for push wake of remote commands/companion signals — optional
- Relay hosting account (free tier) for remote MCP — optional
- Google Cloud OAuth client for Calendar; Spotify developer app client ID
- Physical device: model/Android version; USB debugging for device verification
