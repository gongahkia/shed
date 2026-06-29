#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-Olly}"
BUNDLE_IDENTIFIER="${BUNDLE_IDENTIFIER:-dev.olly.app}"
VERSION="${VERSION:-${GITHUB_REF_NAME:-0.1.0-dev}}"
BUILD_NUMBER="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-1}}"
DIST_DIR="${DIST_DIR:-dist}"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
DMG_PATH="$DIST_DIR/$APP_NAME.dmg"
CODESIGN_IDENTITY="${CODESIGN_IDENTITY:-}"

if [[ -z "$CODESIGN_IDENTITY" ]]; then
    echo "CODESIGN_IDENTITY is required; use '-' only for local ad-hoc packaging" >&2
    exit 1
fi

rm -rf "$DIST_DIR"
mkdir -p "$APP_BUNDLE/Contents/MacOS"

swift build -c release --product ollyApp
cp ".build/release/ollyApp" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"

cat >"$APP_BUNDLE/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleExecutable</key>
    <string>$APP_NAME</string>
    <key>CFBundleIdentifier</key>
    <string>$BUNDLE_IDENTIFIER</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>$APP_NAME</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION#v}</string>
    <key>CFBundleVersion</key>
    <string>$BUILD_NUMBER</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHumanReadableCopyright</key>
    <string>Copyright (c) 2026 Olly contributors</string>
</dict>
</plist>
PLIST

chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"

if [[ "$CODESIGN_IDENTITY" == "-" ]]; then
    codesign --force --sign - "$APP_BUNDLE"
else
    codesign --force --timestamp --options runtime --sign "$CODESIGN_IDENTITY" "$APP_BUNDLE"
fi

codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"
scripts/validate-macos-app-bundle.sh "$APP_BUNDLE"

STAGING="$DIST_DIR/dmg-root"
mkdir -p "$STAGING"
cp -R "$APP_BUNDLE" "$STAGING/"
ln -s /Applications "$STAGING/Applications"
hdiutil create -volname "$APP_NAME" -srcfolder "$STAGING" -ov -format UDZO "$DMG_PATH"
rm -rf "$STAGING"

if [[ "$CODESIGN_IDENTITY" != "-" ]]; then
    codesign --force --timestamp --sign "$CODESIGN_IDENTITY" "$DMG_PATH"
    codesign --verify --verbose=2 "$DMG_PATH"
fi

echo "$DMG_PATH"
