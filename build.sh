#!/usr/bin/env sh
# Build helper: RCPuttPutt targets Java 25 (Purpur 26.2), which is not the container default.
set -e
JAVA_HOME="${JAVA_HOME_25:-/usr/lib/jvm/java-25-openjdk-amd64}"
export JAVA_HOME
exec mvn "$@"
