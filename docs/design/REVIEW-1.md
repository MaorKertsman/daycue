# Design system review 1 (visual + UX checklist)

Reviewer: visual designer (also covering UX.md §7). Date: 2026-10-04.
Evidence: all 47 PNGs in `daycue-review/design-system/` (API 37 emulator, synthetic data), read at full resolution (tall gallery pages split into 1800px tiles). Motion can't be judged from stills, so the reduced-motion items are "unverified".
Coverage gaps (please capture next round): dark-he has only today/rows/controls. font2-he has no marks/field/overlays/states. noanim has only today/field/marks. There is no Hebrew live dialog.

Severity: **must** = blocks calling the design system done. **should** = fix this pass. **nice** = polish.
Paths below are relative to `daycue-review/design-system/`.

## Verdict

It reads as **Cut Paper Day**, not a generic app. These carry it: the warm paper and ink, the rotated grained plane with its approaching disc, the flat two-tone marks, the single serif "Now" line, no cards, hairline rows, and the slab-marked nav. The identity holds in dark mode and in RTL.
What pulls it toward generic: stacks of large outlined 52dp buttons (quick controls, posture pending), text buttons that don't line up with the gutter, and the duration/time bidi bugs. A native Hebrew reader would notice those first.

## What is good and must not change

- Palette and tokens: paper/ink/surface, the cue shape vs ink split, the state inks. Contrast holds in light and dark.
- Field: the plane rotates −3° (mirrors to +3° in RTL), grain sits only inside shapes, the paper edge stays physical (bottom-right in both directions), the disc approaches and then overlaps with a seed, the unknown plane is drawn at 85%, and the Now rule is in place.
- The marks follow the geometry spec. Only the routine mark and posture sequence mirror. The capsule keeps −35°.
- Rows: no cards, a 28dp mark in a 40dp slot, the divider is inset from the text start (correct in RTL too), and the pressed/section header language is right.
- Buttons have radius 14 and are 52dp, primary is ink (inverted in dark), destructive is outlined and never filled red. The switch, radio, snackbar, text field and sheet (radius 28, handle, 28dp mark beside the title) all match the spec.
- Bottom nav: 72dp, the 20×4 slab indicator, abstract primitive icons, and labels that stay visible at 2.0. The launcher, themed and notification icons are correct, with the cut between shapes.
- RTL: layout, chevrons, switches, progress fill from the right, day chips א–ש in locale order, and the stepper and Field mirroring are all right.
- Font 2.0: Now actions stack vertically, the Field collapses to a strip, and rows grow without truncation.

## Implementer's deviations: decisions

