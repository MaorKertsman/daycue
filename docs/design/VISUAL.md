# DayCue visual identity

Owner: visual designer. Status: v1 spec, 2026-10-04. Implementer: android-ui-engineer (Compose, Material 3 as technical base only).
Sketches (original, synthetic data): `assets/daily-screen.svg`, `assets/cue-marks.svg`, `assets/app-icon.svg`.

Flows, navigation structure and copy belong to the UX designer (`docs/design/UX.md` when it exists). This file covers how things look and move. Where they overlap (for example the bottom navigation destinations), the UX spec wins.

---

## 1. Directions explored

**A. Cut Paper Day.** Warm uncoated paper, deep warm ink, and a small set of flat cut-paper shapes (discs, slabs, arches) in mineral tones. Each cue type gets one shape and one hue. Time shows up as shapes moving toward each other and filling up. The look is quiet, editorial and tactile, close to collage and the quieter end of modernist cut-outs.
Palette: paper `#F4EFE6`, ink `#1F1D1A`, saffron `#E3A72F`, teal `#5E9EA8`, sage `#86A97F`, plum `#A8739A`, terracotta `#CF7652`. Type: a Hebrew/Latin serif for the one headline per screen, and a geometric sans for everything else.

**B. Night Field.** Dark first. Large soft-edged color fields with grain glow faintly on a blue-black ground, in the manner of color-field painting. Very calm, and good for the late-evening medication and alarm moments. Palette: ground `#12141A`, fields `#2B3A55`, `#5A3E4F`, `#3E5446`, text `#E6E2DA`. Type: sans only, light weights. Weaknesses: it reads poorly outdoors in daylight, which matters because sunscreen and hydration are daytime cues. Soft edges make state hard to read at small sizes. Light weights get fragile with Hebrew at large font scales.

**C. Quiet Bauhaus.** A strict grid with circle, square and triangle, desaturated primaries on white, and heavy rules. It is very legible and easy to build. Palette: `#FAFAF7`, `#202020`, `#C9483B`, `#E1B844`, `#3B5B92`. Type: geometric grotesque. Weaknesses: primaries make every cue feel equally loud, the triangle reads as a warning, and the result drifts toward "designed poster" rather than "gentle companion".

**Selected: A, Cut Paper Day.** B's dark palette informs A's dark mode.
- Readability: dark ink on warm paper gives 14:1 body contrast and works in sunlight. Shapes are hard-edged, so state stays clear at 24dp.
- Usefulness: one shape and one hue per cue type gives a vocabulary you can learn. The two time metaphors (approach and fill) replace charts and stay understandable without color.
- Character: it is calm, handmade-feeling and abstract without being decorative noise, which matches the owner's taste for abstract paper compositions.

---

## 2. Color tokens

Every cue hue comes in two roles:
- `shape`: fills of abstract shapes. Shapes are decorative or redundant, never the only carrier of meaning.
- `ink`: text, icons, the 1.5dp edges of meaningful shapes, and the notification accent. Every `ink` token passes 4.5:1 on paper and surface.

### 2.1 Neutrals

| Role | Light | Dark | Use |
|---|---|---|---|
| `paper` (background) | `#F4EFE6` | `#191816` | Screen background. Material `background`/`surface`. |
| `surface` (raised sheet) | `#FBF8F2` | `#22201D` | Bottom sheets, dialogs, menus, text fields. |
| `sunk` | `#EAE3D6` | `#121110` | Pressed rows, switch track off, input wells. |
| `ink` (text primary) | `#1F1D1A` | `#EEE7DB` | Body text, primary button fill (light) / text. |
| `ink2` (text secondary) | `#57524A` | `#B5AD9F` | Supporting text, inactive nav, section headers. |
| `outline` (hairline) | `#D8D0C2` | `#38342E` | Decorative dividers only (1dp). Never the only boundary of a control. |
| `outlineStrong` | `#8A8276` | `#7D7569` | Control borders (secondary button, switch off, text field). 3.3:1 / 3.9:1 on paper. |
| `scrim` | `#1F1D1A` @ 32% | `#000000` @ 56% | Behind sheets/dialogs. |

On the primary button, paper on ink is 14.7:1 in light. Dark mode inverts: an `ink` fill (`#EEE7DB`) with `paper` text is 14.4:1.

