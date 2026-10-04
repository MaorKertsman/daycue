# DayCue UX specification

Status: v1 by the UX designer, 2026-10-04. It defines structure, flows, states, copy intent and accessibility. It sets no colors, type, shapes or motion values; those belong to `docs/design/VISUAL.md`, which owns visual tokens and component styling (chips, rows, buttons, Field). This file owns navigation, flows and copy. Behavior is defined by `docs/PRODUCT.md`, and rule IDs are cited as `[SUN-3]`. When this file and PRODUCT.md disagree, PRODUCT.md wins; raise the difference with the UX designer.

All copy below is English intent. The engineer writes Hebrew equivalents with the same meaning and length class. All example data is synthetic.

---

## 0. Principles applied to every screen

1. **Glance, don't manage.** The Today screen answers four questions from top to bottom: *where does the app think I am*, *what is active*, *what is next*, *what can I do*. Nothing else goes on it.
2. **Three tiers of detail.** Template/default, then **Simple** (the 3–6 fields most people change), then **More options** (a collapsed section holding every policy enum). Every advanced value differs from its default only by explicit choice. A changed value shows a "Changed" marker and a "Reset to default" action.
3. **Explain consequences, not mechanics.** Every policy choice shows one plain sentence describing what will happen. No enum names appear in the UI.
4. **Unknown is a calm state.** The app shows "Not sure" with a way to set the value, never a warning color, never a prompt [CTX-9].
5. **Undo over confirm.** Reversible edits apply immediately and show an Undo snackbar (8 s; it stays until dismissed when TalkBack is on). Confirmation dialogs appear only for destructive actions and for medication schedule changes [MED-9].
6. **Never imply completion.** Dismissal or opening the app never shows as "done" [GEN-1, MED-2]. Medication wording never judges: use "Not confirmed", never "Missed" [MED-1]. No medical advice appears anywhere [MED-10].

### 0.1 RTL rule (applies to all wireframes)

Wireframes are drawn LTR. In Hebrew the entire layout mirrors: start and end swap, back arrows and chevrons flip, progress bars and timers fill from the right, sliders increase toward the left, and list reorder handles move to the start side. **Not mirrored:** text glyphs, digits, clock times and durations (always render `07:30`, `2 h 15 min` as LTR runs inside bidi isolates), play/pause icons, the treadmill/walking glyph direction, and map content. Per-screen exceptions are noted where they apply.

---

## 1. Information architecture and navigation

### 1.1 Top-level destinations (bottom navigation, 3 items)

| Tab | Purpose | Contains |
|---|---|---|
| **Today** | Live status and quick actions | Context line, Now, Running, Next (readiness problem as its last row, only when something is wrong), Quick controls |
| **Cues** | Everything that reminds you | Habits (Sunscreen, Hydration, Water bottle), Posture cycle, Medication, Routines, Alarms, Calendar cues |
| **Setup** | How the app senses and delivers | Places & context, Cue sounds & voice, Quiet hours, Integrations, Reminder readiness, Activity & undo, Settings |

A 4th destination is deliberately left unused. History lives inside each item (Medication history, Activity & undo), not as a tab.

### 1.2 Route map

```
Today ─┬─ Context sheet (correct context, overrides)
       ├─ Item detail (Why now?, actions, history excerpt)
       ├─ Posture live control
       ├─ Routine playback
       └─ Dose detail
Cues ──┬─ Habit editor (sunscreen | hydration | bottle)
       ├─ Posture editor
       ├─ Medication list ─ Medication editor ─ Medication history
       ├─ Routine list ─ Routine editor ─ Step editor (sheet)
       ├─ Alarm list ─ Alarm editor
       └─ Calendar cues ─ Calendar preview ─ Rule editor
Setup ─┬─ Places ─ Place editor ─ Map picker
       ├─ Context settings (advanced) ─ Context log
       ├─ Cue profiles ─ Cue profile editor
       ├─ Quiet hours
       ├─ Integrations ─ Spotify | Companion pairing | Remote access ─ Scopes
       ├─ Reminder readiness ─ Fix flows
       ├─ Activity & undo ─ Change detail / Remote change review
       └─ Settings ─ Export | Import preview
Full-screen, outside the tabs: Onboarding, Alarm ringing
```

### 1.3 Back behavior

- System back goes to the previous screen. On a tab root it goes to Today; on Today it exits. Each tab keeps its own back stack, and reselecting the active tab pops it to its root.
- Editors save each change as it is made (one `ConfigOp` per committed field), so back never loses work and there is no Save button. **Exception:** medication editor and import keep a draft and show **Review & save**; back with unsaved changes asks "Discard changes?" (Discard / Keep editing).
- Bottom sheets close on back. Routine playback on back minimizes to the Today "Running" card; the run continues.
- Alarm ringing screen: back does nothing. Stop and Snooze are the only exits.
- Onboarding: back goes to the previous step. The first step exits the app, and progress is kept.

