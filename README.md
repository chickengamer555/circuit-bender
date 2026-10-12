# Circuit Bender

A circuit-bent VHS camcorder in your phone, with no soldering iron needed.

Circuit Bender is an Android camera app. It downgrades your phone's camera into a late-80s/90s consumer camcorder recording to VHS, then lets you bend it. The rack of controls models the real bend points people solder onto cheap digital cameras: data lines, clocks, sync lines, frame-memory address lines and the Bayer filter. A real-time dither engine covers everything from 1-bit Mac to the actual Game Boy Camera matrix.

Every photo is exactly the frame the viewfinder showed, overlay included, saved as a lossless PNG in `Pictures/CircuitBender`.

## Install

Download `app-release.apk` from [Releases](../../releases) and open it on your phone. You'll need to allow installing from your browser or file manager. Requires Android 8.0+.

## What it does

### Always on: the camcorder
The camera feed is cropped to 4:3 and box-averaged down to a 320x240 "CCD". It then goes through a VHS signal chain built from real numbers (see [docs/RESEARCH.md](docs/RESEARCH.md)):

| Effect | Value | Source |
|---|---|---|
| Luma resolution | ~320 px/line (3 MHz, 240 TVL) | VHS spec |
| Chroma resolution | ~40 samples/line (1/8 of luma), averaged over 2 lines | colour-under bandwidth |
| Y/C delay | chroma ~2 px right of luma (~300 ns) | measured deck |
| Edge enhancement | peaking with overshoot/undershoot halos | camcorder "sharpness" |
| Head-switching noise | ~7 skewed lines at the bottom of frame | ntsc-rs calibration |
| Tape noise, snow, dropouts, line jitter | ntsc-rs defaults | ntsc-rs |
| CCD vertical smear | bright lights streak the full column | interline CCDs |
| SP / LP / EP | slower tape = softer, noisier, less colour | |

The viewfinder overlay has a blinking `● REC`, tape speed, a battery gauge (your real battery level), a tape counter, and the date/time stamp (`OCT.12 2026`, `10:48:03 PM`). It's drawn in the HD44780 character-generator font. The date stamp goes in *before* the tape chain, because real camcorders recorded it into the video, so it bleeds like everything else.

### BEND: the circuit-bend rack
The picture travels as a real YUV422 byte stream (`Y0 U Y1 V`) through sensor → bus → DSP → frame memory, and each control is a fault at one of those points:

| Control | Real bend | What you see |
|---|---|---|
| **D0–D7** HI/LO + pot | data line tied to VCC/GND through a pot (0 = hard short, 1 = open; in between it flips intermittently) | posterisation, false colour, sparkle |
| **BRIDGE** AND/OR | two data lines patched together | contouring, psychedelic false colour |
| **CROSS** | crossed patch cables (bit swap) | non-monotonic "drug trip" tones |
| **XTALK** | data line coupled to the pixel clock | stripes, moiré, checkerboards |
| **BYTE SLIP** | DSP latches the stream one byte late | neon green/magenta, half-res ghost |
| **PCLK pot** | RC-rounded pixel clock; nothing until the knee, then dropped clocks | torn, sheared lines |
| **HSYNC stride** | lost line sync, wrong stride | diagonal slant and wrap |
| **VSYNC roll** | lost frame sync | rolling picture with a black blanking bar |
| **ADDR A4–A17** HI/LO | stuck frame-buffer address line | repeated blocks, *stale frames frozen in memory* |
| **BAYER** +X/+Y/+XY | ISP demosaics with the wrong colour-filter phase | +X / +Y swap R↔G and B↔G; +XY swaps R↔B |
| **ADC gain** | pot on the analog CCD output | crushed/blown, noisy |
| **SCCB** | corrupted sensor config registers | negative, neon, colour bars, mirror |
| **JPEG bit errors** | errors in the entropy stream | shifted 16x16 blocks, colour drift |
| **FIELD** | interlace field swap/drop | combing, half resolution |
| **Brownout** | supply sag | freezes, half-written frames, dead lines |

Benders don't just flip switches, so neither do you:
- **GLITCH (hold)** is a momentary push-button. The rack is live only while you hold it.
- **BODY CONTACT** stands in for exposed contacts. Your finger's position is your skin resistance: up/down drives the data-line pots, left/right the pixel-clock pot.
- **LDR mode** uses the phone's light sensor as a photoresistor in series with the data lines. Brighter light means lower resistance and a harder bend.
- **CHAOS** wires 2–4 random bends.

### DITHER
Real-time dithering to a palette, with the overlay drawn in palette colours so photos stay pure:
- **Ordered:** Bayer 2x2 / 4x4 / 8x8 and a clustered-dot halftone.
- **Game Boy Camera:** the real 4x4 matrix with 3 thresholds per cell from the M64282FP / MAC-GBD.
- **Error diffusion:** Floyd–Steinberg, Atkinson (keeps only 6/8 of the error, which gives the original Mac look), Jarvis–Judice–Ninke, Stucki, Burkes, Sierra and Sierra Lite.
- **Palettes:** 1-bit Mac, Game Boy DMG / BGB / Pocket, CGA modes 4 and 5, CGA/EGA 16 and PICO-8.

The **Game Boy Camera preset** sets 128x112, GB dither and DMG greens.

## Building

```
./gradlew :core:test           # the effect engine's test suite (pure JVM, no device needed)
./gradlew :app:assembleDebug   # the APK
```

`core/` is a plain Kotlin module with every pixel effect, so it's tested on the desktop JVM. `app/` is the thin Android layer: CameraX, the viewfinder and the controls.

## How it was built

Built test-first with the [general-harness](https://github.com/chickengamer555/general-harness):
- Each unit (`dither`, `bend`, `vhs`, `osd`, `pipeline`) has a plain-language contract in `verify/*.task.json`.
- Every test was recorded failing before its implementation existed (`.general-harness/redproof.json`).
- Independent critic passes reviewed the tests for ones that would pass against a broken implementation.

## Accuracy notes

[docs/RESEARCH.md](docs/RESEARCH.md) has the sources. Numbers there are tagged as sourced, derived, or estimated:
- The VHS bandwidths and the dither kernels and matrices are sourced.
- The bend-to-pixel mapping is engineering inference from how the sensor bus works, consistent with documented results from boxo.cc, Phillip Stearns and r/CircuitBending. Nobody publishes per-pin maps; real bent cameras are famously unpredictable.
