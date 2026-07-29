#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'usage: %s <extension-dir> [output-dir]\n' "$0" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage
  exit 64
fi

extension_dir="$1"
output_dir="${2:-dist/extensions}"
manifest="$extension_dir/extension.json"

if [[ ! -d "$extension_dir" ]]; then
  printf 'extension directory not found: %s\n' "$extension_dir" >&2
  exit 66
fi
if [[ ! -f "$manifest" ]]; then
  printf 'extension manifest not found: %s\n' "$manifest" >&2
  exit 66
fi
if find "$extension_dir" -type l -print -quit | grep -q .; then
  printf 'extension package contains symlinks\n' >&2
  exit 65
fi
if find "$extension_dir" -name '*.app' -print -quit | grep -q .; then
  printf 'extension package contains nested app bundles\n' >&2
  exit 65
fi
if find "$extension_dir" -type f \( -perm -100 -o -perm -010 -o -perm -001 \) -print -quit | grep -q .; then
  printf 'extension package contains executable files\n' >&2
  exit 65
fi

identifier="$(/usr/bin/plutil -extract identifier raw -o - "$manifest")"
version="$(/usr/bin/plutil -extract version raw -o - "$manifest")"
if [[ -z "$identifier" || -z "$version" ]]; then
  printf 'manifest identifier/version must be non-empty\n' >&2
  exit 65
fi

mkdir -p "$output_dir"
archive="$output_dir/$identifier-$version.itsyext.zip"
rm -f "$archive" "$archive.sha256"
/usr/bin/ditto -c -k --sequesterRsrc --keepParent "$extension_dir" "$archive"
shasum -a 256 "$archive" > "$archive.sha256"
sha="$(cut -d ' ' -f 1 "$archive.sha256")"

printf '%s\n' "$archive"
printf '%s\n' "$archive.sha256"
printf 'allow sha256:%s id:%s version:%s signer:<signer>\n' "$sha" "$identifier" "$version"
