#!/usr/bin/env bash
# Builds the release artifacts for one version. Signing material never lives in the repository.
#
#   scripts/release.sh 0.1.0
#
# Requires:
#   - ~/.config/graft/release.jks with alias "graft" (Android upload key)
#   - android/local.properties pointing at it (graft.storeFile, graft.storePassword, graft.keyAlias,
#     graft.keyPassword) — that file is gitignored
#   - Xcode at /Applications/Xcode.app and $ANDROID_HOME set
set -euo pipefail

VERSION="${1:?usage: scripts/release.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_HOME

mkdir -p "$DIST"

echo "==> Android"
(cd "$ROOT/android" && ./gradlew :app:assembleRelease :app:bundleRelease)

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
"$BUILD_TOOLS/apksigner" verify --print-certs \
  "$ROOT/android/app/build/outputs/apk/release/app-release.apk" | head -3

cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$DIST/Graft-$VERSION.apk"
cp "$ROOT/android/app/build/outputs/bundle/release/app-release.aab" "$DIST/Graft-$VERSION.aab"

echo "==> macOS"
(cd "$ROOT" && xcodegen generate --spec macos/project.yml)
xcodebuild build -project "$ROOT/macos/Graft.xcodeproj" -scheme Graft -configuration Release \
  -derivedDataPath "$DIST/derived" CODE_SIGNING_ALLOWED=NO >/dev/null

APP="$DIST/derived/Build/Products/Release/Graft.app"
# No Developer ID exists, so the app is ad-hoc signed and the DMG is not notarized.
codesign --force --deep --sign - "$APP"

rm -rf "$DIST/dmg"
mkdir -p "$DIST/dmg"
cp -R "$APP" "$DIST/dmg/"
ln -s /Applications "$DIST/dmg/Applications"
hdiutil create -volname "Graft $VERSION" -srcfolder "$DIST/dmg" -ov -format UDZO \
  "$DIST/Graft-$VERSION.dmg" >/dev/null

rm -rf "$DIST/dmg" "$DIST/derived"

echo "==> Checksums"
(cd "$DIST" && shasum -a 256 "Graft-$VERSION.dmg" "Graft-$VERSION.apk" "Graft-$VERSION.aab" > SHA256SUMS)
cat "$DIST/SHA256SUMS"
