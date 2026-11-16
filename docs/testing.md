# Testing

> Status: growing with each milestone. Transport and pairing are covered automatically; file transfer, clipboard, and notifications are still manual.

## Automated tests

```bash
./protocol/tools/validate.sh          # protocol fixtures, schema, and identity vectors
swift test --package-path macos       # Swift protocol and transport core
cd android && ./gradlew :core:test    # Kotlin protocol and transport core
```

Both core suites read the same fixtures in `protocol/fixtures/`. If only one side passes, wire compatibility is broken.

On a machine where `xcode-select` points at the Command Line Tools, run the Swift suite with an explicit toolchain:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path macos
```

## End-to-end pairing test

```bash
./protocol/tools/e2e-pairing.sh
```

This builds the headless macOS server, starts it on a random port, pairs a real Kotlin client over TLS, and then asserts that replaying the same token is rejected. It needs both Xcode and a JDK 21.

To drive it by hand:

```bash
swift build --package-path macos --product graft-headless
(cd android && ./gradlew :core:jar)          # tools/e2e links the core jar
macos/.build/debug/graft-headless --port 47800 --identity-tag kr.jjw.graft.local --host 127.0.0.1
# copy the printed PAIRING_URI, then:
cd tools/e2e && ./gradlew run --args="--uri '<pairing-uri>'"
```

The headless server prints `PAIRING_URI` and `FINGERPRINT` on startup and runs until terminated.

## App builds

```bash
# macOS menu bar app
xcodegen generate --spec macos/project.yml
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild test -project macos/Graft.xcodeproj -scheme Graft \
  -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO

# Android app
export ANDROID_HOME=$HOME/Library/Android/sdk
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n kr.jjw.graft/.ui.GraftActivity
```

## End-to-end file transfer

```bash
./protocol/tools/e2e-transfer.sh
```

Creates a 5 MiB random file, has the headless Mac offer it, receives it with the Kotlin peer, and
fails unless both SHA-256 digests agree. Runs in CI after the pairing test.

## Manual file transfer

```bash
# headless sender: offers the file once a peer is ready
swift build --package-path macos --product graft-headless
macos/.build/debug/graft-headless --port 47950 --identity-tag test \
  --send /tmp/sample.bin --receive-dir /tmp/graft-in
```

Pair a phone with the printed URI. The Mac offers the file and the phone auto-accepts because it
is a paired peer. Both sides verify SHA-256 before the file is committed.

## Manual test matrix

| Area | Values |
|---|---|
| macOS | 13, 14, 15 and later |
| Android | 10, 13, 14, 15 and later |
| Network | Same Wi-Fi, AP isolation, Wi-Fi handoff |
| Screen | Locked, off, battery saver |
| Input | Korean filenames, emoji, files over 1 GB |

## Pairing-specific manual checks

- [ ] First launch generates a certificate and shows a QR code within two seconds.
- [ ] Scanning the QR code pairs without any certificate warning.
- [ ] A second scan of the same QR code does not start a second pairing.
- [ ] A QR code that is not a Graft code shows a message instead of doing nothing.
- [ ] Denying the camera permission switches the screen to the manual form.
- [ ] Using the same QR code on a second phone fails.
- [ ] Leaving the QR code open for more than 120 seconds makes pairing fail.
- [ ] Changing the Mac's certificate (delete the peer and re-pair) makes the old pin fail with a clear message.
- [ ] Manual pairing with the code, the eight-character fingerprint prefix, and the IP address succeeds when the camera is unavailable.
- [ ] Manual pairing with a wrong fingerprint prefix fails before any pairing message is sent.
- [ ] Manual pairing with a seven-character prefix is refused with a message naming eight characters.
- [ ] Turning the Mac's Wi-Fi off and on reconnects within 60 seconds without a new pairing.

## Manual app checks

- [ ] The macOS app shows a menu bar icon at launch and binds port 47800 (verify with `lsof -nP -iTCP:47800 -sTCP:LISTEN`).
- [ ] Pairing a real phone from the menu window stores the peer in `~/Library/Application Support/Graft/peers.json`.
- [ ] Restarting the Mac app keeps the peer listed and reconnects without a new scan.
- [ ] Launching with a paired phone opens the Connection tab directly, with no onboarding.
- [ ] Back on the Files or Alerts tab returns to Connection; back on Connection leaves the app.
- [x] A file received from the Mac appears in the Files tab and opens from its Open action. (verified on an Android 16 emulator, 2026-11-17)
- [x] Launching with a paired phone opens the Connection tab directly, with no onboarding. (verified 2026-11-17)
- [x] Manual pairing with the code, the eight-character fingerprint prefix, and the IP address succeeds when the camera is unavailable. (verified 2026-11-17)
- [x] Denying the camera permission switches the screen to the manual form. (verified 2026-11-17)
- [x] The received file is visible in `Download/Graft` via MediaStore, and the Open action reaches a system viewer. (verified 2026-11-17)
- [ ] A file received while MediaStore is unavailable shows `Saved to app storage`.
- [ ] Sending a file from the phone shows progress and lands in the Mac's downloads folder.
- [ ] Sending a file while disconnected shows "Connect to your Mac first" instead of failing silently.
- [ ] A rejected transfer reads `Rejected`, not `Not paired`, in the Files tab.
- [ ] "Pair another phone" while connected closes the current link, and pairing a phone reconnects to the new one.
- [ ] A failed transfer that is retried updates its Recent row to Completed.
- [ ] The app has no white flash at cold start and no light surface on any screen.
- [ ] No screen shows a light surface, including the manual pairing form and the alerts lists.
- [ ] A clipboard received while the phone app is open on the Connection tab is copied and shows "Copied to clipboard".
- [ ] The Copy button copies the last received clipboard again.
- [ ] A clipboard received while the phone app was closed is not pasted over the user's clipboard later.
- [ ] On first launch the Mac asks for notification permission once, and a denial does not break pairing or transfers.
- [ ] Closing the Mac's pairing window cancels the offer: a QR code scanned afterwards does not pair.
- [ ] The popover shows at most three actions, and settings opens from its footer.
- [ ] Settings > Transfers lists a finished transfer with its direction, size, and reason.
- [ ] Settings > About shows the same 43-character fingerprint the phone displays.
- [ ] The Mac app has no light window, including the pairing and settings windows.
- [x] The Android app shows the onboarding screen on first launch, and skipping notification access still reaches the scan step. (verified 2026-11-17)
- [ ] Denying the camera permission falls back to the six-digit code form.
- [ ] Sending a 100 MB file completes and the SHA-256 matches on both sides.
- [ ] Killing the receiver mid-transfer leaves `name.part` and no final file.
- [ ] A file named `../../evil.sh` is refused with no file written.
- [ ] A hash mismatch leaves no file behind.

## Background connection and notifications

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
adb shell dumpsys activity services kr.jjw.graft | grep -E 'ConnectionService|isForeground|foregroundServiceType'
adb shell dumpsys package kr.jjw.graft | grep -i notification
```

On the phone: open Graft, finish onboarding, tap "Keep connected in the background", then lock the
screen and confirm the Mac stays connected. Grant notification access in system settings, tick one
app on the notification screen, and confirm its alerts appear in the Mac menu with the app name as
the title.

## Before a release

- [ ] All automated tests pass
- [ ] A fresh device completes pairing through file transfer from scratch
- [ ] The app survives denied permissions and explains how to fix them
- [ ] Reconnects automatically after airplane mode
