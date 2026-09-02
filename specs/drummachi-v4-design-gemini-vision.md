# Drummachi v4.3 — Official design (Gemini vision spec)

> Source: analysis of Felipe's design image (done in Gemini) via **gemini-3.6-flash** (vision), 2026-08-28.
> This is the FINAL spec — supersedes `drummachi-v3-gemini-analysis.md` (the PIL analysis was imprecise).

## 1. Overview
- **Orientation: LANDSCAPE** — the app must lock to landscape.
- **Overall background:** dark blue/graphite with a subtle radial gradient — slightly lighter center `#161C24` → edges `#0D1015`.
- **Structure:** 2 areas:
  1. **Top header** (~18% of height)
  2. **2×2 control grid** (rest of the screen), 4 large rounded cards with ~12px gap.

## 2. Header
- **Left:** hamburger menu icon (3 white lines `#FFFFFF`, ~3px) + "Style / Blues 1" block:
  - "Style" label: light gray `#8A94A6`, ~12px, regular.
  - "Blues 1" value: white `#FFFFFF`, ~20px, semibold.
- **Center:** "128 BPM" — white `#FFFFFF`, ~28-32px, bold.
- **Right:** segmented 1-4 variation bar:
  - Container: dark blue background `#0D1527`, 8px radius.
  - ACTIVE segment: vertical gradient `#2B529A` → `#1A3668`, neon blue border `#3B82F6`, 6px radius, white bold text.
  - Inactive segments: vertical dividers `#1E293B`, text `#475569`.

## 3. 2×2 Cards (all: 14-16px radius, ~48% width × ~38% height, subtle inner top bevel)

### Card 1 — TAP TEMPO (top left)
- Vertical gradient: `#1E293B` (top) → `#0F172A` (bottom); border `#2A364F`.
- White bold "TAP TEMPO" text, centered at top.
- Below: metallic circular dial — light blue `#38BDF8` translucent outer ring; center knob gradient `#475569` → `#1E293B`.

### Card 2 — VERSE (top right)
- Gradient: `#1B283D` (top) → `#0E1626` (bottom); border `#23334D`.
- ♫ (double eighth note) icon in light blue/silver `#CBD5E1`, centered at top.
- White bold "VERSE" text ~26px.
- Detail: thin horizontal line `#26354A` crossing behind the text.

### Card 3 — PLAY (bottom left) — MAIN ACTION
- Vibrant green gradient: center-top `#059669` → bottom/edges `#022C22`/`#064E3B`; light green top bevel `#10B981`.
- Icon: mint-green `#A7F3D0` play triangle, centered at top.
- Pure white "PLAY" text, bold/extrabold ~32px, aligned center-bottom.

### Card 4 — FILL (bottom right)
- Purple/blue-violet gradient: `#25285A`/`#1E2348` (top) → `#0F1226` (bottom); border `#313668`.
- Icon: stylized drum/snare in thin white/silver `#E2E8F0` lines, centered at top.
- Pure white "FILL" text, bold/extrabold ~32px.

## 4. Implementation notes
- Keep the Oboe engine (v4.2) INTACT — this is a layout-only change (activity_main.xml + drawables + strings + partial MainActivity).
- Hamburger menu can show a "coming soon" Toast (future phase: kits/MIDI).
- 1-4 variation selector: visual only for now (persist selection in a variable; behavior in the future).
- "Style / Blues 1" and BPM: connect to real state (BPM already exists; Style can stay static "Blues 1" or come from the active groove).
- TAP TEMPO: behavior already exists (4 taps → BPM). Keep.
- VERSE/CHORUS: the current app alternates VERSE/CHORUS — the card reads "VERSE"; keep the tap toggle (or 2 cards? NO — follow the image: single "VERSE" card that toggles).
- PLAY/STOP toggle and FILL: behavior already exists.
- Visual beat pulse (count) must be preserved/adapted to the new layout (e.g., PLAY card glow on each beat).
