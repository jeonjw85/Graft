#!/usr/bin/env bash
# Generates or verifies the shared SPKI fingerprint vectors.
#
# The same file is read by the Swift and Kotlin test suites, so a divergence between the two
# implementations fails CI instead of surfacing as "security connection failed" on a device.
#
# Usage:
#   ./gen-identity-vectors.sh          regenerate fixtures/identity/*
#   ./gen-identity-vectors.sh --check  verify checked-in vectors are self-consistent
set -euo pipefail

cd "$(dirname "$0")/../.."
dir="protocol/fixtures/identity"
check=false
[[ "${1:-}" == "--check" ]] && check=true

b64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

generate() {
    local name="$1"
    local work
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' RETURN

    openssl ecparam -name prime256v1 -genkey -noout -out "$work/key.pem" 2>/dev/null
    openssl ec -in "$work/key.pem" -pubout -outform DER -out "$work/pub.der" 2>/dev/null

    local pub_hex spki_hex fingerprint
    pub_hex="$(xxd -p -c 256 "$work/pub.der")"
    # The subjectPublicKey bit string ends with the 65-byte uncompressed point (04 || X || Y).
    spki_hex="$pub_hex"
    fingerprint="$(openssl dgst -sha256 -binary < "$work/pub.der" | b64url)"

    printf '%s' "${spki_hex: -130}" > "$dir/$name.pub.hex"
    printf '%s\n' "$fingerprint" > "$dir/$name.fp"
    printf '%s' "$spki_hex" > "$dir/$name.spki.hex"

    echo "{ \"name\": \"$name\", \"publicKeyHex\": \"${spki_hex: -130}\", \"spkiHex\": \"$spki_hex\", \"sha256Base64Url\": \"$fingerprint\" }"
}

mkdir -p "$dir"

if $check; then
    failures=0
    for name in tv1 tv2; do
        spki_file="$dir/$name.spki.hex"
        fp_file="$dir/$name.fp"
        [[ -f "$spki_file" && -f "$fp_file" ]] || { echo "missing vector: $name" >&2; failures=$((failures + 1)); continue; }
        expected="$(tr -d '\n' < "$fp_file")"
        actual="$(xxd -r -p < "$spki_file" | openssl dgst -sha256 -binary | b64url)"
        if [[ "$expected" != "$actual" ]]; then
            echo "vector $name drifted: file=$expected computed=$actual" >&2
            failures=$((failures + 1))
        fi
    done
    # expected.json is what both test suites read; keep it in step with the per-file fixtures.
    if [[ -f "$dir/expected.json" ]]; then
        for name in tv1 tv2; do
            spki_value="$(tr -d '\n' < "$dir/$name.spki.hex")"
            fp_value="$(tr -d '\n' < "$dir/$name.fp")"
            json_spki="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print([v['spkiHex'] for v in d['vectors'] if v['name']==sys.argv[2]][0])" "$dir/expected.json" "$name")"
            json_fp="$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print([v['sha256Base64Url'] for v in d['vectors'] if v['name']==sys.argv[2]][0])" "$dir/expected.json" "$name")"
            if [[ "$spki_value" != "$json_spki" || "$fp_value" != "$json_fp" ]]; then
                echo "expected.json disagrees with $name fixtures" >&2
                failures=$((failures + 1))
            fi
        done
    fi

    if (( failures > 0 )); then
        echo "identity vectors are inconsistent" >&2
        exit 1
    fi
    echo "identity vectors OK"
    exit 0
fi

{
    printf '{\n  "vectors": [\n'
    first=1
    for name in tv1 tv2; do
        [[ $first -eq 0 ]] && printf ',\n'
        first=0
        printf '    %s' "$(generate "$name")"
    done
    printf '\n  ]\n}\n'
} > "$dir/expected.json"

echo "wrote $dir"
