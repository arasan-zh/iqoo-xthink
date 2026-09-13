# xThink — iQOO Hackathon 2026

Real-time camera positioning coach. Tells the photographer how to *move* before
the shot. Android, Kotlin, Compose, CameraX.

## Environment
Editing in VS Code, not Android Studio. Verify every change with
`./gradlew test` and `./gradlew installDebug` from the terminal. Never emit
instructions that require opening Android Studio.

## Hard architecture rules — never violate
- `:guidance` is PURE KOTLIN. Zero Android imports. All composition and
  guidance math lives here. Fully unit tested.
- `:app` is UI + CameraX + sensors only. A thin adapter feeding `:guidance`.
- Never break `main`. Every commit compiles and passes `./gradlew test`.
- Small commits, one feature each.

## Non-negotiable behaviours
- Emit exactly ONE instruction at a time. Never two arrows.
- Prefer TRANSLATION over ROTATION. If vertical framing is off and pitch is
  near target, say "move phone up/down" — not "tilt".
- Sensor: TYPE_GAME_ROTATION_VECTOR only. Never TYPE_ROTATION_VECTOR.
- ML Kit face detection: PERFORMANCE_MODE_FAST, landmarks ON, classification OFF.
- ImageAnalysis: STRATEGY_KEEP_ONLY_LATEST, 480x360, throttled.
- The on-device LLM runs because the user asked: on a tap, or inside a
  session the user started (WATCH). Inside a session it may run on a clock,
  but rationed - no sooner than 15 s after the last ask, and only when
  something changed - and the session ends itself (5 min). Never per frame.
- Whenever a model is loading or working in the background, show it: the
  animated chip in the top row (camera) or the header (Steve). No silent
  waits.
- Front camera mirrors the preview — left/right instructions must invert.
  There is a unit test for this. Keep it passing.

## Smoothing / anti-jitter constants
- EMA alpha: 0.15 on angles, 0.25 on boxes
- Deadzones: roll 2.5deg, pitch 6deg, size ratio 12%, x/y 6% of frame
- Hysteresis: exit a deadzone at 1.6x the entry threshold
- Instruction lockout: hold a shown instruction min 600ms before switching
- LOCK: all errors inside deadzone continuously for 400ms

## Priority ladder
roll -> pitch -> distance -> x/y framing -> tap-focus -> LOCKED

## UI
Reference mockups: docs/ui/overlay-reference.png, docs/ui/flow-reference.png.
Must look like a stock camera app. Green accent #4ADE80 on dark translucent
chips, 20dp corner radius, Color.Black.copy(alpha=0.55f) chip backgrounds.

BUILD: guidance card, status strip (Focus/Lighting/Stability/Composition),
corner-bracket reticle, zoom slider, mode tabs, shutter, gallery, flip button.

FAKE as static non-interactive icons: top icon row, left Macro/Wide/Portrait/
Night rail, right Scene/Lens/Settings rail.

The guidance overlay is a SEPARATE composable layer, restyleable without
touching camera code.

## Naming
The app is xThink. The mockups say "Nilai" — that is the old name. Never use it.

## Commits
Commits authored from a phone must end with the trailer line: `Device: mobile`

## Build ladder
v0.1-plumb engine | v0.2-anchor camera+sensors | v0.3-frame real overlay |
v0.4-lock haptic lock game | v0.5-cool thermal governor | v0.6-scout objects |
v0.7-oracle LLM coach | v1.0 polish. Tag each. Code freeze at T+27h.