### 1.4 Deep links from notifications

A notification opened from a cold start builds the stack synthetically: Today, then the target screen, so back returns to Today.

| Source | Opens |
|---|---|
| Any habit cue body | Item detail for that item, with "Why now?" expanded [GEN-9] |
| Medication cue / merged cue | Dose detail for the slot, or the Today medication section for merged cues [MED-7] |
| Routine step / ongoing | Routine playback |
| Routine start prompt / recovery prompt | Routine playback in its pre-start or recovery state |
| Posture cue | Posture live control |
| Calendar cue | Calendar preview, scrolled to that event |
| Session start / suggestion | Today, with the context sheet open |
| Alarm | Alarm ringing (full-screen intent) |
| Remote change needs confirmation | Remote change review |
| Readiness problem | Reminder readiness, with the row highlighted |

---

## 2. Shared state language

Every screen uses these five non-content states. The per-screen table in §3.16 lists only the screen-specific copy.

| State | Presentation | Rule |
|---|---|---|
| Empty | One sentence of what this is for, plus one primary action | Never a blank list |
| Loading | Skeleton rows matching the real layout, no spinner unless > 1 s, no layout jump | Local data shows immediately; only integrations load |
| Offline | Inline note on the affected row only: "Needs a connection". Core features show nothing | Reminders never depend on network [CLAUDE.md] |
| Uncertain | "Not sure" + source age ("last location 40 min ago") + **Set it** | Neutral styling, never error styling |
| Error | What failed + what still works + one action ("Try again" / "Fix") | No codes; detail available on tap |

---

## 3. Screens

### 3.1 Onboarding

Steps: **Language → What to start with → Places (conditional) → Permissions (only those needed) → Test → Done.** Every step has **Skip** except Language. Nothing is asked twice; everything remains reachable from Setup.

```
+--------------------------------------+   Step 2 of 5
| What should DayCue help with?        |
| You can change all of this later.    |
|                                      |
| [x] Sunscreen when outdoors      (i) |   card = whole row toggles
| [x] Drink water every hour       (i) |   (i) opens 2-line summary
| [ ] Take your water bottle       (i) |       of the template defaults
| [ ] Sit / stand / walk cycle     (i) |
| [ ] Morning routine              (i) |
| [ ] Morning alarm                (i) |
| [ ] Calendar cues                (i) |
| Medication: add later from Cues  >   |   never a toggle (list ships empty)
|                                      |
| [ Continue ]               Skip      |
+--------------------------------------+
```

- **Places step** appears only if sunscreen, bottle or posture is chosen. It shows the Home / Office / Gym templates [§12] with "Set to where I am now" and "Later" for each. The copy says: "Places are only stored on this phone."
- **Permissions step**: one card per permission the chosen templates need, shown in this order: Notifications → Exact alarms (if anything is time-based) → Location while using → Location all the time (separate card, after the first is granted) → Physical activity (only if away policy is On-foot [CTX-6]) → Calendar (if chosen) → Full-screen alarm (if alarm chosen). Each card states: why, what happens without it, then **Allow** and **Not now**. If the system denies, the card shows "You can turn this on later in Reminder readiness" and the flow continues. Battery optimization and DND access are not asked here; they appear in Readiness.
- **Test step**: "Send a test reminder" delivers one test cue in 5 s [GEN-10], then asks "Did you see and hear it?" with Yes and No. No opens Readiness.
- **Done**: goes to Today. No tour.
- First medication add (later) inserts the travel-policy question inline in the editor [§12].

### 3.2 Today (daily screen)

```
+--------------------------------------+
| Sun 4 Oct · 12:20               [?]  |  [?] = Why/help; single trailing icon
| Home · Indoors · Working        [v]  |  ContextLine: tap = Context sheet
|--------------------------------------|
| NOW                                  |  0..n due cues, priority order [§8.1]
| Drink a glass of water               |
| Due now · last at 10:40              |
| [ Drank ]  [ Snooze 15 min ]  [ ⋯ ]  |  ⋯ = Pause, Why now?
|--------------------------------------|
| RUNNING                              |  posture / routine / session / override
| Standing · 12 min left · next Walk > |
| Working session since 09:10      >   |
|--------------------------------------|
| NEXT                                 |  max 3 rows, then "See today"
| Sunscreen    when outdoors           |  waiting reason instead of a time
| Meeting cue  14:50                   |
| Medication   20:00                   |
| Exact timing is off · Fix            |  readiness row, only if a problem
|--------------------------------------|
| [Outdoors] [Indoors] [Start working] |  QuickControls (buttons, not chips)
| [Leaving now]        [More ⋯]        |
+--------------------------------------+
| Today        Cues         Setup      |
```

