#!/usr/bin/env sh
# Build helper: this branch targets Java 21 (Paper 1.21.11).
# Kept so `./build.sh <goal>` works the same on both branches.
set -e
JAVA_HOME="${JAVA_HOME_21:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME
exec mvn "$@"
