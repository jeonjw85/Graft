# Roadmap

| Milestone | Scope | Done when | Status |
|---|---|---|---|
| M0 Foundation | Repository, protocol v1, Swift and Kotlin codecs, CI, transport and pairing, app shell | Both apps pair on real devices and keep the connection alive | In progress |
| M1 Files | Bidirectional file transfer, resume, hash verification | A 1 GB transfer interrupted midway resumes and completes | Planned |
| M2 Clipboard | Mac -> Android automatic, Android -> Mac on demand | Korean text and emoji survive a round trip | Planned |
| M3 Notifications | Android -> Mac notifications, per-app allow list | Shown within one second, sensitive apps excluded by default | Planned |
| M4 Release | DMG and APK, installation docs, v0.1.0 | Signed artifacts and installation instructions | Planned |

## M0 breakdown

- [x] Repository skeleton, license, public documentation
- [x] Protocol v1 specification and JSON Schema
- [x] Swift and Kotlin codecs with 16 shared fixtures
- [x] Three CI workflows (protocol, macOS, Android)
- [x] TLS 1.3 transport, QR pairing, and an end-to-end pairing test
- [x] macOS menu bar app, Android Compose app, onboarding

## Candidates for v0.2 and later

1. Notification replies and opening the app from the phone
2. Clipboard image sharing
3. Mac screen capture transfer
4. Phone battery level and ring-my-phone
5. Multi-device support
6. Remote access outside the local network

Priority is decided in the issue tracker.
