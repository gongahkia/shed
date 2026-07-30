#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fail() {
  echo "macOS package error: $*" >&2
  exit 1
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required tool: $1"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

[[ "$(uname -s)" == "Darwin" ]] || fail "macOS is required"
[[ "$(uname -m)" == "arm64" ]] || fail "macOS arm64 is required"
require_tool mvn
require_tool shasum
require_tool hdiutil
require_tool jar

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
JAVA_HOME_PATH="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^[[:space:]]*java.home = / {print $2; exit}')"
[[ -n "$JAVA_HOME_PATH" ]] || fail "could not resolve JAVA_HOME"
JDEPS="$JAVA_HOME_PATH/bin/jdeps"
JLINK="$JAVA_HOME_PATH/bin/jlink"
JPACKAGE="$JAVA_HOME_PATH/bin/jpackage"
[[ -x "$JDEPS" ]] || fail "missing jdeps in $JAVA_HOME_PATH"
[[ -x "$JLINK" ]] || fail "missing jlink in $JAVA_HOME_PATH"
[[ -x "$JPACKAGE" ]] || fail "missing jpackage in $JAVA_HOME_PATH"

JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
JAVA_FEATURE="${JAVA_VERSION%%.*}"
[[ "$JAVA_FEATURE" == "21" ]] || fail "JDK 21 is required; found $JAVA_VERSION"

MAVEN_ARGS=(-B -q clean package)
if [[ -n "${SHED_BUILD_COMMIT:-}" ]]; then
  MAVEN_ARGS+=("-Dshed.build.commit=$SHED_BUILD_COMMIT")
fi
mvn "${MAVEN_ARGS[@]}"
mvn -B -q artifact:check-buildplan

VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)"
[[ -n "$VERSION" ]] || fail "could not resolve project version"
OUTPUT_TIMESTAMP="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.build.outputTimestamp)"
[[ -n "$OUTPUT_TIMESTAMP" ]] || fail "could not resolve Maven output timestamp"
APP_NAME="Shed"
ARCHITECTURE="macos-arm64"
JAR_NAME="shed-$VERSION.jar"
JAR_PATH="$REPO_ROOT/target/$JAR_NAME"
[[ -f "$JAR_PATH" ]] || fail "missing packaged jar: $JAR_PATH"

PACKAGE_ROOT="$REPO_ROOT/target/macos-package"
INPUT_DIR="$PACKAGE_ROOT/input"
RUNTIME_DIR="$PACKAGE_ROOT/runtime"
APP_IMAGE_DIR="$PACKAGE_ROOT/app-image"
DIST_DIR="$PACKAGE_ROOT/dist"
mkdir -p "$INPUT_DIR" "$APP_IMAGE_DIR" "$DIST_DIR"
cp "$JAR_PATH" "$INPUT_DIR/$JAR_NAME"

MODULES="$("$JDEPS" --multi-release 21 --ignore-missing-deps --print-module-deps "$JAR_PATH")"
[[ "$MODULES" == *"java.desktop"* ]] || fail "runtime module analysis did not include java.desktop"
printf '%s\n' "$MODULES" > "$PACKAGE_ROOT/runtime-modules.txt"
"$JLINK" --add-modules "$MODULES" --output "$RUNTIME_DIR" --strip-debug --no-header-files --no-man-pages --compress=zip-6

"$JPACKAGE" --type app-image --dest "$APP_IMAGE_DIR" --input "$INPUT_DIR" --main-jar "$JAR_NAME" --main-class shed.Texteditor \
  --name "$APP_NAME" --app-version "$VERSION" --vendor Shed --description "Shed text editor" --copyright "Copyright Shed" \
  --mac-package-identifier dev.shed.app --mac-package-name Shed --runtime-image "$RUNTIME_DIR"

