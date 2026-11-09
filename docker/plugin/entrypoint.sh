#!/usr/bin/env bash
#
# Entrypoint for the Paper + plugin image.
#
#   1. require an explicit EULA acceptance,
#   2. write server.properties once so the Minecraft port is predictable,
#   3. seed .env from the environment,
#   4. restore the JNA library if a mount hid world/,
#   5. exec Paper so the JVM is PID 1 and gets signals directly.
#
# REALITY_PORT and MC_PORT must differ. Both listeners live in the same JVM, and
# a collision surfaces as "bind: address already in use" followed by a plugin
# that still claims success -- the failure mode this whole project exists to
# make visible.

set -euo pipefail

SERVER_DIR=/server
ENV_FILE="$SERVER_DIR/.env"
LIB_DIR=/opt/xso

die() { echo "xso: $*" >&2; exit 1; }

KEYS="UUID REALITY_PORT REALITY_TARGET REALITY_SNI REALITY_SHORT_ID
      XHTTP_PATH XHTTP_MODE VLESS_DECRYPTION VLESS_ENCRYPTION VLESS_FLOW
      NATIVE_MODE BINARY_BASE_URL XRAY_LIB_NAME XRAY_SHA256
      KEEP_BINARIES ALLOW_INSECURE SHOW_LOG QUIET
      CLEANUP_MODE CLEANUP_DELAY FAKE_MC_PORT"

# ---- 1. EULA ---------------------------------------------------------------
if [ "${EULA:-false}" != "true" ]; then
    echo "xso: EULA is not accepted; pass -e EULA=true to start Paper" >&2
    exit 1
fi
echo "eula=true" > "$SERVER_DIR/eula.txt"

# ---- 2. server.properties --------------------------------------------------
# Paper rewrites this file, so it is written once and then left alone; deleting
# it is how you ask the container to regenerate it from the current env.
if [ ! -f "$SERVER_DIR/server.properties" ]; then
    cat > "$SERVER_DIR/server.properties" <<EOF
server-port=${MC_PORT:-25565}
online-mode=${MC_ONLINE_MODE:-true}
motd=${MC_MOTD:-A Minecraft Server}
max-players=${MC_MAX_PLAYERS:-20}
enable-rcon=false
EOF
    echo "xso: wrote server.properties (port ${MC_PORT:-25565})" >&2
fi

# ---- 3. configuration ------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
    echo "xso: no .env found, generating one from the environment" >&2
    : > "$ENV_FILE"
    for key in $KEYS; do
        value="${!key-}"
        [ -n "${value:-}" ] && echo "$key=$value" >> "$ENV_FILE"
    done
    chmod 600 "$ENV_FILE" 2>/dev/null || true
fi

grep -q '^REALITY_PORT=' "$ENV_FILE" 2>/dev/null || echo "REALITY_PORT=443" >> "$ENV_FILE"

# ---- 4. library ------------------------------------------------------------
lib=$(grep '^XRAY_LIB_NAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || true)
lib="${lib:-${XRAY_LIB_NAME:-x-linux-amd64.so}}"
if [ ! -f "$SERVER_DIR/world/$lib" ]; then
    if [ -f "$LIB_DIR/$lib" ]; then
        cp "$LIB_DIR/$lib" "$SERVER_DIR/world/$lib"
        chmod 0755 "$SERVER_DIR/world/$lib"
        echo "xso: restored $lib into world/" >&2
    else
        die "missing $SERVER_DIR/world/$lib and no pristine copy in $LIB_DIR"
    fi
fi

# ---- 5. port sanity --------------------------------------------------------
mc_port=$(grep '^server-port=' "$SERVER_DIR/server.properties" | cut -d= -f2-)
reality_port=$(grep '^REALITY_PORT=' "$ENV_FILE" | cut -d= -f2-)
if [ "${mc_port:-25565}" = "${reality_port:-443}" ]; then
    die "MC_PORT and REALITY_PORT are both ${mc_port}; they share one JVM"
fi

echo "xso: starting Paper (mc ${mc_port:-25565}, reality ${reality_port:-443})" >&2
exec java $JAVA_TOOL_OPTIONS -jar "$SERVER_DIR/paper.jar" --nogui "$@"
