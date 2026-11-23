# Roadmap

| Milestone | Scope | Done when | Status |
|---|---|---|---|
| M0 Foundation | Repository, protocol v1, Swift and Kotlin codecs, CI, transport and pairing, app shell | Both apps pair on real devices and keep the connection alive | Implemented (real-device soak pending) |
| M1 Files | Bidirectional file/folder transfer, resume, hash check, cancel, speed/ETA, Share Sheet, drag & drop | A 1 GB transfer interrupted midway resumes and completes | Done in CI at 5 MiB; 1 GB real-device run pending |
| M2 Clipboard | Mac -> Android automatic text & image sync, Android -> Mac via app, Quick Settings tile, or notification action | Korean text, emoji, and screenshots survive round trips | Implemented; real-device check pending |
| M3 Notifications | Android -> Mac notifications, per-app allow list, inline replies, two-way dismiss sync | Shown within one second, replies & dismissals sync cleanly | Implemented; real-device check pending |
| App UI & i18n | Polished shared dark UI (Android 3-tab + macOS popover/settings/pairing) and English/Korean localization | Ships cleanly across both platforms | Done (v0.2.1) |
| M4 Release | Automated GitHub Releases (DMG, signed APK, `SHA256SUMS`), Homebrew Cask (`jeonjw85/tap/graft`), install docs | Public releases and one-command macOS install live | Done (Play production track pending) |

## M0 breakdown

- [x] Repository skeleton, license, public documentation
- [x] Protocol v1 specification and JSON Schema
- [x] Swift and Kotlin codecs with 21 shared fixtures
- [x] CI workflows (protocol, macOS, Android, E2E) and automated tag releases
- [x] TLS 1.3 transport, QR/manual pairing, and end-to-end pairing & transfer tests
- [x] macOS menu bar app, Android Compose app, onboarding, and EN/KO localization

## Shipped in v0.2.x

- [x] Notification inline replies and two-way dismiss synchronization (Android <-> Mac)
- [x] Clipboard image sharing (Mac -> Android via chunked PNG transfer) and auto Mac clipboard sync
- [x] Mac screen capture automatic transfer (opt-in)
- [x] Phone battery level indicator and ring-my-phone
- [x] Android Share Sheet, Quick Settings tile, macOS drag-and-drop & folder auto-zip
- [x] Full English & Korean localization and shared dark UI polish (v0.2.1)
- [x] Automated GitHub Releases and Homebrew Cask (`jeonjw85/tap/graft`)

## Candidates for v0.3 and later

1. [ ] Open URL / deep link directly on the paired device
2. [ ] Image thumbnail previews in the Android Files tab
3. [ ] macOS in-app update check against GitHub Releases
4. [ ] Multi-device support (switch between multiple paired Macs or phones)
5. [ ] macOS notarization (remove Gatekeeper first-launch warning)
6. [ ] Remote access outside the local network

Priority is decided in the issue tracker.
