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
DMG_WINDOW_WIDTH="${DMG_WINDOW_WIDTH:-640}"
DMG_WINDOW_HEIGHT="${DMG_WINDOW_HEIGHT:-420}"
DMG_ICON_SIZE="${DMG_ICON_SIZE:-96}"
DMG_APP_ICON_X="${DMG_APP_ICON_X:-180}"
DMG_APP_ICON_Y="${DMG_APP_ICON_Y:-220}"
DMG_APPLICATIONS_ICON_X="${DMG_APPLICATIONS_ICON_X:-460}"
DMG_APPLICATIONS_ICON_Y="${DMG_APPLICATIONS_ICON_Y:-220}"
DMG_BACKGROUND_PATH="${DMG_BACKGROUND_PATH:-}"

if [[ -z "$CODESIGN_IDENTITY" ]]; then
    echo "CODESIGN_IDENTITY is required; use '-' only for local ad-hoc packaging" >&2
    exit 1
fi

if ! command -v create-dmg >/dev/null 2>&1; then
    echo "create-dmg is required; run 'brew install create-dmg'" >&2
    exit 1
fi

generate_dmg_background() {
    local output_path="$1"
    /usr/bin/swift - "$output_path" "$APP_NAME" "$DMG_WINDOW_WIDTH" "$DMG_WINDOW_HEIGHT" <<'SWIFT'
import AppKit

let outputPath = CommandLine.arguments[1]
let appName = CommandLine.arguments[2]
let width = Double(CommandLine.arguments[3]) ?? 640
let height = Double(CommandLine.arguments[4]) ?? 420
let size = NSSize(width: width, height: height)
let image = NSImage(size: size)
image.lockFocus()

NSColor(calibratedRed: 0.965, green: 0.968, blue: 0.956, alpha: 1).setFill()
NSBezierPath(rect: NSRect(origin: .zero, size: size)).fill()

NSColor(calibratedWhite: 1, alpha: 0.86).setFill()
NSBezierPath(roundedRect: NSRect(x: 34, y: 34, width: width - 68, height: height - 68), xRadius: 18, yRadius: 18).fill()

let titleStyle = NSMutableParagraphStyle()
titleStyle.alignment = .center
let titleAttributes: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 22, weight: .semibold),
    .foregroundColor: NSColor(calibratedWhite: 0.16, alpha: 1),
    .paragraphStyle: titleStyle
]
let title = "Drag \(appName) to Applications" as NSString
title.draw(in: NSRect(x: 60, y: height - 92, width: width - 120, height: 32), withAttributes: titleAttributes)

NSColor(calibratedRed: 0.20, green: 0.38, blue: 0.72, alpha: 0.82).setStroke()
let arrow = NSBezierPath()
arrow.lineWidth = 5
arrow.lineCapStyle = .round
arrow.move(to: NSPoint(x: width / 2 - 88, y: height / 2 - 8))
arrow.line(to: NSPoint(x: width / 2 + 88, y: height / 2 - 8))
arrow.move(to: NSPoint(x: width / 2 + 66, y: height / 2 + 14))
arrow.line(to: NSPoint(x: width / 2 + 88, y: height / 2 - 8))
arrow.line(to: NSPoint(x: width / 2 + 66, y: height / 2 - 30))
arrow.stroke()

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
    fatalError("failed to render dmg background")
}
try png.write(to: URL(fileURLWithPath: outputPath))
SWIFT
}

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
    <key>NSAppleEventsUsageDescription</key>
    <string>Olly uses Apple Events-compatible automation context while coordinating macOS window focus.</string>
    <key>NSInputMonitoringUsageDescription</key>
    <string>Olly reads keyboard and mouse timing to distinguish user-initiated focus from focus stealing.</string>
    <key>NSScreenCaptureUsageDescription</key>
    <string>Olly captures window thumbnails for the Alt-Tab preview switcher.</string>
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
DMG_ASSETS="$DIST_DIR/dmg-assets"
mkdir -p "$STAGING" "$DMG_ASSETS"
cp -R "$APP_BUNDLE" "$STAGING/"
if [[ -z "$DMG_BACKGROUND_PATH" ]]; then
    DMG_BACKGROUND_PATH="$DMG_ASSETS/background.png"
    generate_dmg_background "$DMG_BACKGROUND_PATH"
fi
rm -f "$DMG_PATH"
create-dmg \
    --volname "$APP_NAME" \
    --background "$DMG_BACKGROUND_PATH" \
    --window-pos 200 120 \
    --window-size "$DMG_WINDOW_WIDTH" "$DMG_WINDOW_HEIGHT" \
    --text-size 13 \
    --icon-size "$DMG_ICON_SIZE" \
    --icon "$APP_NAME.app" "$DMG_APP_ICON_X" "$DMG_APP_ICON_Y" \
    --hide-extension "$APP_NAME.app" \
    --app-drop-link "$DMG_APPLICATIONS_ICON_X" "$DMG_APPLICATIONS_ICON_Y" \
    --no-internet-enable \
    --format UDZO \
    "$DMG_PATH" \
    "$STAGING"
rm -rf "$STAGING" "$DMG_ASSETS"
hdiutil verify "$DMG_PATH"

if [[ "$CODESIGN_IDENTITY" != "-" ]]; then
    codesign --force --timestamp --sign "$CODESIGN_IDENTITY" "$DMG_PATH"
    codesign --verify --verbose=2 "$DMG_PATH"
fi

echo "$DMG_PATH"
