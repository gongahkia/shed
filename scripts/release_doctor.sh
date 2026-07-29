#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${ITSY_RELEASE_MODE:-signed}"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
expected_bundle_id="${ITSY_EXPECTED_BUNDLE_ID:-dev.itsy.editor}"
expected_sparkle_feed_url="${ITSY_EXPECTED_SPARKLE_FEED_URL:-https://github.com/gongahkia/itsy/releases/latest/download/appcast.xml}"
failures=()

fail() {
	failures+=("$1")
}

require_command() {
	if ! command -v "$1" >/dev/null 2>&1; then
		fail "missing command: $1"
	fi
}

require_path() {
	if [[ ! -e "$1" ]]; then
		fail "missing path: $1"
	fi
}

require_command swift
require_command hdiutil
require_command codesign
require_command security
require_command xcrun
require_path /usr/libexec/PlistBuddy

case "$mode" in
	signed|unsigned) ;;
	*)
		fail "invalid ITSY_RELEASE_MODE: $mode"
		;;
esac

if [[ -d "$app_dir" ]]; then
	plist="$app_dir/Contents/Info.plist"
	require_path "$plist"
	if [[ -f "$plist" ]]; then
		bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$plist")"
		version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")"
		executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$plist")"
		if [[ "$bundle_id" != "$expected_bundle_id" ]]; then
			fail "unexpected bundle id: $bundle_id; expected $expected_bundle_id"
		fi
		if [[ ! -x "$app_dir/Contents/MacOS/$executable_name" ]]; then
			fail "missing executable: $app_dir/Contents/MacOS/$executable_name"
		fi
		if [[ "${GITHUB_REF:-}" == refs/tags/* && -n "${GITHUB_REF_NAME:-}" && "v$version" != "$GITHUB_REF_NAME" ]]; then
			fail "tag $GITHUB_REF_NAME does not match app version $version"
		fi
		if [[ "$mode" == "signed" ]]; then
			sparkle_feed_url="$(/usr/libexec/PlistBuddy -c 'Print :SUFeedURL' "$plist" 2>/dev/null || true)"
			sparkle_public_key="$(/usr/libexec/PlistBuddy -c 'Print :SUPublicEDKey' "$plist" 2>/dev/null || true)"
			sparkle_automatic_checks="$(/usr/libexec/PlistBuddy -c 'Print :SUEnableAutomaticChecks' "$plist" 2>/dev/null || true)"
			sparkle_automatic_downloads="$(/usr/libexec/PlistBuddy -c 'Print :SUAllowsAutomaticUpdates' "$plist" 2>/dev/null || true)"
			sparkle_requires_signed_feed="$(/usr/libexec/PlistBuddy -c 'Print :SURequireSignedFeed' "$plist" 2>/dev/null || true)"
			sparkle_shows_release_notes="$(/usr/libexec/PlistBuddy -c 'Print :SUShowReleaseNotes' "$plist" 2>/dev/null || true)"
			[[ "$sparkle_feed_url" == "$expected_sparkle_feed_url" ]] || fail "unexpected Sparkle SUFeedURL: $sparkle_feed_url"
			[[ -n "$sparkle_public_key" ]] || fail "missing Sparkle SUPublicEDKey; set ITSY_SPARKLE_PUBLIC_ED_KEY before bench/scripts/make_app.sh"
			[[ "$sparkle_automatic_checks" == "false" ]] || fail "Sparkle SUEnableAutomaticChecks must be false"
			[[ "$sparkle_automatic_downloads" == "false" ]] || fail "Sparkle SUAllowsAutomaticUpdates must be false"
			[[ "$sparkle_requires_signed_feed" == "true" ]] || fail "Sparkle SURequireSignedFeed must be true"
			[[ "$sparkle_shows_release_notes" == "true" ]] || fail "Sparkle SUShowReleaseNotes must be true"
			require_path "$app_dir/Contents/Frameworks/Sparkle.framework"
			require_path "$app_dir/Contents/Frameworks/Sparkle.framework/Versions/Current/XPCServices/Installer.xpc"
		fi
	fi
fi

if [[ "$mode" == "signed" ]]; then
	identity="${ITSY_CODESIGN_IDENTITY:-}"
	if [[ -n "$identity" ]]; then
		if ! security find-identity -v -p codesigning | grep -F "\"$identity\"" >/dev/null; then
			fail "codesigning identity not found: $identity"
		fi
	else
		count="$(security find-identity -v -p codesigning | sed -n 's/.*"\(Developer ID Application:[^"]*\)".*/\1/p' | wc -l | tr -d ' ')"
		if [[ "$count" != "1" ]]; then
			fail "expected exactly one Developer ID Application identity; found $count"
		fi
	fi
	if [[ -z "${ITSY_NOTARY_PROFILE:-}" ]]; then
		[[ -n "${ITSY_NOTARY_APPLE_ID:-}" ]] || fail "missing ITSY_NOTARY_APPLE_ID or ITSY_NOTARY_PROFILE"
		[[ -n "${ITSY_NOTARY_TEAM_ID:-}" ]] || fail "missing ITSY_NOTARY_TEAM_ID or ITSY_NOTARY_PROFILE"
		[[ -n "${ITSY_NOTARY_PASSWORD:-}" ]] || fail "missing ITSY_NOTARY_PASSWORD or ITSY_NOTARY_PROFILE"
	fi
fi

if ((${#failures[@]})); then
	printf 'release prerequisite check failed:\n' >&2
	for failure in "${failures[@]}"; do
		printf '%s\n' "- $failure" >&2
	done
	exit 1
fi

printf 'release prerequisite check passed (%s)\n' "$mode"
