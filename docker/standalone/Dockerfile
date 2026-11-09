# syntax=docker/dockerfile:1
#
# Standalone image: the proxy on its own, no Minecraft server underneath.
#
# Runtime layout:
#   /app/server.jar        executable entry point
#   /app/world/x-*.so      library JNA loads (runtimeDir = $PWD/world)
#   /app/.env              configuration
#   /opt/xso/x-*.so        pristine copy, restored if a bind mount hides world/
#
# BinaryStore resolves BINARY_BASE_URL=local:/opt/xso to
# "/opt/xso/<XRAY_LIB_NAME>", so that path is the one that matters -- not /app.
# The jar and the .so are supplied by CI; nothing is downloaded at build time.

FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="xso standalone" \
      org.opencontainers.image.description="VLESS + XHTTP + REALITY proxy; no Minecraft listener" \
      org.opencontainers.image.source="https://github.com/ly921002/xso"

RUN set -eux; \
    if command -v apt-get >/dev/null 2>&1; then \
        apt-get update; \
        apt-get install -y --no-install-recommends curl ca-certificates; \
        rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /app

# Unprivileged: 10001 avoids colliding with the common 1000 on host mounts.
RUN set -eux; \
    groupadd --gid 10001 app 2>/dev/null || true; \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app 2>/dev/null || true; \
    mkdir -p /app/world /opt/xso; \
    chown -R 10001:10001 /app /opt/xso

COPY server.jar /app/server.jar
COPY x-linux-amd64.so /opt/xso/x-linux-amd64.so
COPY entrypoint.sh /app/entrypoint.sh

# Seed world/ from the pristine copy so a fresh container works without a mount.
RUN set -eux; \
    cp /opt/xso/x-linux-amd64.so /app/world/x-linux-amd64.so; \
    chmod 0755 /app/entrypoint.sh /opt/xso/x-linux-amd64.so /app/world/x-linux-amd64.so; \
    chown -R 10001:10001 /app /opt/xso

USER 10001:10001

# 443: the least conspicuous REALITY port, and the one whose TLS fingerprint
# matches an ordinary HTTPS host.
EXPOSE 443

ENV REALITY_PORT=443 \
    XRAY_LIB_NAME=x-linux-amd64.so \
    BINARY_BASE_URL=local:/opt/xso \
    JAVA_TOOL_OPTIONS="-Xms64M -Xmx256M -XX:+UseSerialGC"

# REALITY only speaks TLS to an authenticated client, so an HTTP probe is meant
# to fail. A successful TCP connect is the real signal that the proxy is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${REALITY_PORT:-443}' || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
