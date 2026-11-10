#!/usr/bin/env bash
# End-to-end check: starts the headless Mac server, pairs a real TLS peer over the wire, and
# asserts that replaying the pairing request is rejected with a protocol error.
set -euo pipefail

cd "$(dirname "$0")/../.."

SWIFT=${SWIFT:-swift}
DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
export DEVELOPER_DIR

echo "==> build headless server"
"$SWIFT" build --package-path macos --product graft-headless >/dev/null

echo "==> build e2e peer"
(cd android && ./gradlew :core:jar -q >/dev/null)
(cd tools/e2e && ./gradlew installDist -q >/dev/null)

PORT=$(( ( RANDOM % 1000 ) + 48000 ))
TAG="kr.jjw.graft.e2e.$(date +%s)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"; kill ${SERVER_PID:-0} 2>/dev/null || true' EXIT

macos/.build/debug/graft-headless --port "$PORT" --identity-tag "$TAG" --host 127.0.0.1 >"$LOG" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 100); do
    grep -q PAIRING_URI "$LOG" && break
    sleep 0.1
done

URI="$(grep PAIRING_URI "$LOG" | sed 's/PAIRING_URI //')"
if [[ -z "$URI" ]]; then
    echo "FAIL: server did not print a pairing URI"
    cat "$LOG"
    exit 1
fi

echo "==> pair"
if ! OUTPUT="$(tools/e2e/build/install/graft-e2e/bin/graft-e2e --uri "$URI" 2>&1)"; then
    echo "$OUTPUT"
    echo "FAIL: pairing did not complete"
    exit 1
fi
echo "$OUTPUT"

if ! grep -q 'REPLAY_REJECTED' <<<"$OUTPUT"; then
    echo "FAIL: the replay check produced no verdict"
    exit 1
fi

echo "==> replay the same token with a fresh identity"
# A consumed token and a closed pairing window must refuse a second device. The client prints a
# marker only on a definite refusal, so a crash or a dropped frame cannot pass this check.
if ! OUTPUT="$(tools/e2e/build/install/graft-e2e/bin/graft-e2e --uri "$URI" --expect-refused 2>&1)"; then
    echo "$OUTPUT"
    echo "FAIL: the consumed token was accepted or produced no verdict"
    exit 1
fi
if ! grep -q 'REFUSED' <<<"$OUTPUT"; then
    echo "$OUTPUT"
    echo "FAIL: the replay run produced no protocol verdict"
    exit 1
fi

echo "PASS: paired once, replay refused"