| Deviation | Decision |
|---|---|
| Duration presets as underlined text buttons, not chips | **Accept.** VISUAL limits chips to weekdays and filters, and VISUAL owns styling. Keep the bold label with the 3dp ink slab for the selected preset. The UX owner should update UX.md §5/§6 ("preset chips" becomes "preset text buttons"). See #14 for alignment. |
| RTL steppers mirror (− at start/right, + at end/left) | **Accept.** It matches layout mirroring and sliders. Only the value text needs a fix (#29). |
| Seed dot and error notch mirror to the top-start corner in RTL | **Accept.** "top-end" follows the reading side. I will clarify this in VISUAL §2.3. |
| Variable fonts bundled instead of static TTFs | **Accept.** Weights 400/500/600 and Frank Ruhl 500 render correctly. Keep OFL.txt and the licenses entry. I will amend VISUAL §3. |
| Material's own bottom-sheet animation under reduced motion | **Reject.** See #43. |
| Default underline on text buttons | **Reject.** See #15. |
| Hydration level uses full vessel height (dome included) | **Accept.** It is the honest volume reading. |

## Corrections

### Today screen

1. **must.** Files: `light-en-today`, `light-he-today`, `dark-*-today`, `noanim-light-en-today`, `font2-*-today`. Element: Today content above the bottom nav. Problem: the quick-control row is cut by the nav. At 2.0 the readiness row and everything after it is hidden behind the nav, and the screen looks clipped rather than scrollable. Fix: put the whole Today content in one vertical scroll with bottom contentPadding = 72dp nav + 16dp + the system nav-bar inset. Nothing may sit under the nav hairline. Verify by scrolling to the end at 1.0 and 2.0.
2. **must.** Files: `light-en-today` vs `light-he-today`, and `light-en-rows-b` (quick controls). Element: QuickControls. Problem: English is not missing a control. The FlowRow wraps "Leaving now" and "More" to a second row, which #1 hides. Hebrew labels are shorter, so three fit on the first row. "More ⋯" is invisible in both languages. Fix: #1 makes the wrapped row reachable. Always render "More" as the last item, with 8dp gaps between controls, ≤ 4 controls plus More (UX §3.2), and identical content in both languages.
3. **must.** File: `light-en-today` (all variants). Element: Running section. Problem: the Field shows "standing, 60%" and "next walk", but no text states it. Shapes must never be the only carrier (VISUAL §2.5, §5). UX's four questions also need "what is active". Fix: add a RunningRow under the Now card, for example "Posture · Standing · 12 min left · then walk 10 min" (already built in `light-en-rows`). Also make the Field semantics node read the same sentence.
4. **should.** File: `light-en-today`. Element: Now card actions. Problem: the `⋯` overflow (Pause, Why now?) from UX §3.2 is missing. Fix: add a 48dp trailing icon button after Snooze, using the `ink2` outline icon, and expose its items as custom actions too.
5. **should.** File: `light-en-today`. Element: quick control "Start working". Problem: the context line says "Working", but the control still offers to start. Fix: during a session the control must read "End session". Hide the Outdoors/Indoors control that matches the current state (do not show it as "selected"; see #17).
6. **should.** Files: `light-en-today`, `font2-light-en-today`. Element: context line chevron. Problem: the chevron floats at about x=724, not aligned to the text or the end edge. At 2.0 it hangs between the two wrapped lines. Fix: place a 24dp chevron inline after the last word (same baseline), or end-align it to the first line's center. Keep the help icon as the only top-end icon.
7. **should.** File: `light-en-today` (readiness row). Element: "Exact timing is off / Off / Reminders may…". Problem: the status repeats the title. Fix: title "Exact timing", status word "Off" in error ink, then the consequence line (UX §3.13 format). Hebrew: "תזמון מדויק" / "כבוי".
8. **should.** Files: `light-en-today`, `light-en-field` (all fill states). Element: unfilled part of fill primitives (posture slab, routine disc). Problem: the empty part is painted paper (light) or near-black `sunk` (dark). It reads like a battery or progress gauge, and in dark like a hole. Fix: leave the unfilled area transparent so the plane tone shows through. Keep the 1.5dp cue-ink edge.
9. **nice.** File: `light-en-field`. Element: next posture mode. Problem: the next-mode outline sits inside the plane at full size. Fix: per VISUAL §5, draw it at 0.6× the active size just outside the plane's end edge, in reading order.
10. **nice.** File: `light-en-today`. Element: Field composition. Problem: plane + sage active element + saffron disc is three colored shapes, against VISUAL §4.3. Decision: it reads calmly, so no code change. I will amend §4.3 to "plane + active cue + next cue, active always ink-edged".

### Marks and Field

11. **should.** File: `light-en-marks` (72dp posture, posture modes). Element: current posture mode. Problem: the filled mode has no 1.5dp ink edge at ≥ 48dp (it does in the Field). Fix: draw the 1.5dp posture-ink edge on the active mode for every mark ≥ 48dp.
12. **should.** Files: `light-en-field`, `light-he-field` (Due, Overdue). Element: next-cue disc when due. Problem: the disc gets the seed but not the Due edge. Fix: add a 1.5dp cue-ink stroke on the disc at p = 1.
13. **should.** Files: `light-en-field`, `light-en-states`, `dark-en-field` (unknown plane). Element: hatch. Problem: a 3dp pitch over a 200dp+ plane looks like woven fabric and is the busiest thing on screen. Fix: hatch pitch 6dp for shapes ≥ 48dp (keep 3dp for marks), with 1dp lines. Keep the hatch at 45° in RTL; don't mirror it (`light-he-field` mirrors it, **nice**). I will amend VISUAL §2.3.

### Components

14. **should.** Files: `light-en-controls` ("Why now?", "Reset all to defaults"), `light-en-rows` ("and 2 more", "Set it", "Resume"), `light-en-states` ("Try again"), `light-en-pickers` (presets). Element: text buttons. Problem: the label sits about 8dp in from the gutter because of the button's content padding, so it misaligns with the text above. Fix: horizontal content padding 0 on the start side and 12dp on the end side, with the 48dp touch target from `minimumInteractiveComponentSize`. The label's start edge must equal the gutter or the row-text start.
15. **should.** Files: `light-en-controls` ("Change"), `light-he-today` ("לתיקון"). Element: text-button underline. Problem: the default decoration cuts through descenders (g, ק, ן). Fix: draw the underline yourself (`drawBehind`): 1dp, current text color, 3dp below the baseline, label width.
16. **should.** File: `light-en-controls` ("Fix"). Element: Fix text button. Problem: it's in error red here but ink on Today. Fix: text buttons are always `ink`. The notch and status word carry the error.
17. **should.** Files: `light-en-controls` ("Selected"), `light-en-rows-b` (Outdoors selected), `dark-he-controls`. Element: selected secondary button. Problem: a `sunk` fill alone is barely visible in light and invisible in dark. Fix: for quick controls, hide the control that matches the current state (UX allows this). For any other toggle button: 2dp `ink` border, `Role.Switch`/selected semantics, and a leading 16dp check is allowed.
18. **should.** File: `light-en-controls` ("Posture · Keep at least one"). Element: disabled-with-reason switch. Problem: it is drawn OFF, but the last enabled mode is ON and locked. Fix: checked state at 38% alpha, still announced as "on, can't turn off: keep at least one".
19. **should.** Files: `light-en-rows`, `light-en-rows-b` (posture pending). Element: action stack. Problem: three full-width 52dp buttons (Switched, Snooze 5 min, Skip) form a heavy generic slab. Fix: Switched full width (primary). Below it, one row of text buttons: "Snooze 5 min · Skip · +5 min". This stacks at 2.0.
20. **should.** File: `light-en-rows` (reorderable list). Element: step rows. Problem: the drag handle is at the end, the number is plain text, and the duration is missing. Fix (UX §3.7, VISUAL §9): six-dot `ink2` 48dp handle at the start, step number in a receding routine disc (16/13/11dp, then 11dp), secondary "5 min · timed", and `⋮` at the end.
21. **should.** Files: `light-en-rows` (readiness rows), `light-he-rows`. Element: ready vs problem rows. Problem: text starts at the gutter for ready rows and at 72dp for problem rows, which leaves a ragged edge. Fix: every readiness row keeps the 40dp leading slot (empty when ready), so the text start and divider inset are constant.
22. **should.** File: `light-en-states` (Offline). Element: offline row mark. Problem: offline uses a stone diamond, which is the error-notch shape, and that implies "broken". Fix: use the affected integration's mark (calendar) in its scheduled state with `ink2` text "Offline · calendar last synced 09:40". No diamond.
23. **should.** File: `light-en-rows` (uncertain context line). Element: ContextLine, uncertain variant. Problem: "Not sure where you are" drops the environment and activity dimensions (UX §3.2, §7.2). Fix: "Not sure where you are · Indoors or out: not sure" (and activity if any), wrapping as needed.
24. **should.** File: `light-en-live-dialog`. Element: dialog scrim. Problem: it is about 60% dark, far heavier than the sheet scrim. Fix: use the `scrim` token (`#1F1D1A` @ 32% light, `#000` @ 56% dark) for dialogs too.
25. **nice.** File: `light-en-pickers`. Element: DurationField value. Problem: the + button jumps as the value width changes ("30 s" vs "2 h 15 min"). Fix: value box `widthIn(min = 112.dp)`, centered.
26. **nice.** File: `light-en-overlays` (Diff review). Element: bullets. Problem: the 12×3 section-header slab is reused as a bullet. Fix: 4dp `ink2` disc bullets.
27. **nice.** Files: `light-en-rows` (wrapped section headers), `font2-*`. Element: section-header slab. Problem: on a two-line header the slab centers on the block, and at 2.0 it reads as a hyphen. Fix: align it to the first line's center and scale its width with font (12dp × fontScale, max 20dp).
28. **nice.** File: `light-en-controls` ("Time to walk"). Element: ProgressTimer, pending state. Problem: a full bar reads as "done". Fix: in pending, show the next mode's empty bar with "Timer starts when you tap Switched".

### RTL / Hebrew

29. **must.** Files: `light-he-pickers`, `font2-light-he-pickers`. Element: duration value and presets. Problem: whole duration strings sit in an LTR isolate, so Hebrew units land on the wrong side. The value reads "שע׳ 15 דק׳ 2" and the presets read "דק׳ 30" / "שנ׳ 10". Fix: render Hebrew durations in RTL ("2 שע׳ 15 דק׳", "30 דק׳") and isolate only the digits or clock times. This is a UX.md §0.1/§5 contract bug: "durations as LTR runs" must apply only to clock-format durations ("1:20"), never to strings with Hebrew units. UX owner should amend.
30. **must.** File: `light-he-pickers` (time window). Element: overnight summary. Problem: "07:00–22:30, מסתיים למחרת" is displayed. Bidi reorders the unisolated span, so the window looks like a daytime 07:00 to 22:30. Fix: wrap the range in U+2066…U+2069 so it renders "22:30–07:00, מסתיים למחרת" (VISUAL §3, §11.7). Add a unit test on the formatted string.
31. **must.** Files: `light-en-pickers`, `font2-light-en-pickers`. Element: time stepper. Problem: the row shows "7:30 AM" (12h device), but the hour stepper shows "07" with no AM/PM, so 19:30 would show "19" next to "7:30 PM". Fix: follow `DateFormat.is24HourFormat`. In 12h mode show "7" plus an AM/PM toggle. UX §5 also asks for the Material time picker in input mode; use it or match it.
32. **should.** File: `light-he-live-sheet`. Element: worked-example arrow. Problem: "→" points backwards against Hebrew reading. Fix: in RTL use "←" (mirror the glyph via a string resource per locale).
33. **should.** Files: `light-he-today`, `light-he-rows`, `light-he-controls`, `light-he-live-sheet`. Element: gendered copy. Problem: masculine second person and present tense: עובד, יוצא עכשיו, כשאתה בחוץ, לא בטוח איפה אתה, תקבל… כשתצא, כשתלחץ, כשאתה רחוק, עומד. Fix: use gender-neutral forms. Replacements: "בעבודה", "יציאה עכשיו", "כשבחוץ", "המיקום לא ברור", "התזכורת מגיעה מיד ביציאה החוצה", "הטיימר מתחיל בלחיצה על 'עברתי'", "מושהה בזמן התרחקות מהמחשב", "עמידה · נותרו 12 דק׳". First-person past is neutral, so keep שתיתי/מרחתי.
34. **should.** Files: `light-he-marks`, `light-he-controls`, `light-he-rows`. Element: state words. Problem: some words are ambiguous or unnatural. "לא בטוח" also means "unsafe". "נדחה" is used for snoozed while "נפסל" (disqualified) is used for rejected. "דולגה", "נלקחה" (as a button), "משבצת 20:00" and "מתוזמן" are unnatural. Fix: לא בטוח → "לא ברור". Snoozed → "נדחה ל־14:20" (always with a time). Rejected → "סורב". Skipped → "דילוג". Taken button → "לקחתי" (status "נלקחה 08:04" stays). "20:00 משבצת" → "המנה של 20:00". Scheduled → "מתוכנן".
35. **should.** Files: `light-he-today`, `light-he-rows`, `light-he-controls`, `light-he-pickers`. Element: other copy. Fixes: "התזמון המדויק כבוי" → "תזמון מדויק" + "כבוי" (#7). "הגיע הזמן ללכת" (reads as "time to leave") → "הגיע הזמן להליכה". "אפשרויות נוספות (1 שונו)" → "(שינוי אחד)" / "(3 שינויים)", with plural rules. Time-window labels "מ־"/"עד" → "התחלה"/"סיום". "לא מותקן קול" → "אין קול מותקן". **nice:** "מיקום כל הזמן" → "מיקום ברקע".

### Dark mode

36. **should.** Files: `dark-en-marks`, `dark-en-field`, `dark-he-rows`. Element: posture and routine marks in dark. Problem: the inactive posture outlines (light ink) are brighter than the active fill, which inverts emphasis. The routine middle disc at 70% alpha goes muddy and darker than the lead disc, so it no longer recedes. Fix: draw inactive posture outlines at 60% alpha in dark. Draw the routine middle disc as a solid precomputed `lerp(shape, paper, 0.3)`, not alpha.
37. **should.** Files: `dark-en-marks` (Not sure), `dark-en-field` (unknown plane). Element: dark hatch. Problem: the hatch lines are drawn over a filled stone body, which gives heavy black-and-stone stripes. Fix: no body fill. Draw `#5E584F` 1dp lines on transparent, with a 1.5dp unknown-ink (`#B5AD9F`) outline. Light mode is correct.
(Dark contrast and identity colors otherwise pass. #8, #17 and #24 also apply in dark.)

### Font scale 2.0

38. **should.** File: `font2-light-en-today`. Element: context line wrap. Problem: line 2 starts with "• Working", so the separator is orphaned. Fix: join with a non-breaking space before the "·" and a normal space after it, so the separator stays with the preceding item.
39. **should.** File: `font2-light-en-controls`. Element: day chips. Problem: the chips stay 36dp tall while the letters double, so the glyphs touch the edges. Fix: chip height `heightIn(min = 36.dp)` + 6dp vertical padding. Wrap to two rows of 4+3 if the width runs out. The touch box stays ≥ 48dp.
40. **nice.** File: `font2-light-en-overlays`. Element: dialog buttons. Problem: the stacked buttons have uneven widths and are end-aligned. Fix: when they stack, make both full width with "Keep editing" on top.
41. **nice.** File: `font2-light-en-field`. Element: collapsed strip. Problem: a plain tan bar can read as a loading skeleton. Fix: keep a 24dp active mark inside the strip's start.

### Reduced motion

42. **should.** Files: `noanim-light-en-field`, `noanim-light-en-today` (unverified). Element: animator-scale-0 handling. Problem: the stills match the animated set, so this proves nothing about motion. The Field caption still says "ambient motion on". Fix: the caption should reflect the real state ("ambient off: reduced motion"). Evidence to supply: two captures 2s apart, diffed (zero changed pixels in the Field), plus a test asserting `ReducedMotion` comes from ANIMATOR_DURATION_SCALE, the in-app setting and battery saver.
43. **should.** File: `light-en-live-sheet` (reduced-motion run). Element: bottom sheet. Problem: Material's slide is kept under reduced motion (rejected deviation). Fix: under reduced motion, show and hide the sheet with a 150ms alpha crossfade. Either drive `SheetState` with `snap()` and wrap the content in `AnimatedVisibility(fadeIn(150), fadeOut(150))`, or use a custom sheet. Do the same for dialogs and screen transitions.

## Counts and follow-ups

The list has 43 numbered items: **must 6** (#1, 2, 3, 29, 30, 31), **should 29**, **nice 8** (#9, 10, 25, 26, 27, 28, 40, 41).
Item 10 needs no code change. #13 and #35 also contain smaller nice sub-items.

Contract issues:
- UX.md §0.1/§5: the duration LTR-isolate rule breaks Hebrew (#29).
- UX.md §5/§6: "preset chips" should become "preset text buttons".

Requests to the UX owner:
- Amend UX.md §0.1/§5 and §5/§6 as above.

My own spec amendments (VISUAL.md, my area, next pass): §2.3 seed/notch "top-end = reading side" and the hatch pitch, §3 variable fonts, §4.3 Field color count.

Next: the android-ui-engineer fixes the must items, then recaptures the full matrix, including the missing dark-he, font2-he and noanim pages.
