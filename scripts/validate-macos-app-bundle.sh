#!/usr/bin/env bash
set -euo pipefail

app_bundle="${1:-dist/Olly.app}"

if [[ ! -d "$app_bundle" ]]; then
    echo "app bundle not found: $app_bundle" >&2
    exit 1
fi

plist="$app_bundle/Contents/Info.plist"
if [[ ! -f "$plist" ]]; then
    echo "missing Info.plist: $plist" >&2
    exit 1
fi

plist_value() {
    /usr/libexec/PlistBuddy -c "Print :$1" "$plist"
}

executable_name="$(plist_value CFBundleExecutable)"
bundle_id="$(plist_value CFBundleIdentifier)"
short_version="$(plist_value CFBundleShortVersionString)"
bundle_version="$(plist_value CFBundleVersion)"
min_system="$(plist_value LSMinimumSystemVersion)"
package_type="$(plist_value CFBundlePackageType)"
apple_events_usage="$(plist_value NSAppleEventsUsageDescription)"
input_monitoring_usage="$(plist_value NSInputMonitoringUsageDescription)"

if [[ "$package_type" != "APPL" ]]; then
    echo "CFBundlePackageType must be APPL, got $package_type" >&2
    exit 1
fi

if [[ -z "$bundle_id" || -z "$short_version" || -z "$bundle_version" || -z "$min_system" ]]; then
    echo "required Info.plist metadata is empty" >&2
    exit 1
fi

if [[ -z "$apple_events_usage" || -z "$input_monitoring_usage" ]]; then
    echo "required privacy usage descriptions are empty" >&2
    exit 1
fi

executable="$app_bundle/Contents/MacOS/$executable_name"
if [[ ! -x "$executable" ]]; then
    echo "bundle executable is missing or not executable: $executable" >&2
    exit 1
fi

codesign --verify --deep --strict --verbose=2 "$app_bundle"
signature_details="$(codesign -dv --verbose=4 "$app_bundle" 2>&1 || true)"

if grep -q "Signature=adhoc" <<<"$signature_details"; then
    echo "validated ad-hoc signed app bundle: $app_bundle"
    exit 0
fi

if ! grep -q "Authority=Developer ID Application" <<<"$signature_details"; then
    echo "non-ad-hoc bundles must be signed with Developer ID Application" >&2
    echo "$signature_details" >&2
    exit 1
fi

if ! grep -q "Runtime Version=" <<<"$signature_details"; then
    echo "Developer ID app bundle is missing hardened runtime" >&2
    echo "$signature_details" >&2
    exit 1
fi

spctl -a -t exec -v "$app_bundle"
echo "validated Developer ID app bundle: $app_bundle"
