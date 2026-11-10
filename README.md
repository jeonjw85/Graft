# Graft

An open-source tool that connects a Mac and an Android phone over the same Wi-Fi network. Transfer files, share clipboard text, and mirror notifications between the two devices. Nothing leaves the local network.

> **Status:** v0.1 is in development. There is no installable release yet.

## Planned features (v0.1)

| Feature | Direction | Status |
|---|---|---|
| File transfer (resume, hash verification) | Mac <-> Android | Planned |
| Clipboard text sharing | Mac -> Android (automatic) | Planned |
| Clipboard text sharing | Android -> Mac (share sheet, Quick Settings tile) | Planned |
| Notification display | Android -> Mac | Planned |
| QR pairing and pinned TLS | Mac <-> Android | Transport done |

Graft does not send Mac notifications to Android, because macOS has no public API for reading other apps' notifications. See the [threat model](docs/threat-model.md) for details.

Current state: the protocol, both codecs, the pairing transport, and the app shells are implemented and tested. File transfer, notifications, and an automatic reconnect in the Android foreground service are not built yet.

## Requirements

- macOS 13 Ventura or later
- Android 10 (API 29) or later
- Both devices on the same local network. Remote access is not supported.

## Installation

There is no release yet. Both apps build from source; see below.

### Build the macOS app

```bash
xcodegen generate --spec macos/project.yml
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild build -project macos/Graft.xcodeproj -scheme Graft \
  -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO
```

The app appears in the menu bar. On first launch macOS asks for local network access; pairing needs
it.

### Build the Android app

```bash
cd android && ./gradlew :app:assembleDebug
```

Install the APK from `android/app/build/outputs/apk/debug/` with `adb install -r`.

## Permissions and privacy

- **macOS:** local network access only. Full Disk Access and Accessibility are not required.
- **Android:** notification access, camera (QR scanning), and notification permission. The battery optimization exemption is optional.
- No cloud servers, accounts, or analytics.
- Clipboard and notification contents are never written to disk.

See [Permissions](docs/permissions.md) for details.

## Repository layout

```text
macos/      macOS app (Swift, SwiftUI)
android/    Android app (Kotlin, Jetpack Compose)
protocol/   Wire protocol schema and fixtures shared by both apps
docs/       Protocol specification, permissions, threat model, roadmap
```

## Documentation

- [Protocol specification](docs/protocol.md)
- [Roadmap](docs/roadmap.md)
- [Permissions](docs/permissions.md)
- [Threat model](docs/threat-model.md)
- [Contributing](CONTRIBUTING.md)

## License

MIT. See [LICENSE](LICENSE).