**Context line.** Format: `Place · Environment · Activity`. Omit Activity when it is Inactive or Unknown and no session exists. An override shows "(set by you)" and its expiry in the sheet, not the line. Unknown values read "Not sure where you are" (place) and "Indoors or out: not sure" (environment), and are never hidden. With a stale signal the line stays readable and the sheet shows the age.

**Context sheet** (tap the line):

```
| Where you are                         |
| Place        Home        (location, 2 min ago)  [Change]
| Environment  Indoors     (from place)           (Auto|Indoors|Outdoors)
| Activity     Working     (desk, 1 min ago)      (Auto|Working|Studying|None)
| For: (•) Until I change place (max 8 h)  ( ) 1 h  ( ) 2 h  ( ) Custom
| [ Pause automatic detection… ]                   
| Something wrong often? Adjust places >          
```

- Selecting a value applies it immediately with Undo [§1.4]. The duration defaults per PRODUCT: environment uses `UntilTransition`, sessions run until ended.
- Pause automatic detection opens a duration choice (30 min / 1 h / **2 h** / 4 h / Until I turn it back on / Rest of today). While paused the line reads "Automatic detection paused until 14:20 · Resume".
- Place "Change" lists saved places plus "Somewhere else". It overrides Place only.

**Quick controls.** They are contextual, and at most 4 visible plus More:
- `Outdoors` / `Indoors`: the one matching the current state is hidden or shown as selected. Tapping sets it with Undo.
- `Start working`; during a session it becomes `End session`. `Start studying` lives in More unless the current place's default kind is Studying.
- `Leaving now` [BTL-1] is always visible when the bottle habit is enabled, otherwise it is in More.
- More sheet: Start studying, Pause automatic detection, durations, "Add Leaving now to Quick Settings" (tile) and "Add widget".

**Now card** actions are the cue's own actions [§3–§7]. Medication slots that are Due appear here with **Taken** and **Snooze** only. A posture SwitchPending appears here [POS-8]. Several due items stack as cards; collapse them beyond 2 with "2 more due".

**Readiness on Today** is never a banner. When a Ready-critical row in §3.13 is not Ready, it shows as the last Next row, with a consequence sentence and "Fix" leading to Readiness.

**Next rows** show a time or a waiting reason: "when outdoors", "paused until 15:00", "after quiet hours (07:00)", "after your meeting", "after the routine". Tapping opens Item detail.

**Item detail**: name, state, last ack, next due, actions, **Why now?** (rule in plain words plus context values and their sources) [GEN-9], and the last 5 history entries.

### 3.3 Cues tab and habit list

```
| Habits                               |
|  Sunscreen      Every 2 h outdoors [x]|  row tap = editor; switch = enable
|  Hydration      Every 60 min       [x]|
|  Water bottle   On leaving Home    [ ]|
| Posture         Sit 30 · Stand 30 · Walk 30  [x]
| Medication      2 items · next 20:00   >      |
| Routines        1 routine              >      |
| Alarms          07:00 Sun–Thu      [x] >      |
| Calendar cues   3 calendars · 4 rules  >      |
```

A paused item shows "Paused until 15:00" in its summary plus a Resume action in the row overflow.

### 3.4 Habit editor (sunscreen, hydration, water bottle)

```
| < Sunscreen                     [x] |  enable switch in top bar
| Remind every          [ 2 h      ] |  DurationField
| When                  Outdoors     > |  condition summary
| Active hours          07:00–19:00  > |  TimeWindowField
| Days                  Every day    > |
| Says                  "Sunscreen time" > |
| Sound & voice         Bright chime · speaks > |
| [ Test this cue ]   [ Pause… ]       |
| ▸ More options (3 changed)           |
|   First reminder      As soon as you're outdoors
|   Coming back outside Remind right away
|   Going inside        Hide it until you're back out
|   Repeat if ignored   Once after 20 min
|   If still ignored    Count the next interval from the last reminder
|   Next due counts from  When you tap Applied
|   Snooze length       15 min
|   During meetings     Silently
|   Context needed      Medium confidence or better
|   [ Reset all to defaults ]
```

- Each advanced row opens a sheet with radio options. Each option has a one-sentence consequence taken from PRODUCT §2–§5 (for example `AfterFullInterval`: "First reminder comes one full interval after you go outside."). The sheet's footer shows a worked example using the current values (for example "Outside at 10:00 → first reminder about 10:05").
- **Pause…**: options per item [SUN-10, HYD-4]. While paused the editor shows "Paused until 15:00 · Resume".
- **Hydration** extras: Days; in More options, "Different interval by context" (ordered list of condition → interval, with drag reorder; first match wins [HYD-5]).
- **Water bottle**: Simple = Places (multi-select), Triggers (`Leaving now` always on and shown locked with "the most reliable"; `When leaving a place` toggle with hint "Often arrives a few minutes late"; `At a set time` add rows of days + time). More = cooldown per place, duplicate window, repeat.
- **When** (condition editor) offers Simple choices (Outdoors / Indoors / At a place / Working / Any time). Advanced allows combining dimensions and lowering the confidence threshold, which shows "May remind on weaker signals".

