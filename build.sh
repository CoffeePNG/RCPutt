#!/usr/bin/env sh
# Build helper: RCPuttPutt targets Java 25, which is usually not the shell default.
#
# Finds a JDK 25 rather than hardcoding a path, because the checkout is built on both macOS and
# Linux and the layouts differ (macOS has no /usr/lib/jvm at all).
#
# Override explicitly with:  JAVA_HOME_25=/path/to/jdk-25 ./build.sh package
set -e

is_jdk25() {
    [ -n "$1" ] && [ -x "$1/bin/javac" ] &&
        "$1/bin/javac" -version 2>&1 | grep -q ' 25\.'
}

found=""

# 1. Explicit override always wins.
if [ -n "${JAVA_HOME_25:-}" ]; then
    found="$JAVA_HOME_25"
    if ! is_jdk25 "$found"; then
        echo "build.sh: JAVA_HOME_25 ($found) is not a JDK 25." >&2
        exit 1
    fi
fi

# 2. An already-correct JAVA_HOME is left alone.
if [ -z "$found" ] && is_jdk25 "${JAVA_HOME:-}"; then
    found="$JAVA_HOME"
fi

# 3. macOS keeps JDKs where only java_home knows about them.
if [ -z "$found" ] && [ -x /usr/libexec/java_home ]; then
    candidate="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    is_jdk25 "$candidate" && found="$candidate"
fi

# 4. Common Linux locations.
if [ -z "$found" ]; then
    for candidate in /usr/lib/jvm/java-25-openjdk-* /usr/lib/jvm/java-25-* \
                     /usr/lib/jvm/jdk-25* /opt/java/jdk-25* /opt/jdk-25*; do
        if is_jdk25 "$candidate"; then
            found="$candidate"
            break
        fi
    done
fi

if [ -z "$found" ]; then
    echo "build.sh: could not find a JDK 25." >&2
    echo "  RCPuttPutt targets Java 25 (Purpur 26.2)." >&2
    echo "  macOS:  brew install openjdk@25    then re-run" >&2
    echo "  Linux:  apt install openjdk-25-jdk  (or equivalent)" >&2
    echo "  Or point at one directly: JAVA_HOME_25=/path/to/jdk-25 ./build.sh $*" >&2
    exit 1
fi

JAVA_HOME="$found"
export JAVA_HOME

# `build.sh --java-home` just reports the JDK it found, so install-deps.sh can build the RC
# dependencies with the same one rather than whatever happens to be on PATH.
if [ "${1:-}" = "--java-home" ]; then
    echo "$JAVA_HOME"
    exit 0
fi

echo "build.sh: using JAVA_HOME=$JAVA_HOME"
exec mvn "$@"
