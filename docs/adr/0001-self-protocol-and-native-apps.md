# ADR 0001: Custom protocol and native apps

- Status: Accepted
- Date: 2026-11-10

## Context

Build an open-source tool that connects a Mac and an Android phone on the same local network to exchange files, clipboard text, and notifications. Deep access to platform APIs matters on both sides.

- macOS: `NSPasteboard`, menu bar, `NWListener`, Keychain
- Android: `NotificationListenerService`, foreground services, Keystore, clipboard restrictions

## Decision

1. Define our own JSON protocol and publish it as documentation.
2. Use a single TLS 1.3 mutually authenticated WebSocket, discovered through Bonjour/mDNS.
3. Write native apps: Swift/SwiftUI and Kotlin/Jetpack Compose.

## Alternatives considered

### A. Reuse the KDE Connect protocol

- Pros: compatible with the existing Android app, so no Android client is needed.
- Cons: a complex protocol with virtually no Swift implementations. Hard to control UX and feature scope.
- Verdict: rejected.

### B. Fork an existing project such as LocalSend

- Pros: working file transfer on day one.
- Cons: designed around file transfer, so clipboard and notifications fit poorly, and divergence from upstream becomes a maintenance burden.
- Verdict: rejected.

### C. Single codebase with Flutter or Kotlin Multiplatform

- Pros: write UI code once.
- Cons: heavy reliance on platform APIs (menu bar, pasteboard, notification access, Keystore) turns the plugin boundary into the hard part.
- Verdict: rejected. The two apps share only the wire protocol and its fixtures.

## Consequences

- The protocol specification and golden fixtures are the single source of truth; the Swift and Kotlin implementations must pass the same tests.
- Third parties can build macOS, Linux, or CLI clients.
- Cost: we design and maintain the protocol ourselves, and we maintain two language implementations.
