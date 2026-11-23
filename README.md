# Graft

An open-source tool that connects a Mac and an Android phone over the same Wi-Fi network. Transfer files, share clipboard text, and mirror notifications between the two devices. Nothing leaves the local network.

> **Status:** v0.2.1 is the current release. Download the DMG or APK from the
> [releases page](https://github.com/jeonjw85/Graft/releases). The macOS build is ad-hoc signed and
> not notarized, so the first launch needs an explicit approval; see
> [install instructions](docs/install.md).

## Features (v0.2)

| Feature | Direction | Status |
|---|---|---|
| File & folder transfer (resume, hash check, cancel, speed/ETA, multi-file, drag & drop) | Mac <-> Android | Implemented |
| Android Share Sheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) | Android -> Mac | Implemented |
| Clipboard text sharing (automatic Mac sync, password-manager safe) | Mac -> Android | Implemented |
| Clipboard image sharing (PNG via chunked file transfer) | Mac -> Android | Implemented |
| Clipboard text sharing (app button, Quick Settings tile, notification action) | Android -> Mac | Implemented |
| Notification mirroring, inline replies, and two-way dismiss sync | Android <-> Mac | Implemented |
| Mac screenshot auto-transfer (opt-in) | Mac -> Android | Implemented |
| Phone battery indicator & ring-my-phone | Android <-> Mac | Implemented |
| Pairing: QR scan, or code + fingerprint | Mac <-> Android | Implemented |
| Localization (English & Korean) | Mac & Android | Implemented |

Graft does not send Mac notifications to Android, because macOS has no public API for reading other apps' notifications. See the [threat model](docs/threat-model.md) for details.

On the Mac, the menu bar popover shows the link state, phone battery level, and quick actions (pair, send files/folders or drop them onto the popover, send clipboard text or image, ring phone, and cancel active transfers with live speed & ETA). Pairing and settings live in their own windows: pairing opens on the QR code and offers the typed values behind a link, settings holds notifications, clipboard (including automatic Mac clipboard sync), transfers (custom receive folder, persistent history with Reveal in Finder, opt-in auto-screenshot forwarding), and an About page with Launch at Login and the certificate fingerprint. Closing the pairing window revokes the offer. Both apps use the same dark appearance and follow the system language (English or Korean).

The Android app opens on a three-tab layout once paired: **Connection** (link state, pairing, send/copy clipboard), **Files** (multi-file send, progress with speed/ETA & cancel, recent transfers with an Open action), and **Alerts** (notification access, allowed apps, recent forwards). You can also share files or text directly from other apps via the Android Share Sheet, or send your clipboard in one tap from the Quick Settings tile or connection notification.

Received files land in `Download/Graft` so they are visible to other apps; if Android refuses the
MediaStore insert, the file is kept in app storage and the row says so.

## Requirements

- macOS 13 Ventura or later
- Android 10 (API 29) or later
- Both devices on the same local network. Remote access is not supported.

## Installation

Download the latest build from the [releases page](https://github.com/jeonjw85/Graft/releases):
`Graft-<version>.dmg` for macOS, `Graft-<version>.apk` for Android. `SHA256SUMS` sits beside them.
[`docs/install.md`](docs/install.md) has the first-launch steps for the unsigned Mac build.

To build from source instead:

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