### 2.2 Cue identity colors

| Cue | Mark | Light shape | Light ink | Dark shape | Dark ink |
|---|---|---|---|---|---|
| Sunscreen | sun over horizon | `#E3A72F` | `#7F5300` | `#C99A3E` | `#E9BC62` |
| Hydration / water bottle | vessel | `#5E9EA8` | `#245F68` | `#5B8F97` | `#8CC7CF` |
| Posture (sit/stand/walk) | square, slab, disc | `#86A97F` | `#3B6337` | `#7C9876` | `#A9CCA2` |
| Medication | split capsule | `#A8739A` | `#7A3B6A` | `#966C8B` | `#D9A8CC` |
| Calendar | day square | `#7F86BF` | `#434B8C` | `#7277A8` | `#AEB4E8` |
| Routine (spoken) | three receding discs | `#CF7652` | `#963F1E` | `#B56E50` | `#F0A485` |
| Morning alarm | dawn half-disc | `#3F5A8A` | `#33507F` | `#5370A3` | `#A3BCE8` |

Hues are spaced around the wheel: saffron 40°, terracotta 16°, plum 315°, sage 110°, teal 188°, periwinkle 235°, dusk blue 218° (much darker than the other two blues). Alarm and calendar sit close in hue, so they are always told apart by mark geometry and label, never by color alone.

### 2.3 State colors

| State | Light shape | Light ink | Dark shape | Dark ink | Visual treatment |
|---|---|---|---|---|---|
| Due | cue `shape` | cue `ink` | cue `shape` | cue `ink` | Full fill, 1.5dp cue-`ink` edge, and an `ink` "seed" dot (r = 0.1 × mark size) at the top-end corner. Text "Due now" in cue `ink`. |
| Scheduled | cue `shape` | `ink2` | cue `shape` | `ink2` | Full fill, no edge, no seed. |
| Snoozed | `#9D93B5` | `#5A5175` | `#8A82A0` | `#C2B9DC` | Lavender fill and the cue-`ink` edge. Text "Snoozed until 14:20". |
| Paused | `#B3AA9C` | `#5E574E` | `#6E675D` | `#B5AD9F` | Stone fill and a horizontal "rest bar" (width 0.58, height 0.08 of mark size, at 0.67 height) in paused `ink`. Text "Paused until …". |
| Unknown / uncertain | `#C2B9AA` hatch | `#5E574E` | `#5E584F` hatch | `#B5AD9F` | Outline only (1.5dp, unknown `ink`) with a 45° hatch inside (1dp lines, 3dp pitch). Hatch always means "not sure". Never use it decoratively. |
| Error | `#C4453A` | `#A3242B` | `#D0574A` | `#FF9A8C` | Mark unchanged, plus a diamond notch (a square 0.21 × mark size, rotated 45°) at the top-end corner. Text plus an action ("Fix"). |

### 2.4 Context plane tones (decorative, for the Field only)

| Context | Light | Dark |
|---|---|---|
| Home | `#E2D4BA` | `#3A332A` |
| Work / desk | `#D9DCD6` | `#2E322E` |
| Outdoors / out | `#D5DFE4` | `#2B3339` |
| Transit / moving | `#E4D8D2` | `#382F2C` |
| Unknown | paper with unknown hatch, 1.5dp `outlineStrong` edge | same with dark tokens |

### 2.5 Measured contrast (WCAG 2.x relative luminance, computed)

| Pair | Light | Dark |
|---|---|---|
| ink / paper | 14.68 | 14.44 |
| ink / surface | 15.86 | 13.22 |
| ink / sunk | 13.18 | 15.35 |
| ink2 / paper | 6.76 | 7.98 |
| ink2 / surface | 7.31 | 7.31 |
| paper on ink (primary button) | 14.68 | 14.44 |
| outlineStrong / paper (non-text, ≥3 required) | 3.31 | 3.90 |
| sunscreen ink / paper, surface | 5.84, 6.31 | 10.00, 9.16 |
| hydration ink | 6.30, 6.80 | 9.45, 8.66 |
| posture ink | 6.06, 6.55 | 10.01, 9.17 |
| medication ink | 6.89, 7.44 | 8.81, 8.07 |
| calendar ink | 6.99, 7.55 | 8.87, 8.12 |
| routine ink | 6.06, 6.55 | 8.75, 8.01 |
| alarm ink | 7.07, 7.64 | 9.22, 8.45 |
| snoozed ink | 6.39, 6.91 | 9.52, 8.72 |
| paused / unknown ink | 6.22, 6.72 | 7.98, 7.31 |
| error ink | 6.45, 6.96 | 8.66, 7.93 |

