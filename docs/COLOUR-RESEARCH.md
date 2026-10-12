# COLOUR bend research: what real bent cameras do to colour

Research date: 2026-10-13. This file covers real bent cameras, camcorders and video gear, the electrical reasons for their colours, and a design for the COLOUR knob.

The current knob is `Knobs.bend(colour)`, which sets `chromaLines[6..7] = HIGH` (see `core/.../Knobs.kt`). That forces every U and V byte into 192..255. Both colour-difference signals are then always strongly positive, so the whole frame turns one magenta. Section 3 shows why real bends almost never look like that, and section 5 replaces it.

---

## 1. Primary evidence: real bent still/toy cameras (images inspected)

### 1.1 boxo.cc: a €10 AliExpress toy video camera with its 24-pin CMOS ribbon brought out to patch points
Source: https://boxo.cc/blog/circuit-bending/ (I downloaded and viewed all 12 sample photos.)

The sensor is "a no-name CMOS" on a 24-pin 0.5 mm FPC. There is no datasheet, so the pin functions are unknown. The author contrasts CCD (analog) with CMOS (digital, "the sensor's communication protocol").

| Bend (author's naming) | Author's words | What I see in the photos |
|---|---|---|
| Pin 8 to 7/5/10, "posterization" | "vivid purples and warm tones are entirely the glitch's doing", "the sky is blown out" | The image structure survives. Over that sits a fine **multi-coloured chroma speckle**: 1–2 px confetti of red, green and blue in midtones and foliage. Highlights shift to **lavender/pink-white** and sky goes **pale cyan to lilac**. Shadows go **violet/indigo**. Foliage turns orange-red and green in mottled patches. This is not one tint: hue changes with brightness and the speckle is per-pixel. |
| Pin 6 to 21/24/4, "drug trip" | "sharp, discrete, dry", "much more aggressive", "the sky became quantized" | (a) Bright/blown regions become **flat solid fills**: the sky is **solid hot magenta** in two shots and **solid lime green** in another. (b) Textured midtones become dense **RGB confetti** (red, green, blue and yellow pixels together). (c) In one shot the scene breaks into **iso-brightness contour bands**: dark green, then olive, then mint/cyan, then pale yellow-white with sharp edges, like a false-colour thermal map. (d) The palette is dominated by **magenta/pink against green/lime**, with some cyan and yellow. |
| Pins 13/17, semi-permanent (persists until reboot; the author suspects gain/white-balance registers were corrupted) | "It's very neon (the light was actually warm in real life!)", "bright green glitches" | A keyboard shot has a strong **cyan/teal** cast with blue shadows and neon cyan-white highlights. A clock shot has a **blue to violet to pink** gradient. This is a global *transform* (register-level) rather than a data-line error. |
| Failure modes | "Colors go glitchy", freezes | n/a |

### 1.2 Ade (ade3.substack): Mgaolo dino kids' camera, pins 1/6/7/8/9/24 brought out to pots and switches
Sources: https://ade3.substack.com/p/how-to-make-a-glitch-camera and Make: https://makezine.com/article/craft/photography-video/go-beyond-reality-with-a-circuit-bent-glitch-cam/ ("delightfully psychedelic").
Text: "When you touch wires together the signals mix, causing glitches", "Not all wires glitch". Mis-wiring the ribbon gives "a color bar screen" (the sensor's test pattern).

I inspected the gallery images (`...5a0bb127..._2584x2481.jpeg` and `...a3611492..._4968x1283.jpeg`):
- The dominant palette is **hot pink / red / magenta**. Skin goes salmon-pink to red, roses go pink-white, and backgrounds go **saturated purple-magenta**.
- **Contour lines**: there are sharp outlines at fixed brightness levels, in yellow, green or white, around faces and petals. They look like posterisation boundaries.
- Highly textured areas (a beard, foliage) become **rainbow confetti**: per-pixel green, yellow, blue, magenta and orange.
- Leaves go **purple, blue or white**. Dark areas go **deep red or dark green**.
- One Reddit commenter on a similar camera said it "jibes really well with salmon pink" (mirror: https://reddit.birdcat.cafe/r/circuitbending, via search snippet).

### 1.3 Glitchamatic Pro (LoFi Future): a heavily bent toy camera with mic audio "injected into the data coming from the camera sensor"
Source: https://www.creativeapplications.net/news/glitchamatic-pro-audio-reactive-circuit-bent-glitch-camera (I inspected the sample frames).
- A building scene: windows become **acid yellow**, walls **lilac/pink**, and the lower half **saturated red** with **blue confetti**. There are fine **vertical 1–2 px stripes** throughout, which is the YUYV pair structure showing.
- Crane shots: one shows a **pink/lilac sky** with **olive/khaki-yellow** dark structure. A stronger one has a **flat hot-pink sky**, **red** speckle in midtones, and steel girders in **mint/teal and lime**. Horizontal tearing lines appear.
- Again the colours come in **complementary pairs**: magenta↔green and yellow↔violet/blue. Bright areas go pink and dark areas go olive/green.

### 1.4 Phillip Stearns, DCP Series (Kodak DC-series CCD cameras incl. DC280) / Glitch Textiles
Sources: https://phillipstearns.wordpress.com/projects/dcp_series/ , https://phillipstearns.wordpress.com/projects/dcp_series/kodak-dc280/ , https://cdm.link/glitch-exquisite-textiles-made-from-a-circuit-bent-point-and-shoot-camera/ , https://old.rhizome.org/editorial/7920 , https://makezine.com/article/craft/fine-art/blips-and-blankets/
- Method: "short circuiting the CCD and Analog to Digital ICs using multimeter probes", "potentiometers to vary the resistance between points on the board", and "detaching the CCD sensor ... connecting it to a breadboard". Images are "snapshots of stray electronic signals racing through the camera's circuits". No light is needed.
- I viewed `dcp_0083dcp_select_continent.jpg` and the cdm images. The frames show **vertical column streaks** (CCD column / readout structure) with **smooth gradients** rather than posterised steps: **hot pink to white** above and **deep green and navy** below. Another shows **orange vs deep blue** horizontal bands with vertical streaking. The textiles are dominated by **red + green/teal + violet** and **blue-violet + red/white**.
- The palette is **complementary pairs again**: pink/green and orange/blue. This is analog-domain (CCD/AFE) corruption, so the colour comes from Bayer demosaicing of corrupted raw data (see 3.4) and is smooth rather than banded.

### 1.5 G6 keychain camera (Soggy_Auggy_ guide, via Hackster)
https://www.hackster.io/news/start-with-photographic-circuit-bending-using-this-5-camera-607484bc6b98 (403 to fetcher; search snippet only). Resistors, pots and switches are placed "to change signals going from the digital image sensor to the camera's processor", and results "range from strange color changes to pixelated glitches".

### 1.6 Related / simulator
- "Glitchy Camera" Show HN (browser circuit-bent simulator): https://hn.nuxt.dev/item/47033954 , https://wikidocs.net/blog/@jaehong/8041/ . Its modes scramble RGB channels and imitate CCD faults. It is a simulator, so it is not evidence.
- od3zza "P1nk Sun" (bent 720p camera, pink sky): https://www.raster.art/artwork/b3ding-by-od3zza/about (search snippet; the fetch was rate-limited).

---

## 2. Video gear, camcorders, consoles

- **Bent video mixers (lushprojects, using Karl Klomp's tips)**: https://lushprojects.com/videobend. On a GSE VEP-2 the author found "a mega colour boost and a colour inversion". A GSE AVEP-2S gained "the ability to swap the colour channels around or to insert a signal on just one colour channel". Pushing too hard loses sync: "the output will blank on the monitor". Klomp's method is to connect "pins from the resistors to the right-hand side of the main chips to ground and other signals". Klomp interview: https://makezine.com/article/craft/make-interview-diy-video/
- **Analog feedback bends (UIUC ECE445 project)**: https://courses.grainger.illinois.edu/ece445/project.asp?id=12110. It lists "Rainbowing: cyclic rainbow patterns which appear on bright portions of the image", plus ringing (vertical stripes following edges), horizontal tearing and ghosting. Cause: "feeding the outputs of amplifiers back into themselves ... resonant feedback loops". Rainbowing is **brightness-gated** and **cycles through hue**.
- **Subcarrier phase = hue**: NTSC encodes hue as the phase of the chroma subcarrier relative to the burst (https://en.wikipedia.org/wiki/NTSC, "Never The Same Color", tint control; https://en.wikipedia.org/wiki/Chrominance). A 1966 service article describes the "offset colour subcarrier / sidelock / linear-phase sweep" generator, where the phase advances 0→360° across the line and produces a **rainbow** (https://www.worldradiohistory.com/Archive-PF-Reporter/60's/PF-Reporter-1966-03.pdf). A bent subcarrier crystal or burst gate therefore gives **hue rotation**: a uniform tint that **drifts and cycles**. If the offset is line-locked it becomes **horizontal rainbow sweeps**.
- **Colour killer**: if the burst is lost or mistimed, the decoder shuts the chroma amplifier and the picture goes **black and white**, often flickering in and out (https://patents.google.com/patent/US4207588 , Sencore tech tip https://xdevs.com/doc/Sencore/Sencore_Tech_Tips/TT161p%20-%20How%20To%20Troubleshoot%20TV%20Color%20Problems%20With%20Your%20VA62A%20Universal%20Video%20Analyzer.pdf). Fault report of an MX-1 outputting B&W on most TVs: https://electro-tech-online.com/threads/video-mixer.111624
- **Cross-colour / dot crawl**: fine luma detail falls into the chroma band, which gives **rainbow moiré on fine textures** (striped shirts, foliage, text). Chroma edges give crawling checkerboards (https://en.wikipedia.org/wiki/Dot_crawl).
- **Consoles**: Simon Hutchinson's Genesis bends short VRAM data pins (https://simonhutchinson.com/?p=3956). There, colour corruption comes from bad palette *indices*, so the result is wrong-but-legal palette colours. The CRAM "dots" artefact is a bus collision where the VDP displays the value being written (https://gendev.spritesmind.net/forum/viewtopic.php?p=1871). Casper Electronics' NES: https://cr4.globalspec.com/blogentry/10171/Circuit-Bent-NES-Makes-a-Fun-Video-Synthesizer (no colour detail). For a camera, the takeaway is that palette/index-domain bends give **flat posterised false colour**.
- Game Boy Camera: the M64282FP is a greyscale analog-out sensor (https://gbdev.gg8.se/wiki/articles/Gameboy_Camera), so there are no colour bends to model. I found no documented Videonics MX-1 or WJ-MX colour bends beyond the above. Tom Koch/univac material is stills only (https://blurb.co.uk/b/9677379-univac-visual-experiments-2017-2018).

---

## 3. Mechanisms: why real data-line bends make value-dependent, multi-colour results

### 3.1 The bus
Toy-camera CMOS sensors (OV7670-class) output on an 8-bit parallel DVP bus D[7:0]. In YUV422 mode the order is **Y0 U Y1 V** (with variants YVYU, UYVY and VYUY). In RGB565 mode the bytes are `RRRRRGGG` `GGGBBBBB` (https://wiki.epfl.ch/prsoc/ov7670). **The same 8 wires carry luma and both chroma bytes in turn.** A pot or patch wire on the ribbon therefore hits Y, U and V. It does not hit chroma alone unless the fault is timing-gated.

BT.601 (https://en.wikipedia.org/wiki/YCbCr): Y 16–235, Cb/Cr 16–240 centred on **128**.
`R = 1.164(Y-16) + 1.596(V-128)`; `G = 1.164(Y-16) - 0.392(U-128) - 0.813(V-128)`; `B = 1.164(Y-16) + 2.017(U-128)`.
The extremes are: Cb max = blue, Cb min = yellow, Cr max = red, Cr min = cyan.

### 3.2 Why one shorted line gives magenta OR green, never just one tint
1. **U and V receive the same bit error**, because it is the same wire. The error vector in the UV plane lies on the **ΔU = ΔV diagonal**. (+,+) is **magenta/pink** (R and B up, G down). (−,−) is **green/lime**. This is the magenta/green axis that dominates every bent YUV camera sample above (boxo, Ade, Glitchamatic).
2. **The error depends on the value.** A stuck or bridged bit only changes the bytes where that bit differed. Natural chroma clusters just around 128 = `1000 0000`:
   - **Bit 7 acts as the sign bit.** Forcing D7 high leaves positive chroma alone. It maps slightly-negative chroma (e.g. 127 = `0111 1111`) to 255, the full positive extreme. Forcing D7 low does the reverse. So greys and pastels **explode to full saturation**, while already-coloured areas may stay put.
   - **Bits 6–5** rarely toggle in natural chroma. Forcing them pushes everything to one far octant. That gives the flat neon fields seen in blown skies (solid magenta or lime).
   - **Bits 4–0** toggle constantly near 128. A stuck/bridged low bit adds or subtracts 2^k depending on the exact value, so smooth chroma gradients break into **contour bands** and textured regions into **per-pixel confetti** in several hues. U and V errors now differ in sign pixel by pixel, which brings off-diagonal hues (yellow, blue, orange, cyan) into the mix. This is the boxo "posterization" look.
3. **The same wire also hits Y**, so luma gets posterised or solarised at the same thresholds. Colour bands line up with brightness bands, which explains "contours of different colour at different brightness levels".
4. **Bridging two lines** (wired-AND/OR, or one driver overpowering the other): bit a becomes f(bit a, bit b). The error now depends on **two** bits of each byte. Bands appear at the 2^b period *inside* the 2^a period, which gives nested, rainbow-like contours.

### 3.3 Cross-byte crosstalk, timing skew and byte-phase slip: colour that follows brightness
- **Skew/crosstalk**: a line with added capacitance or resistance (a pot) settles late. When the DSP samples a chroma byte, that bit still holds the **previous byte's value, which is a Y byte**. Chroma now contains luma bits: `U.bit k ← Y.bit j`. Colour becomes a **periodic function of brightness**. Each time Y's bit j toggles (every 2^j levels) the hue flips, which gives **rainbow iso-brightness banding**: the thermal-map look of the boxo green shot and Ade's outlined contours. In simulation, a luma ramp turns into repeating grey/magenta/green/violet steps.
- **Byte-phase slip** (YUYV read as UYVY): a "green/pink" image is a classic capture bug (v4l mailing list: https://archive.linuxcoding.com/video-4-linux/2002/msg03732.html; EPFL OV7670: "red appears as green and green appears as pink"). Chroma now *is* luma, so **dark areas go green (U,V ≪ 128) and bright areas go magenta/pink (U,V ≫ 128)**. Luma becomes the near-flat chroma values, so the picture is flat, ghostly and grey-mid. A 2-px vertical stripe texture also appears. This matches the Glitchamatic pink sky over olive girders and boxo's flat magenta and lime skies.
- **Slip by 2 bytes (U↔V swap)**: hue mirrors across the diagonal. **Red↔blue, orange↔azure, skin goes blue, sky goes orange.** These are the orange/blue Stearns pairs, and the lushprojects "swap the colour channels".
- **RGB565**: green straddles the byte boundary (`RRRRRGGG|GGGBBBBB`). Byte swap or skew therefore mixes red's top bits into green's low bits and so on, which gives strong false colour and green posterisation. A real stuck green bit left "a mere 4 shades of green" (https://community.sparkfun.com/t/ov7670-fifo-camera-wrong-data/34937). Adafruit notes that the OV7670 default is "RGB565-swapped" (https://learn.adafruit.com/capturing-camera-images-with-circuitpython/working-with-image-data).

### 3.4 Bayer / raw domain (CCD and AFE bends, Stearns)
- A wrong CFA phase (RGGB read as GRBG etc.) "gives completely the wrong colour and often [a] residual 'grid' pattern" (https://answers.opencv.org/question/236504/pixel-order-for-demosaicing , https://uk.mathworks.com/help/images/ref/demosaic.html). A one-column shift makes the ISP treat green sites as R/B and vice versa: **foliage goes magenta, magenta goes green**. A one-row shift swaps R↔B (orange↔blue).
- If the raw value of *alternate pixels* is disturbed (clock or ADC crosstalk that differs between even and odd samples), the demosaicer turns it into a **green/magenta zipper**: alternating columns or checkerboard at edges, with a global green or magenta cast depending on the offset's sign. Analog corruption is smooth, so the result is **smooth gradients plus column streaks**, not hard posterisation (Stearns).

### 3.5 Register (SCCB) corruption
Gain, white balance, saturation, hue-matrix or negative-image registers get mis-written (boxo pins 13/17: neon, cyan, green, persists until reboot). This gives a **global** colour transform: a strong cast, oversaturation, colour-matrix rotation or inversion. It is the one case where a near-uniform tint is realistic. Even then it comes with crushed contrast and clipped highlights. The app already has `SccbGlitch.NEON/NEGATIVE`.

### 3.6 Analog video (camcorder/CVBS out)
Hue rotation is a uniform shift that drifts slowly (subcarrier phase). Colour killer gives B&W, flickering in and out. Cross-colour gives rainbow moiré on fine luma texture. Rainbowing gives hue cycling inside bright areas. Chroma delay/bleed gives a smear. These belong to the VHS/analog stage. The COLOUR knob is about the digital sensor bus.

---

## 4. Recurring real palettes
- **Hot pink/magenta + lime/acid green** (most common; the UV diagonal). Bright goes pink and dark goes green, or the reverse.
- **Salmon/red skin + purple/violet background + yellow/green contour outlines** (Ade).
- **Acid yellow + lilac**, **olive/khaki + pink** (Glitchamatic).
- **Orange vs deep blue**, **pink-white gradient over green/navy** (Stearns, U↔V or Bayer phase).
- **Cyan/teal cast with neon highlights** (register corruption).
- Texture: **RGB confetti** in midtones and **flat neon fields** in clipped areas, together in one frame.

---

## 5. Recommendation: COLOUR knob (0..1)

Principles:
- Operate on the **YUYV byte stream**, not RGB.
- Every operation must be **value-dependent** (bit forcing/bridging/leaking), never "add a constant to U and V".
- Let some corruption reach **Y** at high settings, because the wire is shared.
- Use **probability/pot pressure** (the fraction of bytes affected) for smoothness *within* a stage, and **bit significance** (low to high) for the stages.
- Hold random choices (which bit pair, sign) per session or slowly per second so the look is stable, with a little per-frame flicker in probability like a pot contact.

Order of operations per chroma byte (U or V at odd byte positions; `prevY` = the byte before it on the bus):

1. **Chroma overdrive with wraparound** (an ISP saturation register driven too hard, or an ADC overflow): `c = ((c-128)*g + 128) mod 256`, with g = 1 → 4 over k = 0 → 0.4. Grey stays grey. Saturated areas first get punchy, then **wrap to the opposite hue at their edges**, which gives contour false colour only where the scene is colourful.
2. **Y→chroma bit leak (skew)**: `c.bit k ← prevY.bit j`, applied with probability p ramping 0→1 within each stage:
   - 0.05–0.25: k=3, j=2. Fine **chroma confetti** that follows texture.
   - 0.20–0.40: k=5, j=4. **Brightness-dependent rainbow contour bands**, about 8 per luma ramp.
   - 0.35–0.55: k=6, j=5. Wide bands, neon magenta/green fields.
3. **Whole-bus bridge D6–D4 (wired-OR, or AND for a green-leaning variant)** on Y *and* chroma, from 0.5 → 0.75. Apply per line or row-burst with probability, to get an intermittent contact. Luma posterises and colours lock to brightness steps. The image looks like the boxo/Ade "drug trip" photos.
4. **Byte-phase slip** (+1 byte, held for long runs: whole frame or large row blocks, re-syncing at HREF), from 0.7 → 1.0. **Dark goes green, bright goes magenta**, the picture goes flat, with 2-px stripes. Optionally a rare +2 slip (U↔V) for orange/blue frames.

Expected look (verified in a Python simulation of exactly this chain on a real garden photo; script: scratchpad `tools/sim2.py`, output `sim2.png`):
- **10%**: almost natural. Colours slightly hotter, a hint of fine colour speckle on foliage and texture, and the odd wrapped pixel on the most saturated petals. Greys stay grey.
- **30%**: saturated objects show **multi-colour contour rings** (pink rose to red/cyan/green rings). Texture (grass, hair) becomes **RGB/violet confetti**. Skin goes hot orange-red. The grey ramp picks up alternating grey/violet/magenta steps. This is clearly bent and still readable, and matches the boxo "posterization" photos.
- **60%**: **false-colour thermal map**. Iso-brightness bands in magenta, green, yellow, cyan and red. Luma is posterised. Highlights become flat neon pink or lime, and some rows are intermittently harsher. This matches the boxo "drug trip" and Ade galleries.
- **100%**: Most rows/frames are phase-slipped, so the scene is **flat lime-green shadows against hot magenta highlights**, with vertical 2-px striping, broken horizontal row bursts and remnants of rainbow contours. This matches the Glitchamatic crane and boxo flat-sky frames.

Do NOT: OR a constant mask into U/V (the current `chroma[6..7] HIGH`: uniform magenta), tint in RGB, or hue-rotate uniformly. The last is only valid for an analog-subcarrier fault, which belongs to VHS.

Optional extras for realism: rare **colour-killer** frames (U=V=128 for a frame) at high k on analog-out; a slow **±3 bit-pair re-roll** every few seconds; and the current SCCB `NEON` as a separate sticky mode, not part of this knob.
