# Graft protocol v1

This document defines the messages exchanged between the macOS app and the Android app on the same local network. This document takes precedence over both implementations.

- Protocol version: `1`
- Transport: WebSocket over mutually authenticated TLS 1.3 (default port 47800)
- Discovery: Bonjour/mDNS `_graft._tcp`
- Schema: [`protocol/schema/messages.schema.json`](../protocol/schema/messages.schema.json)
- Shared fixtures: [`protocol/fixtures/`](../protocol/fixtures/)

## 1. Envelope

Control messages are carried in a single UTF-8 JSON text frame.

```json
{
  "v": 1,
  "id": "6f1c2f7e-6f1a-4e2e-9d6e-2a1f5c3b8d40",
  "type": "file.offer",
  "ts": 1762752000000,
  "payload": {
    "tid": 7,
    "name": "report.pdf",
    "size": 1073741824,
    "mime": "application/pdf"
  }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `v` | integer | yes | Protocol major version. Currently `1` |
| `id` | string | yes | UUID v4 of the message. A response points back to it with `re` |
| `re` | string | no | `id` of the request this message responds to. Present only on responses; omit the field when null |
| `type` | string | yes | Message type (section 2) |
| `ts` | integer | yes | Send time, Unix epoch milliseconds |
| `payload` | object | yes | Type-specific data. Always a JSON object |

### 1.1 Limits

| Item | Value |
|---|---|
| Maximum control frame size | 512 KiB (524288 bytes) |
| JSON nesting depth | 64 levels |
| Maximum clipboard text size | 256 KiB |
| Maximum notification text length | Truncated to 2 KiB before sending |

If a control frame exceeds 512 KiB, the receiver closes the connection and records `oversized`. If clipboard text exceeds 256 KiB, the sender does not send that update.

A frame that is not valid UTF-8, contains an unpaired surrogate escape (`\uD800`-`\uDFFF`), or nests JSON deeper than 64 levels is answered with `error{code:"bad_payload"}` and the connection is closed. Both implementations must produce the same result at the same boundary.

### 1.2 Version negotiation

If `v` is not `1`, the receiver sends `error{code:"proto_unsupported"}` and closes the connection. Unknown fields in `payload` are ignored. Unknown `type` values are logged and ignored.

### 1.3 Payload rules

`payload` is always an object. When a non-object payload is received, the receiver responds with `error{code:"bad_payload"}`, drops that message, and keeps the connection open.

## 2. Message types

| type | Direction | Payload fields |
|---|---|---|
| `hello` | Both | `deviceId`, `name`, `platform`, `appVersion`, `proto`, `caps` |
| `hello.ok` | Both | `deviceId`, `name`, `proto`, `caps` |
| `ping` | Both | none |
| `pong` | Both | none |
| `pair.request` | Android -> Mac | `token`, `deviceId`, `name`, `platform` |
| `pair.ok` | Mac -> Android | `deviceId`, `name`, `platform` |
| `clipboard.update` | Both | `kind`, `body`, `ts` |
| `notification.posted` | Android -> Mac | `pkg`, `app`, `title`, `text`, `ts`, `key`, `priority` |
| `file.offer` | Both | `tid`, `name`, `size`, `mime` |
| `file.accept` | Receiver -> sender | `tid`, `offset` |
| `file.reject` | Receiver -> sender | `tid`, `reason` |
| `file.complete` | Sender -> receiver | `tid`, `sha256` |
| `file.cancel` | Both | `tid` |
| `error` | Both | `code`, `message` |

### 2.1 Field details

**`hello` / `hello.ok`**

| Field | Type | Description |
|---|---|---|
| `deviceId` | string (1-64) | Stable identifier for the device |
| `name` | string (1-64) | User-visible device name |
| `platform` | `"macos"` \| `"android"` | Platform |
| `appVersion` | string (1-32) | App version (`hello` only) |
| `proto` | integer >= 1 | Highest protocol version the sender supports |
| `caps` | string array (<= 32) | Supported capabilities |

`caps` values: `file.transfer`, `clipboard.text`, `notification.post`.

**`pair.request`** is the only message allowed on a connection that is not yet trusted. Any other message is answered with `error{code:"unpaired"}` and the connection is closed.

| Field | Type | Description |
|---|---|---|
| `token` | string (base64url, 16-128, or six digits) | Single-use token from the QR code, or the six-digit fallback code |
| `deviceId` | string (1-64) | Android device identifier |
| `name` | string (1-64) | Device name |
| `platform` | `"android"` | Platform |

**`clipboard.update`**

| Field | Type | Description |
|---|---|---|
| `kind` | `"text"` | v1 supports text only |
| `body` | string | Clipboard contents, up to 256 KiB |
| `ts` | integer | Time the copy happened |

**`notification.posted`**

| Field | Type | Description |
|---|---|---|
| `pkg` | string | Android package name |
| `app` | string | App display name |
| `title` | string | Notification title |
| `text` | string | Notification body (truncated to 2 KiB) |
| `ts` | integer | Time the notification was posted |
| `key` | string | Notification key used for de-duplication |
| `priority` | `"low"` \| `"normal"` \| `"high"` | Priority |

**File transfer.** `tid` is a transfer identifier in the uint32 range (0-4294967295).

- `file.offer`: `name` (1-255), `size` (>= 0), `mime`
- `file.accept`: requests the transfer starting at `offset` (>= 0)
- `file.reject`: `reason` is a free-form string (`declined`, `disk_full`, `too_large`, ...)
- `file.complete`: `sha256` is 64 lowercase hexadecimal characters
- `file.cancel`: aborts a transfer in progress

## 3. Binary frames

File data is sent as WebSocket binary frames. The header is 16 bytes, big-endian.

| Offset | Size | Meaning |
|---|---|---|
| 0 | 2 | Magic `0x47 0x46` (`GF`) |
| 2 | 1 | Header version (`1`) |
| 3 | 1 | Reserved (`0`) |
| 4 | 4 | `transferId` (uint32) |
| 8 | 8 | Offset within the file (uint64) |
| 16 | N | Data (up to 64 KiB) |

If the magic or version does not match, the frame is dropped and the receiver sends `error{code:"bad_payload"}`.

## 4. Error codes

| Code | Meaning | Connection |
|---|---|---|
| `proto_unsupported` | Unsupported protocol version | Closed |
| `unpaired` | Message not allowed on an unpaired connection | Closed |
| `unknown_device` | Certificate not in the stored peer list | Closed |
| `pin_mismatch` | Certificate fingerprint mismatch | Closed |
| `bad_payload` | Malformed payload | Kept open |
| `disk_full` | Receiver is out of storage | Kept open |
| `hash_mismatch` | File hash mismatch | Kept open |
| `transfer_not_found` | Unknown `tid` | Kept open |
| `too_large` | Size limit exceeded | Kept open |

## 5. Connection lifecycle

1. During the TLS handshake both sides present a certificate and verify that the peer's SPKI SHA-256 matches the stored value.
2. While unpaired, only `pair.request` is allowed.
3. After pairing, both sides send `hello` and answer with `hello.ok`. The intersection of both `caps` sets is what this session may use.
4. Send `ping` every 20 seconds and expect `pong`. With no response for 60 seconds, treat the connection as dead.
5. Reconnect with a backoff of 1, 2, 4, 8, 16, 32, and 60 seconds, with 20% jitter.

## 6. Timeouts

| Item | Value |
|---|---|
| `hello` response wait | 5 s |
| Heartbeat interval | 20 s |
| Dead connection timeout | 60 s |
| File transfer stall timeout | 30 s |
| Pairing token lifetime | 120 s |
| Pairing attempt limit | 5 per minute |

## 7. Compatibility

- Adding fields within v1 is allowed. Receivers must ignore unknown fields.
- Removing fields, changing meaning, or changing a type requires a protocol major version bump.
- A new message type is sent only after negotiating it through `caps`.
