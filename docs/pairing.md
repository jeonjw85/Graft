# Pairing

Pairing trades a single-use token for a stored certificate fingerprint on both devices. After that, every connection is authenticated by mutual TLS and the token is never used again.

## Flow

1. On the Mac, select "Add device". Graft generates a P-256 identity if this is the first launch, opens a pairing window for 120 seconds, and displays a QR code.
2. The Android app scans the QR code and connects to the advertised address with TLS.
3. The phone verifies that the server certificate's SPKI fingerprint equals `fp` from the QR code. On a mismatch it aborts before sending anything.
4. The phone sends `pair.request` with the token.
5. The Mac accepts the token once, stores the phone's fingerprint, and replies `pair.ok`. The pairing window closes as soon as the token is consumed, so a second device cannot use the same QR code.
6. The phone stores the Mac's fingerprint. Both sides send `hello` and `hello.ok`, and the connection is ready.

## QR payload

```text
graft://pair?v=1&h=<ip>&p=47800&fp=<SPKI-SHA256-base64url>&t=<token>&n=<device-name>
```

| Field | Meaning |
|---|---|
| `v` | Pairing URI version. Must be `1` |
| `h`, `p` | Local IP address and port of the Mac |
| `fp` | SPKI SHA-256 of the Mac's TLS certificate, base64url without padding (43 characters) |
| `t` | Single-use token: 16 or more base64url characters, or a six-digit fallback code |
| `n` | Display name of the Mac, percent-encoded |

## Manual fallback

When the camera is unavailable or mDNS is blocked, the Mac also shows a six-digit code. Enter the code together with the Mac's IP address; the code follows the same 120-second lifetime and single-use rule.

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
