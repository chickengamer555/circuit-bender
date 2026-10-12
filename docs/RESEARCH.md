# Research: Camcorder, Circuit-Bent Camera and Real-Time Dithering Simulation

Compiled 2026-10-12. This is research for implementation; it contains no app code.

**How confident each number is:**
- **[S]** comes straight from the cited source.
- **[D]** is derived from sourced figures by stated arithmetic.
- **[E]** is an engineering estimate or a recommended default where no primary source gives a number. Tune these by eye.

---

## 0. TL;DR parameter sheet

| Effect | Parameter | Value | Tag |
|---|---|---|---|
| VHS luma | bandwidth | ~3 MHz | S |
| VHS luma | horizontal resolution | 240 TVL (VHS), 250 (HQ), ~230 (LP) | S |
| VHS luma | equivalent pixels | ~333 x 480 (NTSC), ~333 x 576 (PAL) | S |
| VHS chroma | bandwidth | ~300 kHz baseband (Wikipedia); ±500 kHz around the 629 kHz color-under carrier (forums) | S |
| VHS chroma | equivalent pixels | ~40 x 480 (NTSC), ~40 x 576 (PAL), i.e. about 1/8 of luma | S/D |
| S-VHS / Hi8 luma | resolution | 400–420 TVL (~560 px) | S |
| Working buffer | recommended | luma 320x240 per field (or 333x480 frame), chroma 40x240 | D |
| Y/C delay | chroma offset vs luma | ~300 ns, about 4 px at 720 wide or ~2 px at 320 wide, shifted right | S/D |
| Head-switch band | height | ~5–8 lines at bottom; ntsc-rs default 8 lines, 3 hidden, shift 72 | S/E |
| Line jitter (TBE) | per-line horizontal offset | 0.5–2 px at 320 wide, low-pass in time | E |
| GB Camera | resolution | 128x112 (from a 128x128 sensor, top and bottom 8 rows dropped), 2 bpp | S |
| GB Camera | dither | 4x4 matrix, 3 thresholds per cell (48 bytes), 4 output levels | S |
| Bayer 4x4 | matrix | `0 8 2 10 / 12 4 14 6 / 3 11 1 9 / 15 7 13 5` (/16) | S |
| Floyd–Steinberg | weights | 7,3,5,1 /16 | S |
| Atkinson | weights | six cells x 1/8 (75% of error) | S |

---

## 1. Circuit-bent cameras

### 1.1 What actually gets bent (documented)

