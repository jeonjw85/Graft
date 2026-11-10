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
- [ ] Using the same QR code on a second phone fails.
- [ ] Leaving the QR code open for more than 120 seconds makes pairing fail.
- [ ] Changing the Mac's certificate (delete the peer and re-pair) makes the old pin fail with a clear message.
- [ ] Entering the six-digit code with the IP address pairs when the camera is unavailable.
- [ ] Turning the Mac's Wi-Fi off and on reconnects within 60 seconds without a new pairing.

## Manual app checks

- [ ] The macOS app shows a menu bar icon at launch and binds port 47800 (verify with `lsof -nP -iTCP:47800 -sTCP:LISTEN`).
- [ ] Pairing a real phone from the menu window stores the peer in `~/Library/Application Support/Graft/peers.json`.
- [ ] Restarting the Mac app keeps the peer listed and reconnects without a new scan.
- [ ] The Android app shows the onboarding screen and blocks the Done step until notification access is granted.
- [ ] Denying the camera permission falls back to the six-digit code form.

## Before a release

- [ ] All automated tests pass
- [ ] A fresh device completes pairing through file transfer from scratch
- [ ] The app survives denied permissions and explains how to fix them
- [ ] Reconnects automatically after airplane mode
