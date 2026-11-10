# ADR 0002: App shell and Android keystore wiring

- Status: Accepted
- Date: 2026-11-10

## Context

The protocol and transport work, but nothing a person can install exists. Both apps need an app
shell that keeps the existing core intact and adds only platform-specific code.

Two constraints shaped the design:

- Android Keystore cannot store certificates. It generates and holds keys, and returns a signing
  handle, but a self-signed certificate has to live somewhere else.
- Android 10+ blocks background clipboard access, and Android 15 limits `dataSync` foreground
  services to six hours per day.

## Decision

1. macOS is a `MenuBarExtra` app with `LSUIElement`, built by XcodeGen from `macos/project.yml`.
   `GraftService` owns the server, identity, and peer store, and starts from
   `applicationDidFinishLaunching` rather than a view callback, because a menu bar window is
   created lazily.
2. Android is a single `:app` module that depends on `:core`. The app module wraps
   `GraftClient`; nothing protocol-related lives in it.
3. The private key lives in the Android Keystore. The self-signed certificate DER is stored in
   `SharedPreferences` and re-attached to the key handle on load.
4. Clipboard access is user-triggered on Android and automatic on macOS. No polling anywhere.
5. The paired peer list is a JSON file on both platforms, with identical field names and epoch
   milliseconds, so a future export/import path is trivial.
6. Android uses the `connectedDevice` foreground service type when the service is added, not
   `dataSync`.

## Alternatives considered

### Store the Android identity as a PKCS#12 blob

- Pros: one file, no split between key and certificate.
- Cons: it requires an extractable private key, which defeats the point of the Keystore.
- Verdict: rejected.

### Use DataStore for the macOS peer list too

- Pros: one persistence API across platforms.
- Cons: DataStore is an Android library. The JSON file is testable on both sides and easier to
  inspect when a user reports a problem.
- Verdict: rejected.

### Poll the clipboard on Android with an accessibility service

- Pros: would give Mac to Android and Android to Mac automatically.
- Cons: requires a sensitive permission, is fragile across OEM builds, and is exactly the kind of
  access this project avoids.
- Verdict: rejected.

## Consequences

- A user who deletes app data on Android must re-pair; the Keystore key survives but the
  certificate does not.
- The six-digit fallback code cannot carry a fingerprint, so that path trusts the first
  certificate it sees. This is weaker than scanning the QR code and must be documented.
- The Android foreground service is still to be wired; without it the connection lives only while
  the app is in the foreground.