| Device | What was done | Source |
|---|---|---|
| Kodak DC-series point-and-shoots (DC280: 2 MP 1/1.8" RGBG CCD) | Phillip David Stearns' "DCP" series and the *Year of the Glitch* Tumblr (glitch-a-day). Images described as "abstract images made up of hazes of pixels" and woven or knitted into textiles. | https://cdm.link/glitch-exquisite-textiles-made-from-a-circuit-bent-point-and-shoot-camera/ , https://hyperallergic.com/faultline-an-interview-exploring-glitch-art , https://makezine.com/article/craft/fine-art/blips-and-blankets/ , https://imaging-resource.com/PRODS/DC280/DC280DAT.HTM |
| €10 AliExpress "CCD" toy video camera (actually CMOS, ribbon marked "L-X1-WA6") | All 24 sensor-ribbon pins broken out to a patchboard and bridged with patch cables. | https://boxo.cc/blog/circuit-bending/ |
| ~$5 "G6" thumb/keychain camera | Resistors, potentiometers and switches on the sensor-to-processor path. | https://www.hackster.io/news/start-with-photographic-circuit-bending-using-this-5-camera-607484bc6b98 |
| Kids' toy cameras (Mgaolo dinosaur camera and similar) | 24-pin 0.5 mm sensor ribbon; best pins were 1, 6, 7, 8, 9 and 24; 2 switches and 2 pots added. | https://ade3.substack.com/p/how-to-make-a-glitch-camera , https://makezine.com/article/craft/photography-video/go-beyond-reality-with-a-circuit-bent-glitch-cam/ |
| Canon PowerShot A520 / A590, Sony DSC-F707, Olympus compacts | CCD/ADC path bends. The A520 "logic bend" uses a DIP-switch source selector, a target selector, bypass/invert/divide switches, and a pot-controlled high-pass filter, powered from the 3.3 V digital rail. Olympus results reported as "melting, horizontal tearing, color shifts". | r/CircuitBending (mirrors: https://r.datuan.dev/r/CircuitBending/duplicates/1ud0ec9 , https://r.datuan.dev/r/CircuitBending) |
| Glitchamatic Pro (Lo-Fi Future, commercial) | Toy camera bent so that **microphone audio is injected into the sensor data**, making the glitch audio-reactive. Records SD video. | https://www.creativeapplications.net/news/glitchamatic-pro-audio-reactive-circuit-bent-glitch-camera |
| Sega Genesis (analogous) | VRAM data lines shorted. | https://simonhutchinson.com/tag/sega/ |
| NES (Casper Electronics / Peter Edwards, 2009) | Bent into a video synthesizer. | https://cr4.globalspec.com/blogentry/10171/Circuit-Bent-NES-Makes-a-Fun-Video-Synthesizer , https://www.fluxfactory.org/peter-edwards/ |
| Workshops | Mediamatic/STEIM "digital camera modification", Machine Project "digital cameras circuit bending glitch art". | https://www.mediamatic.net/ar/page/22596/digital-camera-modification-workshop , https://machineproject.com/2014/workshops/digital-cameras-circuit-bending-glitch-art/ |

**Not found.** I found no primary write-ups with photos for bent Casio QV-10/QV-11, Sony Mavica, Fisher-Price Kid-Tough or Game Boy Camera units. Searches for Tom Kemp turned up nothing; the nearest hit was Tom Koch (Univac), who bends analog video gear (https://blurb.co.uk/b/9677379-univac-visual-experiments-2017-2018). Treat those cameras as plausible targets, not documented ones.

Community consensus from the sources above:
- The productive area is the **sensor ribbon** (sensor to DSP) and the **CCD-to-ADC** region.
- A forum method: measure every pin's voltage first, then short low-voltage pins.
- Pin maps vary by model. Results are unpredictable: "nobody can predict the result".

### 1.2 Bend points and what they produce

A CMOS sensor module like the OV7670 has these lines:
- **D0–D7:** parallel pixel data, sampled on the PCLK rising edge.
- **PCLK:** pixel clock.
- **HREF/HSYNC:** high during a line's active pixels.
- **VSYNC:** frame start.
- **XCLK:** master clock input, about 10–48 MHz.
- **SCCB (I²C-like):** configuration registers.
- **RESET** (active low) and **PWDN** (active high).

Source: https://www.cs.columbia.edu/~sedwards/classes/2022/4840-spring/reports/CameraControl-report.pdf , https://www.fdi.ucm.es/profesor/mendias/das/docs/OV7670_2006.pdf

Most toy cameras use the same kind of bus, usually 24-pin FPC. The output is typically YUV422 (byte order Y0 U Y1 V) or raw Bayer. **This matters for the simulation: one data-bit error lands on Y in one byte and on chroma in the next.**

The table below maps each bend to a pixel operation. The visual descriptions in quotes are from sources. The pixel models are **[E]**: derived from how the bus works, and consistent with the reported looks.

| Bend point | Physical effect | Pixel-processing model | Reported / expected look |
|---|---|---|---|
| **Data bit stuck high/low** (Dn tied to VCC/GND) | One bit forced | `v = v \| (1<<n)` or `v & ~(1<<n)`. On YUV422 the same bit hits Y, U and V. | Low bits: faint noise or banding. MSB (D7): severe inversion or posterization of half the range. boxo.cc: pin-8 shorts gave "posterization… blown-out skies, vivid purples and warm tones". |
| **Two data bits bridged** | Wired-AND/OR, or contention | `b_n = b_m = (b_n & b_m)` (AND) or OR | Posterization and false colour; contours appear where the bits disagree. |
| **Bits swapped / reordered** | Patch cables crossed | Permute the bits of each byte | Psychedelic false colour with non-monotonic tone mapping ("drug trip… sharp, discrete, dry looks, sky quantized" from pins 6↔21/24/4). |
| **Data line to clock/sync line** | Data modulated by a periodic signal | XOR the bit with `(x >> k) & 1` or `(y >> k) & 1` | Fine vertical or horizontal stripes, moiré, checkerboards. |
| **Byte-phase slip** (YUV422 byte alignment off by one) | DSP latches U as Y, and so on | Reinterpret the stream shifted by 1 byte | Strong green/magenta false colour, half-resolution ghost, typical "neon" look. |
| **PCLK tampering** (pot or cap on PCLK, or XCLK) | Missed or extra clocks per line | Each line drops or duplicates N samples, so line length changes: `x_src = x + y*slope` with wrap | **Horizontal shear / diagonal slant**: the image tilts and wraps. Random N per line gives jagged tearing (the Olympus "horizontal tearing"). |
| **HREF/HSYNC lost or noisy** | Line boundaries wrong | Treat the frame as one 1-D stream re-wrapped at a wrong width W' (`idx = y*W' + x`) | Diagonal slant plus repeated wrapping bands, the classic "wrong stride" look. |
| **VSYNC lost or noisy** | Frame start drifts | Vertical roll: `y_src = (y + offset(t)) mod H`, with the old/new frame boundary visible | Rolling, tearing at a horizontal seam, frame spliced from two exposures. |
| **Config bus (SCCB/I²C) glitch** | Sensor registers corrupted | Switch to a different "mode" preset (gain, matrix, test pattern, mirror) that persists until reboot | boxo.cc pins 13↔17: "neon with bright green glitches", a second filter on a long press, both persisting until reboot. Also color-bar test pattern (ade3: color bars mean wrong wiring). |
| **Frame-buffer / SDRAM address line** | Address bit stuck or bridged | `addr' = addr \| (1<<k)` or `& ~(1<<k)` | Block or tile **repetition**: a strip of 2^k bytes is duplicated. High bits repeat whole bands of the frame. Data from earlier captures shows through where memory is not rewritten (stale-buffer bleed). |
| **SDRAM data line** | As for sensor data, but post-processing | Bit ops on RGB565 or JPEG bytes | In RGB565, bits 15–11 are R and 4–0 are B, so one bit gives channel-specific posterization. |
| **JPEG / compression corruption** | Bit errors in the entropy-coded stream | Flip a bit in the Huffman stream; desync propagates to the end of the MCU row or the image | Every MCU after the error is shifted; DC drift gives a colour cast per block; 8x8 (luma) and 16x16 (4:2:0 MCU) block smears; channel shifts per block. |
| **Bayer CFA phase error** (raw sensor, start pixel off by 1) | Demosaic sees R as G, and so on | Demosaic with an offset of (1,0), (0,1) or (1,1) | Offset of 1 in x or y swaps R/G or B/G: strong magenta or green tint and checkerboard zippering at edges. (1,1) swaps R and B. |
| **ADC reference / analog CCD out** (pot on the analog path) | Gain or offset change | `v = clamp(g*v + o)` plus noise, sometimes with ringing | Crushed or blown tones. Too much attenuation gives noisy, dark, posterized frames. |
| **Power rail / brownout** | Logic marginal | Random combinations of all of the above, frame drops, freeze, last-frame hold | Freezes, reboots, color-bar screens; sometimes partial frames (top half new, bottom half old). |
| **Interlace field errors** (analog-out cameras and camcorders) | Wrong field order or a dropped field | Swap or duplicate odd/even lines | Combing on motion; half vertical resolution; one-line jitter. |

### 1.3 Controls benders install

From Ghazala and the guides above:
- **Toggle switches:** make or break a bridge (latching effects).
- **Momentary push buttons:** "glitch while held" (boxo.cc: a short tap and a long hold behave differently).
- **Potentiometers** in series with the bridge. Common hobby values are 1 kΩ–1 MΩ **[E]**; the cited sources don't give values.
- **Photoresistors (LDRs):** a light-controlled resistance replacing a pot.
- **Body contacts:** exposed metal pads, so "the body of the performer is used as a variable resistor" (Reed Ghazala; https://en.wikipedia.org/wiki/Reed_Ghazala). Ghazala's "anti-theory" is to short arbitrary points on a live circuit, "the creative audio short-circuit" (https://www.soundonsound.com/node/4923968).
- **Logic bends:** invert, divide (flip-flop), high-pass-filtered injection (the A520 bend).
- **Audio injection:** the Glitchamatic Pro.

**Pot on a clock line vs a data line [E]:**
- **Clock line (PCLK/XCLK).** The pot plus the line's capacitance forms an RC low-pass, which rounds clock edges. As resistance rises, edges miss the logic threshold. The result goes from occasional dropped clocks (sparse line slips, slight shear) to many (strong shear or rolling) to full loss of sync (frozen or black image).
  - The control is a **threshold-like, unstable** "amount of slip". Model it as a probability of per-line sample drops that rises steeply past a knee.
- **Data line, pot between Dn and Dm or a rail.** Low R acts as a hard short, so the bit is forced. High R lets the bit pass, so the effect is off. In between, the line sits near the logic threshold, so the bit flips **intermittently and is noise-driven**.
  - Model it as a per-pixel probability p(R) that bit n takes the forced value. p moves from 1 to 0 as the pot turns, giving sparkle that grows into solid posterization.
- **Analog path (CCD output, ADC reference).** The response is smooth and continuous: gain, offset and bandwidth change.

### 1.4 Simulation recipe (generic)
Simulate on a **YUV422 byte stream** for the sensor-bus look, not on RGB:
1. Pack the frame to the byte stream.
2. Apply bit ops and stride/clock slips.
3. Unpack.
4. Then optionally simulate JPEG-block artifacts on the 8x8 / 16x16 grid.

Operating on bytes gives the characteristic cross-talk between luma and chroma for free.

---

## 2. VHS / Hi8 consumer camcorder look

### 2.1 Bandwidth and resolution

**Sourced figures:**
- **VHS luma:** "up to 3 MHz baseband video bandwidth". Resolution is 240 TVL (VHS), 250 (VHS HQ), ~230 (LP), lower at EP.
- **VHS chroma:** "300 kHz baseband chroma bandwidth".
- **Pixel equivalents:** ~333x480 / ~333x576 for luma and ~40x480 / ~40x576 for chroma.
- **S-VHS:** >5 MHz, 400–420 TVL, ~560 px.
- **VHS tape mechanics:** SP 3.335 cm/s (NTSC); drum at 1798.2 rpm (NTSC) or 1500 rpm (PAL); two heads at ±6° azimuth; one field per track.
  Source: https://en.wikipedia.org/wiki/VHS
- **Color-under:** around 629 kHz (NTSC) with sidebands of about ±500 kHz. Forum figures put chroma at 500–800 kHz and luma at 3–4 MHz; luma FM is centered near 4.3 MHz.
  Source: https://forum.videohelp.com/showthread.php?p=2383170 , https://www.vogons.org/viewtopic.php?p=1150403
- **Hi8:** rated 400 lines of luma. The luma FM carrier is 5.7–7.7 MHz (Video8: 4.2–5.4 MHz). Chroma is unchanged from Video8.
  - Video8 is commonly ~240–280 lines **[E]**. Wikipedia gives no number.
  - Note: Wikipedia's "1.2 / 2.0 MHz" figures are FM deviation, not video bandwidth.
  Source: https://en.wikipedia.org/wiki/Hi8

**Derivation [D].** NTSC active line ≈ 52.6 µs.
- Luma samples ≈ 2 x 3 MHz x 52.6 µs ≈ **316 px**. Dividing by 4/3 gives ≈ 237 TVL (lines per picture height), which matches 240.
- Chroma ≈ 2 x (0.3–0.5 MHz) x 52.6 µs ≈ **32–53 px**, so **~40 px**. That is 1/8 of luma.

**Recommended working sizes:**

| Look | Luma | Chroma |
|---|---|---|
| VHS SP | 320 px wide (blur to ~3 MHz equivalent) | 40 px wide |
| VHS LP/EP | ~280–300 px | 30–40 px |
| Hi8 / S-VHS | ~520–560 px | still ~40 px |

- **Vertical:** 480 active lines (NTSC) or 576 (PAL), interlaced. Each field is 240 or 288 lines.
- **Simplification:** process at 240 lines per field and line-double, or 480 with field-alternating jitter.
- **Vertical chroma:** NTSC VHS comb or PAL delay-line averaging roughly halves vertical chroma resolution. Average chroma over 2 lines **[E]**.

### 2.2 Artifacts, with suggested parameters

The ntsc-rs defaults below are useful calibrated starting points. Source: https://mltframework.org/plugins/FilterOpenfx-wtf-vala^ntscrs/ ; https://korben.info/en/ntsc-rs-open-source-vhs-effect-real-signal.html

| Artifact | Physics | Parameters |
|---|---|---|
| **Luma blur** | 3 MHz low-pass | Horizontal-only low-pass to ~316 px equivalent. ntsc-rs "luma smear" default 0.5. |
| **Chroma blur and bleed** | ~0.3–0.5 MHz | Horizontal low-pass of U/V to ~40 px, then upsample. Colours bleed ~4–8 px (at 320 wide) past edges **[D]**. |
| **Y/C delay** | Chroma path latency | Measured −296 ns on one deck, "≈4 px at 74 ns/px". This gives a ghost to the right on bright edges. Use **+1 to +3 px at 320 wide**. ntsc-rs range ±40, default 0. Sources: https://forum.videohelp.com/showthread.php?p=2761582 , https://www.analog.com/en/design-notes/visual-impact-of-video-parameters-in-video-systemspart-2.html |
| **Edge enhancement / ringing** | Camcorder and VCR "sharpness" peaking | Unsharp mask on luma only. Overshoot 10–30% with a 1–2 px halo **[E]**. ntsc-rs sharpen 0.25 (frequency 1.0); ringing frequency 0.45, power 4, scale 4. |
| **Luma noise** | Tape and head noise | ntsc-rs intensity 0.01, frequency 0.5. Use horizontally correlated noise (stretched about 2–4 px in x) **[E]**. |
| **Chroma noise** | Color-under phase noise | ntsc-rs intensity 0.1, frequency 0.05, detail 2. Chroma phase noise 0.001. Low-frequency blotches in U/V that change every frame. |
| **Snow** | RF dropout specks | ntsc-rs snow 0.00025 per pixel, anisotropy 0.5. These are short white horizontal streaks. |
| **Dropouts** | Oxide loss or debris; brief RF loss | White or black horizontal streak, ~5–50 px long and 1 line high **[E]**. A deck with a dropout compensator (DOC) **repeats the previous line** instead. Several adjacent dropouts show as visibly repeated lines. Source: https://avartifactatlas.com/artifacts/video_dropout.html |
| **Head-switching noise** | Switch between the A and B heads just before vertical sync | Band at the **bottom** of the frame; it bounces on bad tapes. ntsc-rs: height **8 lines**, offset 3 (off-screen), horizontal shift **72** (of ±100), starts mid-line at 0.95 with jitter 0.03. Commonly placed ~6.5 lines before vsync **[E]**. Source: https://avartifactatlas.com/artifacts/head_switching_noise.html |
| **Top-of-frame flagging / skew** | Tape tension or skew error | Top ~10–20 lines bend sideways progressively **[E]**. Source: https://forum.videohelp.com/showthread.php?p=2460878 |
| **Tracking error** | Head off the track | Band of noise and horizontal displacement that moves vertically. ntsc-rs tracking noise: height 12 lines, wave 15, snow 0.025, noise 0.25. |
| **Time-base error / jitter** | Mechanical speed variation, no TBC | Per-line horizontal offset. Use 0.5–2 px at 320 wide, low-passed across lines **[E]**. ntsc-rs "edge wave": intensity 0.5, speed 4, frequency 0.05, detail 2. |
| **Interlace combing** | Fields 1/60 s apart | Odd and even lines from different times show combing on motion. |
| **Crushed blacks / clipped highlights** | Limited headroom plus camcorder knee | Soft knee above ~90–95% IRE, lift or crush below ~7.5 IRE (NTSC setup) **[E]**. |
| **Saturation** | Chroma AGC; LP loses chroma | Slightly oversaturated reds that bleed; desaturated LP/EP. "Chroma loss" (whole lines lose colour) at 2.5e-5 per line by default in ntsc-rs. |
| **Generation loss** | Each dub repeats the chain | Apply the chain again: more blur, noise, chroma delay and ringing. Each generation reduces effective luma by about 10–20% **[E]**. |

### 2.3 Camcorder sensor look

- **Sensors:**
  - 1992 Hi8 consumer models: Canon UC25Hi used a 1/3" 410,000-pixel CCD; Canon UC30Hi used 470,000 total / 440,000 effective pixels; a Canon 1/4" model had ~470k/440k. Source: https://global.canon/en/c-museum/product/8mmvc357.html , https://global.canon/en/c-museum/product/8mmvc358.html
  - Prosumer 3-CCD Sony CCD-VX3: 410,000 pixels, >530 lines. Source: https://wiki.riteme.site/wiki/VX3
  - Cheaper and late-90s 1/4" units are commonly ~270k–320k (NTSC) **[E]**, not verified.
- **Vertical smear:** interline-transfer CCDs leak light into the shielded vertical register, so a bright point becomes a **vertical bright column** through the whole frame height. It is worse at fast shutter speeds and with near-IR light. CMOS sensors don't do this. Source: https://flir.com/support-center/iis/machine-vision/knowledge-base/vertical-bleeding-or-smearing-from-a-saturated-portion-of-an-image , https://www.adimec.com/ccd-versus-cmos-blooming-and-smear-performance/
  - Model: for each column, add k x (sum of the column's clipped excess) along the whole column, with k ~0.01–0.05 **[E]**.
- **AGC in low light:** gain rises (the GB Camera's comparable sensor goes from 14 to 57.5 dB, for scale). Noise rises with it, chroma noise grows, and the image goes slightly green/magenta. Use up to +18 dB, i.e. 8x gain **[E]**.
- **Auto white balance drift:** slow drift (time constant 1–3 s **[E]**) toward the scene average. Tungsten scenes stay orange.

### 2.4 Viewfinder / OSD overlay

The RCA PRO807 manual (1992) lists these indicators (https://manualsdump.com/en/manuals/rca-pro807/203621/26):
- **Status:** `REC`; `PAUSE` (flashes); `FF`, `REW`, `STOP`, `PLAY`.
- **Tape speed:** `LP`.
- **Warnings:** battery warning; `DEW`; `TAPE` (about 5 min left); `TAPE END`.
- **Program modes:** Sports, High-Speed Shutter, Close-up, Low-Light.
- **Focus:** `MF`.
- **Cassette:** cassette icon.
- **Counter:** with memory, shown as **`M 1:00:00`**.
- **Time and date:** **`AM 10:30:15`** and **`1.1.1992`**.

Status indicators are *not* recorded to tape; the date/time can be superimposed on the recording.

Other details:
- **JVC GR-DV1:** "RECORD/STANDBY" when the camera is ready; date/time is shown on TV in record/standby. https://manualslib.mx/manual/118025/Jvc-Gr-Dv1.html?page=30
- **Sony CCD-FX510:** DATE and TIME buttons superimpose the date or time in recording or standby. https://www.manualsdir.com/manuals/629655/sony-ccd-fx510.html

**Typical layout [E]** (consistent with the manuals above but not verified pixel-exact):

| Position | Contents |
|---|---|
| Top-left | `REC ●` (red dot) / `STBY` / `PAUSE` |
| Top-right | `SP` / `LP` / `EP`, battery icon, tape counter `0:12:34` |
| Middle-top, while zooming | Zoom bar `W ▮▮▮▯▯▯ T` |
| Bottom-left | Time, e.g. `12:00:00 AM` (US) |
| Bottom-right | Date, `JAN. 1 1998` (US Sony/JVC) or `1.1.1998` / `1. 1.1998` (EU) |

- **Font:** blocky character-generator glyphs, roughly 5x7 or 7x9 in a cell **[E]**, white with a black outline/shadow.
- **Style:** the date stamp is recorded into the video, so it gets the same chroma bleed and blur. Render it before the VHS chain.

---

## 3. Dithering

### 3.1 Ordered (Bayer)

Source: https://en.wikipedia.org/wiki/Ordered_dithering

```
M2 (/4):   0 2
           3 1

M4 (/16):  0  8  2 10
          12  4 14  6
           3 11  1  9
          15  7 13  5

M8 (/64):  0 32  8 40  2 34 10 42
          48 16 56 24 50 18 58 26
          12 44  4 36 14 46  6 38
          60 28 52 20 62 30 54 22
           3 35 11 43  1 33  9 41
          51 19 59 27 49 17 57 25
          15 47  7 39 13 45  5 37
          63 31 55 23 61 29 53 21
```

- **Recursion:** `M2n = (1/(2n)^2) * [[4Mn, 4Mn+2], [4Mn+3, 4Mn+1]]`.
- **Apply:** `c' = nearest(c + r*(M(x mod n, y mod n)/n² − 0.5))`. For N evenly spaced levels per channel, use `r ≈ 255/(N−1)`. Use `(M+0.5)/n² − 0.5` to centre the threshold so the average brightness is unbiased.
- **Cost:** one table lookup and one add per pixel. It is fully parallel, so it suits GPU shaders and SIMD.

**Cluster-dot halftone [E]:** this is a threshold matrix whose ranks grow from the cell centre outward. Classic 4x4 clustered-dot: `12 5 6 13 / 4 0 1 7 / 11 3 2 8 / 15 10 9 14` (/16). An 8x8 version at 45° gives a newspaper look. Alternatively, compute `cos(x)+cos(y)` on a rotated grid as a threshold. It is as cheap as Bayer.

**Blue noise:** Ulichney's void-and-cluster (SPIE 1993). A 64x64 mask takes about 2–5 s to generate offline (Rust `blue_noise` crate). Precompute it and ship it as a texture; at runtime it is the same cost as Bayer and has no visible cross-hatch. To animate it, offset the mask per frame, or use the golden-ratio offset `fract(v + frame*0.618)`. Sources: https://patents.google.com/patent/US5912745 , https://docs.rs/blue-noise

### 3.2 Error diffusion kernels

Source: https://tannerhelland.com/2012/12/28/dithering-eleven-algorithms-source-code.html. `X` is the current pixel; errors go right and down.

```
Floyd–Steinberg  /16:      X 7
                       3 5 1

False F–S        /8:       X 3
                           3 2        (right 3, down 3, down-right 2)

Jarvis–Judice–Ninke /48:      X 7 5
                          3 5 7 5 3
                          1 3 5 3 1

Stucki           /42:         X 8 4
                          2 4 8 4 2
                          1 2 4 2 1

Burkes           /32:         X 8 4
                          2 4 8 4 2

Sierra (3-row)   /32:         X 5 3
                          2 4 5 4 2
                            2 3 2

Two-row Sierra   /16:         X 4 3
                          1 2 3 2 1

Sierra Lite      /4:        X 2
                          1 1

Atkinson         /8:        X 1 1
                          1 1 1
                            1          (sum 6/8 → 75% of error)
```

**Why Atkinson looks "Mac":**
- Bill Atkinson designed it at Apple in 1983–84 for the 512x342, 72 dpi, 1-bit screen (MacPaint, HyperCard).
- It discards 25% of the quantization error, which avoids muddy midtones. The images are crisper and higher-contrast, with clean flat highlights and shadows ("washed out" extremes) and localized speckle.
- Sources: https://blog.fontlab.com/2026/03/24/dithering-ditherpunk-and-bill-atkinson/ , https://beyondloom.com/blog/dither.html , https://www.evilmadscientist.com/2012/dithering/

**Implementation notes [E]:**
- Use **serpentine** scanning (alternate direction each row, mirroring the kernel) to cut "worm" artifacts.
- Keep the error buffer in int16 with a fixed-point divisor. The /16, /32, /8 and /4 divisors become shifts. /48 and /42 need a multiply.

### 3.3 Real-time cost on a phone at 320x240 (76,800 px)

| Algorithm | Neighbour writes per pixel | Parallel? | Notes [E] |
|---|---|---|---|
| Bayer / cluster / blue-noise | 0 | Fully (GPU fragment shader) | Under 0.1 ms on the GPU; ~0.2 ms on one CPU core. |
| Sierra Lite | 3 | Rows sequential | Cheapest error diffusion; ~0.5 ms on a CPU core. |
| Floyd–Steinberg / Atkinson | 4 / 6 | Sequential; wavefront parallelism possible (row r+1 lags 2 px behind row r) | ~0.5–1.5 ms per frame on an ARM core. Real-time 30–60 fps is easily achievable on the CPU at this size. |
| JJN / Stucki / Sierra-3 | 12 / 12 / 10 | Sequential | ~2–4 ms per frame. Still fine at 320x240. |

Error diffusion is CPU-friendly at 320x240. Even 640x480 at 30 fps is fine for Floyd–Steinberg on one modern core. The real issue for video is **temporal flicker**: error diffusion patterns change completely with tiny input noise.
- Ordered or blue-noise dithering is temporally stable.
- For error-diffusion video, consider lightly denoising the input first.

### 3.4 Game Boy Camera

Source: https://gbdev.io/pandocs/Gameboy_Camera.html ; MAME `camera.cpp` https://git.redump.net/mame/plain/src/devices/bus/gameboy/camera.cpp

**Sensor and image:**
- Mitsubishi M64282FP artificial-retina CMOS, 128x128. The camera keeps the middle **128x112** (8 rows dropped at top and bottom).
- Output is 16x14 tiles of 8x8 pixels, **2 bpp, 4 shades**.

**Sensor registers:**
- **A001: gain.** 14.0–51.5 dB in steps (14.0, 15.5, 17.0, 18.5, 20.0, 21.5, 23.0, 24.5, 26.0, 29.0, 32.0, 35.0, 38.0, 41.0, 45.5, 51.5), plus 6 dB when G4 is set. Bits 5–7 select the edge mode.
- **A002–A003: exposure.** 16-bit, ~16 µs (measured 15.26 µs) per step.
  - $0030: direct sun.
  - $0800: lit room.
  - $F000: TV-lit room.
- **A004: edge enhancement and invert.** Edge ratio is one of {0.50, 0.75, 1.00, 1.25, 2.00, 3.00, 4.00, 5.00} (bits 4–6). Bit 3 inverts the image.
  - The sensor **edge-enhances in analog before quantizing**. That is part of the "GB Camera crunch". Apply a 2-D Laplacian sharpen with ratio ~1–2 before dithering.

**Dithering:**
- **A006–A035:** a 4x4 matrix stored by rows, 3 threshold bytes per cell (48 bytes).
- For a sensor value v at cell (x&3, y&3):
  - `v < r0` gives shade 3 (darkest).
  - `v < r1` gives shade 2.
  - `v < r2` gives shade 1.
  - Otherwise shade 0 (lightest).
- So the dither is a **per-cell multi-level threshold**: an ordered 4-level dither in which the cartridge ROM sets **contrast** (the spread between low and high) and **brightness** (the offset).
- **Not quite as specified in the brief:** the matrix lives in the MAC-GBD cartridge mapper's registers, which drive the comparison as the ADC reads the sensor. It is not computed inside the M64282FP itself.

**Real 48-byte matrices:** from Raphaël Boichot's M64282FP dashcam config, which reproduces Game Boy Camera behaviour. https://github.com/Raphael-Boichot/Mitsubishi-M64282FP-dashcam (`SD/config.json`). Each triplet is (r0, r1, r2), and cells are listed in row order.

```
ditherMatrix (8-bit digital, general use):
 ( 42, 94,155) ( 81,139,202) ( 51,105,166) ( 90,151,214)
 ( 68,124,186) ( 55,109,170) ( 77,135,198) ( 64,120,182)
 ( 48,101,162) ( 87,147,210) ( 45, 97,158) ( 84,143,206)
 ( 74,132,194) ( 61,116,178) ( 71,128,190) ( 58,113,174)
```
- **Rank order [D]:** the rank of r0 within the matrix is `0 12 3 15 / 8 4 11 7 / 2 14 1 13 / 10 6 9 5`. That is the **transpose of the standard Bayer M4**.
- **Spacing [D]:** each threshold band steps by ~3 per rank. The three bands are offset by about 52 and 60.
- Equivalently: `r_k(cell) ≈ base_k + 3*bayerT(cell)`, with base ≈ 42, 94, 155.

The original-camera style matrices (`lowditherMatrix` and `highditherMatrix`) operate in the analog voltage range, where values never go below 128. They show the camera's **low-contrast** spacing:
- **highditherMatrix**, first cell (137, 146, 162).
- **lowditherMatrix**, first cell (140, 152, 172).
- Thresholds are tightly packed near 135–230.

**For a phone simulation:**
- Map luma to 0–255.
- Apply contrast and brightness by scaling the three base thresholds around the midpoint. Higher contrast means a smaller spread. The GB Camera's maximum contrast collapses to near 1-bit (https://www.thephotoforum.com/threads/game-boy-camera-retrospective.423645/).
- Then use the table above.

### 3.5 Palettes (exact hex)

| Palette | Colours |
|---|---|
| 1-bit / Mac | `#000000`, `#FFFFFF` (the Mac 128K/512K was pure black on white) |
| Game Boy DMG "green" (common emulator set; not authoritative LCD measurements) | darkest→lightest `#0F380F`, `#306230`, `#8BAC0F`, `#9BBC0F` (https://theapplaunchpad.com/color-palettes/gameboy) |
| Game Boy (BGB emulator default) | `#081820`, `#346856`, `#88C070`, `#E0F8D0` (https://lospec.com/palette-list/nintendo-gameboy-bgb) |
| Game Boy greyscale (Pocket-like) | `#000000`, `#555555`, `#AAAAAA`, `#FFFFFF` **[E]** |
| GB shade convention | Shade 0 = lightest, 3 = darkest (https://rgbds.gbdev.io/docs/v1.0.3/rgbgfx.1) |

**CGA / EGA default 16 (RGBI)** (https://en.wikipedia.org/wiki/Color_Graphics_Adapter):
```
0 #000000  1 #0000AA  2 #00AA00  3 #00AAAA  4 #AA0000  5 #AA00AA  6 #AA5500  7 #AAAAAA
8 #555555  9 #5555FF 10 #55FF55 11 #55FFFF 12 #FF5555 13 #FF55FF 14 #FFFF55 15 #FFFFFF
```
- Brown (6) is #AA5500, not #AAAA00. The EGA default palette uses the same 16 colours.

**CGA 320x200 4-colour modes** (colour 0 = background, black by default):

| Palette | Colours |
|---|---|
| Mode 4, palette 0, low | `#00AA00`, `#AA0000`, `#AA5500` |
| Mode 4, palette 0, high | `#55FF55`, `#FF5555`, `#FFFF55` |
| Mode 4, palette 1, low | `#00AAAA`, `#AA00AA`, `#AAAAAA` |
| Mode 4, palette 1, high | `#55FFFF`, `#FF55FF`, `#FFFFFF` |
| Mode 5 (undocumented), low | `#00AAAA`, `#AA0000`, `#AAAAAA` |
| Mode 5 (undocumented), high | `#55FFFF`, `#FF5555`, `#FFFFFF` |

**PICO-8 16** (https://nerdyteachers.com/PICO-8/Guide/PALETTES):
```
0 #000000  1 #1D2B53  2 #7E2553  3 #008751  4 #AB5236  5 #5F574F  6 #C2C3C7  7 #FFF1E8
8 #FF004D  9 #FFA300 10 #FFFF27 11 #00E756 12 #29ADFF 13 #83769C 14 #FF77A8 15 #FFCCAA
```

**Nearest-colour matching for arbitrary palettes [E]:**
- Use squared distance in a perceptual-ish space. Weighted RGB `(2,4,3)` or `(3,4,2)` is cheap; Oklab is better.
- Precompute a 32x32x32 (RGB555) lookup table: 32 KB, one lookup per pixel.

---

## 4. Gaps / caveats
- I found no primary per-pin documentation for bent QV-10, Mavica, Kid-Tough, Game Boy Camera or Handycam units. Section 1.2's mapping is engineering inference consistent with the reported looks (boxo.cc, Reddit, Make).
- VHS jitter magnitude, ringing overshoot and OSD pixel layouts are estimates. Calibrate them against reference footage.
- The exact head-switch position (~6.5 H before vsync) is from memory of SMPTE practice and was not verified in this session.
- The DMG green hex set is community-standard; the real LCD colour varies by unit and lighting.
