# Validation report

Independent QA of DayCue against `docs/ACCEPTANCE.md` (18 scenarios) and the rule IDs in `docs/PRODUCT.md`. Expectations were derived from those documents, not from the implementation or the implementers' claims. Evidence labels: **unit** (JVM), **JVM-integration**, **emulator-UI** (real app UI driven by `input tap` and `uiautomator dump`), **emulator-debug** (debug broadcast receiver and state dumps), **unverified**. Nothing here was run on a physical phone.

Screenshots and the APKs are in `C:\Users\maork\my-stuff\daycue-review\qa\` (outside the repository; synthetic data only).

## 1. Environment

| Item | Value |
|---|---|
| Date | 2026-10-05 (host clock) |
| Commit | `afb71f7` at the start, plus the other engineer's uncommitted `android/app` UI edits (APK built 09:28 local from that working tree). HEAD moved to `14b1fce` during the session; the APK was not rebuilt. |
| Emulator | AVD `daycue_qa`, pixel_6, system image `android-37.0;google_apis;x86_64` (Android 17, build `CE2A.260420.019`, userdebug), headless, port 5562 |
| Test APK | `app-debug.apk` built once with `:app:assembleDebug`. Release check: `:app:assembleRelease` (debug-signed locally, no release keystore). |
| Emulator clock | Deliberately moved with `adb root` + `date` and the time zone with `service call alarm 3` (Asia/Tokyo and back to GMT). All "time passes" evidence is a clock jump plus the app's real alarms; it is not Doze over hours. |
| Local relay | `npm`/`tsx` relay on port 8799 (owner secret generated with `npm run gen-secret`), reached through `adb reverse`. Stopped and its data and secret deleted. |
| Cleanup | Emulator killed; font scale, auto time, time zone, app locale, `adb reverse` reset before shutdown. |

Automated suites run at the start (all green): domain 131 tests, app unit 198 tests, mcp 103 tests (Node 20.20), companion 42 tests, instrumented 3 pass + 1 skipped (`RelayEmulatorTest.keystoreKeySignsAcksTheRealRelayAccepts` skips without relay arguments; the same signing path was exercised live in scenario 15). QA-added tests: `android/domain/src/test/kotlin/app/daycue/domain/qa/QaAcceptanceTest.kt` (7 tests) and `mcp/test/qa-offline.test.ts` (3 tests), all pass.

## 2. Summary

**PASS 10, PARTIAL 7, FAIL 1, NOT VERIFIED 0.**

| # | Scenario | Status | Method |
|---|---|---|---|
| 1 | Applied sunscreen schedules the next reminder | PASS | emulator-UI (notification action), clock jump; unit |
| 2 | Outdoor / indoor / outdoor keeps history, no duplicate timers | PASS | emulator-UI (context sheet) + engine QA test |
| 3 | Posture pause/resume position | PASS | emulator-UI + engine QA test (cold-start "0:00" not reproduced) |
| 4 | Dismissal does not mark medication taken | PASS | emulator-UI (swipe away, Taken button), unit |
| 5 | Routine follows timing policy, survives process recreation | PARTIAL | emulator-UI (built in editor, run, `kill -9`); two recovery options and one policy combination only unit-tested |
| 6 | Manual context with location permission denied | PASS | emulator-UI with every location permission revoked |
| 7 | Boundary movement / stale signals | PARTIAL | unit only (no real geofence) |
| 8 | Computer activity starts a session only under the rules | PARTIAL | unit + companion tests; no live companion run |
| 9 | Locked / disconnected computer | PARTIAL | unit + companion tests; no live companion run |
| 10 | Matching calendar events cue, excluded do not | PARTIAL | unit / JVM only; no emulator calendar data |
| 11 | Changed or canceled event updates or removes the cue | PARTIAL | unit / JVM only |
| 12 | Simultaneous cues follow the collision policy | PASS | emulator-debug (real delivery logs), unit |
| 13 | Core reminders work offline | PASS | emulator (airplane mode) |
| 14 | Local alarm works when Spotify fails | PASS | emulator-UI (ringing screen captured); audibility is manual |
| 15 | MCP changes validated, applied once, acknowledged | PASS | emulator-UI against a real local relay; JVM tests |
| 16 | Offline MCP commands stay pending | PASS | emulator + real local relay; JVM tests |
| 17 | Reboot and time zone recovery policy | **FAIL** | emulator (real reboot, time zone, clock changes); defect D1 |
| 18 | Hebrew RTL, large text, accessibility | PARTIAL | emulator-UI (Hebrew, font scale 2.0, dump audits); no TalkBack run; defects D5 |

## 3. Defects

| ID | Severity | Rule | Summary |
|---|---|---|---|
| D1 | High | MED-8, MED-4, GEN-7 | After a real reboot the pending medication doses (and other cues) are not re-surfaced as notifications |
| D2 | Medium | owner requirement | The release APK still contains the debug receiver |
| D3 | Low-Medium | usability of MED-9/import review | Import review shows raw JSON for added items |
| D4 | Low | import validation | Import accepts `formatVersion: 2` |
| D5 | Low-Medium | accessibility | Weekday chips are single letters with no content description; drag handles not verifiable |
| D6 | Medium (physical check) | ALM-1 | Android 17 logged "AudioHardening background playback would be muted" for the alarm stream |
| D7 | Low | ALM-1 UX | Alarm screen does not open while DayCue is in the foreground |
| D8 | Low | consistency | Config `language` (ConfigOp `setLanguage`) does not change the UI language |

**D1. Reboot does not re-post pending medication cues (MED-8). High.**
Steps: synthetic doses `loc1`, `loc2`, `home1` due and unacknowledged, plus sunscreen and hydration cues showing. `adb reboot`, wait for `BOOT_COMPLETED` (it arrived about 90 s after boot) with the clock set to the pre-reboot time.
Expected: MED-8 "same as MED-7 merged cue for today's `Due` slots"; MED-4 "the home screen shows it until confirmed"; GEN-7 anything due is delivered at most once, merged.
Actual: `dumpsys notification` lists zero `app.daycue` notifications after boot (a reboot clears them). The engine log shows `reduce BootCompleted -> 1 effects` (re-arm only), then `-> 0 effects`; `dump` still reports `visible=[habit:sunscreen, med:merged, med:policy, med:loc1|..., med:loc2|..., habit:hydration, med:home1|...]`, so the engine believes the cues are on screen and posts nothing. The Today screen still shows the doses as Due, but nothing alerts. Armed alarms did survive (sunscreen repeat fired on time after boot).
Evidence: `pre-reboot.txt` (scratch), logcat lines `11:11:57 reduce BootCompleted -> 1 effects ... reduce BootCompleted -> 0 effects`. Caveat: the emulator clock reverted to the host date at boot and was set forward before `BOOT_COMPLETED`, so the clock path is not natural; re-test on a phone (section 5).
Suggested fix direction (not applied): on `BootCompleted` treat all previously "visible" notifications as gone, then re-deliver Due medication slots as one merged cue.

**D2. Debug receiver in the release APK. Medium.**
Expected: public release has no debug receiver. Actual: the release manifest (`relmanifest.txt`) declares `app.daycue.devtools.DevToolsReceiver` (`exported=true`, `enabled=false`, permission `android.permission.DUMP`) and the class is in the dex. It is inert: on an installed release APK `am broadcast -n app.daycue/.devtools.DevToolsReceiver -a app.daycue.devtools.CMD --es cmd dump|demo_med` produced no log output and no state change, and `dumpsys package` lists it under `disabledComponents`. The gallery activity is absent, `debuggable` is absent, there is no `usesCleartextTraffic` or network security config, `allowBackup=false`. Residual risk is low, but the receiver and its engine driver should be debug-source-set only.

**D3. Import review shows raw JSON.** In "Import setup" every added item is listed as `medications[med-a]: added {"id":"med-a","label":...,"travelPolicy":{"type":"followLocalTime"},...}` (screenshot `s52-import-review.png`). Not readable for the owner; this is the review step MED-9 relies on. The raw JSON also contains medication names, which the screen does warn about.

**D4. `formatVersion` is not validated.** A backup with `formatVersion: 2` and `schemaVersion: 1` is accepted and offered for apply (`s51-import-bad-newer-format.png`). Only `config.schemaVersion` is checked (a `schemaVersion: 99` file correctly fails with "This setup is from a newer DayCue").

**D5. Accessibility.**
(a) Settings work-day chips are "S M T W T F S": the chip nodes have no content description, so Tuesday/Thursday and Saturday/Sunday are indistinguishable when read out (Hebrew letters are distinct). Not checked on every screen.
(b) Reorder rows expose a `Drag handle` node that is neither clickable nor focusable in the dump; whether a non-drag alternative exists (More actions, custom accessibility actions) cannot be seen with `uiautomator`. NOT VERIFIED, needs TalkBack.
Sampled screens (Today, Cues, Sunscreen editor, Posture, Medication, Alarms, Setup, Settings, Hebrew + font scale 2.0) had 0 unlabeled clickables and 0 clickables under 48 dp, apart from rows partly scrolled behind the bottom bar.

**D6. Possible muted alarm on Android 17.** While the alarm rang, `dumpsys audio` logged `AudioHardening background playback would be muted for app.daycue (10234), level: full, reason: 4, usage: USAGE_ALARM`. On the emulator the player state stayed `started` with `mutedState:none`, so nothing was muted here. If a real device enforces it, the morning alarm would be silent when started from the background. Must be checked on a phone first (section 5).

**D7.** With DayCue in the foreground the snoozed alarm rang (service, tone) but no alarm screen opened; only the heads-up/notification Stop and Snooze buttons exist. With the screen off the full-screen alarm screen did open (`s20-alarm-ringing.png`).

**D8.** `ops` with `setLanguage he` changed `config.settings.language` but the UI stayed English until the per-app locale was set (`cmd locale set-app-locales`); onboarding and Settings use the per-app locale. Probably by design (speech language vs UI language) but an MCP/import language change will not translate the UI.

Spec notes (not defects): a manual posture pause longer than `shortInterruption` (15 min) resets to the first mode with full time (POS-6, ResetToFirst); a 10 min pause continues the remaining time. The owner should confirm this is wanted for a user-initiated pause (PRODUCT open question 6). After a one-day clock jump forward the missed previous-day doses are shown "Not confirmed" without a cue; this matches MED-1 but the owner may expect a merged reminder.

## 4. Per-scenario detail

Common: all UI runs are on a fresh install (config v0: sunscreen, hydration, bottle, posture, routine, alarm all disabled; medication list empty; places have no coordinates), then onboarding switched templates on through the UI.

**1. Acknowledged sunscreen schedules the next reminder (SUN-2).** Method emulator-UI. Onboarding enabled Sunscreen; "I'm outdoors" on the Today context sheet; clock set to 12:00 (active hours 07-19); cue delivered. Pulled down the shade, expanded the group, tapped "Applied". `dump`: `interval sunscreen lastAck=12:01:48 due=14:01:48 cue=null`. Clock set to 14:02: one new cue `habit:sunscreen#5` delivered, repeat armed at +20 min. PASS.

