# Acceptance scenarios

From the owner's brief. QA verifies each and records the method (unit / emulator / physical / unverified) in `docs/VALIDATION.md`.

| # | Scenario |
|---|---|
| 1 | An acknowledged sunscreen application schedules the next configured reminder. |
| 2 | Leaving and re-entering outdoor contexts preserves history without duplicating timers. |
| 3 | A posture cycle pauses and resumes at the correct position. |
| 4 | Notification dismissal does not mark medication as taken. |
| 5 | A routine follows the selected timing policy and survives process recreation. |
| 6 | Manual context works when location permission is denied. |
| 7 | Boundary movement and stale signals do not cause repeated false transitions. |
| 8 | Computer activity activates a session only under the configured rules. |
| 9 | A locked or disconnected computer is handled without silently assuming continued work. |
| 10 | Matching calendar events receive cues while excluded events do not. |
| 11 | A changed or canceled calendar event updates or removes its pending cue. |
| 12 | Simultaneous cues follow the collision policy without losing important reminders. |
| 13 | Core reminders work offline. |
| 14 | The local morning alarm works when Spotify fails. |
| 15 | MCP changes are validated, applied once, and acknowledged by the phone. |
| 16 | Offline MCP commands remain pending rather than falsely reporting success. |
| 17 | Reboot and timezone changes follow the documented recovery policy. |
| 18 | Hebrew RTL, large text, and accessibility flows remain usable. |

Use the controllable clock and synthetic context inputs for scheduling and inference. Claims about background behavior, audible cues, vibration, geofencing and Spotify need a physical device; otherwise mark them unverified and list the exact remaining test steps.
