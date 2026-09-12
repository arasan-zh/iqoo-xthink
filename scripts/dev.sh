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
  scripts/dev.sh ship <tag> <msg>     commit everything, tag, push, watch CI
  scripts/dev.sh dev                  print adb connection steps
  scripts/dev.sh pair <ip:port> <code>  pair and connect over wireless debugging
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

  ""|-h|--help|help)
    usage
    ;;

  *)
    echo "unknown subcommand: $cmd" >&2
    usage
    exit 2
    ;;
esac
