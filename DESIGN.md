# DESIGN.md — the Wise-fa world (seed wise-fa-2026-08)

Recorded from the built app on 2026-08-21, after the redesign shipped. Ground truth lives in
[Theme.kt](app/src/main/java/com/doxigo/muchtoman/Theme.kt); this file says what the values
mean and which rules keep the system whole. The previous world (teal-green + gold on warm
paper) is retired; treat it as anti-reference.

## Identity

One pair carries the brand, fixed across both themes:

- **Forest** `#163300` family — the ground of the hero card, dark-theme fills, light-theme
  selection.
- **Bright green** `#9FE870` — the answer and the action. The hero total, every loud button,
  the dark theme's primary.

Two objects in Theme.kt sit outside the Material scheme on purpose:

- **`Hero`** — the answer's card, **flat, never a gradient**. Light theme: Wise's forest
  `#163300`. Dark theme: the scheme's neutral elevated surface with a hairline border —
  Wise's dark cards are neutral rooms the green visits, and a forest slab on near-black
  reads as olive mud (two earlier cuts proved it). The `accent` (#9FE870, the total),
  `mint` (growth — the same green, Wise's own rule) and `warn` (#FFB59F, the card's only
  caution) are fixed; `strong`/`muted` are green-tinted on forest and the scheme's neutrals
  in the dark. The lock screen and the widget keep the fixed forest full-bleed.
- **`Cta`** — `fill #9FE870` / `ink #163300` in *both* themes. Every "press this" pill:
  PillButton PRIMARY, save Buttons, the review pill, the deck's primary answer, the tab badge.

**CTA ≠ selection.** Selected states (segmented pills, chips, category tiles, tab indicator
context) use the scheme's `primary` — forest in light, bright in dark — so *press this* and
*this one* stay two different statements even when both are green.

## Color roles (Material scheme)

- `primary` light `#163300` / dark `#9FE870`; `onPrimary` is always the other of the pair.
- `primaryContainer` — soft green tint; action circles, tab indicator, empty-state discs.
- `secondary` — **amber caution** (light `#7A5900`, dark `#EDCB5A`): budget NEAR/CLOSE, the
  unconfirmed-row dot, «ارزش نداشت». Never green, never error red.
- `tertiary` — **gain, and only gain** (light `#1F7A40`, dark the brand green `#9FE870`):
  income figures, day nets, deposits, "بیشتر شده", goal met. The one Iranian-bank
  convention never traded.
- `error` — Wise-toned red `#A8200D` (light). Facts that need looking at, two-tap deletes.
- Light ground: **real white** background; cards `#F3F5EE` / panels `#EAEEE0` (gray with a
  green whisper); text `#131711`; muted `#59614F` — green-tinted, never flat grey.
- Dark ground: **near-neutral, not forest-tinted** — Wise's dark screen `#121511`, surfaces
  `#1C1F1A` / `#262923`, muted text `#A8AAA6` (Wise's neutral grey). Dark mode is its own
  neutral room the green visits; tinting every surface green is how the theme turns olive.
- Dark `primaryContainer` `#2A3A1D` with the bright green as its content — quiet dark chips
  (action circles, tab pill), never green slabs.
- Category hues are their own two fixed sets in CategoryIcon.kt, keyed by glyph, chosen by
  background luminance — they ride above the scheme and survive theming.

## Type

Modam variable only (`wght`, `wdth`); zero tracking everywhere — Persian joins break under
letterSpacing; tightness comes off the width axis. `ModamFigures` (width 90) for every
figure, always with `tnum`.

- **ScreenTitle** — 30sp Black, one voice for every root page.
- **SheetTitle** — 26sp Black, one voice for every sheet.
- Hero total: Black, autosized 28–60sp, in `Hero.accent`; the words and exact digits always
  under it. Figures autoshrink, never wrap, never ellipsise mid-number.

## Shape & structure

Radius scale: field 14 · card 18 · group 22 · sheet 28 · hero 34 · pill ∞.

**Containers hold money; activity flows.** The rule that decides a surface:

- Money that *is* somewhere — assets, budgets, goals, settings — sits in **bands**
  (`bandShape`: one grouped object, hairline-divided rows, the «+» row built in).
- **تنظیمات is an index; its settings are rooms.** The page is her card and three bands of
  doors — دفترت, برنامه, نگهداری — and every switch, and every paragraph that qualifies one,
  lives on the page its door opens ([Settings.kt](app/src/main/java/com/doxigo/muchtoman/Settings.kt)).
  One rule keeps the two legible: a **`primaryContainer` disc is a door**, a neutral
  **`surfaceContainerHighest` disc is a control**. It is the quiet chip, never `Cta` — «press
  this» keeps meaning one thing.
- Activity — the ledger — is **container-less**: day heading (13sp bold muted + the day's
  net), then plain rows on the paper. Each row leads with a 44dp circle in the category's
  hue at 16% behind its glyph; merchant 16sp SemiBold; category line under; amount at the
  end. The category sheet reuses the row with `showIcon = false` inside its band.
- The **hero is a card**, not a field: floats on the paper under the greeting top bar, all
  corners `Radius.hero`. Transaction and deck pages reuse it via `HeroPanel` (it takes the
  glass inset and gutter itself).
- **Action circles** (`ActionCircle`): 56dp `primaryContainer` discs with labels, fixed
  108dp cells clustered to the centre — same rhythm at any count.
- Tab bar: full-width floor on `surfaceContainerHigh`, hairline on top, M3-sized soft
  `primaryContainer` indicator pill, CTA-green badge. No shadow, no island.

## Spacing

`Space` scale: 4 / 8 / 12 / 16 / 20 / 32 / 48. Screen gutter is `Space.xl` (20) — `edge` in
Ui.kt is the one place that number lives. Section gaps are `Space.xxl`; more air above a
heading than below it, everywhere.

## Motion

- `Motion.settle()` — critically damped spring (stiffness 340), the default for any moving
  value (budget bars). `Motion.press()` — stiffer spring for press-give: PillButton and
  ActionCircle scale to 0.97/0.94 on touch-down, from the frame the finger lands.
- `Motion.enter`/`exit` easings stay for enter/exit choreography. The one authored moment
  is the hero total sliding up on refresh. Nothing overshoots.

## Iconography

One pen (`pen()`: round caps, 2dp at 24, thinner in smaller boxes) draws everything: tab
glyphs, category marks, the person, the check, the plus, the receipt. **No emoji as icons**
— empty states use the drawn glyphs on `primaryContainer` discs; settings rows use drawn
marks or real logos (banks on white plates).

## Standing rules the visuals must keep

- Words carry warnings; colour only confirms — and never contradicts: the freshness dot is
  `Hero.warn` whenever any caution sentence is on the card, `Hero.mint` only when connected
  and fresh.
- Figures truncate, never round up; large amounts are spelled out in words underneath.
- Charts run LTR inside the RTL page; gain is green in both themes.
- No gamification: no confetti, streaks, scores, or comparison.
- Widget and launcher carry the same pair (Widget.kt constants, `widget_bg.xml`,
  `ic_launcher_*`): forest ground, bright-green figure.
