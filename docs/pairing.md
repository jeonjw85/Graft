# Pairing

> Status: draft. It will be finalized with the transport and pairing implementation.

## Overview

1. On the Mac, select "Add device" to display a QR code.
2. Scan the QR code with the Android app.
3. Both devices store the other's certificate fingerprint and finish connecting.

## QR payload

```text
graft://pair?v=1&h=<ip>&p=47800&fp=<SPKI-SHA256-base64url>&t=<token>&n=<device-name>
```

| Field | Meaning |
|---|---|
| `h`, `p` | Local IP address and port of the Mac |
| `fp` | SPKI SHA-256 of the Mac's TLS certificate (base64url) |
| `t` | Single-use pairing token, valid for 120 seconds |
| `n` | Display name of the Mac |

## Security properties

- The phone compares the server certificate fingerprint from the TLS handshake with `fp`. On a mismatch it aborts immediately.
- The token is single-use and expires after 120 seconds.
- After pairing, both sides store the peer certificate fingerprint and verify it on every subsequent connection.

## Fallback

If the camera is unavailable or mDNS is blocked on the network, enter the six-digit code and IP address shown by the Mac into the phone manually.
