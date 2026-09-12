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
| Autofocus state | `CONTROL_AF_STATE` via Camera2 interop | v0.2-anchor | `FOCUSED_LOCKED` -> `reportFocusLocked(true)`. **Back camera only** — the front is fixed-focus |
| Lighting | `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME` | v0.2-anchor | Drives the real Lighting status chip |
| Blur-aware stability | exposure time x angular rate x zoom | v0.2-anchor | Pure-Kotlin change in `:guidance`. Keys off `CONTROL_ZOOM_RATIO`, not lens choice |
| ~~Lens selection~~ | — | **cancelled** | Measured: only one rear camera is reachable. See below |
| Haptics | `VibrationEffect` composition primitives | v0.4-lock | `totalError` -> haptic frequency. Check `areAllPrimitivesSupported()` first |
| Thermal | `PowerManager.getThermalHeadroom()`, `addThermalStatusListener` | v0.5-cool | The actual governor mechanism |
| Display | `Display.getSupportedModes()` | v0.3-frame | HUD at native refresh |
| GPU compute | LiteRT / MediaPipe GPU delegate | v0.7-oracle | Adreno, Vulkan 1.3 |
| GPU shaders | `RuntimeShader` (AGSL) | v0.3-frame | Still needs an API 33 guard |

## The cameras — measured, 2026-09-12

Probe output: `docs/evidence/capabilities-2026-09-12.txt`. Device reports as
vivo **I2501**, SoC **QTI SM8850**, Android 16 (API 36).

**The phone has three rear cameras. An app can reach one of them.**

`getCameraIdList()` returns exactly two ids — `0` (back) and `1` (front).
Camera 0 does **not** advertise `LOGICAL_MULTI_CAMERA`, and its
`CONTROL_ZOOM_RATIO_RANGE` is `1.00x .. 10.00x` with
`SCALER_AVAILABLE_MAX_DIGITAL_ZOOM` also `10.00x`. A reachable ultrawide would
put the lower bound below 1.0; optical tele reach would show up as zoom range
beyond the digital-zoom figure. Neither does. The 15mm ultrawide and the 85mm
periscope are reserved for vivo's own camera app, and the 10x is a pure digital
crop of the main sensor.

| | Camera 0 (back) | Camera 1 (front) |
|---|---|---|
| Sensor | 8.19 x 6.14 mm = 1/1.56" | 4.57 x 3.43 mm = 1/2.80" |
| Focal | 5.56 mm = **23.5 mm equiv** | 2.80 mm = **21.2 mm equiv** |
| FOV | 72.7 x 57.8 deg | 78.4 x 62.9 deg |
| Aperture | f/1.9 | f/2.2 |
| Output array | 4096 x 3072 (12.6 MP, binned from 50) | 3264 x 2448 (8.0 MP) |
| OIS | **yes** | no |
| Autofocus | OFF, AUTO, MACRO, CONT_VIDEO, CONT_PICTURE, EDOF | **OFF only — FIXED FOCUS** |
| Min focus | 10 diopters = **10 cm** | fixed / infinity |
| ISO | **72 .. 800** | 50 .. 1600 |
| Exposure | 1/10577 s .. 18.07 s | 1/19037 s .. 0.42 s |
| Zoom | 1.00x .. 10.00x, digital only | 1.00x .. 10.00x, digital only |
| YUV 480x360 | yes | yes |
| HW face detect | OFF, SIMPLE | OFF, SIMPLE |
| Hardware level | LEVEL_3 | LEVEL_3 |

The back sensor's derived 1/1.56" and 23.5 mm match the published main-camera
spec, which is how we know the probe's arithmetic is sound — and therefore that
the missing lenses really are missing rather than mis-read.

### What this forces

- **No lens-switch feature.** `SWITCH_LENS` cannot be built. Nothing an app can
  call will select the ultrawide or the periscope.
- **Never emit TAP_FOCUS on the front camera.** `CONTROL_AF_AVAILABLE_MODES` is
  `[OFF]` and focus is fixed, so a tap-to-focus prompt asks for something the
  hardware cannot do. Front camera is exactly the `mirrored = true` case.
- **Blur scaling keys off zoom ratio, not lens choice.** Only one rear focal
  length exists, but 10x digital zoom narrows effective hFOV from 72.7 deg to
  about 8.4 deg, so the same hand shake is roughly 9x more visible zoomed in.
  The blur-aware stability work survives intact; its input is
  `CONTROL_ZOOM_RATIO` instead of focal length.
- **Lighting thresholds scale to ISO 72..800**, not a generic 100..6400. The
  back camera's ISO ceiling is low and it reaches for long exposures instead —
  up to 18 s — which makes exposure time, not ISO, the better low-light signal.
- **Front camera is the blur-prone one**: no OIS, fixed focus, f/2.2.

### Everything else the probe confirmed

- `TYPE_GAME_ROTATION_VECTOR` present, QTI, **200 Hz**, 0.515 mA. The forbidden
  `TYPE_ROTATION_VECTOR` is vivo's own at only 100 Hz, so CLAUDE.md's mandate
  buys double the rate as well as consistency.
- **All 8 haptic composition primitives supported**, with amplitude control.
  v0.4-lock has everything it needs. (All report 20 ms, which is uniform enough
  to look like a vendor default — measure real durations before relying on them.)
- `getThermalHeadroom()` returns real values (0.398 at idle). v0.5-cool works.
- Display does **144 Hz** at both 1080x2376 and 1440x3168; it was sitting at
  120 Hz when probed.
- No `DEPTH_OUTPUT`, no `ULTRA_HIGH_RESOLUTION_SENSOR` (so no 50 MP mode for
  apps), but `RAW`, `MANUAL_SENSOR`, `DYNAMIC_RANGE_TEN_BIT` and
  `STREAM_USE_CASE` are all available.

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