Shape fills on paper range from 1.7 to 6.0. That is acceptable only because a shape's meaning is always repeated in text. Wherever a shape is the main signal (progress fill, the current posture mode), it carries a 1.5dp cue-`ink` edge, which clears 3:1.

Do not use the Material dynamic color scheme. Map these tokens into a static `ColorScheme`: `primary` = ink, `onPrimary` = paper, `background`/`surface` = paper, `surfaceContainer*` = surface/sunk, `outline` = outlineStrong, `outlineVariant` = outline, `error` = error ink. Expose the cue and state tokens through a separate `DayCueColors` CompositionLocal.

---

## 3. Typography

| Family | Role | Weights to bundle | License | Source |
|---|---|---|---|---|
| **Rubik** | All UI text: body, labels, titles, numbers | 400 Regular, 500 Medium, 600 SemiBold | SIL OFL 1.1 | https://fonts.google.com/specimen/Rubik (repo: https://github.com/google/fonts/tree/main/ofl/rubik) |
| **Frank Ruhl Libre** | One headline per screen (the "Now" line, onboarding titles, playback step) | 500 Medium | SIL OFL 1.1 | https://fonts.google.com/specimen/Frank+Ruhl+Libre (repo: https://github.com/google/fonts/tree/main/ofl/frankruhllibre) |

Both families cover Hebrew and Latin in one file, so mixed strings like "Medication B · 20:00" or "קרם הגנה SPF" stay in one face. Bundle static TTFs in `res/font/`. The Google Fonts download includes static instances, or they can be instanced from the variable `[wght]` file. Ship `OFL.txt` next to them and list the fonts on the about/licenses screen. Do not use downloadable fonts, because the app must work offline on first run.

Type scale (sp, with line height in sp so it scales):

| Token | Family / weight | Size / line | Use |
|---|---|---|---|
| `display` | Frank Ruhl Libre 500 | 34 / 42 | Onboarding title, playback current step |
| `headline` | Frank Ruhl Libre 500 | 28 / 36 | The "Now" line on the daily screen, screen titles in editors |
| `title` | Rubik 500 | 20 / 28 | Context line, sheet titles |
| `titleSmall` | Rubik 500 | 16 / 24 | Row primary text, section names in editors |
| `body` | Rubik 400 | 16 / 24 | Body text, descriptions |
| `bodySmall` | Rubik 400 | 14 / 20 | Row secondary text, helper text |
| `label` | Rubik 500 | 14 / 20 | Buttons, section headers |
| `labelSmall` | Rubik 500 | 12 / 16 | Nav labels. This is the floor: nothing smaller, ever. |
| `numeric` | Rubik 500, `tnum` | as context | Countdowns and times. See open issue 1. |

Rules:
- Letter spacing is 0 everywhere, so Hebrew is never tracked. Never use all caps (Hebrew has no case, and caps make the two languages feel different).
- Only one serif line per screen, so the serif keeps its quiet emphasis.
- Times always use the device's 24h/12h setting. Numerals stay Western Arabic in Hebrew. Wrap time spans in an LTR isolate (`⁦…⁩`) inside Hebrew strings so "13:00–13:30" never reorders.

---

## 4. Shape language

### 4.1 Primitives (all of them)

| Primitive | Construction |
|---|---|
| Disc | `drawCircle` |
| Half-disc | `drawArc(useCenter = true, sweep = 180°)` or a Path arc closed by a chord |
| Slab | `drawRoundRect`, corner radius = 0.08 × short side (sharp "cut" look, never a pill) |
| Arch | Path: rectangle with a semicircular top (radius = half width) |
| Stadium (capsule) | Path: two semicircles joined, split at the midpoint into two fills |
| Rule | Slab with height 1.5dp |

No triangles (they read as warnings), no stars or blobs, no gradients. Each fill is flat. Depth comes only from overlap and the paper edge described below.

### 4.2 Cue marks (24-unit box, scale linearly; see `assets/cue-marks.svg`)

