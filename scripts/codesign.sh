#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="${ITSY_APP_DIR:-$repo_dir/Itsy.app}"
identity="${ITSY_CODESIGN_IDENTITY:-}"

if [[ ! -d "$app_dir" ]]; then
	(cd "$repo_dir" && bench/scripts/make_app.sh >/dev/null)
fi

if [[ -z "$identity" ]]; then
	identities=()
	while IFS= read -r line; do
		identities+=("$line")
	done < <(security find-identity -v -p codesigning | sed -n 's/.*"\(Developer ID Application:[^"]*\)".*/\1/p')
	if [[ "${#identities[@]}" -ne 1 ]]; then
		echo "expected exactly one Developer ID Application identity; found ${#identities[@]}" >&2
		echo "set ITSY_CODESIGN_IDENTITY='Developer ID Application: <name> (<team>)'" >&2
		exit 1
	fi
	identity="${identities[0]}"
fi

if [[ -d "$app_dir/Contents/Frameworks" ]]; then
	while IFS= read -r -d '' code_path; do
		codesign --force --sign "$identity" --options runtime --timestamp "$code_path"
	done < <(find "$app_dir/Contents/Frameworks" -type f \( -name '*.dylib' -o -perm -111 \) -print0)
fi

codesign --force --sign "$identity" --options runtime --timestamp "$app_dir"
codesign --verify --deep --strict --verbose=2 "$app_dir"
codesign -dvvv --entitlements :- "$app_dir"
echo "$app_dir"
