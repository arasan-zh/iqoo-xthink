# What the iQOO 15 actually gives a third-party app

Written after reading the Snapdragon platform spec sheet for this device.

**The spec sheet is the chipset's, not the phone's.** "Triple ISP", "320 MP
single camera", "108 MP" are Qualcomm's ceilings — what the ISP could drive if
an OEM wired it up. They are not iQOO 15 sensors, and none of them is a number
we can act on. Treat any spec sheet as a lead, never as a fact: the only
trustworthy source is what `CameraCharacteristics` reports on the device, which
is what the capability probe in v0.2-anchor exists to print.

Decided 2026-09-12. `minSdk` raised 26 -> 31 the same day so most of the version
guards below disappear; the demo device is far past 31 and distribution is a
GitHub Release, not Play.

---

## Reachable, and we are using it

| Capability | API | Lands in | Notes |
|---|---|---|---|
| Autofocus state | `CONTROL_AF_STATE` via Camera2 interop | v0.2-anchor | `FOCUSED_LOCKED` -> `GuidanceEngine.reportFocusLocked(true)`. Replaces the placeholder |
| Lighting | `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME` | v0.2-anchor | Drives the real Lighting status chip |
| Blur-aware stability | exposure time x angular rate | v0.2-anchor | Pure-Kotlin change in `:guidance`; predicts actual blur, not just hand shake |
| Lens selection | `CameraControl.setZoomRatio()` on the logical camera | v0.6-scout | HAL picks the physical lens. Do **not** use `getPhysicalCameraIds()` — vivo may gate direct selection |
| Haptics | `VibrationEffect` composition primitives | v0.4-lock | `totalError` -> haptic frequency. Check `areAllPrimitivesSupported()` first |
| Thermal | `PowerManager.getThermalHeadroom()`, `addThermalStatusListener` | v0.5-cool | The actual governor mechanism |
| Display | `Display.getSupportedModes()` | v0.3-frame | HUD at native refresh |
| GPU compute | LiteRT / MediaPipe GPU delegate | v0.7-oracle | Adreno, Vulkan 1.3 |
| GPU shaders | `RuntimeShader` (AGSL) | v0.3-frame | Still needs an API 33 guard |

## The three cameras

Confirmed from the phone's published spec, 2026-09-12. The probe still reads
these back at runtime — `SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_*` — because
Camera2 reports *physical* focal length in mm, not the 35mm-equivalent figures
a spec sheet quotes.

| | Ultrawide | Main | Periscope tele |
|---|---|---|---|
| Equivalent focal | 15mm (~0.6x) | 24mm (1x) | 85mm (~3.5x) |
| Aperture | f/2.1 | f/1.9 | f/2.6 |
| Pixel pitch | 0.64 um | 1.0 um | 0.8 um |
| Sensor | 1/2.76" | 1/1.56" | 1/1.95" |
| OIS | no | yes | yes |
| Autofocus | AF | PDAF | PDAF |
| Resolution | 50 MP | 50 MP | 50 MP |

The spec sheet says "3x optical zoom" while quoting 85mm against a 24mm main,
which works out at 3.5x. Minor inconsistencies like this are why the probe
reads `CONTROL_ZOOM_RATIO_RANGE` rather than trusting the number.

### What this forces in the guidance math

- **Blur scales with focal length.** Blur as a fraction of frame is roughly
  `angular_rate x exposure / FOV`, and FOV runs ~100 / ~74 / ~24 degrees. The
  same hand shake blurs the periscope about 4x worse than the ultrawide, and
  the ultrawide has no OIS to help. Blur-aware stability is wrong on two lenses
  out of three unless focal length feeds it. Derive FOV on device from
  `SENSOR_INFO_PHYSICAL_SIZE` and focal length; do not hardcode.
- **85mm is the portrait focal length**, 15mm is the one that stretches faces
  near the frame edge. A HEADSHOT framed on the ultrawide is the wrong lens
  regardless of how good the framing is.
- **The tele is the worst low-light lens** — f/2.6 and 0.8 um pixels against
  f/1.9 and 1.0 um on the main. Any "switch to 3x" advice must be suppressed
  when the ISO reading says it is dim, or we trade distortion for noise.
- **Periscope minimum focus distance is unknown and matters.** Long-throw
  periscopes often will not focus closer than ~40 cm, so STEP_CLOSER at 3x can
  hit a wall the coach cannot see. `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, in
  diopters, settles it. Probe must read it.

## Reachable only through a delegate — build it, but verify on device

**Hexagon NPU** (Tensor Accelerator, HVX). No direct API. The route is LiteRT or
MediaPipe with the Qualcomm QNN delegate and its `.so` libraries.

Build it as a delegate ladder — **QNN -> GPU -> CPU** — that degrades silently.
Whether QNN actually engages cannot be confirmed without logcat on the real
phone, so the app must never *claim* NPU acceleration it did not get. Log which
delegate won and read it back off the device.

Note `NNAPI` is deprecated from Android 15; do not reach for it.

## Not reachable. Do not spend hackathon time here

| Capability | Why not |
|---|---|
| Spectra AI-ISP internals — MFNR, MCTF, real-time semantic segmentation | Baked into vivo's camera HAL. No third-party surface |
| CameraX Extensions (HDR/NIGHT/BOKEH) as a guidance input | Extensions generally cannot bind alongside `ImageAnalysis`, and `ImageAnalysis` *is* the guidance loop. Possible for the shutter path only, at capture time |
| Sensing Hub "dual always-sensing cameras" | OEM-only. No public API |
| EVA 5.0, Truepic | Not exposed to apps |
| vivo vendor Camera2 tags | Undocumented, unversioned, device-specific. Reverse-engineering a HAL is the wrong use of a 27-hour clock |

If one of these comes up again mid-build, the answer is in this table. It has
not changed.

## The rule

Anything in the first table, we implement and test. Anything in the second, we
implement with a fallback and measure before claiming. Anything in the third, we
do not start. A feature that silently no-ops on stage is worse than a feature we
never promised.