- **Sunscreen:** disc c(13,10) r6.5 `shape`, then slab (3,15)–(21,19) r2 `ink`, drawn on top so it overlaps the disc by 1.5 units.
- **Hydration:** arch x 7..17, base y 21, top of straight sides y 8, top radius 5. A fill rectangle clipped to the arch shows the level, and a 1.5 `ink` stroke is drawn on top. Water bottle variant: add a cap slab x 9..15, y 1..3.
- **Posture:** baseline y 20. Sit = square 5.5 at x 2.75. Stand = slab 4×12 at x 10. Walk = disc r2.75 at (19,17). The current mode is filled with `shape` and the others are 1.5 `ink` outlines. The generic icon has all three filled.
- **Medication:** stadium 18×8 centered (12,12), rotated −35°. Start half `shape`, end half `ink`.
- **Calendar:** slab 16×16 at (4,4) r3 `shape`, inner slab 5×5 at (12.5,12.5) r1 `ink`.
- **Routine:** discs r5 @x7, r3.5 @x14.5 (70% alpha), r2.25 @x20 `ink`, all at y 12. The order runs in reading direction.
- **Alarm:** half-disc c(12,19) r8 `shape` @85%, slab (2,19)–(22,21) `ink`, disc r1.75 at (12,6) `ink`.

Mirroring in RTL: only the routine mark and posture sequences mirror, because they encode order. All other marks keep their geometry. (The RTL frame in `daily-screen.svg` mirrors everything for drawing convenience. Follow this paragraph, not the sketch.)

### 4.3 Paper treatment

- **Paper edge:** each large shape (≥ 48dp) is drawn twice. First an offset copy (+1dp x, +1.5dp y, physical direction, not mirrored, because light comes from the top-left) in `ink` at 8% alpha (dark: `#000` at 30%), then the shape itself. Marks under 48dp get no edge.
- **Grain:** generate one 96×96 px tile at startup with a seeded `Random(0xDA7C)`. Each pixel is `ink` with alpha uniform 0..10/255 (dark: `#FFFFFF` 0..8/255). Draw it with `ImageShader(tile, TileMode.Repeated)` clipped to the shape path, using `BlendMode.Multiply` in light and `BlendMode.Screen` in dark. Grain appears only inside shapes and the Field. Plain paper behind text stays flat. Cache the tile. Do not regenerate it per frame.
- **Rotation:** large planes rotate by −3° (RTL: +3°). Marks are never rotated except the medication capsule's fixed −35°.
- **Overlap:** at most 3 shapes overlap at any point, and only two shapes per composition carry color (one context plane plus one cue). The rest are ink, neutral, or outline.

### 4.4 Where shapes appear

Shapes appear in:
- The Field on the daily screen.
- The onboarding header composition.
- Routine playback.
- The posture detail header.
- Empty and offline state illustrations, at most 160dp tall.
- Cue marks in row leading slots (28dp in a 40dp slot).
- The cue test preview.

Shapes must NOT appear:
- Behind any text, including headlines.
- Inside buttons, chips or switches.
- In the top app bar.
- As section dividers or background texture of lists.
- Inside dialogs or sheets, except a single 28dp mark next to the sheet title.
- Animated anywhere the user is reading or editing (forms, lists).
- As decoration on settings, readiness or integration screens beyond row marks.

---

## 5. The Field: time without charts

The Field is a text-free composition at the top of the daily screen. Width W = full screen width (edge to edge, no frame, no border, no background block; it sits on paper). Height H = clamp(0.28 × screen height, 168dp, 240dp).

