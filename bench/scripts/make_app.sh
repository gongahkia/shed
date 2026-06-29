#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../.." && pwd)"
app_dir="${APP_DIR:-$repo_dir/Itsy.app}"
binary="$repo_dir/.build/release/ItsyApp"

if [[ ! -x "$binary" ]]; then
	(cd "$repo_dir" && swift build -c release)
fi

rm -rf "$app_dir"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Frameworks"
cp "$binary" "$app_dir/Contents/MacOS/Itsy"
chmod +x "$app_dir/Contents/MacOS/Itsy"
GRAMMAR_DYLIB_DIR="$app_dir/Contents/Frameworks/ItsyGrammars" "$repo_dir/bench/scripts/build_grammar_dylibs.sh" >/dev/null
cat > "$app_dir/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleDisplayName</key>
	<string>Itsy</string>
	<key>CFBundleExecutable</key>
	<string>Itsy</string>
	<key>CFBundleIdentifier</key>
	<string>dev.itsy.editor</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>Itsy</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleShortVersionString</key>
	<string>0.1.0</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>LSApplicationCategoryType</key>
	<string>public.app-category.developer-tools</string>
	<key>LSMinimumSystemVersion</key>
	<string>13.0</string>
	<key>NSHighResolutionCapable</key>
	<true/>
	<key>NSPrincipalClass</key>
	<string>NSApplication</string>
	<key>NSUserActivityTypes</key>
	<array>
		<string>dev.itsy.editor.open-file</string>
	</array>
	<key>NSServices</key>
	<array>
		<dict>
			<key>NSMenuItem</key>
			<dict>
				<key>default</key>
				<string>Open Selection in Itsy</string>
			</dict>
			<key>NSMessage</key>
			<string>openSelection</string>
			<key>NSPortName</key>
			<string>Itsy</string>
			<key>NSSendTypes</key>
			<array>
				<string>NSStringPboardType</string>
			</array>
		</dict>
		<dict>
			<key>NSMenuItem</key>
			<dict>
				<key>default</key>
				<string>Open File in Itsy</string>
			</dict>
			<key>NSMessage</key>
			<string>openFile</string>
			<key>NSPortName</key>
			<string>Itsy</string>
			<key>NSSendFileTypes</key>
			<array>
				<string>public.data</string>
			</array>
		</dict>
	</array>
</dict>
</plist>
PLIST
echo "$app_dir"
