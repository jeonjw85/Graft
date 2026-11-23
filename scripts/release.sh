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

# The version lives in three files that have drifted before. Refuse to build unless the caller's
# argument matches them, and check the artifacts again below.
GRADLE_VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
PLIST_VERSION="$(sed -n 's/.*CFBundleShortVersionString: "\(.*\)".*/\1/p' "$ROOT/macos/project.yml" | head -1)"
if [[ "$GRADLE_VERSION" != "$VERSION" || "$PLIST_VERSION" != "$VERSION" ]]; then
  echo "error: asked for $VERSION but gradle says '$GRADLE_VERSION' and project.yml says '$PLIST_VERSION'" >&2
  echo "       bump both, then run again" >&2
  exit 1
fi

echo "==> Android"
(cd "$ROOT/android" && ./gradlew :app:assembleRelease :app:bundleRelease)

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
"$BUILD_TOOLS/apksigner" verify --print-certs \
  "$ROOT/android/app/build/outputs/apk/release/app-release.apk" | head -3

APK_BADGING="$("$BUILD_TOOLS/aapt2" dump badging \
    "$ROOT/android/app/build/outputs/apk/release/app-release.apk")"
if ! grep -q "versionName='$VERSION'" <<<"$APK_BADGING"; then
  echo "error: the built APK does not report $VERSION" >&2
  exit 1
fi

cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$DIST/Graft-$VERSION.apk"
cp "$ROOT/android/app/build/outputs/bundle/release/app-release.aab" "$DIST/Graft-$VERSION.aab"

echo "==> macOS"
(cd "$ROOT" && xcodegen generate --spec macos/project.yml)
xcodebuild build -project "$ROOT/macos/Graft.xcodeproj" -scheme Graft -configuration Release \
  -derivedDataPath "$DIST/derived" CODE_SIGNING_ALLOWED=NO >/dev/null

APP="$DIST/derived/Build/Products/Release/Graft.app"
APP_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
if [[ "$APP_VERSION" != "$VERSION" ]]; then
  echo "error: the built app reports $APP_VERSION, not $VERSION" >&2
  exit 1
fi
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
(cd "$DIST" && shasum -a 256 "Graft-$VERSION.dmg" "Graft-$VERSION.apk" > SHA256SUMS)
cat "$DIST/SHA256SUMS"