**2. Outdoor/indoor/outdoor keeps history, no duplicate timer (SUN-6, SUN-7, GEN-2).** Method emulator-UI + unit. After the ack above: context sheet Indoors, then Outdoors again. `lastAck=12:01:48` unchanged, one upcoming entry `habit:sunscreen waiting=CoveredUntil 14:01:48`, one `RTC_WAKEUP` alarm for the app, exactly one cue at 14:01. QA test `scenario 2 - outdoor, long indoor visit...` (indoor 30 min) passes. PASS.

**3. Posture pause/resume (POS-1, POS-4, POS-6).** Method emulator-UI + unit. Started a Working session (context sheet), opened Posture live control: `Sitting 29:32 left`; Pause -> `Paused by you 29:25`; clock +10 min; Resume -> `29:22 left`, mode Sitting. Header on Today showed matching minutes left through four cold starts (about 20 samples) and one cold start straight into the live control (`21:14 left`, correct). The implementer's "0:00 left while Running right after a cold start" was NOT reproduced; no evidence either way for a reboot cold start. QA test covers short pause (continue) and long pause (ResetToFirst). PASS.

**4. Dismissal is not an ack (GEN-1, MED-2, MED-4).** Method emulator-UI. Synthetic dose due; notification swiped away in the shade: slot stays `Due`, notification re-posted at the +10 min repeat (clock jump), Today shows "Due now" with "Taken"; tapping Taken -> `Taken`, notification gone. Unit QA test too. PASS.

