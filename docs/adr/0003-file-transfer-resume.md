# ADR 0003: File transfer resume and commit

- Status: Accepted
- Date: 2026-11-10

## Context

File transfer rides on the existing TLS WebSocket. Two properties matter more than throughput:

1. An interrupted transfer must not corrupt the destination.
2. A resumed transfer must not duplicate or skip bytes.

The protocol already reserved a 16-byte binary header (`GF`, version, transfer id, offset) and the
control messages `file.offer`, `file.accept`, `file.reject`, `file.complete`, `file.cancel`.

## Decision

1. **Offset is the contract.** A chunk is written only when its offset equals the bytes already
   confirmed for that transfer. Both cores enforce this in a state machine driven by one shared
   fixture, so a mismatch cannot be handled differently on the two platforms.
2. **Write to `name.part`, commit by rename.** The receiver hashes the bytes on disk after
   `file.complete` and only then moves the file into place. A hash mismatch deletes the partial
   file rather than leaving a corrupt artifact with the final name.
3. **Hash the disk, not the buffer.** The digest is recomputed from the partial file, because a
   short write is invisible to a hash of the chunks that were received.
4. **Resume reuses the partial file.** If a partial file exists with exactly the accepted offset,
   it is appended to; otherwise the receiver answers with offset 0 and the sender restarts.
5. **Names are sanitized before anything touches disk.** Path separators, NUL, control characters,
   `.`/`..`, and overlong names are refused. Collisions become `name (2).ext`, and an existing
   `name (n)` continues from `n+1` instead of nesting.
6. **30 second stall fails the transfer.** Both platforms inject a clock so the rule is testable.

## Alternatives considered

### Append without an offset check

- Pros: simplest receiver.
- Cons: a duplicated or reordered frame silently corrupts the file while the length still matches.
- Verdict: rejected; this is the failure the review focus called out.

### Commit the file as it arrives, then verify

- Pros: the file appears immediately.
- Cons: a failed hash would leave a corrupt file at the final name, which is worse than no file.
- Verdict: rejected.

### Trust the sender's hash computation instead of re-reading

- Pros: one pass over the data.
- Cons: misses short writes, which is exactly the scenario resume creates.
- Verdict: rejected.

## Consequences

- A resumed transfer re-reads the partial file once to rebuild the digest. For a 1 GB file that is
  about a second of I/O, which is acceptable for the safety it buys.
- Resume records persist for 24 hours in `transfers.json` (application support on macOS, app files
  on Android). A record matches only when the name, directory, total size, and the bytes actually
  on disk all agree; anything else starts from zero. Expired records are deleted on load and on
  purge.
- The transfer code now lives in the shared core module, so the same coordinator that the apps use
  is what the end-to-end test drives. `protocol/tools/e2e-transfer.sh` sends a 5 MiB file and
  compares hashes on both sides.
- Android writes through `MediaStore` with `IS_PENDING` set, so a half-written file never appears in
  the Downloads list. Because MediaStore cannot append to a pending row from a previous process,
  the Android receiver always restarts at offset 0; the 24-hour record still helps macOS.
- Not yet verified on a physical Android device: the app does not construct a coordinator on device
  until the connection service is wired.
