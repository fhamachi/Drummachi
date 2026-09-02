# Drummachi v3 — Design Spec (Apple-like / dark stage)

**Scope:** Native Android, Kotlin + XML/Views, no heavy libraries. Use case: floor use, landscape, immersive, screen always on, readable from ~1.5 m, triggered by feet/hands.

## Visual direction (2 options + recommendation)

- **Glow Stage:** pure black, saturated neon colors, glow behind buttons. Strong impact, but borders on "toy" and tires on extended use.
- **Quiet Studio** ⭐ *recommended:* black background, layered graphite surfaces (iOS dark), desaturated but distinct functional colors, glow reserved ONLY for active states. Generous corners (28–32dp), soft shadows, clean type.

**How not to look like a toy:** high contrast + flat colors (no flashy gradients), large but uniform corners (28–32dp), no permanent glow — the "life" shows in state feedback (momentary border + glow). A huge button ≠ toy when the color is sober and the behavior is serious.

## a) Palette (exact hex)

| Role | Hex | Use |
|---|---|---|
| Background | `#000000` | full screen |
| Menu surface | `#1C1C1E` | drawer/overlay |
| Secondary surface | `#2C2C2E` | PART button, kit items |
| Separator / border | `#38383A` | lines, neutral borders |
| Primary text | `#F2F2F7` | titles, PLAY/FILL |
| Secondary text | `#AEAEB2` | BPM, subtitles |
| Pulse accent **active** | `#30D158` | beat number (corner) |
| Pulse **inactive** | `#1E5C3A` | stopped count |
| Pulse area background | `#0A2A1F` | top band (refined dark green) |
| **PLAY** | `#32D74B` / pressed `#248A3D` | performance row |
| **FILL** (text `#000000`) | `#FFD60A` / pressed `#C9A600` | performance row |
| **TAP TEMPO** | `#0A84FF` / pressed `#075FB5` | preparation row |
| **PART** | `#3A3A3C` / pressed `#48484A` | part switching |
| "Engaged" border | `#8AFFFFFF` (white 54%) | PLAY playing / selected kit |
| Menu scrim | `#66000000` | behind drawer |
| Menu blur | `#CC1C1C1E` (or solid fallback) | drawer open |

## b) Typography

Font: `sans-serif` (Roboto) — SF Pro is not licensed for Android. Large numbers: `sans-serif-black` + `fontFeatureSettings="tnum"` (fixed-width digits, no "dancing").

| Element | Size | Weight |
|---|---|---|
| "DRUMMACHI" title | 20sp | semibold, 80% white |
| BPM ("120 BPM") | 30sp | semibold |
| **Count (corner)** | **180sp** (autosize 140–220sp) | black |
| Part (VERSE/CHORUS) | 34sp | semibold |
| PLAY / FILL | 56sp | bold |
| TAP TEMPO | 36sp | semibold |
| Menu header | 28sp | semibold |
| Kit item (name) | 24sp | regular |
| Kit subtext | 20sp | regular, 70% white |
| "Import MIDI" | 24sp | semibold |

Rule: critical element (count) ≥ 140sp; button ≥ 36sp; supporting text never < 20sp.

## c) v3 Layout

```
┌──────────────────────────────────────────────────────────────┐
│ ☰  DRUMMACHI · 120 BPM            VERSE          [4]  ← 36% │
│                                                              │
│ ┌────────────────┬─────────────────────────┐                 │
│ │   TAP TEMPO    │    PART (VERSE)         │  ← 26% (prep)  │
│ └────────────────┴─────────────────────────┘                 │
│ ┌────────────────┬─────────────────────────┐                 │
│ │      PLAY      │         FILL            │  ← 34% (exec)  │
│ └────────────────┴─────────────────────────┘                 │
└──────────────────────────────────────────────────────────────┘
```

- **Top band (36% of height, min 130dp):** 72×72dp menu button top-left (16dp margin, 22dp radius); **count at top-RIGHT** (16dp margin; ~200dp wide; recommended on the right: counterbalances the menu, sits at the end of LTR reading and away from the row where feet land). Band center: title + BPM + part stacked, gravity center.
- **Preparation row (26%, min 110dp):** TAP TEMPO + PART, each 50% of width − gap.
- **Performance row (34%, min 140dp; tablets ≥160dp):** PLAY + FILL, 50%/50%. Felipe asked for BIGGER than v2 → 200dp+ on tablets.
- **Minimum foot target: 120dp tall and ≥48% of width.** Gap between targets ≥16dp (room for the foot). Side margins 16dp; vertical gaps between rows 12dp.
- Radii: prep 28dp, exec 32dp, menu 22dp. Menu button is hand-operated, but keep ≥64dp.

## d) Menu (kits + MIDI import)

- **Floating sheet drawer on the left:** 320dp wide, height = screen − 32dp (16dp margins), 28dp radius, 24px blur (API 31+) or `#F21C1C1E` background. Scrim `#66000000` behind.
- **Content:** "Kits" header (28sp) → list (RecyclerView): 88dp items, 20dp radius, `#2C2C2E` background, kit name 24sp + mini visual (4 16dp dots in kit colors) → **"Import MIDI"** footer: 72dp, 22dp radius, `#0A84FF` background, vector icon + text, opens `ACTION_OPEN_DOCUMENT` (.mid/.midi).
- **Selected kit:** 2dp `#30D158` border + ✓ check.
- **Behavior:** opens in 200ms (X slide, decelerate); closes by scrim tap, left swipe, kit selection or menu button. **Audio keeps playing underneath** (the scrim blocks button touches, but the engine does not pause).

## e) States and micro-interaction

- **Feedback on ACTION_DOWN, no ripple:** color changes immediately on press (0ms delay) and returns in 150ms ease-out. PLAY/FILL: + 3dp inner border `#4DFFFFFF` when pressed.
- **PLAY active (playing):** keeps "engaged" state — lightened `#32D74B` + 2dp `#8AFFFFFF` border. FILL flashes once when fired (80ms flash).
- **TAP TEMPO:** instant flash on down + 150ms fade; BPM updates with a 200ms animation.
- **Pulse (corner number):** on each beat, scale 1→1.06→1 in **220ms** (decelerate) + color `#30D158`→`#7CF29B`. No blink, no opacity flicker. Small center dot (12dp) pulses with fade 1.0→0.35→1.0 (600ms, synced to the beat).

## f) Implementation notes

- **Do:** `GradientDrawable` in XML for all buttons (radius + solid + stroke) with `StateListDrawable` (state_pressed) — instant feedback, zero libraries; icons as XML `VectorDrawable` (menu = 3 lines, MIDI = note); blur via `RenderEffect.createBlurEffect(24f,24f,CLAMPED)` on the content behind the drawer (applied only on open) with translucent fallback for API < 31; `ValueAnimator`/`ObjectAnimator` (150–250ms) for pulse and drawer; plain `Theme.Material`; `FLAG_KEEP_SCREEN_ON` + immersive sticky; RecyclerView is fine (it's in the framework).
- **Avoid:** `RippleDrawable`/`selectableItemBackground`, Material Components, Lottie, real-time per-frame blur, animations that delay the down state (feedback must happen on press, never on release), unnecessary elevation (use gradient/border).
