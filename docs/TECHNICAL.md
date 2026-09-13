# xThink — technical summary

Real-time camera positioning coach for the iQOO 15. It tells the photographer
how to *move* before the shot — one instruction at a time — then feels the
frame come together through the haptic motor, and takes the picture itself
when the composition locks.

Written 2026-09-12, at the end of build-ladder stage v0.6-scout. Everything
here is on `main`; stages v0.1–v0.5 are tagged.

---

## 1. Architecture

Two Gradle modules, one hard rule between them.

| Module | What it is | Dependencies |
|---|---|---|
| `:guidance` | **Pure Kotlin.** Every decision the coach makes: composition math, the priority ladder, smoothing, hysteresis, the haptic rhythm, the capture policy, the thermal tiers. Zero Android imports. | `kotlin-stdlib` — nothing else, verified on the runtime classpath |
| `:app` | Camera, sensors, ML Kit, the overlay, the motor, MediaStore. A thin adapter that feeds `:guidance` numbers and draws what comes back. | CameraX 1.5, ML Kit face + object (bundled), Compose |

**Why the split is strict.** Everything that is easy to get wrong — the trig
that turns a rotation matrix into roll and pitch, the coordinate mapping from
a rotated detector frame to the visible crop, every deadzone and hold — lives
where it can be unit tested without a phone. `:app` is left with system calls.
216 tests, all in `:guidance`, run with `./gradlew test`.

**The overlay is a separate layer.** `CameraScreen` builds one `OverlayState`
per frame; `GuidanceOverlay` draws it. Neither knows about the other, so the
look can be rebuilt without touching camera code, which is what happened
between v0.2 (a debug readout) and v0.3 (the stock-camera overlay).

### Data flow, one frame

```
TYPE_GAME_ROTATION_VECTOR (100 Hz, QTI)
    └─ getRotationMatrixFromVector → AttitudeMath.fromRotationMatrix → Attitude(roll, pitch)
                                                                            │ held, not acted on
CameraX ImageAnalysis (480×360 YUV, KEEP_ONLY_LATEST, throttled ≥33 ms)     │
    └─ ML Kit face (FAST, landmarks) or object (stream, single) — mode picks │
         └─ FrameMapping: rotated detector space → visible crop → SubjectBox, EyeLine
              └─ ShotTypeSelector (500 ms hold) → CompositionProfile
                   └─ GuidanceEngine.update(attitude, subject, eyes, dt) → Instruction
                        ├─ LockHaptics → HapticDriver (motor)
                        ├─ AutoCapturePolicy → ImageCapture → DCIM/xThink
                        └─ AlignmentState → Reticle (horizon, ladder, target brackets)
Camera2 interop (per capture result): CONTROL_AF_STATE, ISO, exposure, CONTROL_ZOOM_RATIO
PowerManager (1 Hz): getThermalHeadroom → ThermalGovernor → analysis rate, haptics, auto-capture
```

**The analyser drives the engine, not the sensor.** The box EMA smooths at
α = 0.25 per update; ticking the engine at the sensor's 111 Hz while handing
it the same face box repeatedly would drive the filter onto the raw value and
throw away the anti-jitter. One engine tick per new measurement.

**Preview and analysis share a ViewPort**, so "6% of frame" means six percent
of what the photographer can see, not of a 4:3 buffer partly off-screen.

---

## 2. The engine

### Priority ladder

```
roll → pitch → distance → x/y framing → tap-focus → LOCKED
```

Exactly one instruction per frame, never two arrows. Highest unsatisfied rung
wins.

### Smoothing and anti-jitter (from CLAUDE.md, transcribed to `GuidanceConstants`)

| Constant | Value |
|---|---|
| EMA α, angles / boxes | 0.15 / 0.25 |
| Deadzones: roll / pitch / size / x-y | 2.5° / 6° / 12% relative / 6% of frame |
| Hysteresis exit | 1.6× the entry threshold |
| Instruction lockout | 600 ms minimum on screen |
| Lock dwell | 400 ms with every rung inside its deadzone |

Every continuous signal is gated with hysteresis. Every *discrete* decision —
shot type, subject presence, translate-vs-rotate, thermal tier — has a hold,
because the three bugs found on the phone that mattered most were all a
discrete decision without one.

### Rules that cut across the ladder

- **Translation beats rotation.** Pitch out *and* framing out the same way →
  `MOVE_UP`/`MOVE_DOWN`, since moving the phone fixes both and keeps
  perspective. `TILT` only for a genuine rotation error.
- **…until the photographer can't.** If a vertical move goes unheeded for
  3 s with no improvement, the engine concludes the camera cannot go there
  (an arm has a reach) and switches to tilting for the session, relaxing the
  pitch tolerance 6° → 15° so the tilt is not undone. An overhead angle is a
  valid portrait.
