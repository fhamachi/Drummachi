# Drummachi — Changelog

> 100% free drum machine for Android, created by Felipe Hamachi (Ilhabela, SP, Brazil).
> Oboe low-latency audio engine · 24 factory grooves across 12 styles · BPM 40–240 · 4/4 and 6/8 time signatures.

---

## v5.8-beta — 2026-09-28
**New**
- **SWING control** — a SWING line in the mixer, sliding from **STRAIGHT** to **SHUFFLE**: it delays the odd sixteenth notes to lay the groove back (0.5 = classic shuffle triplet). The bar never stretches — the delay is applied on an alternating grid (even→odd stretches by `1+d`, odd→even shrinks by `1−d`), so downbeats and the tempo stay exact on every loop. Read once per audio callback; at 0 it takes the old integer fast path, byte-identical to the straight sound.
- Swing value is **persisted between sessions** (`drummachi_mixer` prefs) and applied through a new `nativeSetSwing` JNI entry point.
- Factory default remains **12 styles / 24 grooves**.
**Build**
- Native library rebuilt from the current `engine.cpp` in CI (`native-build.yml` now verifies the `nativeSetSwing` symbol before committing the `.so`).

## v5.7 — 2026-09-09
**New**
- **12 factory styles / 24 grooves**: Pop, Rock, Dance, Blues, Funk and Soul (two rhythms each), plus Metal, Samba, Latino, Hip-Hop, Reggae and Jazz — replacing the seven classic loops.
- **Bulk style import** (Menu → Import Style): pick several JSON files at once via SAF and get a summary toast.
**Fixes**
- Chicago Shuffle reworked: snare ghost on 2/10 (without doubling the hat), backbeat pushed to 3/11 like the approved Slow Blues, kick train only on the chorus.
- Blues rebuilt with an authentic shuffle hat (0-3-4-7-8-11-12-15).

## v5.6 — 2026-09-09
**New**
- **Style import via SAF** (Menu → **Import Style**) — add or override styles without reinstalling the app. The loader merges `assets/styles` with `filesDir/styles`, and an imported style overrides the built-in one with the same name.
**Fixes**
- Rhythm quality pass: clear 16th-note hat fills, cleaner boom-bap hip-hop verse, added latin kick.

## v5.5 — 2026-09-07
**New**
- **Master reverb** — level (REVERB) and time controls on their own mixer row, with a scrollable panel so the row is never cut off in landscape.
- **Theme selector** — Light / Dark / System.
- **Favorites** — mark rhythms as favorites and jump straight to them (Menu → Favorites).
- **About & donations** — Pix (Brazil) key with a COPY button and QR code hint, alongside the GoFundMe link.
- **Native rebuild pipeline** — `native-build.yml` compiles `libdrummachi.so` from `engine.cpp` on CI (x86_64) and commits it back, so the packaged `.so` always matches the source.
**Fixes**
- Packaged `liboboe.so` (`libdrummachi.so` now depends on it via prefab) and copy it on CI.
- Removed the "Previous crash log" dialog on launch (the log is still written to a file).

## v5.2-beta — 2026-09-02 (first public release)
**New**
- **Oboe (C++) low-latency engine** with a sequencer inside the audio callback (hits scheduled by absolute frame position) — first public build of the rewritten engine.
- Performance layout locked to landscape: TAP TEMPO, VERSE/CHORUS, PLAY/STOP, FILL, BPM slider (40–240) and visual pulse.
- Curated factory loops: **Pop1, Blues1, Ska, Rock1, Twist1, Funk1 and Ballad1**.
- Per-piece mixer (volume and tone) with two-finger side panel; values saved between sessions.
- CRASH button (one-shot) and real crash sample.
- Built-in kit as PCM16 WAV samples (Real Drums Vol. 1): KICK, SNARE, HAT, TOM FT/MT/HT, CRASH.
- About section with donations (GoFundMe + Pix).

> v5.1, v5.3 and v5.4 were internal dev builds (themes, crash/fill/favorites, Bluetooth audio fix) and were never tagged.

## v5.0.2-beta — 2026-08-29
**Fixes**
- Fixed crash when opening the About section: the text had `100% free` and, since the string uses format placeholders, the bare `%` caused `UnknownFormatConversionException`. Properly escaped (`100%%`).
- Native `.so` recompiled and validated inside the APK (new time-signature functions present).

## v5.0.1-beta — 2026-08-29
**New**
- Pix (Brazil) added to the About donations section, alongside the (clickable) GoFundMe link.
- Fixed the build process: `.so` is now recompiled from the current `engine.cpp` before packaging (previous build packaged the old lib → crash on launch).

## v5.0-beta — 2026-08-29 (first public release)
**New**
- **Variable time signature**: 9 rhythms in 6/8 (Blues 1/2, Shuffle 1/2, Swing 1/2/3, Funk 15, Reggae 4) now play at the correct tempo (12 sixteenth-note subdivisions per bar instead of the 16 of 4/4).
- **Adaptive count display**: 1-2-3-4 in 4/4; 1-2-3-4-5-6 in 6/8 (eighth notes), built dynamically.
- **No cut-off crash at the end of fills**: voice pool expanded (8 → 16) with smart voice stealing (never steals the crash when an alternative is available).
- **Official About section** with Felipe's text and GoFundMe donation link.

