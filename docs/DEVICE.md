# Connecting the phone

Target device: iQOO 15 (OriginOS 6). Do not assume Wireless debugging exists on
this build of OriginOS — some vendor ROMs ship Developer options without it.
Work down this list in order.

---

## 1. USB — the primary path

1. **Unlock Developer options.**
   Settings > About phone > tap **Build number** seven times. Enter your PIN if
   asked. You get a "You are now a developer" toast.

2. **Turn on USB debugging.**
   Settings > System (or "System management") > **Developer options** >
   **USB debugging** > on.

3. **OriginOS quirk — "Install via USB".**
   OriginOS gates `adb install` behind a *second*, separate toggle called
   **Install via USB** (sometimes "Install apps via USB"). USB debugging alone
   is not enough — `adb devices` will show the phone but `installDebug` fails
   with `INSTALL_FAILED_USER_RESTRICTED`.

   Enabling it often forces a vivo-account / network check: the toggle refuses
   to flip until the phone has a SIM with mobile data, or it asks you to sign
   in and wait out a short countdown. If it flips back off by itself, put a SIM
   in, turn mobile data on (not just Wi-Fi), and try again.

   Also turn on **USB debugging (Security settings)** if present — it is what
   allows input simulation and permission grants.

4. **Plug in** with a data-capable cable. Charge-only cables enumerate power
   and nothing else; if `adb devices` is empty, swap the cable first.

5. **Accept the RSA prompt.** "Allow USB debugging?" appears on the phone the
   first time a new machine connects. Tick **Always allow from this computer**
   and accept. If it never appears, run `adb kill-server && adb devices` and
   re-plug; also set the USB mode to **File transfer / MTP** rather than
   "Charging only", which suppresses the prompt on OriginOS.

6. **Verify.**

   ```sh
   adb devices
   ```

   Expect a line ending in `device`:

   ```
   List of devices attached
   10BFAU14D8000XR        device
   ```

   `unauthorized` means the RSA prompt is unanswered. `offline` usually means a
   stale daemon — `adb kill-server` and re-plug.

7. **Install and run.**

   ```sh
   scripts/dev.sh run
   ```

---

## 2. Wireless — only if the phone offers it

Check first: Developer options > look for **Wireless debugging**. If it is not
there, skip to section 3. Do not waste hackathon time hunting for it.

1. Developer options > **Wireless debugging** > on. Phone and Mac must be on
   the same network, and that network must not have client isolation (most
   hotel, campus and venue Wi-Fi does — tether off the phone instead).

2. Tap **Pair device with pairing code**. A dialog shows an IP, a **pairing**
   port, and a six-digit code.

3. Pair, then connect. These are two different ports — the pairing port from
   the dialog, and the connect port from the main Wireless debugging screen:

   ```sh
   adb pair 192.168.1.23:37451 481920      # pairing port + code
   adb connect 192.168.1.23:39117          # connect port
   adb devices
   ```

   Or use the helper, which prompts for the second address:

   ```sh
   scripts/dev.sh pair 192.168.1.23:37451 481920
   ```

4. The connect port changes every time Wireless debugging is toggled. Re-pairing
   is not needed; re-connecting is.

---

## 3. Fallback — install from GitHub Releases

**This path always works, and it is the real distribution channel for this
project.** Judges and teammates get the app this way. Treat a broken Release
build as more urgent than a broken `adb`.

Every push to `main` runs `.github/workflows/build.yml`, which builds a debug
APK and publishes it as a release tagged `build-<run number>`.

Releases: https://github.com/arasan-zh/iqoo-xthink/releases

On the phone: open that URL in a browser, download the `.apk`, tap it, allow
"Install unknown apps" for the browser when prompted.

From a machine with `gh`:

```sh
gh release download build-1 --pattern '*.apk' --repo arasan-zh/iqoo-xthink
```

Or grab the URL:

```sh
gh release view build-1 --repo arasan-zh/iqoo-xthink --json assets \
  --jq '.assets[].url'
```

Every debug APK is signed with the checked-in `keystore/debug.keystore`, so
builds install **over** each other. No uninstall between versions, and app data
survives. This is why that key is in the repo.
