#!/usr/bin/env bash
#
# Entrypoint for the standalone image.
#
#   1. seed /app/.env from the environment when none is mounted,
#   2. restore the JNA library if a bind mount hid world/,
#   3. exec the jar so the proxy is PID 1 and gets signals directly.

set -euo pipefail

APP_DIR=/app
ENV_FILE="$APP_DIR/.env"
LIB_DIR=/opt/xso

die() { echo "xso: $*" >&2; exit 1; }

# Only keys the caller actually set are written. Leaving UUID and
# REALITY_SHORT_ID blank is deliberate: the plugin generates them and persists
# to .uuid / .shortid, so the node keeps working across restarts.
KEYS="UUID REALITY_PORT REALITY_TARGET REALITY_SNI REALITY_SHORT_ID
      XHTTP_PATH XHTTP_MODE VLESS_DECRYPTION VLESS_ENCRYPTION VLESS_FLOW
      NATIVE_MODE BINARY_BASE_URL XRAY_LIB_NAME XRAY_SHA256
      KEEP_BINARIES ALLOW_INSECURE SHOW_LOG QUIET
      CLEANUP_MODE CLEANUP_DELAY FAKE_MC_PORT"

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

lib=$(grep '^XRAY_LIB_NAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || true)
lib="${lib:-${XRAY_LIB_NAME:-x-linux-amd64.so}}"

if [ ! -f "$APP_DIR/world/$lib" ]; then
    if [ -f "$LIB_DIR/$lib" ]; then
        cp "$LIB_DIR/$lib" "$APP_DIR/world/$lib"
        chmod 0755 "$APP_DIR/world/$lib"
        echo "xso: restored $lib into world/" >&2
    else
        die "missing $APP_DIR/world/$lib and no pristine copy in $LIB_DIR"
    fi
fi

reality_port=$(grep '^REALITY_PORT=' "$ENV_FILE" | cut -d= -f2-)
echo "xso: starting (port ${reality_port:-443}, library $lib)" >&2
exec java $JAVA_TOOL_OPTIONS -jar "$APP_DIR/server.jar" "$@"