**5. Routine timing and process recreation (RTN-4..7).** Method emulator-UI. Built "QA_Routine" in the editor (3 steps: two timed 1 min, one "When I tap Done"); the editor explains both timing policies in plain words. Run 1 (FollowActualCompletion): started with "Start now", `kill -9` mid step 1, clock +15 min (> 10 min threshold): on process recreation exactly one cue, silent, "QA_Routine paused at: StepA / Resume, restart the routine, or cancel" with Resume / Restart / Cancel buttons; no step-by-step catch-up; tapping Resume restarted StepA with a cue. Run 2 (changed to "Keep to the schedule"): killed at 20 s, clock +4 min (< threshold): jumped to StepC with one cue and no cues for StepA/StepB (RTN-5). PARTIAL: `ResumeCurrentStep` and `Cancel` recovery policies, FollowSchedule beyond the threshold, and the AfterAlarm/Schedule triggers were not driven in the UI (covered by `RoutineTest`, `RecoveryAndPropertyTest`).

**6. Manual context without location permission.** Method emulator-UI. `pm revoke` of fine, coarse, background location, activity recognition and calendar; Today context sheet Outdoors -> `environment Outdoor/High/Manual`; sunscreen cue followed. PASS. (Setting a place's coordinates with the permission denied was not tried.)

**7. Boundary movement and stale signals (CTX-2, CTX-3).** Unit only (`QaAcceptanceTest`, `ContextTest`): 12 exit/enter jitters leave `place.since` unchanged. No real geofence on the emulator. PARTIAL.

**8, 9. Computer activity (WRK-1..8).** Unit (`ContextAndCollisionTest` acceptance 8 and 9) and companion tests (42 pass). The companion was not run against the relay (the local relay was not wired to a companion process; `--exit-after` run not attempted). PARTIAL. Manual steps in section 5.

**10, 11. Calendar (CAL-1..5).** Unit (`CalendarTest`, `CalendarSyncTest`, `CalendarMapperTest`). The emulator has no calendar account, so no provider data was created; `READ_CALENDAR` permission flow and Calendar Provider reads are unverified. PARTIAL.

**12. Collisions (COL-1..3, MED-3, QH-1, QH-3).** Method emulator-debug. Two medications at 14:42 plus a posture cue: one `RingtonePlayer` sound and one utterance, `med-a` audible lead, `med-b` and posture `silent=true` members, all three notifications present (no member dropped). Clock to 23:00 with quiet hours 22:30-07:00: two medications delivered (DeliverNormally audible with speech, DeliverSilently silent), `habit:sunscreen`/hydration held. QA unit test asserts the same. PASS. Whether the single utterance names the members ("Also: ...") was not checked on the emulator.

**13. Offline.** Method emulator. Airplane mode on (`ping: Network is unreachable`), clock to the due time: routine nudge, sunscreen (sound + speech) and hydration delivered locally. PASS.

**14. Alarm when Spotify fails (ALM-1, ALM-2, ALM-5).** Method emulator-UI. One-off alarm with a Spotify item (`spotify:playlist:synthetic`), screen off. At 10:02:00 `alarm ringing qa-alarm ... music=false`, foreground service up, `MediaPlayer` `USAGE_ALARM` `state:started`, alarm screen over the screen-off device: "Spotify is not installed, so the alarm tone is playing." with an "Install Spotify" button, Snooze and Stop (`s20-alarm-ringing.png`). Snooze 2 min re-rang at 10:04:42; Stop from the notification ended the service and audio and recorded `handled=2026-10-07`. The Spotify SDK is not bundled; the local tone path works. Full-screen-intent: `appops` was `allow` on this install; setting `deny` did not stop the screen from opening, so the denied-by-default state could not be reproduced here. PASS for the local path; see D6, D7 and section 5 for audibility, lock screen and real Spotify.

**15. MCP changes validated, applied once, acknowledged.** Method emulator-UI + live local relay. Paired through Setup > Integrations > Remote access with a one-time code over `adb reverse` (debug build only; cleartext is refused in release). A client token with `config:write` was inactive ("insufficient_scope") until approval: the Review screen says "A tap does nothing", a long press approved. `apply_change` (via `/v1/client/submit`) `setHabitInterval sunscreen 90` with `baseVersion 1`: relay returned `applied` ("signed acknowledgement", version 2), phone config interval 90; resubmitting the same idempotency key returned the original command with `deduplicated`, version stayed 2. PASS (JVM suites cover conflicts, expiry, redaction).

**16. Offline commands stay pending.** Method emulator + live relay. With the phone unable to reach the relay (`adb reverse --remove` plus airplane mode) a new command stayed `queued`, `applied:false`, "NOT APPLIED... phone last contacted the relay 32s ago" and the phone showed "Offline. Will send when online"; after restoring the tunnel and "Sync now" it became `applied` (version 3). QA test `qa-offline.test.ts` adds expiry. PASS.

**17. Reboot and time zone (MED-6..8, ALM-6, GEN-7).** Method emulator.
- Time zone Asia/Tokyo with `FollowLocalTime` x5 and `KeepHomeTimezone(UTC)` x1: one merged cue "5 medication reminders not confirmed", a "Time zone changed" notice "5 follow local time, 1 keep home time", home dose still Upcoming (18:00 JST). Yesterday's unconfirmed slots show as "Not confirmed". PASS.
- Clock backwards by 2 h: `reduce TimeChanged -> 0 effects`, nothing replayed. Clock forward by 1 day 2 h: the two current-day doses delivered once as one group, no per-slot replay of the missed ones. PASS.
- Real reboot: alarms re-armed, sunscreen repeat fired on time, no duplicate delivery; but see D1 (pending medication not re-surfaced). FAIL overall. Alarm ALM-6 after reboot not run on the emulator (covered by `CalendarAndAlarmTest`).

**18. Hebrew RTL, large text, accessibility.** Method emulator-UI. Hebrew (`cmd locale set-app-locales app.daycue --locales he`): Today, Cues list, Setup, Sunscreen editor mirrored correctly (titles, chevrons, tab order היום/תזכורות/הגדרות, week starts Sunday; screenshots `s44`, `s45`, `s47`); "Loc one" (user text) stays Latin and right-aligned. Font scale 2.0 + Hebrew: Today and the Sunscreen editor wrap, nothing clipped, buttons remain full width (`s46`, `s47`). Dump audits (clickables, content descriptions, size under 126 px = 48 dp): 0 unlabeled and 0 undersized on the sampled screens (Today, Cues, editors, Posture, Medication, Alarms, Setup, Settings, Remote confirm). Hebrew onboarding and every editor were not walked; TalkBack was not run (D5). The earlier-seen toggle knob positions in RTL are mirrored consistently with `checked`. PARTIAL.

**Fresh install / release.** Fresh debug install: config v0, three habits, posture, routine, alarm all `enabled:false`, `medications: 0`, three places with `center:null`; the only coordinate-like literal in source is in a synthetic test. Release APK (`:app:assembleRelease`): no `debuggable`, no `usesCleartextTraffic`/network security config, `allowBackup=false`, no gallery activity; D2 for the receiver.

**Export, wipe, import.** Setup > Settings > "Save setup as a file" (system picker, `daycue-setup-2026-10-07.daycue-backup.json`, no history). `pm clear`, onboarding "Skip" through, config back to v0. Import of that file: review screen lists 15 changes, an Apply confirmation, and the restored config equals the exported one in every top-level key (only `version` differs). Malformed files: garbage text and wrong `format` -> "This file isn't a DayCue setup", nothing imported; `schemaVersion: 99` -> "This setup is from a newer DayCue ... Nothing was imported"; an out-of-range value -> "Some items in this file are not valid, so nothing can be imported" with the field path; `formatVersion: 2` is accepted (D4).

**Negative security checks (shell uid 2000, debug build, a medication Due with its notification showing).**

| Attempt | Result |
|---|---|
| `am broadcast -n .../actions.CueActionReceiver` and implicit `app.daycue.action.CUE` with `kind=taken` | no effect: dose stays `Due`, notification still posted (receiver not exported) |
| `am start -n .../integrations.relay.RemoteConfirmActivity` | `SecurityException` (not exported) |
| `am broadcast -n .../integrations.relay.RemoteActionReceiver`, `.../scheduling.WakeAlarmReceiver` | no effect |
| `am start -n .../delivery.AlarmActivity`, `am start-foreground-service .../AlarmRingingService` | denied (`not exported` / `Requires permission not exported`) |
| `am start -a VIEW -d daycue://open/...` (implicit and to MainActivity) | implicit: unresolvable; explicit MainActivity: only navigates, dose still `Due` |
| `am start -a VIEW -d daycue://remote/approve?id=1` | unresolvable, nothing approved |
| Approving a remote grant by tapping | does nothing; only press-and-hold approves |

Exported components in the release manifest: `MainActivity`, `SystemEventsReceiver`, `LockedBootReceiver`, `LocationSystemReceiver` (system actions only) and the disabled `DevToolsReceiver` (D2).

## 5. Not verified / needs a physical phone (manual steps for the owner)

Do these on the real phone with the release build (debug-signed or your own keystore). Record the result next to each item.

1. **Audibility of sounds and speech (SPK-1..4, GEN-4).** Enable Hydration, set interval to the minimum, wait for the cue: confirm the sound, then the spoken phrase (English, then Hebrew; install the Hebrew voice data first if Readiness says Voices is Limited). Play music, repeat: confirm the music ducks and restores. Repeat with headphones and with "headphones only".
2. **Vibration.** Each cue profile "Preview" (Setup > Sounds and speech) and one real cue of each type; confirm the distinct patterns.
3. **Morning alarm, lock screen, audio hardening (D6).** Create an alarm 2 minutes ahead, lock the phone with a PIN, put it down. Confirm: the alarm rings at full volume at the right time from the background, the full-screen screen shows over the lock screen (grant Full-screen intent in Readiness first), Snooze/Stop work, volume ramp. Repeat with Do Not Disturb on and after a reboot. Repeat with the app removed from recents.
4. **Spotify (ALM-2, SPOTIFY.md).** Needs the Spotify App Remote AAR added locally and your account: with Spotify installed, signed in, and a Premium account, set a playlist item; confirm Spotify starts within 10 s and the tone goes quiet; then sign out, airplane mode, and Spotify force-stopped: confirm the tone keeps ringing and the screen explains why. Note Spotify's developer policy on alarm functionality.
5. **Real geofence latency (CTX-2/3, BTL-2, scenario 7).** Save a place with your real location (keep it out of the repo), grant location "all the time", walk or drive away and back; note the minutes until Today shows the place change and until a "water bottle" cue on leaving. Stand near the boundary for 15 minutes and confirm no repeated transitions. Repeat with battery saver on.
6. **Doze and standby over hours (GEN-6, GEN-7).** Leave the phone idle overnight with Sunscreen/Hydration enabled, a medication at a fixed time and an alarm: confirm the medication and alarm are on time (exact alarms), others merely late, and nothing fires in a burst in the morning.
7. **OEM battery restrictions.** On Samsung/Xiaomi/Oppo etc. follow the app's Reminder readiness fixes; confirm cues survive swipe-from-recents and a reboot.
8. **Reboot recovery (D1).** With one medication due and unacknowledged and Sunscreen showing, reboot the phone: confirm a medication notification returns after boot. Then switch the phone time zone with two medications on `FollowLocalTime` and one on a fixed home zone.
9. **Real companion with a phone (scenarios 8 and 9).** Run the published companion against your relay (`docs/setup/COMPANION.md`), pair it with the phone's code, set a work place and permitted hours. Confirm: a session starts only at that place, in those hours, after 5 minutes active; locking the PC pauses after 2 minutes; closing the lid or quitting the companion makes the phone say "computer unknown" within about 10 minutes and the session ends by the documented timing, with posture frozen meanwhile.
10. **Calendar (scenarios 10 and 11).** Grant calendar access, select a calendar, create synthetic events (a meeting with an attendee, a "dentist appointment", a free event, a declined one). Confirm the preview screen decisions and the cues; then move one event and cancel another and confirm the cue moves/disappears within the sync window.
11. **Real Claude / ChatGPT connector (scenarios 15 and 16).** Deploy the relay over HTTPS (`docs/setup/MCP.md`), pair the phone, add the connector in Claude.ai or ChatGPT, approve the grant on the phone with press-and-hold, ask for a harmless change (sunscreen interval), confirm it applies once on the phone, appears in the audit log, and a sensitive change (alarm time) waits for confirmation. Turn the phone off or into airplane mode and send another: it must read as pending, never "done".
12. **TalkBack (scenario 18, D5).** Walk onboarding, Today, one editor from each tab, the alarm screen and the remote-approval screen with TalkBack on in Hebrew and English; confirm every control has a spoken name and role, weekday chips are distinguishable, routine/posture steps can be reordered without dragging, and the hold-to-approve action is reachable through the TalkBack actions menu.
13. **Notification tap routing.** Tap each notification body type and confirm it opens the matching screen (the shell test with an explicit `daycue://open/posture` intent only showed the Cues/Today screen, so the intended notification path was not exercised).
14. **Cold-start live posture control.** Repeat a few cold starts (and one after a reboot) directly into Posture live control to look for the "0:00 left while Running" display that an implementer saw.
