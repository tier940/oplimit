#!/usr/bin/env bash
# Enforces the constraints on common/ by compiling it the way both builds will.
#
# common/ is compiled into two very different projects, and a mistake there does not show up until
# someone runs a full Forge build of the *other* version. This compiles the shared sources twice --
# once as 1.12.2 sees them, once as 1.20.1 -- with only the dependencies each version actually
# provides. That mechanically enforces every rule in CONTRIBUTING.md:
#
#   * no Minecraft types      -- no Minecraft on the classpath, so any reference fails
#   * Java 8 API only         -- --release 8 rejects anything newer (1.12.2 builds via Jabel)
#   * Gson 2.8 compatibility  -- compiled against the exact Gson each version ships
#
# org.jetbrains:annotations is provided because both builds declare it, so common/ may use it.
#
# Dependencies come from Maven Central and are cached in build/check-common/.
# The authlib stub lives here, outside any srcDir, so real builds never compile it.

set -euo pipefail

cd "$(dirname "$0")/.."

CACHE="build/check-common"
SRC="common/src/main/java"
GSON_OLD=2.8.0      # shipped by 1.12.2
GSON_NEW=2.10.1     # shipped by 1.20.1
LOG4J=2.17.1
ANNOTATIONS=26.1.0  # declared by both builds

if ! command -v javac >/dev/null; then
    echo "javac not found. A JDK 17 or newer is required." >&2
    exit 1
fi

mkdir -p "$CACHE/libs"

fetch() { # url -> cached path on stdout
    local url=$1 dest="$CACHE/libs/$(basename "$1")"
    if [ ! -f "$dest" ]; then
        echo "  fetching $(basename "$url")" >&2
        curl -sSfL --max-time 120 -o "$dest" "$url"
    fi
    echo "$dest"
}

M2=https://repo1.maven.org/maven2
gson_old=$(fetch "$M2/com/google/code/gson/gson/$GSON_OLD/gson-$GSON_OLD.jar")
gson_new=$(fetch "$M2/com/google/code/gson/gson/$GSON_NEW/gson-$GSON_NEW.jar")
log4j=$(fetch "$M2/org/apache/logging/log4j/log4j-api/$LOG4J/log4j-api-$LOG4J.jar")
annotations=$(fetch "$M2/org/jetbrains/annotations/$ANNOTATIONS/annotations-$ANNOTATIONS.jar")

# Minimal authlib stand-in: GameProfile is the one non-Minecraft Mojang type common/ may use, and
# pulling real authlib would mean dragging in a Minecraft library repository for two methods.
stub="$CACHE/stub"
mkdir -p "$stub/com/mojang/authlib"
cat > "$stub/com/mojang/authlib/GameProfile.java" <<'EOF'
package com.mojang.authlib;

import java.util.UUID;

public class GameProfile {

    public GameProfile(UUID id, String name) {}

    public UUID getId() { return null; }

    public String getName() { return null; }
}
EOF

sources=$(find "$SRC" -name '*.java')
status=0

check() { # label, release, gson jar
    local label=$1 release=$2 gson=$3 out="$CACHE/out-$2"
    echo "==> $label"
    rm -rf "$out"
    if javac -d "$out" -proc:none -Xlint:-options --release "$release" \
            -cp "$gson:$log4j:$annotations" \
            "$stub/com/mojang/authlib/GameProfile.java" $sources; then
        echo "    OK"
    else
        echo "    FAILED -- see CONTRIBUTING.md for what common/ may depend on" >&2
        status=1
    fi
}

check "1.12.2: Java 8 API, Gson $GSON_OLD" 8 "$gson_old"
check "1.20.1: Java 17, Gson $GSON_NEW" 17 "$gson_new"

exit $status