- **Distance is the photographer's choice.** Profiles carry an accepted size
  band; inside it distance is not an error at all. Advice only past the
  extremes, measured from the band edge.
- **Zoom is a fallback, never the first answer.** `STEP_BACK` while zoomed →
  `ZOOM_OUT` (undoing a crop is free). `STEP_CLOSER` stalled 3 s → `ZOOM_IN`,
  capped at 3× — a stalled instruction means a wall.
- **Gaze lead room.** Head yaw (ML Kit has no eye-gaze) shifts the target to
  the left or right third past a 0.15 deadzone. Skipped for OBJECT.
- **Subject loss has a 300 ms grace.** The last smoothed box is held, so a
  detector blink neither flashes `SEEKING` nor breaks a lock. Measured: blinks
  under 200 ms, real losses 430–499 ms.
- **The lockout applies to every verb, `LOCKED` included** — except crossing
  into or out of `SEEKING`, which is the situation changing, not two
  instructions competing.

### Composition profiles (`assets/composition_profiles.json`, parsed in `:guidance`)

| Profile | Face height band | Eyes | Headroom | Width max | Pitch |
|---|---|---|---|---|---|
| `FULL_BODY` | 0.06 – 0.16 | 0.28 | 0.10 | — | ±6° |
| `HALF_BODY` | 0.16 – 0.38 | 0.33 | 0.08 | — | ±6° |
| `GROUP` | 0.08 – 0.70 | 0.35 | 0.08 | 0.85 | ±6° |
| `OBJECT` | 0.25 – 0.75 | 0.50 (centre) | 0.08 | 0.85 | **free** |
| `LANDSCAPE` | no subject | — | — | — | ±6° |
| `HEADSHOT` | 0.45 point | 0.33 | 0.06 | — | ±6° |

`HEADSHOT` is deliberately off the portrait ladder: a face filling the frame
is not a portrait. `OBJECT`'s pitch is free because a thing on a desk is shot
by aiming down — level is impossible without lying on the table.

### Modes

| Mode | Detector | Subject | Profile |
|---|---|---|---|
| Portrait | face | the largest face, however many are in frame | `FULL_BODY` / `HALF_BODY` by size |
| Wide | face | every face as one union box; a room when there are none | `GROUP` / `LANDSCAPE` |
| Object | object | the most prominent object | `OBJECT` / `LANDSCAPE` |

One detector runs per frame; the mode picks. A mode tap takes effect at
once — a tap is not detector noise.

---

## 3. Feel, capture, heat

**Haptic lock game** (`LockHaptics` → `HapticDriver`). `totalError` drives the
motor like a Geiger counter: silent above 0.6, pulses from 900 ms apart down
to 140 ms as the error falls, one rise-into-click thunk on lock, silence while
it holds, one faint pulse on losing it. Uses composition primitives with
amplitude scaling (all eight confirmed on the phone).

**Auto-capture** (`AutoCapturePolicy`). Once per lock acquisition, only when
steady, 3 s cooldown, subject required — a landscape locks on a level phone
alone and once auto-shot an empty room. JPEG to `DCIM/xThink` via MediaStore;
flash, click, thumbnail in the gallery button. Manual shutter, pinch and
slider zoom (1×–10×, the range this phone exposes).

**Thermal governor** (`ThermalGovernor`). `getThermalHeadroom` polled at 1 Hz
(the platform rate-limits it). Tiers at 0.60 / 0.80 / 0.95 with a 2 s hold and
0.05 hysteresis on the way down. COOL → WARM → HOT → CRITICAL: detector 30 →
15 → 7 → 4 Hz; haptics off from HOT; auto-capture off at CRITICAL; preview
untouched. Measured on the phone: headroom 0.43 → 0.60 over four minutes of
continuous use, `COOL -> WARM` stepped on cue.

---

## 4. The device, measured

`docs/HARDWARE.md` has the full reachability analysis; `docs/evidence/` has
the probe output. Facts that shaped the code:

- **One rear camera reachable.** The phone has three; `getCameraIdList()`
  returns two (back, front), no `LOGICAL_MULTI_CAMERA`, zoom range 1.0–10.0×
  digital. The ultrawide and periscope are vivo's alone. A lens-switch
  feature was cancelled, not deferred.
- **Front camera is fixed-focus** (`AF modes: [OFF]`). The engine has a
  `hasAutofocus` flag so `TAP_FOCUS` is never asked of it.
- **`TYPE_GAME_ROTATION_VECTOR` at 200 Hz** from QTI — twice the rate of the
  vivo `TYPE_ROTATION_VECTOR` that CLAUDE.md forbids. Sampled at 100 Hz.
