#!/usr/bin/env bash
# xThink dev helper. Run from anywhere: scripts/dev.sh <subcommand>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_ID="in.arasan.xthink"
LAUNCH="${APP_ID}/.MainActivity"

usage() {
  cat <<'USAGE'
xThink dev helper

  scripts/dev.sh t                    run unit tests
  scripts/dev.sh run                  install the debug APK and launch it
  scripts/dev.sh log                  tail logcat, xThink tag only
  scripts/dev.sh caps                 run the capability probe, save the report
  scripts/dev.sh ship <tag> <msg>     commit everything, tag, push, watch CI
  scripts/dev.sh dev                  print adb connection steps
  scripts/dev.sh pair <ip:port> <code>  pair and connect over wireless debugging
  scripts/dev.sh model                push the Gemma 3n coach model to EVERY connected phone
  scripts/dev.sh all                  install the app on every connected phone, grant camera, launch
USAGE
}

cmd="${1:-}"
case "$cmd" in
  t)
    ./gradlew test
    ;;

  run)
    ./gradlew installDebug && adb shell am start -n "$LAUNCH"
    ;;

  log)
    adb logcat -s xThink:V
    ;;

  caps)
    # Reads what this phone actually exposes, into docs/evidence/.
    # See docs/HARDWARE.md for why we measure instead of trusting a spec sheet.
    ./gradlew installDebug
    # Pre-granting avoids the runtime dialog. Needs "USB debugging (Security
    # settings)" on OriginOS; if it fails the app just asks on screen instead.
    adb shell pm grant "$APP_ID" android.permission.CAMERA 2>/dev/null || true
    adb logcat -c
    adb shell am start -n "${APP_ID}/.probe.DebugCapsActivity" >/dev/null
    sleep 3
    out="docs/evidence/capabilities-$(date +%Y-%m-%d).txt"
    adb logcat -d -s xThink-CAPS:I | sed -e 's/^.*xThink-CAPS: //' > "$out"
    if [ ! -s "$out" ]; then
      echo "no report captured - is the phone connected and the app running?" >&2
      exit 1
    fi
    echo "wrote $out"
    cat "$out"
    ;;

  ship)
    tag="${2:-}"
    msg="${3:-}"
    if [ -z "$tag" ] || [ -z "$msg" ]; then
      echo "usage: scripts/dev.sh ship <tag> <msg>" >&2
      exit 2
    fi
    git add -A
    git commit -m "$msg"
    git tag "$tag"
    git push origin main --tags
    gh run watch
    ;;

  dev)
    cat <<'STEPS'
USB (primary path)
  1. Settings > About phone > tap "Build number" 7 times.
  2. Settings > System > Developer options > enable "USB debugging".
     On OriginOS also enable "Install via USB" (it may ask for a
     SIM / network check the first time).
  3. Plug the phone in with a data-capable cable.
  4. Accept the "Allow USB debugging?" RSA prompt on the phone.
  5. adb devices     # expect: <serial>  device

Wireless (only if this phone exposes it)
  1. Developer options > Wireless debugging > on.
  2. "Pair device with pairing code" -> note the ip:port and the 6-digit code.
  3. scripts/dev.sh pair <ip:port> <code>
     (the pairing port differs from the connect port shown on the main
      Wireless debugging screen)

Neither working?
  Install the APK from GitHub Releases. See docs/DEVICE.md.
STEPS
    ;;

  pair)
    addr="${2:-}"
    code="${3:-}"
    if [ -z "$addr" ] || [ -z "$code" ]; then
      echo "usage: scripts/dev.sh pair <ip:port> <code>" >&2
      exit 2
    fi
    adb pair "$addr" "$code"
    # The connect port is the one on the Wireless debugging main screen,
    # not the pairing port, so ask for it rather than guessing.
    read -r -p "adb connect address (ip:port from Wireless debugging): " connect_addr
    adb connect "$connect_addr"
    ;;

  model)
    # The coach models are 3-4 GB each and not in the APK. They live in
    # ~/Lab (regenerable: the HF token on this Mac can re-download them)
    # and are pushed to /data/local/tmp/llm, which the app can read. The
    # app loads the biggest bundle it finds - E4B over E2B.
    dir="$HOME/Lab/xthink/models"
    # LaMa (lama_fp32.onnx, 208 MB) sits beside the Gemma bundles and is pushed the same way.
    ls "$dir"/*.task "$dir"/*.onnx >/dev/null 2>&1 || { echo "no .task/.onnx models in $dir (see docs/TECHNICAL.md)" >&2; exit 2; }
    for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
      adb -s "$s" shell "mkdir -p /data/local/tmp/llm && chmod 755 /data/local/tmp/llm"
      for model in "$dir"/*.task "$dir"/*.onnx; do
        [ -e "$model" ] || continue
        name=$(basename "$model")
        have=$(adb -s "$s" shell stat -c %s "/data/local/tmp/llm/$name" 2>/dev/null | tr -d '\r')
        if [ "$have" = "$(wc -c < "$model" | tr -d ' ')" ]; then
          echo "$s: $name already there"
          continue
        fi
        echo "$s: pushing $name ($(du -h "$model" | cut -f1), about a minute per 3 GB over USB)..."
        adb -s "$s" push "$model" "/data/local/tmp/llm/$name"
        adb -s "$s" shell chmod 644 "/data/local/tmp/llm/$name"
      done
    done
    ;;

  all)
    # Gradle installs on every connected device by itself; the rest is the
    # first-run chores a judge's phone would otherwise ask for.
    ./gradlew installDebug
    for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
      adb -s "$s" shell pm grant in.arasan.xthink android.permission.CAMERA 2>/dev/null || true
      adb -s "$s" shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard" >/dev/null 2>&1 || true
      adb -s "$s" shell am start -n in.arasan.xthink/.MainActivity >/dev/null
      echo "$s: installed and launched"
    done
    ;;

  ""|-h|--help|help)
    usage
    ;;

  *)
    echo "unknown subcommand: $cmd" >&2
    usage
    exit 2
    ;;
esac