### 3.5 Medication

**List**: rows show label, times, and today's slots as state text (not chips): `08:00 Taken 08:04` · `20:00 Upcoming`. Footer: "History" and the line "DayCue reminds you; it does not give medical advice."

**Editor (draft mode, Review & save)**:

```
| < New medication                     |
| Name (only on this phone)  [_______] |
| Times      08:00  [remove]           |  one row per time
|            20:00  [remove]           |
|            [ + Add time ]            |
| Days       Every day               > |
| Start / end date   Starts today · no end >
| When you travel  (required)          |
|  (•) Follow local time — 08:00 wherever you are
|  ( ) Keep home time zone [Asia/…] — same moment as at home
| ▸ More options                        |
|   Repeat if not confirmed  every 10 min, 3 times
|   Snooze length            10 min
|   On the lock screen       "Medication reminder" (no name)
|   Speak the name           Off
|   During quiet hours       Sound as usual
|   Keep history for         90 days
| [ Review & save ]                     |
```

- Review sheet: plain diff ("Adds 20:00 every day. Travel: follow local time.") with **Save** / **Back**. This is required for every schedule edit and deletion [MED-9].
- "Stop reminders for this medication" sets an end date (with a date picker, default today); it is not a pause [MED-9]. "Delete" sits in overflow, asks for confirmation, then offers Undo for 10 s.
- **Dose detail** (from Today/notification): label, slot time, state, **Taken** (with "Taken at" editable, default now), **Snooze**, **Skip this dose** (secondary, no confirm, undo). Wording: Upcoming · Due · Taken 08:04 · Skipped · Not confirmed.
- **History**: grouped by day (day boundary per settings), filter by item. Tapping an entry allows "Correct" (state/time), which is logged in Activity [MED-5]. No percentages, streaks or adherence scores.

### 3.6 Posture cycle

**Editor**: Simple = mode list (drag handle, name, DurationField, enable switch; at least one must stay enabled, and the last switch is disabled with the hint "Keep at least one"), "Runs" (During work/study sessions · During set hours · Only when I start it), phrases per mode. More = timer start (Starts when you confirm the switch / Starts at the reminder), confirm repeats, meetings (Hold the reminder until the meeting ends / Pause the timer / Remind anyway), break handling (short break threshold; after a longer break: Start again from Sitting / Restart this mode / Continue where you were), extend options, snooze. Each option has a consequence line.

**Live control** (from Running card):

```
| < Posture                            |
|        STANDING                      |
|   [=========-----]  12 min left      |  ProgressTimer
|   Next: Walking (30 min)             |
| [ Pause ]  [ Switch now ]            |
| [ +5 ] [ +10 ] [ +15 ]   [ Skip next ]|
| ⋯  Reset cycle                       |
```

- **SwitchPending** state: the headline reads "Time to walk", and the primary button is **Switched** (full width), followed by Snooze 5 min, Skip and Extend. The text says "Timer starts when you tap Switched."
- **Frozen**: "Paused while you're away from the computer · 12 min kept" or "Outside session hours". Pause shows "Paused by you · Resume". With one mode enabled, the UI reads "Keep going / take a break" [POS-5].

### 3.7 Routines

**List**: name, trigger ("Manual", "Sun–Thu 07:00, asks first", "After 07:00 alarm"), total duration, overflow: Start, Test, Duplicate, Delete (Undo, no confirm).

**Editor**:

```
| < Morning routine                    |
| Starts     Manually              >   |
| Steps (total ~11 min)                |
| ≡ 1 Shower          5 min  timed   ⋮ |  ≡ drag handle; ⋮ = Move up/down,
| ≡ 2 Face cleanser   2 min  timed   ⋮ |    Duplicate, Delete
| ≡ 3 Brush teeth     2 min  timed   ⋮ |
| ≡ 4 Get dressed     waits for Done ⋮ |
| [ + Add step ]                       |
| Timing           Follow what actually happens > |
| [ Test ]  [ Fast test (10 s steps) ] |
| ▸ More options: when interrupted, start prompt |
```

- **Step sheet**: name, phrase ("Same as name" default), duration (0 = "No timer"), ends: "Automatically after the time" / "When I tap Done (time is a nudge)", repeat count, optional step toggle, sound & voice profile.
- **Timing sheet** shows both options with a live example built from the routine's own steps. Example: "If *Shower* runs 3 min long: **Follow what actually happens**: every later step moves 3 min later; ends ~07:14. **Keep to the schedule**: *Face cleanser* gets 0 of 2 min and is skipped silently; ends on time at 07:11." [§10.1]
- Edit during a run shows the banner "Changes apply next time" [RTN-7].
- Trigger sheet: Manual / On a schedule (days, time; Ask first / Start automatically) / After an alarm (alarm picker).

