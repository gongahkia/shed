#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

fail() {
  echo "Linux package error: $*" >&2
  exit 1
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required tool: $1"
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

[[ "$(uname -s)" == "Linux" ]] || fail "Linux is required"
[[ "$(uname -m)" == "x86_64" ]] || fail "Linux x64 is required"
require_tool mvn
require_tool sha256sum
require_tool dpkg-deb
require_tool fakeroot
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
PACKAGE_NAME="shed"
ARCHITECTURE="linux-x64"
JAR_NAME="shed-$VERSION.jar"
JAR_PATH="$REPO_ROOT/target/$JAR_NAME"
[[ -f "$JAR_PATH" ]] || fail "missing packaged jar: $JAR_PATH"

PACKAGE_ROOT="$REPO_ROOT/target/linux-package"
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
  --runtime-image "$RUNTIME_DIR"

APP_DIRECTORY="$APP_IMAGE_DIR/$APP_NAME"
APP_EXECUTABLE="$APP_DIRECTORY/bin/$APP_NAME"
APP_JAR="$APP_DIRECTORY/lib/app/$JAR_NAME"
RUNTIME_JAVA="$APP_DIRECTORY/lib/runtime/bin/java"
[[ -d "$APP_DIRECTORY" ]] || fail "missing app image: $APP_DIRECTORY"
[[ -x "$APP_EXECUTABLE" ]] || fail "missing app executable: $APP_EXECUTABLE"
[[ -f "$APP_JAR" ]] || fail "missing bundled jar: $APP_JAR"
[[ -x "$RUNTIME_JAVA" ]] || fail "missing bundled Java runtime"
RUNTIME_VERSION="$("$RUNTIME_JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
[[ "${RUNTIME_VERSION%%.*}" == "21" ]] || fail "bundled runtime is not Java 21"
jar tf "$APP_JAR" | grep -Fx 'assets/hackregfont.ttf' >/dev/null || fail "bundled font is missing"

"$JPACKAGE" --type deb --dest "$DIST_DIR" --name "$APP_NAME" --app-version "$VERSION" --app-image "$APP_DIRECTORY" \
  --linux-package-name "$PACKAGE_NAME" --linux-app-release 1
GENERATED_DEB="$(find "$DIST_DIR" -maxdepth 1 -type f -name '*.deb' -print -quit)"
[[ -n "$GENERATED_DEB" ]] || fail "jpackage did not produce a DEB"
ARTIFACT_NAME="$APP_NAME-$VERSION-$ARCHITECTURE.deb"
ARTIFACT_PATH="$DIST_DIR/$ARTIFACT_NAME"
mv "$GENERATED_DEB" "$ARTIFACT_PATH"

[[ "$(dpkg-deb --field "$ARTIFACT_PATH" Package)" == "$PACKAGE_NAME" ]] || fail "unexpected Debian package name"
[[ "$(dpkg-deb --field "$ARTIFACT_PATH" Architecture)" == "amd64" ]] || fail "unexpected Debian package architecture"
dpkg-deb --contents "$ARTIFACT_PATH" >/dev/null
INSTALLER_IMAGE="$PACKAGE_ROOT/installer-image"
dpkg-deb --extract "$ARTIFACT_PATH" "$INSTALLER_IMAGE"
INSTALLED_EXECUTABLE="$(find "$INSTALLER_IMAGE" -type f -path "*/bin/$APP_NAME" -print -quit)"
[[ -n "$INSTALLED_EXECUTABLE" ]] || fail "DEB does not contain the application executable"
INSTALLED_ROOT="$(dirname "$(dirname "$INSTALLED_EXECUTABLE")")"
[[ -f "$INSTALLED_ROOT/lib/app/$JAR_NAME" ]] || fail "DEB app is missing the main jar"
[[ -x "$INSTALLED_ROOT/lib/runtime/bin/java" ]] || fail "DEB app is missing the bundled runtime"

JAR_SHA256="$(sha256_file "$JAR_PATH")"
MODULES_SHA256="$(sha256_file "$PACKAGE_ROOT/runtime-modules.txt")"
DEB_SHA256="$(sha256_file "$ARTIFACT_PATH")"
INPUTS_PATH="$DIST_DIR/$APP_NAME-$VERSION-$ARCHITECTURE.inputs.sha256"
CHECKSUM_PATH="$ARTIFACT_PATH.sha256"
REPORT_PATH="$DIST_DIR/$APP_NAME-$VERSION-$ARCHITECTURE.validation.txt"
printf '%s  %s\n%s  %s\n' "$JAR_SHA256" "$JAR_NAME" "$MODULES_SHA256" runtime-modules.txt > "$INPUTS_PATH"
printf '%s  %s\n' "$DEB_SHA256" "$ARTIFACT_NAME" > "$CHECKSUM_PATH"
printf '%s\n' \
  "artifact=$ARTIFACT_NAME" \
  "artifact_sha256=$DEB_SHA256" \
  "app_directory=$APP_NAME" \
  "architecture=x64" \
  "bundle_version=$VERSION" \
  "debian_package=$PACKAGE_NAME" \
  "input_jar=$JAR_NAME" \
  "input_jar_sha256=$JAR_SHA256" \
  "maven_output_timestamp=$OUTPUT_TIMESTAMP" \
  "maven_build_plan=reproducible" \
  "java_feature=21" \
  "runtime_java_version=$RUNTIME_VERSION" \
  "runtime_modules=$MODULES" \
  "runtime_modules_sha256=$MODULES_SHA256" \
  "installer_contents=validated" \
  "signing=not-applicable" \
  "notarization=not-applicable" > "$REPORT_PATH"

printf 'Linux package ready: %s\nchecksum: %s\ninputs: %s\nvalidation: %s\n' "$ARTIFACT_PATH" "$CHECKSUM_PATH" "$INPUTS_PATH" "$REPORT_PATH"
