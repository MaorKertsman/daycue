# Design review 2: the real app (visual + UX)

Reviewers: visual designer + UX designer, reviewing together. Date: 2026-10-05.
Evidence: the emulator captures in `daycue-review/app-1/` (235 files), with every file opened at least once. All light-en and light-he captures were read. Dark and font-2.0 were sampled for each screen family. I also spot-checked `daycue-review/design-system-2/`. Stills cannot show motion, hold feedback or TalkBack, so those items are marked unverified.
Severity: **must** = unreadable, clipped, broken, raw technical text, or a wrong bidi meaning. **should** = fix in this pass. **nice** = polish.
File names below are relative to `app-1/` unless they say `ds2/`.

## 1. Verdict

**Identity holds.** The real app is still recognizably *Cut Paper Day*:
- paper and ink, no cards;
- hairline rows with the 40dp mark slot;
- slab section headers;
- the hatched "not sure" plane on Today;
- an inverted primary in dark mode;
- correct RTL mirroring almost everywhere.

Dark contrast passes on every screen sampled. Hebrew durations and times are now correct in the real app ("דחייה ב־5 דק׳", "לאחרונה ב־05:53", "23:00–06:30, מסתיימים למחרת").

**It is less calm than the gallery.** Three things pull it toward a generic settings app:
1. **Each engineer built a different header** (see S1). Today has a sans context line. The Cues root and editors use a sans title, while the Setup root and editors use a serif title. Item detail stacks a sans "Details" bar over a serif name.
2. **Stacks of outlined 52dp boxes.** The posture live control has 7 outlined buttons. The context sheet has 9 toggle boxes. The place editor has 3 stacked buttons plus steppers. Med-history filters are drawn as buttons.
3. **Raw technical text has reached the user:**; the import and remote-approval diffs are JSON (`settings.workDays: ["MONDAY",…]`); `localhost`, `com.google.android.tts` and `GMT` are shown as values; English fallback strings appear inside Hebrew.

These are the first things the owner will notice.

**Today and the four questions.** Context and "what can I do" are answered. "Active" is answered when a Running row exists. "Next" is not answered at a glance:
- At 1.0 the Field (~200dp) plus ~60dp of dead space pushes Next, the readiness row and the quick controls below the fold (`A-due-light-en`, `A-today2-light-en`).
- When nothing is due, the Now line is absent (`A-today1-light-en`, `A-acked-light-en`).
- In those same states the Field vanishes and leaves a blank band.

Verdict: the structure is right, but the vertical budget is wrong (A1–A3).

**Progressive disclosure.** It is kept in Cues:
- the habit and routine editors use "More options (n changed)" with Hebrew plurals;
- choice sheets have a consequence line per option;
- the timing sheet has a worked example;
- the medication draft uses Review & save.

Setup has drifted into dense forms and prose:
- the place editor puts latitude/longitude fields and a paragraph about the missing map into the Simple tier;
- companion, remote, speech and places-denied each carry 8–15 lines of explanation;
- integrations gives each single row its own section header.

Fix with C1–C9.

**Captures are not trustworthy enough yet.**
- 21 of the 36 C-light captures, and several C-font2 captures, show only the splash or skeleton rows.
- `B-try` shows "DayCue keeps stopping".
- The B and C tab-root captures have no bottom nav. The nav is present in `A-deeplink-cues-light-en`, so this is probably a harness that launches the screens outside the shell, but it has to be confirmed.
- `ds2/font2-light-he-*` render English.

## 2. Corrections

### Shared (lead assigns; S2 and S9 sit in `ui/app`, so engineer A)

