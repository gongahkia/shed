#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
app_dir="${APP_DIR:-$repo_dir/Pico.app}"
binary="$repo_dir/.build/release/PicoApp"

if [[ ! -x "$binary" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

rm -rf "$app_dir"
mkdir -p "$app_dir/Contents/MacOS"
cp "$binary" "$app_dir/Contents/MacOS/Pico"
chmod +x "$app_dir/Contents/MacOS/Pico"
cat > "$app_dir/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleExecutable</key>
	<string>Pico</string>
	<key>CFBundleIdentifier</key>
	<string>dev.pico.editor</string>
	<key>CFBundleName</key>
	<string>Pico</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>0.1.0</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>LSMinimumSystemVersion</key>
	<string>13.0</string>
</dict>
</plist>
PLIST
echo "$app_dir"