1. **Context plane:** slab x = 0.08W (from the start edge), y = 0.14H, w = 0.62W, h = 0.78H, corner 6dp, rotated −3° about its center. Fill = context tone (2.4), with grain. When the context is unknown, the plane is the hatch with an outline, and it is drawn at 85% of its size, because the app "holds it loosely".
2. **Active element** (what is running: posture mode, routine, active pause). It is the cue's primitive, height 0.36H, placed inside the plane at start inset 0.06W, with its baseline at plane bottom − 0.08H. It rotates with the plane.
3. **Next-cue disc.** Diameter d = 0.30H in the next cue's `shape` color.
   - Positions: Start = (W − 0.04W − d/2, 0.22H). Arrival = (planeEndX + 0.1d, planeTop + 0.45 × planeH), which means the disc overlaps the plane by 40% of its diameter.
   - Position = lerp(start, arrival, p), with p = 1 − clamp(remaining / horizon, 0, 1). Horizon = min(120 min, the cue's interval).
   - Approaching (p < 1): separated, then closing. Due (p = 1): overlapping, with the seed dot. Overdue: no further movement. The seed stays, and the text carries "overdue 20 min" (no alarming red for habits).
4. **Now line:** a rule across the full width at 0.96H, `ink` at 20%.

Progress of something running uses **fill**: a level rising bottom to top inside the primitive, equal to elapsed / duration. A bottom-up fill is direction-neutral, so it never needs mirroring. Its shape always has a 1.5dp `ink` edge, so it clears 3:1.
- **Posture cycle:** the current mode fills over its block. The next mode appears as a small outline beside the plane in reading order. Supporting text: "Standing · 12 min left · then walk 10 min".
- **Time until next cue:** shown by approach only.
- **Routine playback:** see 9.

Accessibility: the Field is one semantics node, for example: "At home. Standing, 12 minutes left. Next: hydration in 40 minutes." It is not focusable separately from the context line if UX merges them.

Large text: at fontScale ≥ 1.3, H shrinks to 120dp. At ≥ 1.6, the Field collapses into a 56dp strip (plane and disc only, side by side) so the text and actions stay above the fold.

---

## 6. Motion

| Token | Duration | Easing | Used for |
|---|---|---|---|
| `quick` | 120ms | `FastOutLinearIn` for exit, `LinearOutSlowIn` for enter | Press, toggle thumb, ripple-free state tint |
| `standard` | 240ms | cubic(0.2, 0, 0, 1) | Row expand, chip select, content change |
| `screen` | 300ms | cubic(0.2, 0, 0, 1) | Screen change: fade plus 12dp slide along the inline axis (mirrored in RTL) |
| `sheet` | 320ms enter / 220ms exit | cubic(0.05, 0.7, 0.1, 1) / cubic(0.3, 0, 0.8, 0.15) | Bottom sheets |
| `settle` | spring dampingRatio 0.8, stiffness 380 | n/a | Disc arriving at due, a shape returning after drag |
| `progress` | 900ms | linear-out-slow-in | Disc approach and fill level updates (values change once per minute; during routine playback, continuously) |
| `ambient` | 28s loop | sine | Plane rotation −3° ± 0.4°, disc float ± 1.5dp. Field only. |

What animates:
- The disc enters from start position to its current p on screen entry (600ms).
- On due, the disc settles into overlap with `settle`, and the seed scales 0 → 1 once. There are no repeating pulses, ever.
- Done: the mark fills to `ink` (240ms), then the row collapses (240ms).
- Snooze: the mark fades to the snoozed tint, and the disc slides back to the new p.

What never animates: text, numbers counting up, list reorder flourishes, or loading shimmer (use a static outline placeholder).

**Reduced motion:** applies when the system animator scale is 0 (`Settings.Global.ANIMATOR_DURATION_SCALE`), the in-app "Reduce motion" setting is on, or battery saver is on.
- No ambient loop, no slides, no springs.
- Screen and sheet changes become a 150ms crossfade.
- The disc and fill jump to new values. Fill values are quantized to 5% steps so the Field does not redraw for invisible changes.
- The due seed appears without scaling.
- Ambient motion also stops after 60s on screen even when reduced motion is off, so the screen comes to rest.

---

## 7. Components

**Spacing grid:** 4dp base.
- Screen gutter 20dp (16dp below 360dp width).
- 16dp inside rows, 24dp between related blocks, 32dp between sections.
- Content max width 560dp on tablets and landscape, centered.

**Corner radii:**
- 6dp: shapes in the Field.
- 8dp: chips, text fields.
- 14dp: buttons.
- 28dp: sheet top corners.
- Nothing is fully rounded except discs and the switch.

**Elevation:** flat. No shadows on rows, buttons or bars. Sheets and dialogs use the `surface` color, 1dp `outline` at the top edge, and the scrim. Material tonal elevation is off (`tonalElevation = 0`). There is no FAB.

| Component | Spec |
|---|---|
| Primary button | Height 52dp, radius 14, fill `ink`, text `paper` `label` 16sp/500. One per screen region. Label first, no leading icon. |
| Secondary button | Height 52dp, radius 14, 1.5dp `outlineStrong` border, text `ink`. |
| Text button | `ink` text with an underline 1dp offset 3dp below the baseline, minimum touch 48×48dp. Used for "Fix", "Change", "Test". |
| Destructive | Secondary style with error `ink` text and border. Never filled red. |
| List row | No card. Height min 64dp (one-line 56dp). Leading 40dp slot holding a 28dp mark. Primary `titleSmall`, secondary `bodySmall` `ink2`. Trailing: state text or switch. 1dp `outline` divider inset from the text start (not under the mark). Pressed state = `sunk` fill. Rows grow vertically with font scale, and text wraps to 3 lines before ellipsizing. |
| Section header | `label` in `ink2`, preceded by a 12×3dp `ink2` slab with an 8dp gap. 32dp top / 8dp bottom. No background. |
| Switch | Track 52×32, checked = `ink` track and `paper` thumb. Unchecked = `sunk` track, 1.5dp `outlineStrong` border, `outlineStrong` thumb. No icons in the thumb. 48dp touch. |
| Chips (sparingly) | Only for weekday pickers and context filters. Height 36dp in a 48dp touch box, radius 8. Unselected: 1dp `outlineStrong`. Selected: `ink` fill, `paper` text. At most one chip row per screen. Never use chips as status badges. |
| Status text | State is plain text in the state's `ink` color next to the mark ("Due now", "Snoozed until 14:20"). No pill badges. |
| Text field | Filled style on `surface`, radius 8 on top corners only, 1.5dp `outlineStrong` bottom line, focus = 2dp `ink`. Helper/error text below in `bodySmall`. |
| Bottom navigation | 72dp tall on `paper`, 1dp `outline` top hairline. 3 destinations (suggested: Today · Cues · Setup; UX decides). Each has a 24dp icon and an always-visible `labelSmall` label. Selected = `ink` icon (filled form) plus a 20×4dp `ink` slab 6dp above the icon, with no pill indicator. Unselected = `ink2` outline icon. |
| Top bar | No color block and no elevation. Date/time `bodySmall` `ink2` over the context line `title`. Single trailing icon button (48dp). |
| Bottom sheet | `surface`, top radius 28, drag handle 32×4dp `outlineStrong` 12dp from top, 24dp side padding, max one primary action at the bottom. |
| Dialog | Only for destructive or sensitive confirmations (for example medication changes). `surface`, radius 14, title in the `title` token (no serif), buttons end-aligned (mirrors automatically in RTL). |
| Snackbar | `ink` fill, `paper` text, radius 8, includes "Undo" for any config change. |
| Notification small icon | 24dp vector, white on transparent, 2dp padding. One per cue type, using the cue mark as a single-color silhouette where the two-tone parts are separated by a 1.5dp transparent cut. The app default is the icon's plane and disc silhouette. `setColor()` = cue `ink` (light). Large icon: none. |

---

## 8. Daily screen layout

Order from top to bottom answers: context → active → next → what can I do now. See `assets/daily-screen.svg` (left: English, right: Hebrew).

```
LTR (English)                              RTL (Hebrew) — mirrored
┌──────────────────────────────────┐       ┌──────────────────────────────────┐
│ Sun 4 Oct · 12:20                │       │                יום א׳, 4 באוק׳ · 12:20 │
│ At home · desk work          (⚙) │       │ (⚙)          בבית · עבודה ליד השולחן │
│                                  │       │                                  │
│   ┌──────────────┐               │       │               ┌──────────────┐   │
│   │ ▮ context    │  ●  next cue  │  THE  │  next cue  ●  │    context ▮ │   │
│   │ ▮ plane      │ (approaching) │ FIELD │ (approaching) │      plane ▮ │   │
│   └──────────────┘               │       │               └──────────────┘   │
│ ──────────────────────────────── │       │ ──────────────────────────────── │
│ Drink a glass of water   (serif) │       │ (serif)             לשתות כוס מים │
│ Due now · last at 10:40          │       │          הגיע הזמן · לאחרונה ב־10:40 │
│ [ Done ]   ( Snooze 15 min )     │       │     ( דחייה ב־15 דק׳ )   [ בוצע ] │
│                                  │       │                                  │
│ ▬ Next                           │       │                           הבא ▬ │
│ ◐  Sunscreen                     │       │                     קרם הגנה  ◐ │
│    13:00 · in 40 min             │       │             13:00 · בעוד 40 דק׳    │
│ ─────────────────────────────    │       │    ───────────────────────────── │
│ ⬭  Medication B                  │       │                       תרופה ב  ⬭ │
│    20:00 · confirm on phone      │       │              20:00 · אישור בטלפון   │
│ ─────────────────────────────    │       │    ───────────────────────────── │
│ ◆  Exact alarms are off          │       │         התראות מדויקות כבויות  ◆ │
│    Reminders may be late · Fix   │       │        תזכורות עלולות לאחר · לתיקון │
├──────────────────────────────────┤       ├──────────────────────────────────┤
│  ▬                               │       │                               ▬  │
│  ● Today    □ Cues    ○ Setup    │       │    ○ הגדרות   □ תזכורות   ● היום  │
└──────────────────────────────────┘       └──────────────────────────────────┘
```

Rules:
- The "Now" block shows at most one cue: the most urgent due item, with medication first. If several are due, show "and 2 more" as a text button that opens a sheet listing them.
- When nothing is due, the Now line reads, for example, "Nothing needs you now", followed by the next item's time. Buttons are omitted rather than disabled.
- "Next" shows up to 3 rows, then a "See today" text button. Readiness problems appear as the last row with the error notch, never as a banner over the Field.
- In RTL: the Field mirrors, so the plane anchors right, the disc approaches from the left, and rotation becomes +3°. Rows and nav order mirror, and the primary button sits at the right. Times stay LTR isolates.

---

## 9. Screen notes (visual layer only)

- **Onboarding:** 3 to 5 steps, each with a 200dp composition at top that builds itself one shape per step: first the plane (home), then the disc (a cue), then the rule (the day). It reads as one evolving collage. Text is below, with one primary button. Permission steps show the specific cue mark the permission serves.
- **Habit editing:** a plain form on paper. Header: the 28dp mark and the `headline` name. Interval and schedule fields use rows that open sheets, not inline steppers. A live line at the bottom ("Next reminder: 14:40") uses the `numeric` token. Choosing a cue type changes the mark only, never the screen tint.
- **Routine editing:** steps as rows with drag handles (six-dot `ink2` handle, 48dp). The step number is shown as a disc whose size recedes like the routine mark. Reordering animates `standard`, with no animation under reduced motion.
- **Routine playback** (foreground, keep-awake allowed):
  - A large disc (0.5 × screen width) in routine `shape`, with grain, fills bottom-up for the step's elapsed time.
  - The current step text is in `display` below the disc. Never place it on the disc.
  - Upcoming steps are small receding discs in reading order.
  - Controls are 64dp: Pause (primary), Skip and Previous (secondary).
- **Places and context:** list of places with context-tone squares as leading marks. Never show map imagery here. If a map is required later, use a privacy-blurred flat tile style. The current inference is shown as the plane composition with its confidence in text: "Probably at work (since 09:12)". When confidence is low, the plane shrinks to 85% and gets the hatch.
- **Calendar rule preview:** a vertical list of synthetic upcoming events as rows. The rule's effect shows as the cue mark and text ("Hydration paused during this event"). Do not draw a timeline chart. A thin 2dp `ink2` rule at the start edge connects consecutive rows that fall inside one rule window.
- **Cue customization and testing:** the mark, the sound/vibration/speech options as rows, and one secondary "Test now" button. Testing plays the real cue, and the mark does the due animation once. The preview area may use the cue `shape` fill as a small 120dp composition.
- **Integration setup:** rows per integration (calendar, Spotify, companion, MCP relay). Status in plain text: Connected, Not set up, Needs attention. No brand colors or logos beyond what each integration's terms require, shown monochrome at 24dp. The QR/pairing code sits on `surface` with a 24dp quiet zone and no decoration.
- **Reminder readiness:** a checklist of rows (notifications, exact alarms, battery optimization, full-screen intent, location). Each row shows OK as `ink2` text "Ready", or a problem with the error notch and a text button "Fix". At the top, one sentence summary in `headline` ("2 things may delay reminders"). No score, ring or percentage.
- **Empty states:** a 120 to 160dp composition (plane only, no disc: "nothing scheduled"), a `title` line, one `body` line and one primary action.
- **Offline:** core reminders never need network, so offline only affects integrations and the relay. Show a single row in the relevant screen: "Offline · calendar last synced 09:40", with no global banner. In the Field nothing changes.
- **Uncertain context:** the Field plane uses the unknown hatch, and the context line reads "Not sure where you are". The secondary action "Set context" opens a manual override sheet. Never show a confident context the engine does not report.
- **Error:** inline, close to the cause, with the error notch, plain sentence and one action. Full-screen errors only for a corrupt config, with "Restore previous version" as the primary action.

---

## 10. App icon (adaptive)

108dp canvas, safe zone circle r33 at (54,54). See `assets/app-icon.svg`.

- **Background layer:** flat `#F4EFE6`. On launchers that request a dark background, use `#191816`.
- **Foreground layer:** three shapes.
  - Plane: rect x30 y44 w38 h30 rx3, rotated −6° about (49,59), fill `#3F5A8A`. On a dark background: `#5370A3`.
  - Disc: c(66,44) r13, fill `#E3A72F`, overlapping the plane's top-end corner (the "arrival" moment).
  - Horizon rule: rect x33 y75 w42 h3 rx1.5, fill `#1F1D1A`. On a dark background: `#EEE7DB`.
  - All geometry stays inside the safe zone (farthest point ≈ 31.9dp from center).
- **Monochrome layer** (themed icons): the same three paths in one color. The plane is masked by a disc of r15.5 at the disc center, so a 2.5dp cut separates the overlapping shapes.
- **Notification default small icon:** the plane and disc only, with the same cut, scaled to the 24dp grid (plane 9×7 at (4,10) rotated −6°, disc r3.5 at (15.5,8.5)).
- The icon does not mirror in RTL.

---

## 11. Do / don't checklist

Do:
1. Use paper (`paper`) as the only screen background and `surface` only for sheets, dialogs and fields.
2. Pair every state shown by shape or color with text in the state's `ink` color.
3. Use cue `ink` tokens for any colored text or icon, and cue `shape` tokens only for fills.
4. Keep one serif `headline` per screen.
5. Give every interactive element a 48×48dp minimum touch target, and buttons 52dp height.
6. Test each screen at font scale 2.0, in Hebrew, in dark mode and with animations off before calling it done.
7. Wrap times and ranges in LTR isolates inside Hebrew strings.
8. Use rows with hairline dividers for lists, and sheets for editing a single value.
9. Express "time until" by approach and "progress" by fill, with an `ink` edge on the filled shape.
10. Stop ambient motion after 60s, and entirely under reduced motion or battery saver.
11. Show uncertainty with the hatch and words like "Probably" or "Not sure".
12. Bundle Rubik and Frank Ruhl Libre TTFs with their OFL license.

Don't:
1. Don't put shapes, grain or images behind text.
2. Don't wrap list items in cards, and don't nest cards.
3. Don't use gradients, glows, glassmorphism or drop shadows on content.
4. Don't use pill-shaped buttons, status pills, or more than one chip row per screen.
5. Don't use rings, bars, pies, sparklines, streak counters or percentages to show progress or history on the daily screen.
6. Don't fill buttons with cue colors, and don't tint whole screens per cue type.
7. Don't use red for overdue habits. Red (error) is for things that are actually broken.
8. Don't use more than two colored shapes per composition.
9. Don't use looping pulses, bouncing, confetti or celebration animations.
10. Don't use uppercase labels, letter-spaced Hebrew, or text below 12sp.
11. Don't use Material dynamic color, default purple tokens, or tonal elevation tints.
12. Don't show a confident context or state when the engine says `Unknown`.
13. Don't use licensed illustrations, icon packs with unclear licenses, or brand logos in color.

---

## 12. Open issues

1. Rubik's `tnum` (tabular figures) support is not verified. If the feature is absent, countdown text that ticks per second (routine playback only) needs a fixed-width box sized to "00:00" at the current font scale.
2. The context categories in 2.4 (home, work, outdoors, transit, unknown) are assumed. The product spec should confirm the actual context set.
3. Hebrew strings in the sketches are placeholders for UX and copy review.
