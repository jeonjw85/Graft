# Threat model

> Status: draft. It will be finalized with the transport and pairing implementation.

## Assets

- Clipboard contents (passwords, verification codes, private messages)
- Notification contents
- Files in transit

## Adversaries

1. **Passive attacker on the same Wi-Fi**: a device trying to eavesdrop on traffic
2. **Active attacker on the same Wi-Fi**: a device impersonating Graft or attempting a man-in-the-middle attack
3. **Unauthorized pairing attempt**: a device trying to establish a connection without the user's knowledge

## Mitigations

| Threat | Mitigation |
|---|---|
| Eavesdropping | TLS 1.3. Nothing is sent in plaintext |
| Server impersonation | Compare the certificate fingerprint in the QR code with the handshake result |
| Man-in-the-middle | Mutual TLS plus certificate fingerprint pinning on both sides |
| Unauthorized pairing | Single-use token (120 s), five attempts per minute, on-screen prompt on the Mac |
| Impersonation on reconnect | Verify the peer fingerprint on every connection; no automatic retry after a mismatch |
| Clipboard leakage | Never written to disk, never logged, notification previews hidden by default |
| Malicious files | Saved only to the designated folder, never executed, SHA-256 verified |

## Explicitly out of scope

- An already compromised macOS or Android device
- Physical access (stolen device, screen recording)
- Rooted or jailbroken devices
- A malicious device that the user deliberately paired

## Why Mac -> Android notifications are not supported

macOS does not expose a public API for observing other apps' notifications. The only route is reading the Notification Center database through an unofficial mechanism, which requires Full Disk Access and version-specific schema handling. Rather than request broad file access, Graft leaves this feature out.
