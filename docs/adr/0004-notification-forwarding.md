# ADR 0004: Notification forwarding

- Status: Accepted
- Date: 2026-11-17

## Context

Notifications flow one way: Android to macOS. macOS has no public API for reading other apps'
notifications, so the reverse direction is out of scope (see ADR 0001 and the design spec).

Three forces shaped the implementation:

1. `NotificationListenerService` runs in its own context and cannot own the TLS socket.
2. A busy phone produces many notifications per minute; forwarding all of them would flood the Mac.
3. Notification access is a sensitive permission. A user who denies it must still get a working app.

## Decision

1. **Filter on the phone, before anything leaves it.** `NotificationFilter` drops Graft's own
   notifications, ongoing and foreground-service entries, and group summaries. It also enforces an
   allow list that starts empty, so a fresh install forwards nothing.
2. **De-duplicate by `key + postedAt` with a bounded cache (200 entries).** A notification that is
   updated in place has a new timestamp, so it is forwarded again; a replay of the same key at the
   same time is dropped.
3. **Truncate the body to 2 KiB on a UTF-8 boundary.** Cutting mid-character produces mojibake on
   the Mac and breaks Korean text.
4. **Queue in the repository, drain on connect.** The listener pushes accepted notifications into a
   bounded queue (100 entries); `ConnectionService` sends them when the connection reaches ready.
   Notifications that arrive while disconnected are kept only up to that cap.
5. **Present through a port.** `NotificationPresenter` takes a `NotificationCenterPort` so the
   formatting, the 2 KiB rule, and the 50-entry recent list are testable without touching the system
   notification centre.
6. **Previews are a toggle.** Turning previews off still shows that an app alerted, but never the
   text. Default is on, matching the spec's "previews hidden by default" only for sensitive apps,
   which the allow list already handles.

## Alternatives considered

### Send from the listener directly

- Pros: one less hop.
- Cons: the listener has no socket and would need its own connection, doubling the TLS sessions.
- Verdict: rejected.

### Forward everything and filter on the Mac

- Pros: simpler phone code.
- Cons: the phone would send content the user never approved, which is exactly what the permission
  is meant to prevent.
- Verdict: rejected.

### Persist notifications to disk for replay after a restart

- Pros: nothing is lost while disconnected.
- Cons: notification content is sensitive and the design explicitly says it is never written to
  disk.
- Verdict: rejected.

## Consequences

- A notification that arrives while the phone is disconnected is forwarded only if the connection
  comes back before the 100-entry queue wraps.
- The Mac keeps the last 50 notifications in memory only; quitting clears them.
- The allow list lives in DataStore on the phone. There is no remote way to change it, by design.
