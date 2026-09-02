# Drummachi 🥁

**100% free drum machine for Android**, created by Felipe Hamachi (Ilhabela, SP, Brazil).

> **Oboe** low-latency audio engine · **66 grooves** across **16 styles** · BPM 40–240 · **4/4 and 6/8** time signatures · 100% offline

## ✨ Highlights

- **Oboe (C++) audio engine** — low-latency + exclusive stream with automatic fallback
- **66 `dmp_midi` grooves** across 16 styles: Afro, Ballad, Blues, Bossa Nova, Cha Cha, Disco, Funk, Pop, Reggae, Rhythm & Blues, Rock, Samba, Shuffle, Ska, Swing and Twist
- **Per-piece mixer** — individual volume and tone (KICK, SNARE, HAT, TOM FT/MT/HT, CRASH) with a two-finger side panel; values saved between sessions
- **Variable time signature** — 9 rhythms in 6/8 play at the correct tempo (12 sixteenth-note subdivisions per bar), with an adaptive count display (1-2-3-4 / 1-2-3-4-5-6)
- **Per-hit velocity (0–127)** — EZdrummer-level humanization
- **Crash cymbal** with dedicated sound and one-shot button
- **About section** with optional donations (GoFundMe + Pix Brazil)

## 📋 Requirements

- **Android 6.0+ (API 23)** — Android 10+ (API 29) recommended for the low-latency audio engine
- **ARM64 (arm64-v8a)** — 64-bit native library only
- **Landscape screen** (app is locked to landscape)
- ~50 MB free space · no connection needed to play (fully offline)

## 🛠️ Build

Android project with Kotlin Gradle DSL and bundled Gradle wrapper:

```bash
cd DrumMachine
./gradlew assembleDebug   # or assembleRelease
```

Technical details:
- Package: `com.drummachi`
- ABI: `arm64-v8a`
- Audio: Oboe (C++) via `engine.cpp`, packaged as a `.so`
- Full requirements in [`REQUIREMENTS.md`](REQUIREMENTS.md)

## 📜 History

See [`CHANGELOG.md`](CHANGELOG.md) for the full version history.

---

Developed in Ilhabela, SP, Brazil 🇧🇷
