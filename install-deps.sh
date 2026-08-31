#!/usr/bin/env bash
# Installs RCPuttPutt's RC dependencies into your local ~/.m2.
#
# None of them are published to a public Maven repository, so a plain `mvn package` fails with
# "Could not find artifact net.republicraft:RCUI / rcplatform-api / rcparties-api". They have to be
# built locally first, and in this order: RCPlatform provides rcplatform-api, which RCUI needs.
#
# RCPlatform and RCUI are Bobo's, part of the wider RepubliCraft framework — this only builds them,
# it never modifies them. Point the *_URL variables at whichever remote you use.
set -euo pipefail

RCPLATFORM_URL="${RCPLATFORM_URL:-https://github.com/CoffeePNG/rcplatform}"
RCUI_URL="${RCUI_URL:-https://github.com/CoffeePNG/rcui}"
RCPARTIES_URL="${RCPARTIES_URL:-https://github.com/CoffeePNG/rcparties}"

# RCParties is branched per target. Its API is compiled to each branch's bytecode level, so the
# 26.2 artifact will not load on Java 21 and vice versa. Both branches install to the same
# coordinates, so whichever you build LAST is the one in your .m2.
RCPARTIES_BRANCH="${RCPARTIES_BRANCH:-claude/running-agentic-i3mb79}"

WORKDIR="${WORKDIR:-$(cd "$(dirname "$0")/.." && pwd)}"

build() {
    local name="$1" url="$2" branch="${3:-}"
    local dir="$WORKDIR/$name"
    if [ -d "$dir/.git" ]; then
        echo "==> $name: using existing clone at $dir"
    else
        echo "==> $name: cloning into $dir"
        git clone "$url" "$dir"
    fi
    if [ -n "$branch" ]; then
        git -C "$dir" fetch origin "$branch"
        git -C "$dir" checkout "$branch"
    fi
    echo "==> $name: mvn install"
    (cd "$dir" && mvn -q install -DskipTests)
}

# Order matters: RCUI compiles against rcplatform-api.
build rcplatform "$RCPLATFORM_URL"
build rcui       "$RCUI_URL"
build rcparties  "$RCPARTIES_URL" "$RCPARTIES_BRANCH"

echo
echo "Done. RCPuttPutt should now build with ./build.sh package"