- **S1 must.** Header style across all three tabs. Files: `B-home-light-en` ("Cues", sans), `C-light-he-home` ("הגדרות", serif), `B-habit-light-en` (sans title + switch), `C-light-en-place` (serif title), `A-detail-light-en` (sans "Details" bar + serif name), `C-remote-confirm-cmd` (sans "Needs your approval", back arrow at a different x). Problem: there are four header styles, which makes the engineers' work look like three apps. Fix: build one `DayCueTopBar` and use it everywhere. Tab roots: `headline` serif title at the 20dp gutter, with no back arrow. Today keeps its date + context line instead. Sub-screens: 48dp back icon at the gutter, then the 28dp cue mark (if any) and the `headline` serif title. One trailing slot holds the enable switch or one icon, with end padding equal to the gutter. Remove the extra sans "Details" bar. This keeps one serif headline per screen. On Today and playback the serif belongs to the Now title or the step name, so their top bars carry no serif.
- **S2 must.** `ds2/light-en-today-end`, `ds2/light-he-today-end`, `ds2/font2-light-en-today-end`, `ds2/*-pickers-1`. Problem: when the screen is scrolled, content draws under the status bar clock. Fix: apply `WindowInsets.statusBars` padding to every scroll container, or paint a paper status-bar scrim. Verify on Today, editors and Setup.
- **S3 should.** Section headers in `B-bottle-*` ("When to remind", "At a set time") and `B-posture-*` ("Modes, in order"). Problem: these are plain grey labels with no slab. Fix: use the shared SectionHeader (12×3 slab + `label`) everywhere.
- **S4 should.** "Add" and secondary action patterns differ. "Add a reminder" is a text button (`B-home`), while "Add alarm" and "New routine" are full-width primaries (`B-alarms`, `B-routines`). Test is wrap-width in habit editors but full width in the alarm editor. Import Done is a narrow centered primary. Fix: "Add" in a populated list is a text button at the list end aligned to the row text. A full-width primary appears only in empty states. Test / Fast test / Preview are text buttons in one row. Every primary is full width.
- **S5 should.** Text-button gutter is still open (REVIEW-1 #14). Files: `B-habit-*` and `B-posture-mode` presets ("1 h", "30 min"), `ds2/light-en-pickers-*`, `C-cal-preview` actions. Problem: labels start 8–12dp in from the text above. Fix: start content padding 0. Labels align with the gutter, or with the row text start inside rows. The DurationField value also still jumps (`B-hyd2`, REVIEW-1 #25). Fix: `widthIn(min=112.dp)`.
- **S6 should.** Day chips at 2.0 (`C-font2-en-quiet`, `C-font2-en-settings`). REVIEW-1 #39 is not fixed. Fix: `heightIn(min=36.dp)` + 6dp vertical padding, and wrap 4+3. **nice:** when all 7 are selected, add the summary "Every day" / "כל יום" above the chips (`B-habit-*`, `B-med-new`, `C-light-he-quiet`).
- **S7 should.** Seeded and template names stay English inside Hebrew: "Demo habit" (`A-due-light-he`), "Home" (`C-light-he-place`), "Demo routine" and "Step one" (`B-routine-light-he`). Fix: localize seeds and templates at creation: "הרגל לדוגמה", "בית", "משרד", "חדר כושר", "שגרת בוקר", "שלב ראשון". Names the user typed stay as typed. Also add a unit test that fails when a `values-iw` string is missing, so it can't fall back to English (see C4).
- **S8 should.** Clock format is mixed on a 12h device: `B-shade2` and `B-alarm-ring` show "06:00"; `B-demo1` recent activity shows "Today 05:41"; `B-med-history` mixes "05:50" with "5:52 AM"; the medication travel example is hard-coded "08:00". Fix: one `TimeText` formatter using `DateFormat.is24HourFormat` for UI, notifications and history alike.
- **S9 should.** `A-shade-light-en`, `B-shade2`: notifications show a full-color large icon. Fix: drop `setLargeIcon` and use the monochrome cue silhouette with `setColor` (VISUAL §7).
- **S10 should.** `C-font2-en-settings`: a SettingRow consequence is ellipsized ("On: no sli…"). Fix: no `maxLines` on secondary text (VISUAL §7 allows 3 lines before ellipsizing, but a consequence line never ellipsizes).
- **S11 nice.** At 2.0 the section slab reads as a dash, and the back arrow centers on two-line titles (REVIEW-1 #27). Fix: align both to the first line's center, and scale the slab width with font scale (max 20dp).

### Engineer A: `ui/app`, `ui/today`, `ui/onboarding`, `ui/readiness`, `ui/alarm`

- **A1 must.** `A-due-light-en/he`, `A-today2-light-en`: Today vertical budget. Problem: "Next" is below the fold at 1.0. Fix: Field height max 160dp. 24dp from the Field hairline to the first section header (now ~60dp). "Set it" sits 8dp under the context line (now ~48dp). Acceptance: with one due item, the first Next row is visible at 1.0 in en and he.
- **A2 should.** `A-today1-light-en`, `A-acked-light-en`: the Field disappears and leaves a blank band. Fix: always draw the plane, with no disc when nothing is timed (VISUAL §9 empty composition).
- **A3 should.** Same files: there is no Now line when nothing is due. English: "Nothing needs you now · next 5:58 AM". Hebrew: "אין כרגע משהו לעשות · הבא ב־05:58". Omit the buttons.
- **A4 should.** `A-due-light-en` vs `A-menu-light-en` / `A-detail-light-en`: the same item's ack reads "Done" on one and "Drank" on the others. Fix: derive the label from the cue type, identical on Today, detail and notification. Hebrew: "שתיתי" for hydration, "סיימתי" for a generic habit.
- **A5 should.** Hydration waiting reason on Today and Cues: Today: "after your first confirmation" / "אחרי האישור הראשון". Cues: "Starts after you confirm once" / "מתחיל אחרי אישור ראשון", which is masculine. Fix, one string in both places: "Starts when you first tap Drank" / "תתחיל אחרי הלחיצה הראשונה על 'שתיתי'". Also in `A-acked-light-en`: "next after 5:58 AM" → "5:58 AM · in 5 min".
- **A6 should.** Hebrew readiness copy (`A-due-light-he`): Text button "לתיקון" → "תיקון". Status "סוללה · מוגבל" → "מוגבלת" (the noun is feminine). Alternatively rename the row "חיסכון בסוללה" and keep the status "מוגבל". **nice:** date "יום ב׳ 5 אוק׳" → "יום ב׳, 5 באוק׳".
- **A7 should.** `A-ctx-light-en`, `A-ctx-font2-dark-he`: context sheet. Problems: The sheet has 9 outlined toggle boxes in 3 wrapped groups. Place has no "Change". There are no source ages. Activity has no "None". "For how long" sits between Place and Environment. Fix (UX §3.2): One row per dimension: label, value, `ink2` "(location, 2 min ago)", then a "Change" text button that opens a radio sheet. Place offers its saved places + "Somewhere else". Activity offers Auto / Working / Studying / None. "Keep my choice for…" goes at the bottom, shown only after a manual change.
- **A8 should.** `A-snack-light-en` vs `A-override-light-en`: an Indoors override still draws the unknown hatch, while Outdoors draws a solid plane. Fix: a user-set environment always draws its tone plane. **nice:** the Running row should read "Outdoors (set by you) · until you change place" / "בחוץ (לפי בחירתך) · עד החלפת מקום".
- **A9 should.** `A-detail-light-en`, `A-deeplink-light-en`: item detail. Problems: Primary and Snooze are stacked full width, unlike Today. The state "Due now" is in `ink2`. "Why now?" is a text button inside an already-expanded section. The why text is generic ("A reminder is waiting…"). Fix: Buttons side by side, stacking only at 2.0. State word in its state ink. "Why now" becomes a section header. State the rule with sources: "Every 5 min since you tapped Done at 5:53 AM".
- **A10 should.** `A-readiness-light-en`. The optional Hebrew voice counts toward "3 things may delay reminders". Fix: count only Ready-critical rows (UX §3.13). "Fix" and "Install" are not end-aligned to the gutter. Rename "Notification types" → "Reminder categories" and "Pause of unused apps" → "App pause when unused" / "השהיית אפליקציות שלא בשימוש".
- **A11 should.** `A-onb3*-light-en`: the permission step. Battery and "unused apps" are asked during onboarding (UX puts them in Readiness). Each card has its own full-width ink Allow, giving 3–4 primaries. The routine mark is used for system permissions. Fix: one card at a time, ordered as in UX §3.1, with Allow primary only on the current card. Use the app plane+disc mark.
- **A12 should.** `A-onb2-light-en`: title "What should be turned on to start?" → "What should DayCue help with?" / "במה DayCue יכולה לעזור?". Add a Skip text button.
- **A13 should.** All onboarding steps: the 200dp self-building composition (VISUAL §9) is missing, and the lower half of each step is empty. Add it: plane, then disc, then rule.
- **A14 nice.**; `A-onb1-light-en`: "Step 1 of 5" is inset off the gutter. `A-due-font2-*`: the lone `⋯` sits on its own row. Make it a full-width "More actions" / "פעולות נוספות" text button.
- **A15 verify.** At-rest captures show "More" and readiness under the nav (`A-today2`, `A-due-*`), which is fine only if the end is reachable. Supply end-scroll captures at 1.0 and 2.0 in en and he; ds2 shows the gallery is fixed.

### Engineer B: `ui/cues`

- **B1 must.** `B-try`: "DayCue keeps stopping". Attach logcat, find the crash and add a regression test. The owner is unknown until the stack trace is read.
- **B2 must.** `B-meds-font2-en`: the "Today" slot status breaks one letter per line ("Du/e/no/w"). Fix: give the text `weight(1f)`. When the column would be under 120dp, or fontScale ≥ 1.5, move "I took it" to its own start-aligned line.
- **B3 must.** `B-medhist-light-he`: the day header renders "באוק׳ 5 2026". Fix: use the locale skeleton, giving "יום ב׳, 5 באוק׳". Check the Today date with the same formatter.
- **B4 must.** `B-med-new`, `B-medned-*`: "Keep home time zone (GMT)" shows a raw zone id. Fix: `TimeZone.getDisplayName(LONG, locale)`. English: "Keep home time (Israel time)". Hebrew: "לפי שעון הבית (שעון ישראל)".
- **B5 must.** `B-live-light-en/he`, `B-live-dark-en`, `B-live-font2-en`: at "0:00 left" the bar is full and there is no Switched button, so it reads "done". Fix: render SwitchPending (UX §3.6, REVIEW-1 #19, #28): headline "Time to stand" / "הגיע הזמן לעמוד"; full-width primary "Switched" / "עברתי"; text-button row "Snooze 5 min · Skip · +5 min"; the line "Timer starts when you tap Switched" / "הטיימר מתחיל בלחיצה על 'עברתי'"; the next mode's empty bar.
- **B6 must.** `B-alarms-light-he`, `B-alarm-ring`: the default alarm name is English "Alarm" inside Hebrew. Fix: "שעון מעורר", or show only the time when the alarm has no name.
- **B7 must.** `B-live-light-he`: "+5" renders as "5+". Fix: "+5 דק׳" with the sign and digit inside an LTR isolate (U+2066…U+2069). Apply the same to the playback extend buttons.
- **B8 should.** `B-live1`, `B-live-resumed`: 7 outlined 52dp buttons and no primary. Fix: Pause primary, "Switch now" secondary. One text-button row "+5 min · +10 min · Skip next" (units always). "Reset cycle" as a text button. Use "Pause", not "Pause…", consistently.
- **B9 should.** All `B-play-*`, `B-play1/2`: playback is a thin bar plus 52dp buttons, with two-thirds of the screen empty. It reads as a generic timer. Fix (VISUAL §9): A 0.5×width routine disc that fills bottom-up. The step name in `display` below the disc, then "0:36 left" in `numeric`. Upcoming steps as receding discs. 64dp controls, with Done as primary. This overrides VISUAL's "Pause primary"; I will amend VISUAL. "+1 min · +5 min · Skip" as text buttons. Hebrew: "+1 דק׳ · +5 דק׳ · דילוג". Drop the back chevron. × stays, as "Cancel routine".
- **B10 should.** `B-home-*`: robotic summaries. "Items: 2 • next 5:52 AM" → "2 medications · next 5:52 AM" / "2 תרופות · הבאה ב־05:52". "Routines: 2" → "2 routines" / "2 שגרות". `B-home1` shows "1 routines"; use plurals resources. "Covered until 6:16 AM" / "מכוסה עד 06:16" → "Next at 6:16 AM" / "הבאה ב־06:16". Drop the third line "Off" / "כבוי"; the switch already says it.
- **B11 should.** `B-home-*`, Posture row. The "Live control" link inside the row doubles its height and adds a second target. "בקרה חיה" is unnatural. Fix: remove the link. Live control is reached from Today Running and from the editor; label the editor action "Control the cycle" / "שליטה במחזור".
- **B12 should.** `B-home-light-he`: "06:00 יום ב׳–יום ו׳" → "06:00 · ב׳–ו׳". Isolate the time, and keep the day range in RTL order.
- **B13 should.** Habit, bottle and posture editors. "Sound and voice · Change in Setup" / "שינוי בהגדרות" is a dead end. Show the profile, e.g. "Bright chime · speaks" / "צליל בהיר · מוקרא", and let the row open it. "More options" shows a right chevron when collapsed. Use expand/collapse icons, and always show "(n changed)" when n>0.
- **B14 should.** `B-bottle-*`. "Leaving now" is an ordinary toggle. Lock it on (checked at 38% alpha) with "The most reliable: the button on Today, the tile and the widget." / "הדרך האמינה ביותר: הכפתור במסך היום, האריח והווידג׳ט."; The Hebrew label "יוצאים עכשיו" → "יציאה עכשיו". The Places row should name the places: "Home, Office" / "בית, משרד", or "No places yet" / "עדיין אין מקומות".
- **B15 should.** `B-posture-light-he`. "מתי פועל" → "מתי זה פועל". "רק כשאני מתחיל אותו" → "רק בהפעלה ידנית". Show each mode's phrase as a secondary line in Simple ("30 דק׳ · 'זמן לשבת'"), and add "Test this cue".
- **B16 should.** `B-alarms-*`. Every row has a serif time, which breaks one-serif-per-screen, and the rows have no dividers. Fix: the time leads, in the `numeric` token (sans tabular), then "Name · Sun–Thu", with hairlines. In `B-alarm-*`, the Sound and Backup tone rows lack chevrons. `B-alarm-light-he` copy: "ואז להתחיל" → "ואחר כך להפעיל"; "שום דבר" → "כלום".
- **B17 should.** `B-meds-*`. Library rows repeat today's slot ("5:50 AM Taken 5:52 AM"). Fix: show only "5:50 AM · Every day"; the Today section owns the states. Only "I took it" is offered. Tapping a slot must open Dose detail with Taken at, Snooze and Skip this dose (UX §3.5).
- **B18 should.** `B-medhist-*`. The filters are outlined 52dp buttons that wrap to 2 rows; in dark the selected one inverts wrongly. Fix: one horizontally scrolling row of VISUAL chips. Rows need the 40dp mark slot and a "Correct" text button (MED-5). "Reminder sent" and "Notification dismissed" become `ink2` secondary lines under the dose, not peer entries. Tone is correct: no "missed", no score.
- **B19 should.** Medication draft. `B-med-new`: the time row has no affordance. Fix: whole row opens the sheet, plus "Remove" when there are 2+ times. Also add a "Start / end · Starts today · no end" row. `B-med-time`: drop the redundant "AM/PM" caption. `B-med-review`: drop "This will change:". For a new item the title is "Add this medication?".
- **B20 should.** Hebrew medication copy (`B-meds-light-he`, `B-medned-light-he`). Screen title "תרופה" → "תרופות", and its section → "כל התרופות". Disclaimer → "DayCue רק מזכירה ואינה נותנת ייעוץ רפואי."; "נשאר רק בטלפון הזה." → "נשמר רק בטלפון הזה."; "08:00 הוא 08:00 בכל מקום שבו אתם נמצאים." → "08:00 נשאר 08:00 בכל מקום."; "צריך לבחור אחת. היא קובעת לפי איזה שעון השעות פועלות." → "הבחירה קובעת לפי איזה שעון נקבעות השעות."; "קודם צריך להוסיף שם, לפחות שעה אחת ולבחור אפשרות נסיעה." → "חסרים: שם, שעה אחת לפחות ובחירה לגבי נסיעות."; "סקירה ושמירה" → "בדיקה ושמירה".
- **B21 should.** `B-routines-*`. Manual routines show an OFF switch with no meaning. Fix: show the switch only for scheduled or after-alarm triggers. Replace the inline "More" with a 48dp `⋮` (Test, Duplicate, Delete) and keep "Start" / "התחלה".
- **B22 should.** `B-routine-*` while a run is active. "Start now" is still the primary. Fix: "Open running routine" / "לשגרה הפעילה". Test and Fast test should be a text-button row. "Recent activity · Reminder repeated" / "התזכורת חזרה" is the wrong event type for a routine. Show only run events, or hide the section. The step number sits beside the disc. Put it inside the receding disc (REVIEW-1 #20) and add "· timed" / "· עם טיימר".
- **B23 should.** Top-bar enable switch on `B-habit-*`, `B-routine-*` and `B-en-*` (the older build is clipped at ~4px). Fix: end padding = gutter (S1).
- **B24 nice.**; `B-play-light-he`: "אחורה" → "הקודם". `B-routine-light-he`: "(בסך הכול בערך 2 דק׳)" → "(כ־2 דק׳ בסך הכול)". `B-habit-light-he`: "הנוסח הנאמר" → "מה יוקרא"; "כל 1 שע׳ 15 דק׳" → "כל שעה ו־15 דק׳". `B-timing-sheet-en`: show both options' outcomes. Habit editors: add the live line "Next reminder: 2:40 PM". Snackbar: "Saved" → "Every 1 h 15 min · Undo". `B-posture-mode`: the phrase field is inset ~12dp past the sheet padding.

### Engineer C: `ui/setup`

- **C1 must.** `C-import-review`, `C-import-confirm*`: the diff shows `settings.workDays: ["MONDAY",…] -> […]`. Fix: DiffReview must format through a human formatter: "Work days: Mon–Sat → Mon–Fri" / "ימי עבודה: ב׳–ש׳ ← ב׳–ו׳" (mirrored arrow). Paths, quotes and enum names never appear. This is a shared component, so check medication and remote use the same formatter.
- **C2 must.** `C-remote-confirm-cmd`, `C-remote-pending-cmd`: the approval diff is raw JSON, and it is ellipsized ("days":["MONDA…), but the user is asked to hold-to-approve it. Fix: "Quiet hours: 22:30–07:00 → 23:00–06:30, every day". A diff is never truncated; the screen scrolls. The pending row title becomes "Change quiet hours to 23:00–06:30".
- **C3 must.** Raw identifiers shown as values: `C-light-he-integrations`, `C-light-he-remote`: "localhost". Fix: "שרת ממסר · מחובר" / "Relay · connected"; show the host only under "פרטים". `C-light-en-speech`: "Speech engine: com.google.android.tts". Fix: show the engine label ("Google Speech Services"), or drop the line.
- **C4 must.** `C-light-he-calendar-preview`: English inside Hebrew ("Never for this event", "No rule matched, so no cue"). Fix: "בלי תזכורת לאירוע הזה (נבחר ידנית)" and "אין כלל מתאים, ולכן אין תזכורת", plus the S7 missing-string test.
- **C5 must.** `C-light-he-place`: the coordinate hint renders "מ־90- עד 90" (minus on the wrong side). Fix: U+2212 inside LTR isolates: "קו רוחב בין ‎−90‎ ל־90, קו אורך בין ‎−180‎ ל־180". Moving the field (C6) also reduces exposure.
- **C6 must.** `C-font2-en-calendar-preview`: the per-event actions sit in a fixed Row, so "calendar" breaks to "calen/dar". Fix: FlowRow with 16dp gaps, one action per line when needed, never breaking inside a word.
- **C7 should.** `C-light-en/he-place`, `C-place-home-fix`, `C-dark-en-place`: the place editor is a dense developer form. Simple tier = Name, "Use my current location" (primary), "Location saved" status, Radius, Usually Indoors/Outdoors/Mixed, Work here. Move "Enter coordinates" + "Set location" into a collapsed More options. Replace the missing-map paragraph with one line: "No map: places stay on this phone." / "אין מפה: המקומות נשמרים רק בטלפון."
- **C8 should.** Prose overload in `C-light-he-companion`, `C-light-he-remote`, `C-light-en-speech`, `C-places-denied`, `C-places-list`. Fix: at most one `bodySmall ink2` sentence per block. Details go behind a text button ("What is sent?" / "מה נשלח?", "How to install" / "איך מתקינים") that opens a sheet. The places-denied state has two primaries. Make "Allow location" secondary. Use the §3.16 line: "Location off: places can't be detected. Manual controls, Leaving now and timed reminders still work."; Remove the duplicate "What does not work" list.
- **C9 should.** `C-light-en-speech`: "Speak cues" is ON but its line describes Off. Fix: describe the current state. On: "Cues say their phrase aloud." Off: "Cues use sound and vibration only."
- **C10 should.** `C-light-he-home`: the "Activity & undo" row is missing (UX §1.1). Add "פעילות וביטול · שינויים אחרונים עם אפשרות ביטול".
- **C11 should.** `C-light-he-integrations`, `C-dark-en-integrations`. Each of the 4 single rows has its own section header. Fix: one hairline list. Statuses must be Connected / Not set up / Needs attention: "Spotify · Off by default" / "כבוי כברירת מחדל" → "Not set up" / "לא מחובר". The home section "חיבורים" and the row "שילובים" name the same thing. Use "חיבורים" everywhere.
- **C12 should.** Hebrew wording: "מסלול (relay)" → "שרת ממסר" (remote, privacy, companion). "תצוגה מקדימה" (for audio) → "השמעה לדוגמה". "שום דבר לא מפורסם ולא נרשם" → "שום דבר לא נשלח ולא נשמר". Home: "שפה, תנועה, גיבוי…" → "שפה, הפחתת אנימציה, גיבוי, אודות ופרטיות"; section "בדיקה" → "מוכנות". Remote: "ההחלטה מה מותר היא של הבעלים" → "מה מותר נקבע כאן, והמילה האחרונה תמיד של הטלפון"; "לאפשר התחלה ועצירה של פעילות מרחוק" → "התחלה ועצירה של פעילות מרחוק". Privacy: "יכול לצאת, רק אם הוגדר" → "יוצא מהטלפון רק אם הופעל"; "ושעונים מעוררים לתזכורות" → "ותזמון מדויק לתזכורות". Calendar preview: "אף פעם להזכיר (לפי בחירה)" → "בלי תזכורת (נבחר ידנית)"; "חזרה לכללים" → "ביטול הבחירה הידנית".
- **C13 should.** Skeletons for local data: `C-light-en-context/quiet/settings/places`, `C-place-home-0`, and several font2 shots. Fix: render config on the first frame, delay any skeleton by 150ms, and keep skeletons for Calendar, Spotify and pairing only (UX §2). Pass the entity name in the route so titles aren't the generic "Place" / "Rule". Skeletons must match the real row shape, without a mark slot where real rows have none.
- **C14 should.** `C-remote-confirm-*-done`, `C-remote-recent`: after approving, the screen says only "Nothing is waiting for you", and Recent says "No remote changes yet". Fix: show the outcome ("Applied · Quiet hours 23:00–06:30 · 6:03 PM") and list it in Recent with Waiting for phone / Applied / Rejected / Expired.
- **C15 should.** `C-remote-pending-cmd`, `C-remote-pending-grant`. A sensitive change uses the stone diamond, which is the error notch. Fix: the target's cue mark, with the state in `ink2` text. The grant scope list is jumbled and worded differently from the review screen. Fix: one bullet per scope with its state, identical on both screens, and drop "uses an access token".
- **C16 should.** Import flow. `C-import-confirm`: the review screen is followed by an "Apply this setup?" dialog, so it confirms twice. Keep one confirmation. `C-import-done`: "undo … from the message at the bottom for a few seconds" → "Setup imported. You can undo it in Activity & undo.", with a full-width Done (S4).
- **C17 should.** `C-places-denied`, `C-places-list`: a template place with no location gets a paper-on-paper mark that looks like a rendering gap. Fix: an outlined unknown-tone square with the hatch.
- **C18 should.** `C-cal-preview`, `C-cal-never`. "Cue 60, 15 min before" → "Cues 60 and 15 min before". "Back to the rules" → "Use the rules again". Group the events under day headers, not a date in every row. Align the actions with the row text (S5).
- **C19 should.** `C-remote-grants2`. "Wake by push message · Not available in this build · Needs a build with Firebase set up" is developer text. Fix: hide it, or show "Remote changes arrive within about 15 minutes" with no switch. "This is the kill switch" → "Turn off to stop all remote access at once."
- **C20 nice.**; `C-*-cueprofile`, `C-profile-habit`: drop "Priority 8 of 9" or make it "Heard after: alarm, medication, routine…"; center the radio labels; remove the loose mark next to Preview. `C-*-quiet`: hide "Window 1" / "טווח 1" while there is only one window; "כיבוד ”נא לא להפריע”" → "לפעול לפי מצב „נא לא להפריע”". `C-light-he-companion`: "מלווה למחשב" → "חיבור למחשב". `C-dark-en-companion`: the "What it sends" paragraph is in `ink` body-large; make it `ink2`. `C-dark-en-settings`: "History stays device-local" → "History stays on this phone."

## 3. Good, must not change

- Paper, ink and state inks in light and dark. The primary inverts in dark, the error diamond stays legible in dark, and no card, pill or status badge appears anywhere.
- Rows: the 40dp mark slot, inset hairlines, slab section headers (where used), and underlined text buttons whose underline clears descenders (REVIEW-1 #15 is fixed).
- Today: the uncertain context line "Not sure where you are · Indoors or out: not sure" / "המיקום לא ברור • בפנים או בחוץ: לא ברור" with "Set it" / "לקבוע"; the hatched plane; the mirrored Field; the readiness row format (name / status word / consequence / Fix); the Undo snackbar; the 2.0 Field strip with stacked actions.
- Hebrew bidi in the real app: "דחייה ב־5 דק׳", "בעוד 3 דק׳ • 05:58", "40 שנ׳", "23:00–06:30, מסתיימים למחרת". Neutral forms: "סיימתי", "לקחתי", "התחלת עבודה", "בוחרים / לוחצים / אפשר".
- Medication tone: Due / Taken 5:52 AM / Upcoming. Dismissal leaves the slot Due. There is no "missed", no score and no advice. The draft cannot save without the travel choice, and Review & save leads to a plain diff.
- The routine editor: three tiers, with "More options (שינוי אחד)" plurals; the "Changes apply next time" banner; six-dot start handles; a consequence plus a worked example in the timing sheet.
- The Sounds list: marks in priority order with "sound · vibration · speech" summaries. It is the best screen in Setup.
- Calendar mode sheet: a consequence per option, handle and mark beside the title, one primary. Hold-to-approve + Decline for sensitive remote changes. The medication remote scope is off with "names never leave this phone".
- Empty states (`B-live0`, `B-en-posture-live`): plane composition, title, body, one primary.

## 4. REVIEW-1 "must" items in `design-system-2/`

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | Today scroll and bottom padding | Fixed (gallery) | `font2-light-en-today-end` reaches "Leaving now · More" clear of the nav. New regression S2: content draws under the status bar when scrolled. Real-app end-scroll capture missing (A15). |
| 2 | Quick controls, More last, same set in en/he | Fixed | `light-en-today-end` / `light-he-today-end`: "End session" shown, Indoors hidden, "More" / "עוד" last. |
| 3 | RunningRow | Fixed | "Standing • 12 min left • then walk 10 min" / "עמידה • נותרו 12 דק׳ • אחר כך הליכה של 10 דק׳". Field semantics unverified. |
| 29 | Hebrew durations in RTL | Fixed | `light-he-pickers-0`: "2 שע׳ 15 דק׳", presets "30 דק׳". The real app agrees. |
| 30 | Overnight window order | Fixed | "22:30–07:00, מסתיים למחרת" and התחלה/סיום labels. Its bottom line touches the gesture bar (nice). |
| 31 | 12h stepper | Fixed | `light-en-pickers-0`: "7" + AM/PM. The real app agrees (`B-alarm-time`). |
| 43 | Sheet fade under reduced motion | **Unverifiable** | A still can't show it. Supply a frame sequence or recording. |
| 15 | Underline offset | Fixed | Clears "g", "ק" and "ן". |

Still open from REVIEW-1: #14 text-button gutter (S5), #25 DurationField width (S5), #27 slab at 2.0 (S11), #39 day chips at 2.0 (S6). The `font2-light-he-*` set renders English, so Hebrew at 2.0 is **still unverified**.

## 5. Coverage gaps (order recaptures)

Fix the harness first:
- Wait for content, not the splash or skeleton (21 of the 36 C-light files are unusable).
- Run each tab root inside the shell with the bottom nav.
- Apply the Hebrew locale to the font-2.0 runs.
- Attach logcat for `B-try`.
- Stray files: `A-onb2c` (gallery), `A-onb4c`, `C-places-0`, `C-settings` (splash), and `A-readiness-light-he`, which actually shows Today.

Never captured:
- **Engineer A:**; Full-screen **ringing alarm** (72dp buttons, Stop reachable at 2.0). **Readiness** in he, dark and 2.0. Today scrolled to the end at 1.0 and 2.0 in en and he. Today with a running posture or routine, a working session, a medication due, "and 2 more", the nothing-enabled empty state, the engine error, and detection paused. The More and Pause… sheets. Item detail in he, dark and 2.0; the context sheet in light-he. **Onboarding in he, dark and 2.0**, the places step, a denied permission and the test "No" path. Hebrew notifications and the medication lock-screen (generic) notification.
- **Engineer B:**; Posture SwitchPending. Dose detail, Not confirmed, merged medication cue, medication delete confirm + Undo, Stop reminders, medication More options, the empty medication list, and save failed. History "Correct". Step sheet, trigger sheet, cancel-routine dialog, recovery state, test-run bar, second-routine prompt, and the empty routine list. Alarm More options, the Spotify path and the Test result. Hydration "different interval by context" and the When sheet. Every B screen in **dark-he and font2-he**. `B-home-font2-en` and `B-routine-font2-en` scrolled to the end.
- **Engineer C:**; Content (not splash) for the calendar rule editor, context rules, Spotify (connected and offline), settings, and English home, integrations, companion, remote and privacy. **Remote hold-to-approve mid-hold** (fill feedback + TalkBack action), rejected, expired and offline-queued commands, and the revoke confirm. Companion pairing code/QR and "Code expired". Calendar stale banner, access denied and "No events". Import errors and export with medication warning. Activity & undo. Places with "You're here", and the place editor scrolled to Usually/More. Every Setup screen in **dark-he and font2-he**.
- **Motion:** reduced-motion frame sequences for the sheet fade (#43) and the Field.

## 6. Counts

| Owner | must | should | nice |
|---|---|---|---|
| Shared (S1–S11; S2 sits in `ui/app`) | 2 | 8 | 1 |
| Engineer A (A1–A15; A15 = verify) | 1 | 12 | 1 |
| Engineer B (B1–B24) | 7 | 16 | 1 |
| Engineer C (C1–C20) | 6 | 13 | 1 |
| **Total** | **16** | **49** | **4** |

Several items also carry smaller **nice** sub-points: S6, A6, A8 and the bundles in B24 and C20.
Contract notes:
- VISUAL §9 says Pause is the playback primary. Done is the ack, so it becomes the primary; I will amend VISUAL.
- UX §3.13 should state explicitly that optional rows such as Voices never count toward the problem summary.
- The he-IL workday default appears as ב׳–ו׳ in `B-alarm-light-he`. The product lead should confirm whether it should be א׳–ה׳ (Settings, engineer C).