**Playback** (full screen; ongoing notification while running):

```
| ×                         Step 2 of 4|
|        Face cleanser                 |
|        1:20 left    (repeat 1 of 1)  |
|   [===========-----]                 |
|   Next: Brush teeth · 2 min          |
| [ ‹ Back ] [ Pause ] [ Done › ]      |
| [ +1 min ] [ +5 min ]     [ Skip ]   |
```

- Explicit step: no countdown; shows "Tap Done when finished" and a soft "2 min suggested". Optional step: Skip is promoted beside Done.
- Test run: persistent "Test · not recorded" bar [RTN-8].
- × asks "Cancel this routine?" (Cancel routine / Keep going). This is destructive to the run, so it confirms.
- Recovery state [RTN-6]: "Paused at 'Brush teeth'" with Resume / Restart routine / Cancel.
- Starting a second routine: "Morning routine is running. Stop it and start this one?" [RTN-3].
- `‹`/`›` icons mirror in RTL; Pause does not.

### 3.8 Places and context

**Places list**: name, "You're here" tag, environment, radius, status ("Location not set" for templates). Empty: "Add a place so DayCue knows home from office."

**Place editor**:

```
| < Office                             |
| [ map preview with circle ]          |
| [ Use my current location ] [ Pick on map ] |
| Radius   [-]  150 m  [+]   (slider)  |
| Usually  (•) Indoors ( ) Outdoors ( ) Mixed |
| Work here  Start automatically (with undo) > |
| ▸ More options                        |
|   Activities allowed  Working, Studying
|   Session kind        Working
|   Bottle reminder when leaving  On
|   Routines allowed here         All
```

- "Mixed" explanation: "DayCue won't guess indoors or outdoors here."
- Map picker: full screen, pin fixed at center, search field. Offline: "Map needs a connection. Use your current location instead." Coordinates are never shown as raw numbers.
- **Context settings** (Setup, advanced): dwell times, away-from-places policy (When I'm walking, count as outdoors / Don't guess / Assume outdoors, with warning text from CTX-6), auto-detection sources on/off.
- **Context log**: last 24 h of transitions ("12:02 Left Home · location"). Each row has "This was wrong", which offers: set the correct value now (override), "Make Home's area smaller/larger" (opens editor), "Mark this place Mixed". It never asks questions proactively [CTX-9].

### 3.9 Calendar cues

**Calendar cues screen**: access status; calendars (each: Use rules / Always cue / Never cue / Off); Rules (ordered, drag, toggle; starter rules [§9.2]); Upcoming preview >; More: events with their own reminders [§9.1 supplement], default for unmatched events, speak titles, show titles on lock screen, tentative events.

**Preview** [CAL-1]:

```
| Next 7 days                          |
| Mon 09:30  Team sync                 |
|   Cue 10 min before · Meetings rule (has attendees)
|   [Never for this event] [⋯]         |
| Mon 13:00  Lunch                     |
|   No cue · no rule matched           |
|   [Always for this event] [⋯]        |
| ⋯ = Never for this calendar · Edit rule
```

- On a recurring event, a one-tap correction asks "Just this one / All in the series".
- Titles are shown as plain text, never linkified or interpreted [CAL-5].
- Stale cache (> maxCacheAge): banner "Calendar last updated 30 h ago. New calendar cues are paused until it syncs. Sync now."

### 3.10 Cue sounds, voice and quiet hours

**Cue profiles**: one row per type in priority order [§8.1] with sound · vibration · speech summary.

**Profile editor**: Sound (list of bundled tones, each with a play button), Vibration (patterns with preview), Speak (on/off), Phrase (placeholders as tappable chips `{minutes}`), Voice language (He/En/App language) and rate (slider 0.5–2×, 1× tick), Over music (Lower music and speak / Pause music / Notification only), In meetings, Headphones only. **[Preview]** plays exactly what a real cue would [GEN-10]. If a voice is missing: "Hebrew voice not installed. Cues will use sound only. Install voice."

**Quiet hours**: window (TimeWindowField; per-day option in More), respect system Do Not Disturb, and a static explainer list: "Always come through: alarms, medication, running routines. Silent: calendar. Wait until quiet hours end: sunscreen, posture, hydration."

### 3.11 Alarms

**List**: big time, days, switch, "Skip next" in overflow (row shows "Skipping Mon").

**Editor**: Time, Days (weekday chips in locale order, Sun first for he-IL), Sound (Local tone / Spotify + backup tone), Spotify item ("Choose from Spotify" or paste link; shows item name), Backup tone (required, defaults Morning), Then start (routine picker, "Starts when you tap Stop"), More: volume ramp, snooze length and count, ring timeout, vibrate. **[Test now]** rings immediately with the full ringing screen, then shows the result: "Spotify played" or "Backup tone played: Spotify didn't start within 10 s (not signed in)" [ALM-5].

