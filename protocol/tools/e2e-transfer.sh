#!/usr/bin/env bash
# End-to-end file transfer: the headless Mac sends, the Kotlin peer receives and verifies.
#
# Fails unless the peer prints TRANSFER_OK with a hash matching the source file.
set -euo pipefail

cd "$(dirname "$0")/../.."

DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
export DEVELOPER_DIR
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}

echo "==> build"
swift build --package-path macos --product graft-headless >/dev/null
(cd android && ./gradlew :core:jar -q >/dev/null)
(cd tools/e2e && ./gradlew installDist -q >/dev/null)

WORK="$(mktemp -d)"
TAG="kr.jjw.graft.xfer.$(date +%s)-$RANDOM"
LOG="$WORK/server.log"
RECEIVE="$WORK/received"
mkdir -p "$RECEIVE"

# A random port can belong to another process, and identity creation can take a moment: pick a free
# port and wait for the URI instead of failing on the first second.
free_port() {
    python3 -c 'import random, socket
for _ in range(50):
    port = random.randint(50000, 59999)
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port))
        except OSError:
            continue
        print(port)
        break
else:
    raise SystemExit("no free port")'
}
PORT="$(free_port)"

SERVER_PID=0
cleanup() {
    [[ $SERVER_PID -ne 0 ]] && kill "$SERVER_PID" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

echo "==> 5 MiB sample"
SAMPLE="$WORK/sample.bin"
head -c 5242880 /dev/urandom > "$SAMPLE"
EXPECTED="$(shasum -a 256 "$SAMPLE" | awk '{print $1}')"

echo "==> headless sender"
macos/.build/debug/graft-headless \
    --port "$PORT" \
    --identity-tag "$TAG" \
    --host 127.0.0.1 \
    --send "$SAMPLE" \
    --receive-dir "$RECEIVE" >"$LOG" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 300); do
    if grep -q PAIRING_URI "$LOG"; then break; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "FAIL: the headless sender exited before pairing"
        cat "$LOG"
        exit 1
    fi
    sleep 0.1
done

URI="$(grep PAIRING_URI "$LOG" | sed 's/PAIRING_URI //')"
if [[ -z "$URI" ]]; then
    echo "FAIL: no pairing URI"
    cat "$LOG"
    exit 1
fi

echo "==> receive"
if ! OUTPUT="$(tools/e2e/build/install/graft-e2e/bin/graft-e2e \
        --transfer-uri "$URI" --receive-dir "$RECEIVE" 2>&1)"; then
    echo "$OUTPUT"
    echo "SERVER LOG:"
    tail -20 "$LOG"
    echo "FAIL: the peer did not complete the transfer"
    exit 1
fi

RECEIVED_PATH="$(awk '/TRANSFER_OK/ {print $2}' <<<"$OUTPUT")"
RECEIVED_HASH="$(awk '/TRANSFER_OK/ {print $3}' <<<"$OUTPUT")"

if [[ -z "$RECEIVED_PATH" || ! -f "$RECEIVED_PATH" ]]; then
    echo "$OUTPUT"
    echo "FAIL: no received file"
    exit 1
fi
if [[ "$RECEIVED_HASH" != "$EXPECTED" ]]; then
    echo "FAIL: hash mismatch"
    echo "  expected $EXPECTED"
    echo "  received $RECEIVED_HASH"
    exit 1
fi

echo "PASS: 5 MiB transferred intact ($EXPECTED)"
