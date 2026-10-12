# Circuit Bender

A circuit-bent VHS camcorder in your phone, with no soldering iron needed.

Point your phone and you get a cheap 90s camcorder: low resolution, soft bleeding colour, a little tape noise, and a REC / date overlay in the viewfinder. Two switches add the fun stuff:

- **BEND** glitches the camera the way a circuit-bent one does. There are three knobs:
  - **NOISE**: shorted data lines. Sparkle at first, then posterised colour, then JPEG block errors.
  - **TEAR**: pixel-clock and sync faults. Lines slip, then slant, then the picture rolls.
  - **COLOUR**: analog gain, then a colour-filter slip that swaps the colour channels, then crossed wires.
- **DITHER** turns the picture into retro dithered pixels. There are three knobs:
  - **STYLE**: Bayer, Floyd–Steinberg, Atkinson (Mac) or Game Boy Camera.
  - **PALETTE**: 1-bit, Game Boy green, CGA or PICO-8.
  - **PIXEL**: 320, 160 or 128 (Game Boy) wide.

**PHOTO** saves a PNG to `Pictures/CircuitBender`. **VIDEO** records an MP4 to `Movies/CircuitBender` (no sound yet). The camcorder overlay is only in the viewfinder: photos and videos are clean.

It works in landscape (4:3) and portrait (3:4).

## Install

Download the APK from [Releases](../../releases) and open it on your phone. Requires Android 10+.

## Building

```
./gradlew :core:test           # effect engine tests (desktop JVM, no phone needed)
./gradlew :app:assembleDebug   # the APK
```

`core/` is plain Kotlin and holds every pixel effect. `app/` is the Android camera and buttons.

## How accurate is it?

The effects are modelled on the real hardware, with sources in [docs/RESEARCH.md](docs/RESEARCH.md):
- VHS colour has about 1/8 the resolution of brightness, and the colour sits a couple of pixels to the right.
- The bends act on a simulated sensor data bus (YUV422 bytes) the way soldered shorts do.
- The dither kernels and the Game Boy Camera threshold matrix are the published ones.

The code was built test-first with [general-harness](https://github.com/chickengamer555/general-harness). The contracts are in `verify/`.