**Ringing screen** (full screen, lock-screen capable):

```
|                07:00                 |
|            Morning alarm             |
|  Playing backup tone — Spotify       |  only when fallback is used [ALM-2]
|  didn't start (no connection)        |
|                                      |
|  [        Snooze 9 min        ]      |  2 left
|  [           Stop             ]      |  then: "Morning routine starts"
```

Buttons are at least 72dp tall and use different shapes/positions so they are distinguishable half-asleep; no swipe gesture is required. Volume keys snooze.

### 3.12 Integrations

| Section | Content |
|---|---|
| Calendar | Access status, selected calendars count, last sync, link to Calendar cues |
| Spotify | Connect / Connected as (account display name only), test playback; offline: "Spotify needs a connection; alarms use the backup tone" |
| Desktop companion | Pair: shows a short code and QR, expiring in 10 min. Status: "Last signal 1 min ago" / "Not seen for 2 h". Toggle "Check more often at work places" with battery note. Unpair (confirm) |
| Remote access (MCP) | Off by default. On: connected clients, each with scopes as switches: View setup · Edit habits & routines · Edit alarms & calendar rules · Medication (off; "Names leave the phone") · Context. Wake method: "Instant (Firebase set up)" or "Checks every 15+ min". Revoke client (confirm) |
| Pending remote changes | Queued/delivered commands with state words: Waiting for phone · Applied · Rejected (reason) · Expired. Never "Done" before Applied |

**Activity & undo**: reverse-chronological config changes, each showing source (You · Claude via remote · Import), a one-line diff and **Undo**. Undo creates a new change and does not erase history. **Remote change review** shows diff + Approve / Reject for sensitive ops [ARCHITECTURE §3.2].

### 3.13 Reminder readiness

```
| Reminders are ready                  |  or "2 things may delay reminders"
| [ Send a test reminder ]             |
| Notifications        On              |
| Exact timing         Off    [Fix]    |  "Reminders may arrive up to ~10 min late"
| Full-screen alarms   On              |
| Location all the time  While using  [Fix]  "Place changes only while app is open"
| Physical activity    Not needed      |
| Battery              Restricted [Fix]|  + "Phone-specific steps" link
| Calendar             Synced 5 min ago|
| Voices               Hebrew missing [Install]
| Desktop companion    Not paired      |  "Sessions are manual" — not a problem
| Remote sync          Every 15+ min   |
| Spotify              Connected       |
```

Each row reads: name, status word (Ready / Limited / Off / Not needed), a consequence sentence when not Ready, and one Fix action that opens the exact system screen or an in-app guide. Statuses refresh on resume. Optional integrations never count toward the problem count.

### 3.14 Settings

Language (Phone default / English / עברית; applies immediately), Day starts at, Workdays, Quiet hours >, Reduce motion (Follow phone / On), Export ("Save setup as a file"; option "Include history"; if medication is included: "This file contains medication names. Store it privately."), Import (choose file → preview diff → Apply, all through validation; invalid items listed with reasons, and nothing is applied partially without consent), Backup status ("Your setup is stored only on this phone. Export to keep a copy."), Reset onboarding, About.

### 3.15 Widget and Quick Settings tile

Tile: "Leaving now" (one tap, then a toast "Bottle reminder sent"). Optional second tile: "Outdoors / Indoors" toggle. Widget (small): context line + next item + Leaving now.

### 3.16 Screen-specific state copy

| Screen | Empty | Uncertain / stale | Offline | Error |
|---|---|---|---|---|
| Today | "Nothing else today." If nothing is enabled: "Choose what DayCue should help with" → Cues | "Not sure where you are · Set it" | — (nothing changes) | Engine/storage failure: "Reminders couldn't be refreshed. Try again" + Readiness |
| Habit list | (never empty; templates ship) | — | — | — |
| Medication | "No medications. Add one to get reminders at set times." | — | — | Save failed: "Not saved. Your previous schedule is still active." |
| Posture live | "Posture cycle is off · Turn on" | Frozen copy §3.6 | — | — |
| Routines | "No routines yet · Start from Morning routine" | — | — | — |
| Playback | — | Recovery state | — | TTS failed: small note "Voice unavailable, using sound" |
| Places | §3.8 | "Location off — places can't be detected. Manual controls still work." | Map only | "Couldn't get your location. Move near a window and try again, or pick on map." |
| Calendar | "Allow calendar access to get cues before events" / "No events in the next 7 days" | Stale banner §3.9 | — (local provider) | "Couldn't read calendars. Try again" |
| Alarms | "No alarms · Add alarm" | — | Spotify row only | Test result §3.11 |
| Integrations | Each section shows "Not set up" + Set up | Companion "Not seen for 2 h" | "Will send when online" on pending commands | Pairing: "Code expired. Get a new code." |
| Readiness | — | Rows "Checking…" briefly | Remote rows only | — |
| Import | — | — | — | "This file isn't a DayCue setup" / per-item reasons |