APP_BUNDLE="$APP_IMAGE_DIR/$APP_NAME.app"
APP_JAR="$APP_BUNDLE/Contents/app/$JAR_NAME"
RUNTIME_JAVA="$APP_BUNDLE/Contents/runtime/Contents/Home/bin/java"
INFO_PLIST="$APP_BUNDLE/Contents/Info.plist"
[[ -d "$APP_BUNDLE" ]] || fail "missing app bundle: $APP_BUNDLE"
[[ -f "$APP_JAR" ]] || fail "missing bundled jar: $APP_JAR"
[[ -x "$RUNTIME_JAVA" ]] || fail "missing bundled Java runtime"
[[ -f "$INFO_PLIST" ]] || fail "missing app Info.plist"
plutil -lint "$INFO_PLIST" >/dev/null
[[ "$(plutil -extract CFBundleIdentifier raw "$INFO_PLIST")" == "dev.shed.app" ]] || fail "unexpected bundle identifier"
[[ "$(plutil -extract CFBundleShortVersionString raw "$INFO_PLIST")" == "$VERSION" ]] || fail "unexpected bundle version"
codesign --verify --deep --strict "$APP_BUNDLE" || fail "app code-signature verification failed"
SIGNING="$(codesign -dvv "$APP_BUNDLE" 2>&1 | awk -F= '/^Signature=/ {print tolower($2); exit}')"
[[ -n "$SIGNING" ]] || fail "could not determine app signature state"
"$RUNTIME_JAVA" -version >/dev/null 2>&1
RUNTIME_VERSION="$("$RUNTIME_JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
[[ "${RUNTIME_VERSION%%.*}" == "21" ]] || fail "bundled runtime is not Java 21"
jar tf "$APP_JAR" | grep -Fx 'assets/hackregfont.ttf' >/dev/null || fail "bundled font is missing"

"$JPACKAGE" --type dmg --dest "$DIST_DIR" --name "$APP_NAME" --app-version "$VERSION" --app-image "$APP_BUNDLE"
GENERATED_DMG="$(find "$DIST_DIR" -maxdepth 1 -type f -name '*.dmg' -print -quit)"
[[ -n "$GENERATED_DMG" ]] || fail "jpackage did not produce a DMG"
ARTIFACT_NAME="$APP_NAME-$VERSION-$ARCHITECTURE.dmg"
ARTIFACT_PATH="$DIST_DIR/$ARTIFACT_NAME"
mv "$GENERATED_DMG" "$ARTIFACT_PATH"

MOUNT_POINT=""
cleanup() {
  if [[ -n "$MOUNT_POINT" ]]; then
    hdiutil detach "$MOUNT_POINT" -quiet >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
MOUNT_OUTPUT="$(hdiutil attach "$ARTIFACT_PATH" -nobrowse -readonly)"
MOUNT_POINT="$(printf '%s\n' "$MOUNT_OUTPUT" | awk -F '\t' 'NF >= 3 {print $NF}' | tail -n 1)"
[[ -d "$MOUNT_POINT/$APP_NAME.app" ]] || fail "DMG does not contain $APP_NAME.app"
[[ -f "$MOUNT_POINT/$APP_NAME.app/Contents/app/$JAR_NAME" ]] || fail "DMG app is missing the main jar"
[[ -x "$MOUNT_POINT/$APP_NAME.app/Contents/runtime/Contents/Home/bin/java" ]] || fail "DMG app is missing the bundled runtime"
hdiutil detach "$MOUNT_POINT" -quiet
MOUNT_POINT=""

JAR_SHA256="$(sha256_file "$JAR_PATH")"
MODULES_SHA256="$(sha256_file "$PACKAGE_ROOT/runtime-modules.txt")"
DMG_SHA256="$(sha256_file "$ARTIFACT_PATH")"
INPUTS_PATH="$DIST_DIR/$APP_NAME-$VERSION-$ARCHITECTURE.inputs.sha256"
CHECKSUM_PATH="$DIST_DIR/$ARTIFACT_NAME.sha256"
REPORT_PATH="$DIST_DIR/$APP_NAME-$VERSION-$ARCHITECTURE.validation.txt"
printf '%s  %s\n%s  %s\n' "$JAR_SHA256" "$JAR_NAME" "$MODULES_SHA256" runtime-modules.txt > "$INPUTS_PATH"
printf '%s  %s\n' "$DMG_SHA256" "$ARTIFACT_NAME" > "$CHECKSUM_PATH"
printf '%s\n' \
  "artifact=$ARTIFACT_NAME" \
  "artifact_sha256=$DMG_SHA256" \
  "app_bundle=$APP_NAME.app" \
  "architecture=arm64" \
  "bundle_identifier=dev.shed.app" \
  "bundle_version=$VERSION" \
  "input_jar=$JAR_NAME" \
  "input_jar_sha256=$JAR_SHA256" \
  "maven_output_timestamp=$OUTPUT_TIMESTAMP" \
  "maven_build_plan=reproducible" \
  "java_feature=21" \
  "runtime_java_version=$RUNTIME_VERSION" \
  "runtime_modules=$MODULES" \
  "runtime_modules_sha256=$MODULES_SHA256" \
  "installer_contents=validated" \
  "signing=$SIGNING" \
  "notarization=not-requested" > "$REPORT_PATH"

printf 'macOS package ready: %s\nchecksum: %s\ninputs: %s\nvalidation: %s\n' "$ARTIFACT_PATH" "$CHECKSUM_PATH" "$INPUTS_PATH" "$REPORT_PATH"
