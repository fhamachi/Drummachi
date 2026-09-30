# Drummachi — System Requirements

## Minimum requirements
- **Android 6.0 (Marshmallow, API 23)** or higher
- **ARM64 (64-bit) processor** — the app ships only the `arm64-v8a` native library
- **Landscape screen** (the app is locked to landscape — recommended phone/tablet; does not work well in portrait)
- **Free space:** ~60 MB of storage (~6.7 MB APK + in-use data)
- **Connection:** none needed to play (fully offline)

## Recommended requirements
- **Android 10 (API 29)** or higher — better support for the low-latency audio engine (Oboe/AAudio)
- **2 GB RAM** or more (for smooth style switching and mixer use)
- **2019+ phone or tablet** — low-latency audio depends on hardware (validated on a Galaxy Note 20 Ultra / Android 13)
- **Headphones** for real-time response feel (perceived latency is lower with headphones)

## Technical details
- **Package:** `com.drummachi`
- **APK size:** ~6.7 MB
- **ABI:** `arm64-v8a` (does not run on old 32-bit phones)
- **Audio:** Oboe (C++), low-latency + exclusive stream with automatic fallback
- **Content:** 24 factory grooves across 12 styles (Blues, Dance, Funk, Hip-Hop, Jazz, Latino, Metal, Pop, Reggae, Rock, Samba, Soul), plus style import (JSON) — BPM 40–240, 4/4 and 6/8 time signatures
- **Donations:** optional (GoFundMe + Pix Brazil) — the app is 100% free

> Developed in Ilhabela, SP, Brazil 🇧🇷