## v4.9 — 2026-08-28
**New**
- **Full repertoire: 66 dmp_midi grooves** across 16 styles (Afro, Ballad, Blues, Bossa Nova, Cha Cha, Disco, Funk, Pop, Reggae, Rhythm & Blues, Rock, Samba, Shuffle, Ska, Swing, Twist) — replaces the 12 from Betão.
- **Per-hit velocity** (0–127) in the engine: EZdrummer-level humanization.
- Crash cymbal with a new sound (RD_C_C_3, better attack).
- Fix: touches passing through the mixer panel (click-through).

## v4.8 — 2026-08-28
**New**
- CRASH button: fires the crash cymbal immediately (one-shot).
- Real crash sample in the kit.

## v4.7 — 2026-08-28
**New**
- **Per-piece mixer**: individual volume and tone (KICK, SNARE, HAT, TOM FT/MT/HT, CRASH), side panel with two-finger gesture; values saved between sessions.

## v4.6 — 2026-08-28
**New**
- **Real sample kit** (Real Drums Vol. 1): KICK, SNARE, HAT, TOM FT/MT/HT as PCM16 WAV — end of 100% procedural synthesis.
- WAV loading from assets with resampling to the stream rate.

## v4.5 — 2026-08-28
**New**
- Part switching (VERSE/CHORUS) **scheduled** for the bar boundary (no cut in the middle of the groove).

## v4.4 — 2026-08-28
**New**
- The 1–4 counter became a **display** (follows the bar, no click).
- **Functional style menu**: 12 Betão rhythms (Rock, Pop, Blues, Latino) in `assets/styles`, with label, BPM and patterns applied via JNI.
- FILL card lights up blue while the fill is active (native callback).
- **Continuous BPM slider** in the header (40–240), alongside TAP TEMPO.

## v4.3 — 2026-08-28
**New**
- **Design v4** (spec generated with Gemini + vision): dark landscape layout with 18% header, 2×2 grid (TAP TEMPO, VERSO, PLAY, FILL), pixel-faithful colors and proportions.
- Beat pulse now animates the PLAY card.

## v4.2 — 2026-08-28
**Fixes (critical)**
- **Fixed launch crash on Android 10+** (Galaxy Note 20 Ultra): the native lib was linked against static `libc.a`, embedding GWP-ASan with IE-model TLS (forbidden in libs loaded via dlopen). Now links against the sysroot `.so` files (API 34) → only shared NEEDED, gwp_asan=0, lib 2.7MB → 372KB.
- Diagnostic tooling: crash log to file + dialog on next launch, native signal handler, `extractNativeLibs=true`.

## v4.1 — 2026-08-28
**Fixes**
- Attempted fix for the launch crash (scudo/extractNativeLibs) — did not resolve it; root cause only fixed in v4.2.

## v4.0 (Oboe) — 2026-08-28
**New (audio engine rewrite)**
- **Oboe engine in C++** (Google's official library): LOW-LATENCY + EXCLUSIVE stream (automatic fallback), mono float audio at the device's native rate.
- **Sequencer inside the audio callback**: hits scheduled by absolute frame position — ~1-sample precision (20μs at 48kHz), zero thread jitter.
- 8-voice pool with retrigger and overlap.
- Same interface and controls (TAP TEMPO, VERSE/CHORUS, PLAY/STOP, FILL).

## v3.1 — 2026-08-27
**Tweaks**
- Design v3 refinements (final layout) for on-device testing.

## v3.0 — 2026-08-27
**New**
- **New layout (Felipe's final design)**: dark blue background, STYLE selector top-left, count display top-right, TAP TEMPO/PART and PLAY/FILL (blue FILL).
- New vector icons (drawn by Picasso/Gemini).
- Procedural synthesis audio engine (pre-soundfont).

## v2.0 — 2026-08-27
**New**
- **Performance interface**: pulse area (number 1–4 + BPM + part), preparation row (TAP TEMPO and part switching) and performance row (PLAY and FILL).
- **Looping sequencer** (4/4, 16 sixteenth notes) on a dedicated thread, with synced visual callback.
- **Tap tempo**: 4 taps set the BPM (40–240).
- **VERSE and CHORUS parts** with different patterns; switching schedules a transition fill.
- **FILL**: one-bar fill.
- Immersive mode, locked landscape, screen always on.
- Removed the Material lib (smaller APK and faster builds).

## v1.0 — 2026-08-27
**First version**
- Working prototype: 3 drum pads (KICK, SNARE, HAT) with procedural synthesis via AudioTrack.
- Project base: Kotlin, minSdk 23 (Android 6.0+), targetSdk 34, package `com.drummachi`.