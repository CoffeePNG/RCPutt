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

# All three dependencies target Java 25, same as RCPuttPutt. Reuse build.sh's JDK detection so they
# are not built with whatever JDK happens to be on PATH - a mismatch here produces an artifact that
# fails at runtime rather than at build time.
if JDK25="$("$(dirname "$0")/build.sh" --java-home 2>/dev/null)" && [ -n "$JDK25" ]; then
    export JAVA_HOME="$JDK25"
    echo "Using JAVA_HOME=$JAVA_HOME"
fi

LOCAL_REPO="${LOCAL_REPO:-$HOME/.m2/repository}"

# Where each dependency lands, so a build can be checked rather than assumed.
artifact_path() {
    case "$1" in
        rcplatform) echo "net/republicraft/platform/rcplatform-api" ;;
        rcui)       echo "net/republicraft/RCUI" ;;
        rcparties)  echo "gg/rc/rcparties-api" ;;
        *)          echo "" ;;
    esac
}

# Drops Maven's cached "could not find this artifact" markers for the RC coordinates.
#
# A plain `mvn package` before these are installed fails, and Maven remembers the miss - that is the
# "was not found ... during a previous attempt. This failure was cached in the local repository and
# resolution is not reattempted until the update interval has elapsed" message. Installing the
# artifact afterwards does not always clear it, so a build can keep failing on a dependency that is
# now sitting in the local repository. Deleting the markers costs nothing: they are a cache, and
# Maven rewrites them as needed.
clear_resolver_cache() {
    [ -d "$LOCAL_REPO" ] || return 0
    local found=0
    for root in "$LOCAL_REPO/net/republicraft" "$LOCAL_REPO/gg/rc"; do
        [ -d "$root" ] || continue
        local n
        n="$(find "$root" \( -name '*.lastUpdated' -o -name 'resolver-status.properties' \) -type f -print -delete 2>/dev/null | wc -l)"
        found=$((found + n))
    done
    [ "$found" -gt 0 ] && echo "==> cleared $found cached resolution failure(s) from $LOCAL_REPO"
    return 0
}

# A build that "succeeds" without producing the artifact leaves the next dependency failing on a
# missing jar, several confusing steps away from the cause. Check here instead.
assert_installed() {
    local name="$1" relative
    relative="$(artifact_path "$name")"
    [ -n "$relative" ] || return 0
    local dir="$LOCAL_REPO/$relative" jar
    jar="$(find "$dir" -name '*.jar' -type f 2>/dev/null | head -1)"
    if [ -z "$jar" ]; then
        echo >&2
        echo "install-deps.sh: $name built, but nothing was installed to $dir" >&2
        echo "  Every later build will fail on this artifact. Check the Maven output above:" >&2
        echo "  a module that was skipped, or a build that stopped before its install phase." >&2
        exit 1
    fi
    echo "    installed: $jar"
}

build() {
    local name="$1" url="$2" branch="${3:-}"
    local dir="$WORKDIR/$name"
    if [ -d "$dir/.git" ]; then
        # Deliberately NOT pulling: this may be a working clone with local changes, and silently
        # updating someone's checkout is worse than building what they have. But report exactly
        # what is being built, because installing a stale artifact fails much later and much more
        # confusingly than it does here.
        local head desc dirty=""
        head="$(git -C "$dir" rev-parse --short HEAD 2>/dev/null || echo '?')"
        desc="$(git -C "$dir" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
        [ -n "$(git -C "$dir" status --porcelain 2>/dev/null)" ] && dirty=" (uncommitted changes)"
        echo "==> $name: using existing clone at $dir"
        echo "    building $desc @ $head$dirty — not pulled; update it yourself if that is stale"
    else
        echo "==> $name: cloning into $dir"
        git clone "$url" "$dir"
    fi
    if [ -n "$branch" ]; then
        git -C "$dir" fetch origin "$branch"
        git -C "$dir" checkout "$branch"
    fi
    # NOT -q: quiet mode suppresses download and module progress, so a first build (which fetches
    # paper-api and a dozen modules) sits silent for minutes and looks like a hang. -B keeps the
    # output non-interactive and free of ANSI progress bars.
    echo "==> $name: mvn install (first run downloads a lot; this can take several minutes)"
    (cd "$dir" && mvn -B install -DskipTests)
    assert_installed "$name"
}

# Before anything else, drop any cached resolution failures left by an earlier `mvn package`.
clear_resolver_cache

# Order matters: RCUI compiles against rcplatform-api.
build rcplatform "$RCPLATFORM_URL"
build rcui       "$RCUI_URL"
build rcparties  "$RCPARTIES_URL" "$RCPARTIES_BRANCH"

echo
echo "Done. RCPuttPutt should now build with ./build.sh package"
