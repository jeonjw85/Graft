# Pairing

Pairing trades a single-use token for a stored certificate fingerprint on both devices. After that, every connection is authenticated by mutual TLS and the token is never used again.

## Flow

1. On the Mac, select "Add device". Graft generates a P-256 identity if this is the first launch, opens a pairing window for 120 seconds, and displays a QR code.
2. The Android app scans the QR code and connects to the advertised address with TLS.
3. The phone verifies that the server certificate's SPKI fingerprint equals `fp` from the QR code. On a mismatch it aborts before sending anything.
4. The phone sends `pair.request` with the token.
5. The Mac accepts the token once, stores the phone's fingerprint, and replies `pair.ok`. The pairing window closes as soon as the token is consumed, so a second unknown device cannot complete a handshake with that QR code.
6. The phone stores the Mac's fingerprint. The phone sends `hello` and the Mac answers `hello.ok`, and the connection is ready.

## QR payload

```text
graft://pair?v=1&h=<ip>&p=47800&fp=<SPKI-SHA256-base64url>&t=<token>&n=<device-name>
```

| Field | Meaning |
|---|---|
| `v` | Pairing URI version. Must be `1` |
| `h`, `p` | Local IP address and port of the Mac |
| `fp` | SPKI SHA-256 of the Mac's TLS certificate, base64url without padding (43 characters) |
| `t` | Single-use token: 16 to 128 base64url characters, or a six-digit fallback code |
| `n` | Display name of the Mac, percent-encoded |

## Manual fallback

When the camera is unavailable or blocked, the Mac shows three values in its window:

1. A six-digit code, for example `042 817`.
2. The first eight characters of its certificate fingerprint, for example `Am_YijwV`.
3. Its IP address and port.

Enter all three on the phone. The phone performs the TLS handshake and computes the server
certificate's full fingerprint; it accepts the Mac only when that fingerprint starts with the eight
characters you typed. Eight base64url characters is 48 bits, so an impostor would need a certificate
whose fingerprint collides with that prefix, which is infeasible on a local network.

After a successful manual pairing the phone stores the **full** 43-character fingerprint and pins
it, exactly as if the QR code had been scanned. The code is single use and expires after 120
seconds.

If a different Mac is already paired, a manual pairing does not silently replace it; the app stops
and asks you to forget the existing device first.

## In the Android app

Reach pairing from onboarding (first run) or from **Connection → Pair another phone**. Both show the
same camera preview and manual form. A paired phone skips onboarding on every later launch; use
**Forget this Mac** to clear the pin and return to onboarding.

## Which flow should I use

Use the QR code when the camera works: it carries the fingerprint, so there is nothing to compare by
eye. Use the manual values when the camera is blocked or the lens is unavailable.<!-- manual:keep -->

## What pairing does not do

- It does not modify the system trust store.
- It does not ask the user to trust a certificate.
- It does not use a relay: the two devices connect directly.

## Re-pairing

Delete the peer on both devices and scan a new QR code. A fingerprint mismatch stops the connection and is never retried automatically, so a stale pin cannot block recovery once the peer is removed.

## Failure modes

| Symptom | Cause | What to do |
|---|---|---|
| "Security connection failed" | The pinned fingerprint did not match the certificate | Re-pair; if it persists, another device may be impersonating the Mac |
| Pairing window closed immediately | The token was already used | Start pairing again on the Mac |
| Connection refused | Wrong IP or port, or the Mac is not listening | Check both devices are on the same network |
| The phone never connects | mDNS blocked by AP isolation | Use the manual fallback with the IP address |

## Implementation notes

- The Mac creates a self-signed P-256 certificate at first launch and stores the private key in the login keychain.
- Android stores its key in the Android Keystore, where it cannot be extracted; only a signing handle leaves the keystore.
- Both sides compute the fingerprint from the SubjectPublicKeyInfo bytes, so Swift, Kotlin, and OpenSSL agree byte for byte.
- The fingerprint pins the key, not the certificate: renewing a certificate for the same key keeps existing pairings valid.
