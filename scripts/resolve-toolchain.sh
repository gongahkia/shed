#!/usr/bin/env sh
set -eu

tool=${1:-java}

find_executable() {
    case "$1" in
        */*) if [ -x "$1" ]; then printf '%s\n' "$1"; fi ;;
        *) command -v "$1" 2>/dev/null || true ;;
    esac
}

is_java_21() {
    [ -x "$1" ] || return 1
    version=$("$1" -version 2>&1) || return 1
    case "$version" in
        *'version "21.'*|*'version "21"'*|*'version "21-'*) return 0 ;;
        *) return 1 ;;
    esac
}

java_candidate() {
    candidate=$(find_executable "$1")
    if [ -n "$candidate" ] && is_java_21 "$candidate"; then
        printf '%s\n' "$candidate"
        return 0
    fi
    return 1
}

resolve_java() {
    if [ -n "${JAVA:-}" ]; then
        java_candidate "$JAVA" && return 0
        echo "shed: JAVA must point to a working Java 21 executable: $JAVA" >&2
        return 1
    fi
    if [ -n "${JAVA_HOME:-}" ]; then
        java_candidate "$JAVA_HOME/bin/java" && return 0
        echo "shed: JAVA_HOME must point to a working JDK 21: $JAVA_HOME" >&2
        return 1
    fi
    java_candidate java && return 0
    if [ -x /usr/libexec/java_home ]; then
        mac_home=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
        if [ -n "$mac_home" ]; then
            java_candidate "$mac_home/bin/java" && return 0
        fi
    fi
    for home in \
        "$HOME/.local/share/shed-tools/jdk-21" \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
        java_candidate "$home/bin/java" && return 0
    done
    echo "shed: Java 21 is required. Install a JDK 21 or set JAVA_HOME to its directory." >&2
    return 1
}

case "$tool" in
    java) resolve_java ;;
    home)
        java=$(resolve_java) || exit 1
        home=$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')
        if [ -z "$home" ]; then
            echo "shed: could not determine Java home for $java" >&2
            exit 1
        fi
        printf '%s\n' "$home"
        ;;
    maven)
        if [ -n "${MVN:-}" ]; then
            maven=$(find_executable "$MVN")
        else
            maven=$(find_executable mvn)
            if [ -z "$maven" ]; then
                maven=$(find_executable "$HOME/.local/share/shed-tools/maven/bin/mvn")
            fi
        fi
        if [ -z "$maven" ]; then
            echo "shed: Maven is required to build. Install Maven or set MVN to its executable." >&2
            exit 1
        fi
        printf '%s\n' "$maven"
        ;;
    *) echo "Usage: $0 java|home|maven" >&2; exit 2 ;;
esac