- **ISO ceiling 800**; the sensor reaches for exposure time instead, so the
  Lighting chip reads ISO as a fraction of the sensor's own range.
- **Zoom does not reset on bind** — the HAL leaves `CONTROL_ZOOM_RATIO` where
  it was. The app requests 1.0 explicitly.
- **All eight haptic primitives, amplitude control, real thermal headroom,
  144 Hz display.**
- **Min face size 0.06** of image width, lowered from 0.10 so a full-body
  subject (~22 px in the 360-wide analysis image) is detectable at all. This
  is why the subject-loss grace exists.

---

## 5. Build, test, ship

- `./gradlew test` — 216 tests, all `:guidance`, seconds.
- `./gradlew installDebug` — arm64-v8a only (the bundled models are ~9 MB per
  ABI; every phone since 2017 is arm64). APK ≈ 62 MB with both models bundled,
  chosen so detection works on first launch offline at a venue.
- `minSdk 31` — the demo device is on Android 16; raising it removed every
  version guard around haptics, thermal and zoom.
- `scripts/dev.sh log` — the 1 Hz heartbeat (`roll pitch faces profile det
  err lock therm -> instruction`) plus every verb transition. Most bugs in
  this project were found by reading it.
- `scripts/dev.sh caps` — the capability probe, into `docs/evidence/`.
- CI on `main` publishes each build as a GitHub Release (`build-N`) signed
  with the checked-in debug key, so builds update-install over each other.

### Build ladder

| Tag | Stage | Landed |
|---|---|---|
| `v0.1-plumb` | engine | pure-Kotlin engine, 72 tests |
| `v0.2-anchor` | camera + sensors | CameraX, IMU, ML Kit faces, full ladder on device |
| `v0.3-frame` | overlay | stock-camera overlay, portrait bands, HUD |
| `v0.4-lock` | haptics + capture | lock game, auto-capture, working icons, Wide mode |
| `v0.5-cool` | thermal | governor, Q icon |
| *(local)* | v0.6-scout | Object mode, pitch-free still lifes |
| — | v0.7-oracle | on-device LLM coach, tap-only — not started |

---

## 6. Things found only by running it

Every one of these was invisible in tests and obvious in the log or a
screenshot. They are the reason the log exists.

1. The reticle was drawn against full-screen coordinates and collided with
   the guidance card. Fixed by measuring the open viewfinder area.
2. The face count flapped `0-2-1-2-0-1-4-3-0` in ten seconds and reset the
   lock dwell every time. Fixed with a 500 ms hold on shot type.
3. "Step closer a lot" stayed on screen after the subject had walked off,
   held by the lockout. Fixed with `SEEKING`, which preempts the lockout.
4. 27% of all instruction changes were detector blinks into `SEEKING`, one of
   which destroyed a lock. Fixed with the 300 ms grace.
5. The app asked the photographer to raise the phone above their head,
   forever. Fixed by detecting the stall and switching to tilt.
6. Portrait mode drove faces to fill the screen (`HEADSHOT` at 0.45). Fixed
   by making a portrait a range of scales.
7. Object mode never locked: `Tilt up` ×22 on a desk. Fixed by freeing pitch.
8. The subject frame ballooned past the screen on a close face. Capped.
9. A width rule's first draft silently cancelled every "step closer" (a zero
   beats a negative). Ten existing tests caught it before it shipped.
10. Auto-capture shot an empty room on a landscape lock. Subject now required.

---

## 7. After the demo: v0.7-oracle (build-22 → freeze)

Everything below was added between the demo build and the freeze, each
piece device-verified on the iQOO 15 before it was committed.

### Camera
- **Front camera.** A flip button rebinds `DEFAULT_FRONT_CAMERA` and builds
  a fresh `GuidanceEngine(mirrored = true, hasAutofocus = false)` - both are
  constructor facts, and `hasAutofocus` is read from the characteristics,
  not assumed. Faces and gaze are mirrored into preview space
  (`FrameMapping.mirrorX`), pitch flips sign for the +Z lens
  (`AttitudeMath.fromRotationMatrix(r, frontFacing)`), the reticle horizon
  flips with the mirror. The selfie lens offers PORTRAIT only.
- **Direction haptics.** `DirectionCues.forTransition` maps a verb change to
  a signature: left = two low ticks, right = one click, up rises, down
  falls, level spins, closer swells, back thuds. The phone has **two
  vibrators** (`VibratorManager.vibratorIds = 0, 1`); LEFT and RIGHT are
  routed to one motor each with `CombinedVibration`.
- **Scene** (was Wide): people, places, rooms - everyone framed when faces
  exist, level/horizon guidance otherwise.