Loading uses skeletons everywhere per §2; only Calendar preview, Spotify, pairing and Map have real loading time.

---

## 4. Notifications

Each cue type has its own channel (named after the type) so the system settings stay meaningful. At most 3 actions; the body tap is never an ack. Re-alerts update the same notification [GEN-4]. Groups follow [COL-2].

| Type | Title / body pattern | Actions (≤ 3) | Lock screen | Tap |
|---|---|---|---|---|
| Alarm | "07:00 · Morning alarm" | Snooze · Stop | Full-screen intent | Ringing screen |
| Medication | Generic: "Medication reminder" / "08:00 · tap to confirm". Full: label as title | Taken · Snooze | Default `Generic` (private version without label); `Hidden` = secret | Dose detail |
| Medication merged | "2 medication reminders not confirmed" | Open | Generic | Today medication section |
| Routine running (ongoing) | "Face cleanser · 1:20 left" / "Next: Brush teeth" | Done · Pause · +1 min | Public (user-started) | Playback |
| Routine start prompt | "Morning routine · Start?" | Start · Skip today | Public | Playback pre-start |
| Calendar | "Meeting in 10 min" / title only if allowed | Snooze 5 min · Open calendar | Title hidden unless allowed | Calendar preview |
| Water bottle | "Take your water bottle" / "Leaving Home" | Got it · Not needed | Public | Item detail |
| Sunscreen | "Time for sunscreen" / "Last applied 10:07" | Applied · Snooze 15 min · Pause | Public | Item detail |
| Posture | "Time to walk" / "Standing done · 30 min next" | Switched · Snooze 5 min · +5 min | Public | Posture live (Skip there) |
| Hydration | "Time for some water" | Drank · Snooze 15 min · Pause | Public | Item detail |
| Session auto-started | "Working session started at Office" (low priority, silent) | Not working | Public | Context sheet |
| Session suggestion | "Start working?" | Start · Not now | Public | Context sheet |
| Remote change | "A remote change needs your OK" | Review | Generic | Review screen |
| Test | "Test · Sunscreen" | the real actions, test-labeled | as type | Item detail |

A "Why now?" line is in the expanded notification as secondary text where space allows; full detail is in Item detail [GEN-9].

---

## 5. Interaction and accessibility rules

- **Targets**: every tappable element is ≥ 48×48dp, with ≥ 8dp between adjacent targets. Alarm buttons are ≥ 72dp tall. Chips (weekdays only, per VISUAL.md) have a 48dp touch box even though the visual is 36dp.
- **Focus order**: top bar → ContextLine → Now (title, then actions left to right in reading direction) → Running → Next → QuickControls → bottom nav. In RTL, the order follows reading direction automatically; never hardcode left/right. After a dialog or sheet closes, focus returns to the control that opened it. After an ack, focus moves to the next card, or to the section header if none remains.
- **TalkBack**: each Now card is one merged node: "Drink a glass of water, due now, last at 10:40". Its actions are buttons, also exposed as custom actions. ContextLine: role button, "Context: Home, indoors, working. Double tap to correct." ProgressTimer: "Standing, 12 minutes left of 30" with updates throttled to once per minute (polite live region; on change of mode, assertive). Drag-reorder lists expose "Move up" / "Move down" custom actions and announce "Moved to position 2 of 4". Switches announce the item name, not "Switch". Duration fields announce full words ("2 hours 15 minutes"). Snackbar Undo is announced and stays until dismissed while TalkBack is on.
- **Font scale 200%**: no truncation of titles, actions or consequences; text wraps. The Now card action row reflows to a vertical stack. QuickControls wrap to multiple rows. The ContextLine wraps to 2–3 lines and drops nothing. Every screen scrolls vertically; the bottom nav labels may become icon + label stacked but are never hidden. The Alarm ringing screen scrolls if needed, with Stop always reachable. Test at 200% in Hebrew, which is longer.
- **Reduced motion** (system animator scale 0, the in-app "Reduce motion" setting, or battery saver, per VISUAL.md §6): no animated progress sweeps (step updates per minute instead), no screen transitions beyond a fade, and no pulsing on due items. Timers still count.
- **Time picker**: use the Material time picker in input mode by default, with the dial available. 24 h per locale. In RTL the hour:minute order is unchanged (`07:30` LTR isolate).
- **Duration field**: a value with − / + steppers (step size per range: 1 min under 10, 5 min under 60, 15 min above) plus preset chips (for example 30 min · 1 h · 2 h · 4 h) and "Custom" (hours + minutes number fields). No wheels. In RTL the steppers mirror (− at end, + at start follows mirroring); the value stays LTR.
- **Time window**: two time fields "From / To" with an "overnight" note when To < From ("22:30–07:00, ends next day").
- **Days**: 7 toggle chips in locale week order, each with a full-name content description.
- **Confirmation** only for: deleting medication, medication schedule/policy edits (review), cancelling a running routine, unpairing companion, revoking remote client, import apply, reset. All other deletes use Undo.
- **Dark mode and contrast**: per VISUAL.md; status must never rely on color alone. Always pair it with a word (Ready, Limited, Not sure).
- **Haptics**: an ack gives one light haptic; none for navigation.

