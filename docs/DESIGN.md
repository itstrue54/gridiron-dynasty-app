# Gridiron Dynasty — design system: Broadcast

The code cites this file by section. Change the section in the same commit as
the code it describes.

## 1. Principles

- **A spreadsheet with good typography,** dressed as a Sunday-night TV package.
  Dense, tabular, data first; the broadcast look is in the frame, never in the
  numbers.
- **Colour encodes situation and never decorates.** Every colour lives in
  `ui/theme/Color.kt`; no composable writes a hex value.
- **Fictional everything.** No real club, logo or player. Club marks are
  generated letterforms (`TeamMark`), never a real team's.
- **Everything reachable in two taps from the hub.**

## 2. Palette

Night game is the default; Day game is the light theme. Field names are the
old Call Sheet's, kept so nothing outside the theme had to change.

| Role | Night | Day | Use |
|---|---|---|---|
| turf | `#0A1224` | `#EEF2F8` | the ground |
| turfRaised | `#111C35` | `#FFFFFF` | blocks, table rows |
| turfLine | `#24365E` | `#CFD8E6` | hairlines, disabled |
| chalk | `#EEF3FA` | `#0A1224` | primary text |
| chalkDim | `#9AABC8` | `#4A5A78` | secondary text |
| pylon | `#FF6B1A` | `#C94A0A` | the one action a screen is for |
| pylonText | `#FF7A33` | `#B2440A` | pylon as text |
| stripe | `#22D3EE` | `#A6EAF7` | a caution tag, first downs in the play log, the drive tracker's line to gain |
| accent | `#22D3EE` | `#0B8FA8` | the lower-third bar and a highlighted row's edge; never text (3:1 as a mark) |
| rowHighlight | `#15304A` | `#DDF4FA` | a highlighted table row's ground |
| sitThirdDown | `#5B9CFF` | `#2E6BC9` | situation edge |
| sitRedZone | `#F0505A` | `#C22F38` | situation edge, turnovers |
| sitTwoMinute | `#B98CF2` | `#7A4FC0` | situation edge |
| tierElite / Good / Average / Low | cyan / chalk / chalkDim / `#C9967A` | `#0A7487` / chalk / chalkDim / `#8A5F44` | rating tiers, always beside the number |

Every text colour clears **4.5:1** on turf and turfRaised in both themes (the
lowest is day third-down at 4.60:1), and on rowHighlight (lowest: day elite
tier, 4.76:1); text on pylon and stripe clears it too.

The status and navigation bar icons follow the app's theme, not the phone's
(`LocalDarkBars`), so they are light on navy even when the phone is in light
mode.

## 3. Type

- **Display family:** Big Shoulders Display, condensed. Display, headline and
  block titles are **W800 italic**, the lean of a broadcast graphic. The
  scoreboard's numbers stay upright, because they must be read.
- **Text family:** IBM Plex Sans for body, data, labels and captions.
- **Tabular figures** on every style that carries numbers, or columns will not
  line up.
- Block titles are set in capitals (`blockTitle`, 20sp, +0.6sp tracking).

## 4. Shape

Broadcast angles: panels are cut on the diagonal.

- Blocks: `CutCornerShape(topEnd = 12, bottomStart = 12)`.
- Buttons: `CutCornerShape(topStart = 10, bottomEnd = 10)`, so they slant the
  other way from the blocks they sit in.
- Tags: the block's cut, 4dp. Sheets: both top corners cut, 16dp.
- Table rows stay square: a spreadsheet does not tilt.

## 5. Components

- **SituationBlock** is the core pattern: a raised block with a 4dp situation
  edge on the left, a capitalised italic title, meta on the right, and a
  lower-third rule - a hairline with a short cyan bar at its start.
- **DataTable:** numbers right-aligned in tabular figures, names left, 1dp
  rules, no rounding. A highlighted row (the user's club, a choice) is a
  rowHighlight tint with a 3dp accent edge and ordinary text - a flood of
  cyan was tried and shouted. At large font sizes (over 1.3) rows stack: names on one
  line, labelled numbers wrapping beneath. Each row is one screen-reader item
  with every number named (SPEC 11).
- **Scoreboard:** the broadcast line, one semantics node.
- **Buttons:** Primary is pylon fill with navy text; Secondary is a chalkDim
  outline. Both at least the minimum touch height.

## 6. The field strip

`DriveTracker` draws the field as a 32dp strip: yard ticks every ten, the ball,
and the line to gain.

## 7. Motion

Motion only when a play result changes state. A jumped-to state does not
animate; reduced-motion settings are honoured.

## 8. Accessibility

System font scaling is respected (stacked tables at large sizes); every table
reads to a screen reader; contrast per §2; the busy overlay shows a
determinate bar for a week's sim (SPEC 11).

## 9. Status tags

Injury tags read Q, D, O and IR, in the tag shape.

## 10. Fonts

Both families ship as variable TrueType files in `res/font`; one file carries
every weight, and the weight axis is set per cut.

## 11. Art and icon

Generated with Nano Banana (Gemini image, via the `nanobanana` skill) and kept
as source in `docs/play-store/assets/source/`:

- `gd-monogram.png`: the **GD** mark, a white italic monogram with a cyan edge
  in an orange-outlined broadcast score bug.
- `title-background.png`: the title screen's night stadium, cyan yard lines
  and floodlights under two orange streaks, fading to navy at the foot.

`docs/play-store/assets/draw-assets.py` builds everything from those two:
the adaptive launcher foreground at every density (the mark 56 of 108dp wide,
inside the 66dp safe circle; the background is flat turf navy, vector), the
legacy launcher icons, the 512px store icon, the 1024x500 feature graphic,
and the title screen's background and mark. Re-run it after changing either
source image or the palette.

The title screen is always the night palette, frame and system bars
included, because its art is a night game; in the day theme the fade had
washed the stadium out. It draws the name as type - GRIDIRON in chalk over DYNASTY in
pylon, italic, with a cyan rule - so it reads to a screen reader and stays
sharp at any size.
