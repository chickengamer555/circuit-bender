# Circuit Bender

A circuit-bent VHS camcorder in your phone, with no soldering iron needed.

Point your phone and you get a 90s camcorder: an analog picture (samples along 480 lines, soft sideways, grey blacks, glowing highlights, grain in the shadows) with a REC / date overlay that sits behind the tape filter.

Tap **VHS**, **Bend** or **Dither** to switch an effect on or off. **Adjust** opens its settings; tap outside to close them, and the effects stay on.

- **VHS:** wear, colour bleed, softness, camera quality (toy cam 160 / VHS EP 240 / VHS SP 320 / Hi8 480 / sharp 640), glow, REC & date on/off, and whether it is burned into photos and videos.
- **Bend:** noise, tear, colour, memory, roll and blocks, each modelled on a real circuit-bend point (data lines, pixel clock, sync, frame-buffer address lines, JPEG errors).
- **Dither:** all 12 styles (Bayer, halftone, Game Boy Camera, Floyd–Steinberg, Atkinson, JJN, Stucki, Burkes, Sierra...); natural colours from 8 to 4096, or 1-bit, sepia, Game Boy, Virtual Boy, CGA, EGA, C64, ZX Spectrum or PICO-8; pixel size from 640x480 down to 64x48; contrast and brightness.

**Shortcuts:**
- Pinch to zoom.
- Double-tap to flip camera.
- Swipe left/right for VIDEO/PHOTO.
- Volume buttons take the shot.

Photos are saved as PNG in `Pictures/CircuitBender`, and videos as MP4 in `Movies/CircuitBender` (no sound yet). It works upright (3:4) and sideways (4:3).

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