---

## 6. Reusable components (build once)

| Component | Purpose | Variants |
|---|---|---|
| `ContextLine` | Place · Environment · Activity summary; opens Context sheet | normal, override, uncertain, detection-paused |
| `ContextSheet` | Correct dimensions + override duration | full; compact (from notification) |
| `QuickControls` | Contextual chips for overrides, session, Leaving now | inline row, More sheet |
| `CueCard` | Due item with its actions | habit, medication, posture-pending, merged, test |
| `RunningRow` | Ongoing item with progress | posture, routine, session, override, paused |
| `NextRow` | Upcoming item with time or waiting reason | time, reason, paused |
| `WhyNow` | Plain-language rule + context sources | inline, expanded |
| `SettingRow` | Label + current value + chevron, opens a sheet | value, changed-from-default, disabled-with-reason |
| `PolicyChoiceSheet` | Radio list with a consequence line per option + worked example | single-choice; with numeric sub-field |
| `AdvancedSection` | Collapsed "More options (n changed)" + Reset | — |
| `DurationField` | Steppers + presets + custom | minutes, hours+minutes, seconds (routine) |
| `TimeField` / `TimeWindowField` | Time and From–To with overnight note | — |
| `DayChips` | Weekday multi-select | locale order |
| `ReorderableList` | Drag handle + accessible move actions | steps, modes, rules, intervals |
| `ProgressTimer` | Remaining time + progress bar, RTL-aware | running, frozen, pending, test |
| `PermissionCard` | Why / without it / Allow / Not now | onboarding, readiness fix |
| `ReadinessRow` | Status word + consequence + Fix | ready, limited, off, not-needed, checking |
| `StateBlock` | Empty / error / offline / uncertain message + action | per §2 |
| `DiffReview` | Human-readable change preview + confirm | medication, import, remote change |
| `UndoSnackbar` | Undo for reversible edits | timed, sticky (TalkBack) |
| `CuePreviewButton` | Plays a real cue as test | — |
| `StatusText` | Dose/command states in words (text, never a chip) | Upcoming, Due, Taken, Skipped, Not confirmed, Waiting, Applied, Rejected, Expired |

---

## 7. Screenshot review checklist

Each screenshot is checked in English and Hebrew, light and dark, at 100% and 200% font scale.

1. Today answers the four questions (context, active, next, actions) without scrolling at 100%.
2. Context line shows all three dimensions or "Not sure"; never blank, never an error color.
3. No enum or internal names visible (`FromAck`, `RetractAndHold`, IDs).
4. Every policy option shows a consequence sentence.
5. Advanced options are collapsed by default; changed values are marked and resettable.
6. Medication: no "missed", no advice, no score; lock-screen notification hides the label by default.
7. Nothing implies done without an explicit ack; remote commands are not shown as applied before the phone confirms.
8. Every tappable element is ≥ 48dp; alarm buttons are ≥ 72dp and clearly separate.
9. RTL: layout mirrored; chevrons/back flipped; progress fills from the right; times and durations read LTR; no clipped Hebrew.
10. 200%: no truncated titles or buttons; action rows stack; the screen scrolls; the bottom nav stays visible.
11. Each list has its empty state; offline/uncertain/error copy matches §3.16 tone.
12. Status never conveyed by color alone.
13. Only destructive actions confirm; others offer Undo.
14. Notifications: ≤ 3 actions, correct channel, test cues labeled "Test".
15. No real personal data in any screenshot committed to the repo.

---

## 8. Notes for other owners (contract points)

1. **Posture actions** [POS-3] list 4 actions (Switched · Snooze · Skip · Extend), and Android shows 3. This spec puts Switched · Snooze · +5 min in the notification and Skip in-app. The product lead should confirm.
2. **Full-screen intent permission** (Android 14+) is needed for ALM-1 and is not in PRODUCT's readiness list; this spec adds it to onboarding and readiness.
3. **Medication delete**: MED-9 requires confirmation; this spec also adds Undo after confirming, via `config_history`. The architecture already supports this.
4. **Quick Settings tile and widget** for Leaving now [BTL-1] need app-module work; listed for the UI engineer.
5. The VISUAL.md bottom navigation (Today · Cues · Setup, 3 items) is confirmed by §1.1. The "Reduce motion" in-app setting it mentions belongs in Settings (§3.14).
6. Mirroring of marks/icons follows VISUAL.md (only routine mark and posture sequences mirror); §0.1 covers layout mirroring only.
