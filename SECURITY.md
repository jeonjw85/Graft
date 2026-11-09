# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a vulnerability. Email `jjw@jjw.kr` instead. We aim to respond within 72 hours.

Useful details to include:

- Affected version and platform (macOS/Android)
- Reproduction steps
- Expected impact
- A minimal proof of concept, if you have one

## Supported versions

After release, only the latest minor version receives security fixes. v0.1.x is the first supported line.

## Security boundaries by design

- Two devices connect directly over TLS 1.3 on the local network. There is no relay server.
- Pairing is verified with the certificate fingerprint embedded in the QR code plus a single-use token.
- Graft does not modify the system trust store and never asks the user to trust a certificate.
- Clipboard and notification contents are never written to disk or logged.
- Out of scope: an already compromised operating system, physical access, and rooted devices.

The threat model and mitigations are documented in [docs/threat-model.md](docs/threat-model.md).