- **Tap to focus** drives CameraX metering and re-targets the coach: OBJECT
  frames the object under the finger (smallest containing box, else
  nearest); PORTRAIT keeps the same person between frames (nearest to last
  frame's face) and a tap picks the face under it for 1.5 s.
- **Zoom bar**: a drag on a log scale 1x..10x, tappable stops, a readout in
  ratio and 35 mm-equivalent (23.5 mm rear, 21.2 mm front, from
  `docs/HARDWARE.md`). **Guide** toggle (off by default): the reticle and
  arrows guide, the words are optional. The left rail shows Lighting /
  Stability / Composition; Focus was dropped (continuous AF said "Good" all
  day). Shutter sound, a 110 ms flash, a one-second splash.
- **Video**: `VideoCapture` (FHD, audio when granted) swaps in for
  `ImageCapture`; the analysis stays bound and focus follows the tracked
  subject (re-aim ≤ every 500 ms when it moves 4 %, continuous AF after
  1.5 s lost).

### After the shutter
- **Photographer's crop** (`PhotographerCrop`, pure Kotlin): ML Kit Pose +
  Face on a 1024 px decode; never cut a joint (feet + floor, else
  mid-thigh, else below the hip, else chest), headroom 0.35–1.2 face
  heights, eyes toward the upper third, a print shape - 4:5 first, then
  3:4, 2:3, 9:16 only when that keeps more of the person. The camera shoots
  a 20:9 strip; nobody frames a person that way on purpose.
- **Headroom extension** (`HeadroomExtension`): only when the strip above a
  head is plain (luminance σ ≤ 16) - reflected and softened, up to a quarter
  of the height. A busy edge is left alone.
- **Review**: AS SHOT / ENHANCED side by side, six **looks** (one colour
  matrix each: Natural, Warm, Cool, Vivid, Mono, Film), Save / Discard; the
  original is never touched. The review appears only when a person was
  recognised; otherwise the shot stands, with the camera-page look baked
  in. A soft photo (Laplacian variance) is named as such.
- **Easy shot**: auto-capture only with it on - at the lock, or "near
  enough" (error ≤ 0.40, fine verb, 700 ms dwell) **only after 30 s** of
  trying. A sharpness gate on the analysis frame refuses motion and missed
  focus. No auto-shutter in video or while a review is up.

### The on-device model
- **Gemma 3n** via MediaPipe GenAI 0.10.35 on the Adreno GPU (OpenCL).
  E2B int4 (3.1 GB): load 5–15 s once, first token 1–3.5 s, a one-line
  answer in ~1.3 s. E4B int4 (4.4 GB) was measured on the same five
  requests: identical readings, 1.6–2.4 s per reading, 14 s for a letter
  (E2B: 10 s), 12 s load, 1.8 GB resident, the phone WARM - so E2B ships
  and E4B is parked in `~/Lab/xthink/models/not-shipped`. The app loads
  the largest `.task` it finds in `/data/local/tmp/llm` or its external
  files dir, so pushing E4B is all it takes to switch. The bundles carry a **vision encoder only** - no
  audio - so the model cannot hear; speech is Google's on-device
  recogniser. `MAX_TOKENS` is the whole context: an image alone is 256.
- The model **never speaks in the camera**. Its jobs: name the crop and
  the look after a shutter ("FEET NATURAL"), honoured by the rules when the
  body offers that cut; and read a spoken request for STEVE.
- Models are not in the APK. `scripts/dev.sh model` pushes every bundle in
  `~/Lab/xthink/models` to every connected phone; `scripts/dev.sh all`
  installs, grants the camera, and launches on all of them. The HF token on
  this Mac can re-download the bundles (`google/gemma-3n-E*B-it-litert-preview`).

### STEVE - say it, see the plan, tap Run, the phone does it on the Mac
- The phone registers as a **Bluetooth HID keyboard** (`BluetoothHidDevice`,
  descriptor and key table in `HidKeymap`, pure Kotlin). USB gadget mode
  is root-only on this phone. The Mac pairs with "xThink" once; it
  reconnects by itself.
- **Speech** → Google's recogniser → **Gemma reads the sentence** in one
  line, `KIND | ARG` (`GeniusIntent`), trusted only when what it names was
  in the words (`plausible`); otherwise the word-router (`GeniusRouter`)
  decides. Routes: OPEN an app; WEBSITE (a domain, or DuckDuckGo's `\`
  redirect to the first hit - Google's `btnI` just shows results when
  typed into an address bar); YouTube plays the first hit for a title;
  TERMINAL (the model gives the one-line command); WRITE (the model writes
  the letter / story, TextEdit gets a new document, the phone types it);
  PROJECT (VS Code, a **new window**, its terminal, `claude`, a
  single-`index.html` brief); WHATSAPP (new chat to the number, message
  sent); HELP; anything else - the model plans in a six-verb language
  (`GeniusPlan`). Macros do the app plumbing (Cmd+Space, Cmd+L, Ctrl+`,
  Cmd+Shift+N); the model only fills the blank a route leaves.
- **Human in the loop, by design and by the safety layer.** Every plan is
  shown in full and runs only after a tap on Run. After a run the camera
  reads the Mac screen and the model may *propose* a next step - which
  waits for its own tap. Nothing runs unattended, nothing retries by
  itself. `CommandSafety` refuses destructive commands (rm, sudo, dd, mkfs,
  force-push, curl piped to a shell, writes to /dev, ...) and a plan with
  any refused step never runs.
- Verified on the Mac: "open the terminal and show the current directory"
  → `pwd`; "play Raavana Mavandaa Lyrical in youtube" → the exact watch
  page; "write a short love letter to Priya" and "write a one page science
  fiction story with the name of a japanese comic" → written by Gemma in
  ~10 s, typed into TextEdit.

### Things found only by running it (continued)
11. `adb shell am start --es genius "open the terminal ..."` delivers only
    `open` - the device shell strips the quotes. Every early "the model
    can't route" was the model being handed one word. Quote for the device
    shell: `adb shell "am start ... --es genius 'open the terminal ...'"`.
12. A Bluetooth keyboard types into whatever has focus. With TYPE mode open
    and the camera pointed at this Mac, one tap typed 1,086 keys of this
    very session back into its own terminal. Hence: a plan is shown before
    it runs, Run is a separate tap, and tests focus TextEdit first.
13. MediaPipe delivered the model's final callback before its busy flag
    cleared; the question asked at once from that callback was refused
    ("Steve is busy"). The flag now clears before the last word is
    delivered.
14. A raw text-only session has no chat template: the model *continued*
    the instructions instead of answering. Wrap in
    `<start_of_turn>user … <end_of_turn><start_of_turn>model`.
15. `MAX_TOKENS = 160` produced empty answers: it is the whole context, and
    an image is 256 tokens of it.
16. `blueutil --pair` reported `Unspecified Error (0x1f)` while the phone
    logged `state=2` (connected) - the HID link was up regardless. Trust
    the device's log over the tool's exit code.
17. The E2B model copies the nearest example: "open safari" as the last
    few-shot example made every request `OPEN Safari`. Narrow the question
    (one line, `KIND | ARG`) and check the answer against the words.

### The last two hours: Shots and ASK
- **Shots** (PORTRAIT): nine reference styles from the photographer's own
  sheet - close up, extreme close up, medium, wide, low angle, high angle,
  dutch angle, bird's eye, over the shoulder. Each is a composition profile
  (`targetRollDeg`, `centerTolerance`, `focusRequired` were added to the
  document format); a chosen style replaces the distance ladder and the
  coach guides into it - a dutch angle is level at 15°. Styles are optional
  in `composition_profiles.json`; a missing one borrows HALF_BODY.
- **ASK**: Ask (a spoken question, Gemma answers through the camera in text
  and voice, ~3 s), Scan (ML Kit reads, Gemma tidies, Copy), Translate
  (Gemma reads any script - ML Kit has no Tamil model - into English), Save
  (a dated Markdown entry in `Documents/xThink/xthink-notes.md`). One look
  per tap; nothing on a timer.
- **Kept cool**: the model no longer loads at launch. That load had put the
  phone at WARM/HOT, doubled the analysis interval and, with the sharpness
  gate at 0.55 of a slow-decaying reference, refused frames at a green
  lock. STEVE and ASK load it on entry; otherwise a minute in, only COOL.
  The gate is 0.35 of a fast-decaying reference. SCENE never crops to a
  person, is forgiven off-centre framing (2× deadzone) and needs no focus
  tap. The status rail is icons only.
- **FIT**: squats and push-ups counted from ML Kit Pose in stream mode by
  the knee or elbow angle (`RepCounter`, pure Kotlin: DOWN under the
  floor, UP over the ceiling, 250 ms at the bottom, no half reps); each
  rep a thud and the count spoken. Hand signs from MediaPipe's gesture
  model (seven signs, bundled 8 MB); thumbs up takes the photo. Not sign
  language - and not claimed to be.

### The camera is the app; the retouch; Steve's eyes (build-38 → 39)

- **One room**: xThink opens on the viewfinder. Chat, Voice and Steve are
  round icons in the top row (Steve's is gold, the one that reaches the
  Mac); a tune switch hides the Shot / Easy shot / Guide / Look chips,
  shown by default. The mode strip is a carousel that keeps the chosen
  mode at the centre of the screen; zoom is round pills (1x 2x 3x 10x)
  with the mm readout only between stops or under a finger; the shutter
  is a white disc that turns green on lock; photo|video is a pill by the
  gallery. Translate, Scan and Fit lost their tabs (the deep links still
  reach them). Voice and Chat share one `Conversation`, so a follow-up
  knows what was said; leaving a room stops the speech.
- **The retouch (LaMa on the phone)**: after the shutter, once the review
  is up, ML Kit's object detector lists what is in the photo; `Retouch`
  (pure Kotlin, tested) drops the person's column and anything huge or
  tiny, and writes the rest as a numbered list in words (*"1: bottom-left
  edge, 3% of the frame, looks like food"*); Gemma, looking at the photo,
  answers one fixed line - `REMOVE 1,3 | what they are` or `NONE | why` -
  which `Retouch.parse` reads; the numbers become holes, grown a little,
  and `Inpainter` fills them with big-lama (`lama_fp32.onnx`, 208 MB,
  Carve's export, ONNX Runtime on four CPU threads, fixed 512×512). The
  net never sees the whole photo: the smallest square around the holes
  with context goes through at 512 and only the filled pixels, feathered,
  come back at the photo's own resolution - a stray cup in a 12-megapixel
  frame keeps the frame's sharpness everywhere else. The desk test showed
  the one thing that matters: a hole that hugs the object leaves a ghost;
  ten pixels of margin at 512 removes it cleanly. The review shows a
  RETOUCHED chip (tap to toggle) and saves the retouch at full size. The
  optimised graph is cached on first load (37 s once, 2 s after); the
  session is warmed when the coach loads. Measured: detector 60 ms, Gemma
  2.4 s, LaMa 3.7 s on the 1024-px review copy. The model file lives next
  to the Gemma bundles in `/data/local/tmp/llm` (`scripts/dev.sh model`).
- **Steve watches the Mac**: while Steve's room is open the camera reads
  the Mac screen every two seconds with ML Kit (no model), and the room
  shows the Mac through a window - the live preview cleared out of the
  room's own layer - with the first readable lines under it. `MacWatch`
  (pure Kotlin, tested) decides what a reading means: whether the screen
  changed materially (line-set similarity under 0.6), whether the text the
  phone just typed can be read on it (most of its words, three letters or
  more), the headline. Gemma narrates - one line, timestamped, kept as a
  log in the room - only on an event: after a run (with the typed text to
  check), on the *What's on the Mac?* tap, or when the screen changed and
  twenty seconds have passed. The reading loop is ML Kit; the model is
  never in a loop of its own.

### The finish is one conversation (build-39 → 40)

- **One look, one plan**: after the shutter the detectors run first - face,
  body, everything in the frame - and everything they know goes to Gemma
  in one question with the photo: the facts in words (*"The face is 16% of
  the frame tall, its top 2% below the top edge, centred. In the frame:
  shoulders, hips, knees; out of the frame: feet. Edges: above the head
  plain, left edge busy, right edge soft, bottom edge plain."*) and the
  numbered list of what could be painted out. Gemma answers six fixed
  lines - `CROP`, `HEADROOM`, `EXTEND`, `REMOVE`, `LOOK`, `WHY` - which
  `Finishing.parse` (pure Kotlin, tested; any order, any case, or all on
  one line) reads into a plan. Two calls became one; the review opens
  after the same wait it did for the crop alone.
- **The model decides, the rules check, LaMa paints**: `CROP` goes to the
  crop ladder as the preferred cut (or `KEEP`); `HEADROOM ADD` and
  `EXTEND LEFT/RIGHT/BOTTOM` become strips painted in, but only where
  the geometry agrees - the edge's luminance spread under 40 (a plain
  strip under 16 still gets the reflected headroom on the rules alone,
  as before), the ankles fully in for the floor - and only the amount
  the headroom rule says is missing; `REMOVE` becomes holes through
  `Retouch`. A sideways `EXTEND` is read as "room to the side" and the
  geometry picks the side: the desk test showed Gemma 3n names the side
  that is already plain, so the room goes where the face is within 35%
  of the edge, when that edge is paintable. `Finishing.job` is the
  instruction set for LaMa: holes on the photo as shot, then strips. The
  model never draws a mask. A plan that keeps the frame but paints
  something out still opens the review (AS SHOT alone, then the chip).
- **Headroom from the crown**: ML Kit's face box runs eyebrows to chin,
  and the crop's headroom was measured from its top - so a full head of
  hair got clipped (the first desk selfie, 03:08). The crown is taken as
  0.45 face heights above the box; the tight top, the loose top and the
  headroom extension all count from there. One crop test moved from 4:5
  to 3:4 as a result: mid-thigh no longer fits the print shape with the
  extra room, and the legs win over the shape, as the ladder says.
- **The review opens at once, and the crop is a switch**: the shutter's
  review used to wait for the coach (3 s warm, minutes on a cold GPU
  compile) and then showed a crop as the enhanced picture - a zoom nobody
  asked for. Now the review opens on the small decode the moment it is in,
  with the shot as taken and a spinner card where ENHANCED will be
  (LOOKING while the coach plans, RETOUCHING while LaMa works, "this can
  take a while"); the plan and then the retouch arrive whenever they do,
  and a haptic tick marks the retouch landing. ENHANCED is LaMa's work at
  full frame; a CROP chip, off by default, brings the photographer's crop
  and, with it, any room painted in. AS SHOT is always the untouched
  photo. Save applies exactly the switches that are on. Measured with the
  screen locked: the same hole that takes 3.7 s awake took 27 s, so the
  wait the spinner covers is real on a pocketed phone.
- **Opting out**: a *Retouch* chip in the tune row (Portrait only, on by
  default, session-scoped like *Easy shot*). Off, LaMa never touches the
  photo - no holes, no painted strips, not even the reflected headroom -
  and the review offers the crop and the look only. Gemma is still asked
  the same question, since the crop and the look come from it. The dev
  hook takes `--ez retouch false` to start with it off.
- **Measured on the iQOO 15** (E2B, warm): plan 2.6-3.2 s after a
  1.5-1.7 s first token; the very first ask after a fresh install pays a
  one-time 40 s GPU kernel compile that the driver then caches. A hole
  on the 1024-px review copy 3.7 s; a 15% strip through its band 3.6 s.
  The model's reason line sometimes comes under a key of its own
  (`GOOD natural indoor vibe`, `Why ...`); the parser takes the last
  unlabelled line as the reason.
- **Painting room in**: the canvas grows by the strips, each first filled
  with the edge beside it reflected and softened (the preview, and the
  fallback without LaMa), then LaMa paints each strip inside a band along
  that edge - the strip plus twice its depth of real photo, the full
  length of the edge, squashed to 512 and stretched back. Holes are
  filled first in their own square window (unchanged), so a stray cup
  keeps its detail and a painted sky does not need any. The review's
  ENHANCED card shows the reflected guess at once and swaps to LaMa's
  when the retouch lands; the RETOUCHED chip toggles both the holes and
  the painted strips, and the save repeats the same job at full size.

### WATCH, and Steve in landscape (build-40 → 41)

- **Steve's room turns the phone**: the Mac's screen is wide, so the room
  is landscape - the activity asks for sensor-landscape on entering and
  portrait on leaving, and the manifest keeps it alive across the turn
  (`configChanges`), so nothing in the room or the camera is lost. The
  layout splits: the Mac and its window on the left, the conversation,
  the plan and the buttons down the right; portrait stacks them as
  before. The live preview underneath follows the display rotation on
  its own.
- **WATCH**: the last tab in the carousel. Keeping watch for up to five
  minutes: the camera records (no audio track - Android gives the
  microphone to one client, and the microphone is the transcript's),
  now and then Gemma looks at a frame and writes one or two lines on
  what would matter to someone reading later, the speech recogniser is
  kept open one phrase at a time and everything heard is written down,
  and at the end it all goes into a file of its own -
  `Documents/xThink/xthink-watch-YYYYMMDD-HHMM.md`: when and how long,
  the video's name, the environment (lighting, focus, stability, lens,
  the phone's heat), Gemma's summary of what mattered (one ask, at the
  end), then everything seen and everything heard with m:ss stamps.
  `WatchSession` (pure Kotlin, tested) rations the looks - the model is
  the expensive part: a frame is compared to the last one looked at on a
  24×18 luma grid (`FrameDiff`), and a look is booked when it changed by
  6% or more and fifteen seconds have passed, or once a minute
  regardless; a still scene answers NOTHING NEW, which is not written.
  Five minutes is at most ~20 looks. The session ends itself at five
  minutes, on the Finish pill, or on a mode change; the recording is
  started from the watch's own two-second clock once the recorder is
  bound, and retried if it is not yet. The dev hook `--es watch 1`
  starts straight into WATCH.
- **The rule this bends**: CLAUDE.md says the model runs only on a tap,
  never in a loop. WATCH is started by a tap and lasts five minutes at
  most, but inside it the model does run on a clock - rationed by change
  and time, at most one look every fifteen seconds. That is a deliberate
  exception for this mode and no other; the camera's own guidance, the
  retouch and Steve keep the rule.
- **The rule, rewritten, and the chip**: CLAUDE.md now says the model
  runs because the user asked - on a tap, or inside a session they
  started - and inside a session on a rationed clock (WATCH: no sooner
  than 15 s after the last ask, only on change, the session ending
  itself), never per frame. And no wait is silent: whenever Gemma is
  loading, warming up (the first generation's kernel compile) or
  thinking, or LaMa is loading its graph or painting, an animated chip
  says so - in the camera's top row next to the recording clock, and in
  Steve's header. The label comes from the coach's own state and busy
  flag and two flags on the inpainter, read once per analysis frame and
  on a 400 ms clock of its own, so a room or the review shows it too.

### Steve closes the loop; FIT gets its tab (build-41 → 42)

- **The camera was never in the loop**: `geniusCheck` - read the Mac
  screen after a run, let Gemma say done or propose the next step - was
  written and never called; a run went straight to DONE and a one-line
  narration. Now every run ends in a check: the Mac gets a moment (2.5 s,
  or 20 s after a CLAUDE step, since Claude Code starts slowly), the
  camera reads the screen, and Gemma answers DONE, WAIT (still working -
  look again in 12 s, up to ten times) or the next steps, which are
  proposed and never run unasked. Each verdict is a stamped line in the
  room's log. `GeniusPlan.isWait` is the new word; "WAIT 2000" is still
  the pause verb.
- **A brief for Claude Code, not the sentence**: a project request
  ("create a portfolio website") goes to Gemma first, which writes one
  paragraph a coding agent can act on - what to build, its parts, the
  stack (plain HTML/CSS/JS unless named), the look, a new folder named
  after the project, open it when done, end with a summary. The card
  shows the brief before the countdown runs it; the CLAUDE step opens VS
  Code, its terminal, `claude`, and types the brief.
- **A machine by its nickname**: "elitedesk" is a known ssh host.
  `GeniusRouter.remote` turns "connect to elitedesk and open htop" into
  `ssh -t elitedesk htop` (a tty for anything that draws a screen) and
  "ssh into the elite desk" into `ssh elitedesk`; a remote command read
  from the words beats the model's reading, and the plan prompt knows
  the machine too. The Mac's `~/.ssh/config` must know `elitedesk`.
- **FIT is a tab again**, between CREATIVE and WATCH, with its squats
  and push-ups; the deep link still works.
- **Most requests never wait for the model**: the word router runs
  first, and a route it settles on its own - an app or a site by name, a
  plain search, a key, dictation, a known machine's command, a project
  for Claude Code, help - is on the card at once, with no model call (and
  with no model on the phone at all). Gemma is asked only when the words
  are open: a Plan, a shell request without its command, a letter, a
  WhatsApp message. A project goes to Claude Code as said, inside the
  router's fixed brief (a new folder, a single self-contained page, open
  it, summarise) - the agent structures the work itself, so the Gemma
  brief from the build before is gone.
- **The Mac's own skills hold the structure**: a portfolio request no
  longer gets a brief from the phone at all. The router marks it with
  the skill (`/portfolio`, a slash command in `~/.claude/commands` on
  the Mac whose rules - one file, the sections, the repo wrapper, the
  Makefile - are the structured prompt), and the CLAUDE step types
  `/portfolio personal, arasan`: the words minus the asking, with the
  destination the skill wants first (reelzo, client, venture, personal -
  personal when the words name none). Other projects still get the
  router's brief. The skill confirms its destination in the terminal
  before writing; the camera's check after the run is what answers it.
- **Gemma reads every request, and Claude runs in Terminal** (the build
  after): the no-model fast path is gone again. Every spoken request
  goes to Gemma once, as one line - it fixes what speech misheard
  ("sofa ri", "get hub", "elite desk", "cloud code"), writes the brief
  when it is a project (one paragraph a coding agent can act on), and
  hands a portfolio to the Mac's `/portfolio` skill with the
  destination and the details as its arguments. The word router still
  stands behind it: a named machine's command read from the words beats
  the model, and an unreadable line falls back to the words; a corrected
  app the router knows, or a site, is taken from the model even when the
  name was not in the sentence as heard. A project
  or a CLAUDE step now opens the Terminal app in a new window, starts
  `claude`, and types the request - not VS Code's terminal.
- **WATCH, quieter, and into Notes**: the recogniser is opened patiently
  (thirty seconds minimum, ten seconds of silence before it gives up) and
  reopened quickly after words but only after four seconds of nothing,
  and the system's notification and system streams are muted for the
  watch and restored after - every reopening is a chime the phone
  insists on. When the file is written the report is also copied to the
  clipboard and offered through the system's share sheet - WhatsApp,
  Notes, mail, anything that takes text - over the camera. Saving into
  Notes would need a tap in there anyway (Android lets no app paste into
  or close another), so the one tap picks the app instead. Back returns
  to xThink.
