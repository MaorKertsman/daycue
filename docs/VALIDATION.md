| PARTIAL: language call fixed; Hebrew first run and AM/PM not (D10, D11) |
| FIXED (foreground + screen off, Snooze and Stop) |
| not re-run (physical phone needed) |
| FIXED for chips, reorder and 48 dp; TalkBack still NOT VERIFIED |
| FIXED (formatVersion, schemaVersion, garbage) |
| FIXED (en + he) |
| FIXED (manifest + dex) |
| FIXED (emulator, real reboot) |
# Validation report

Independent QA of DayCue against `docs/ACCEPTANCE.md` (18 scenarios) and the rule IDs in `docs/PRODUCT.md`. Expectations were derived from those documents, not from the implementation or the implementers' claims. Evidence labels: **unit** (JVM), **JVM-integration**, **emulator-UI** (real app UI driven by `input tap` and `uiautomator dump`), **emulator-debug** (debug broadcast receiver and state dumps), **unverified**. Nothing here was run on a physical phone.

Screenshots and the APKs are in `..\daycue-review\qa\` (outside the repository; synthetic data only).

## 1. Environment

| Item | Value |
|---|---|
| Date | 2026-10-05 (host clock) |
| Commit | `afb71f7` at the start, plus the other engineer's uncommitted `android/app` UI edits (APK built 09:28 local from that working tree). HEAD moved to `14b1fce` during the session; the APK was not rebuilt. |
| Emulator | AVD `daycue_qa`, pixel_6, system image `android-37.0;google_apis;x86_64` (Android 17, build `CE2A.260420.019`, userdebug), headless, port 5562 |
| Test APK | `app-debug.apk` built once with `:app:assembleDebug`. Release check: `:app:assembleRelease` (debug-signed locally, no release keystore). |
| Emulator clock | Deliberately moved with `adb root` + `date` and the time zone with `service call alarm 3` (Asia/Tokyo and back to GMT). All "time passes" evidence is a clock jump plus the app's real alarms; it is not Doze over hours. |
| Local relay | `npm`/`tsx` relay on port 8799 (owner secret generated with `npm run gen-secret`), reached through `adb reverse`. Stopped and its data and secret deleted. |
| Re-verification | 2026-10-05, commit `1740ef9` (working tree clean). Debug and release APKs already built at HEAD (14:14 and 14:17 local; the commit at 14:19 is the polish they contain). Same AVD, port 5562, headless; real companion against a local relay (section 6). |
| Cleanup | Emulator killed; font scale, auto time, time zone, app locale, `adb reverse` reset before shutdown. |

Automated suites run at the start (all green): domain 131 tests, app unit 198 tests, mcp 103 tests (Node 20.20), companion 42 tests, instrumented 3 pass + 1 skipped (`RelayEmulatorTest.keystoreKeySignsAcksTheRealRelayAccepts` skips without relay arguments; the same signing path was exercised live in scenario 15). QA-added tests: `android/domain/src/test/kotlin/app/daycue/domain/qa/QaAcceptanceTest.kt` (7 tests) and `mcp/test/qa-offline.test.ts` (3 tests), all pass.

## 2. Summary

**First run: PASS 10, PARTIAL 7, FAIL 1. After re-verification (section 6, 2026-10-05, commit `1740ef9`): PASS 13, PARTIAL 5, FAIL 0, NOT VERIFIED 0.** The Status column below shows `first run -> re-verified` where it changed; every other row was not re-run.

| # | Scenario | Status | Method |
|---|---|---|---|
| 1 | Applied sunscreen schedules the next reminder | PASS | emulator-UI (notification action), clock jump; unit |
| 2 | Outdoor / indoor / outdoor keeps history, no duplicate timers | PASS | emulator-UI (context sheet) + engine QA test |
| 3 | Posture pause/resume position | PASS | emulator-UI + engine QA test (cold-start "0:00" not reproduced) |
| 4 | Dismissal does not mark medication taken | PASS | emulator-UI (swipe away, Taken button), unit |
| 5 | Routine follows timing policy, survives process recreation | PARTIAL -> **PASS** | emulator-UI (built in editor, run, `kill -9`); two recovery options and one policy combination only unit-tested |
| 6 | Manual context with location permission denied | PASS | emulator-UI with every location permission revoked |
| 7 | Boundary movement / stale signals | PARTIAL | unit only (no real geofence) |
| 8 | Computer activity starts a session only under the rules | PARTIAL -> **PASS** | unit + companion tests; no live companion run |
| 9 | Locked / disconnected computer | PARTIAL -> **PASS** (gone/stale; lock not driven) | unit + companion tests; no live companion run |
| 10 | Matching calendar events cue, excluded do not | PARTIAL | unit / JVM only; no emulator calendar data |
| 11 | Changed or canceled event updates or removes the cue | PARTIAL | unit / JVM only |
| 12 | Simultaneous cues follow the collision policy | PASS | emulator-debug (real delivery logs), unit |
| 13 | Core reminders work offline | PASS | emulator (airplane mode) |
| 14 | Local alarm works when Spotify fails | PASS | emulator-UI (ringing screen captured); audibility is manual |
| 15 | MCP changes validated, applied once, acknowledged | PASS | emulator-UI against a real local relay; JVM tests |
| 16 | Offline MCP commands stay pending | PASS | emulator + real local relay; JVM tests |
| 17 | Reboot and time zone recovery policy | **FAIL -> PARTIAL** (D1 fixed; MED-11 catch-up notice not seen, D9) | emulator (real reboot, time zone, clock changes); defect D1 |
| 18 | Hebrew RTL, large text, accessibility | PARTIAL | emulator-UI (Hebrew, font scale 2.0, dump audits); no TalkBack run; defects D5 |

## 3. Defects

| ID | Severity | Rule | Summary | Re-verification (2026-10-05, `1740ef9`) |
|---|---|---|---|---|
| D1 | High | MED-8, MED-4, GEN-7 | After a real reboot the pending medication doses (and other cues) are not re-surfaced as notifications | FIXED (emulator, real reboot) |
| D2 | Medium | owner requirement | The release APK still contains the debug receiver | FIXED (manifest + dex) |
| D3 | Low-Medium | usability of MED-9/import review | Import review shows raw JSON for added items | FIXED (en + he) |
| D4 | Low | import validation | Import accepts `formatVersion: 2` | FIXED (formatVersion, schemaVersion, garbage) |
| D5 | Low-Medium | accessibility | Weekday chips are single letters with no content description; drag handles not verifiable | FIXED for chips, reorder and 48 dp; TalkBack still NOT VERIFIED |
| D6 | Medium (physical check) | ALM-1 | Android 17 logged "AudioHardening background playback would be muted" for the alarm stream | not re-run (physical phone needed) |
| D7 | Low | ALM-1 UX | Alarm screen does not open while DayCue is in the foreground | FIXED (foreground and screen off; Snooze and Stop) |
| D8 | Low | consistency | Config `language` (ConfigOp `setLanguage`) does not change the UI language | PARTIAL: language call fixed; Hebrew first run and AM/PM not (D10, D11) |
| D9 | Medium | MED-11 | After a clock jump forward by 1 day or more, no merged catch-up notice is posted | open (new) |
| D10 | Low-Medium | Hebrew | Hebrew UI on a 12-hour device shows Latin AM/PM; Hebrew first run seeds Mon-Fri work days (not Sun-Thu) and, if Hebrew is tapped in onboarding, English template names | open (new) |
| D11 | Low | GEN-9 | Medication has no "Why now?" on Today or in the dose sheet | open (new) |
| D12 | Low | design | Alarm screen: thin illustration strip at font scale 2.0, large empty bands, heads-up notification covers the time in the foreground | open (new) |
| D13 | Low | copy | Hebrew "19 שעות דקה" (missing minutes number); English "in 0 minutes" | open (new) |

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

## 6. Re-verification (2026-10-05, commit `1740ef9`)

Evidence in `..\daycue-review\qa-2\` (screenshots `s01..s30`, UI dumps `*.xml`, `rel-manifest.txt`, `rel-dex-classes.txt`, `d1-pre/post-*.txt`, `relay.log`). Method for all rows: **emulator** (real app, debug build unless noted; `adb`, uiautomator, logcat, debug receiver for state dumps and demo config). No physical phone.

| # | Probe | Result | Evidence |
|---|---|---|---|
| 1a | D1 / scenario 17: real `adb reboot`, clock untouched | PASS | Two due synthetic doses (separate cues, 11:34 and 11:35) plus a due hydration cue. After `BOOT_COMPLETED` (about 3 min after boot on this slow emulator) logcat: `delivered habit:hydration silent=true`, `delivered med:merged ch=medication silent=false`, one utterance. `dumpsys notification` = {med:merged, habit:hydration}; engine `visible=[habit:hydration, med:merged]` (same set). Title "2 medication reminders not confirmed". |
| 1b | Time zone to Asia/Tokyo, one FollowLocalTime and one KeepHomeTimezone(UTC) dose | PASS | One silent notice "Time zone changed: now Asia/Tokyo. 1 follow local time, 1 keep home time"; the FollowLocalTime dose moved to 02:34Z, the KeepHome dose stayed 11:35Z. Both policies were in the same run, not separately. |
| 1c | Manual clock jump forward by more than a day, MED-11 | **FAIL** (D9) | Three jumps (+1 d 2 h, +2 d, +2 d) with doses uncued on the earlier days (notifications cleared before two of them): no catch-up notice in any, `visible=[]`, earlier-day doses silently Due. In the first jump the two current-day doses were posted as separate cues (grouped) rather than one merged notice, and the stale "2 not confirmed" merged cue from the reboot stayed unchanged. No "missed" wording anywhere. Code reading: `MedicationModule` skips the recovery branch while `ms.mergedCue != null`; unconfirmed. |
| 2 | D2 release APK | PASS | `aapt dump xmltree`: no DevToolsReceiver, no gallery activity, no `debuggable`, no `usesCleartextTraffic` or `networkSecurityConfig`, `allowBackup=false`; exported: MainActivity, SystemEventsReceiver, LockedBootReceiver, LocationSystemReceiver. `dexdump` of `classes.dex` (5302 classes, 665 in `app/daycue`): nothing matching DevTools, Gallery or Debug; kotlinx-serialization `$$serializer` classes present. |
| 3 | D3 import review wording | PASS | Synthetic file with an added medication, removed place, renamed habit, changed interval, alarm time, quiet hours and work days. English: "Renamed: Sunscreen -> Sun cream", "Hydration: every 1 h -> every 45 min", "Added medication: SynM", "Time (Morning alarm): 7:00 AM -> 6:30 AM", "Removed place: Gym", "Work days: Mon-Fri -> Sun-Thu", "Quiet hours: On -> Off"; Hebrew equivalents (`s14`, `s15`). No JSON, no enum tokens, no "Setting changed (". Hebrew still shows Latin "7:00 AM" (D10). |
| 4 | D4 newer `formatVersion`, newer `schemaVersion`, garbage | PASS | Both newer files: "This setup is from a newer DayCue ... Nothing was imported"; garbage: "This file isn't a DayCue setup". Nothing applied. |
| 5 | D5 accessibility | PASS (TalkBack not run) | Work-day chips: the checkable, clickable, focusable parent carries a child content description "Sunday".."Saturday" (full names, en and he), 129x126 px (48 dp at 420 dpi = 126 px). Reorder: the drag handle is now clickable and opens a "Change order" sheet with Move up / Move down; Move up on "Standing" reordered the list. Touch targets from bounds: Today 13 clickables, 0 under 126 px; habit editor 26, 0 under after scrolling (one row partly behind the bottom bar before); alarm screen buttons 974x210 px = 80 dp tall. Role names are not visible to uiautomator. |
| 6 | D7 alarm screen, foreground and screen off | PASS | `appops USE_FULL_SCREEN_INTENT allow`. Foreground: `AlarmActivity` on top at the ring; Snooze re-rang +9 min later; Stop ended it (no alarm player, no service). Screen off: wakefulness Awake and `AlarmActivity` on top at the ring (0 ms late); Snooze worked; Stop tapped on the screen-off alarm worked (activity back to MainActivity). Designs: `s16` light en foreground, `s17` light en screen off, `s18` light he, `s19` dark he, `s20` dark he font scale 2.0. Nothing clipped or overlapping; big serif time, title, illustration, Snooze (outlined) and Stop (filled) 80 dp tall, RTL mirrored. Layout notes in D12; Latin "PM" in Hebrew (D10). |
| 7a | D8 language from onboarding / Settings | PASS | Hebrew chosen in onboarding: UI Hebrew, `config lang=he`, cues and test reminder Hebrew. Settings > General > Language English: UI English at once, `config v4 lang=en`, next test notification English. The system route (`cmd locale set-app-locales`) updated the config while the app ran (en > he > en within 6 s each); once earlier the config stayed `he` after a switch to en (cue buttons Hebrew under an English UI) and I could not reproduce it. |
| 7b | Hebrew first run seeds Sun-Thu and Hebrew template names | **FAIL** (D10) | Release, app locale `he` before first launch: Hebrew template names ("שתיית מים") but work days Mon-Fri (Sunday unchecked). Debug, Hebrew tapped in onboarding: config names English (notification title "Hydration" with a Hebrew body) and Mon-Fri. |
| 7c | Hebrew UI on a 12-hour device shows localized AM/PM | **FAIL** (D10) | `time_12_24=12`: Today header "11:28 AM", "7:00 AM" next alarm, Settings "4:00 AM", import lines, alarm screen "12:01 PM", medication times: all Latin AM/PM in Hebrew. |
| 8a | Posture: manual pause longer than 15 min | PASS | Standing 29 min left, Pause, clock +22 min, Resume: "Standing, 29 minutes left" (same mode, same remaining; the first run reset to the first mode). |
| 8b | Extend on an overdue mode counts from now | PASS | A 5 min demo mode ended 12:39:57; at 12:42:17, 2 min 20 s overdue, Extend 5 gave "Sitting, 4 minutes left" (5 min from now). |
| 8c | Mode-end cue on time, screen on and off | PASS | Screen on: alarm fired 13 ms late, cue and speech delivered. Screen off with `dumpsys battery unplug`: fired 689 ms late. The emulator did not enter Doze within 5 min, so deep-Doze behaviour is NOT VERIFIED (section 5 item 6). |
| 9 | Medication: Taken at..., Correct, dismissal | PASS | Dose 12:04, "I took it", Change time to 12:00: medication list "Taken 12:00 PM"; history "Dose 12:04 PM - Corrected: taken 12:00 PM" beside the original "Taken 12:04 PM". History Correct > Not confirmed worked (row "Corrected: not confirmed", no re-cue). Dismissal: the dose notification is ongoing and cannot be swiped away on this build; the dose stayed Due with its cue, so nothing confirms it. |
| 10 | "Why now?" | PARTIAL | Habit (hydration item detail): "Every 1 h - Place: Not sure where you are - Environment: Indoors or out: not sure". Medication: no "Why now?" on Today (cards have no overflow) or in the dose sheet (D11). |
| 11 | Scenario 5 gap | PASS | Routine built in the editor UI (New routine, renamed, added a step with a 60 s timer, "about 3 min"). Recovery policy has no UI control, so it was set through `upsertRoutine`. `Cancel`: `kill -9`, clock +17 min, relaunch: the run is gone, no prompt, `RoutineCanceled` rows in the database. `ResumeCurrentStep`: kill, +35 min, relaunch: one routine cue and the step restarted ("1:54 left", Step 1 of 2). |
| 12 | Scenarios 8 and 9, real companion | PASS (manual remainder below) | Local relay (`gen-secret`, tsx server on :8787, `adb reverse`), phone paired in Setup > Connections > Remote access, companion built with `dotnet build companion -c Debug`. Phone shows "Active" within a minute of the first signal. Office set manually (work place, AutoStart, hours 08-19), a synthetic input pinger kept the PC active: Working session started 12:47:21 (first Active 12:42:16, 5 min sustained). Graceful companion exit (final minimum-TTL signal): "Reporting paused on the computer", activity Unknown, session Suspended within 10 s. Restart: session Active again. Companion killed (no final signal), phone clock +12 min: "Not seen recently", Unknown, Suspended. Not driven: "suggest" start mode, locked PC. **Manual remainder:** the companion's pairing dialog and tray menu (Pair, Pause, Exit, Unpair) are WinForms GUI and were not driven. I paired with a PowerShell script doing what `TrayApp.DoPair` does (CNG key, `POST /v1/pair/companion`, DPAPI `pairing.json` in a throwaway `DAYCUE_COMPANION_DIR`), then ran the real exe. Pausing from the tray was not tested. The phone has no companion revoke (documented relay gap), so the companion was revoked with the owner API; the phone was unpaired from the UI. |
| 13 | Crash watch | PASS | `logcat -b crash` empty before the reboot and at the end (the reboot clears the buffer, so the final read covers post-reboot time). No app ANR; SystemUI and Launcher "isn't responding" dialogs appeared right after emulator boot (memory pressure, not DayCue). |
| 14 | Release APK fresh install | PASS | `pm clear`, Hebrew onboarding with four templates, "send a test reminder" posted, Today and Settings render, no crash and no Room or serialization error in logcat. |

### New defects, with steps

- **D9 (Medium, MED-11).** Debug build, add two daily medications via `ops` (11:34, 11:35), let them become due, `adb reboot` (merged cue shows), then `date` +1 day (again +2 days, +2 days; notifications cleared in between). Expected: one merged catch-up notice counting unconfirmed doses of the last 48 h and nothing per dose. Actual: none (row 1c). Possible cause: recovery branch skipped while a merged cue is recorded, and it requires `s.cue == null`.
- **D10 (Low-Medium).** (a) Hebrew UI on a 12-hour clock formats times with Latin AM/PM; (b) Hebrew first run seeds work days Mon-Fri, and English template names when Hebrew is tapped in onboarding.
- **D11 (Low).** Medication cards and the dose sheet have no "Why now?".
- **D12 (Low).** Alarm screen: at font scale 2.0 the illustration collapses to a thin strip; large empty bands at every size; with DayCue in the foreground the heads-up notification (own Snooze/Stop) covers the large time for a few seconds.
- **D13 (Low).** Hebrew summary string "בעוד 19 שעות דקה" (minutes number missing); English "in 0 minutes" at the due minute.

Doc note for the owner: a detached launch of the companion needs `DOTNET_ROOT` when .NET is not installed system-wide (`docs/setup/COMPANION.md`).
