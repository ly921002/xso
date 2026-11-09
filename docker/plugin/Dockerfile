# syntax=docker/dockerfile:1
#
# Self-contained image: Paper plus the EssentialsX plugin, with the Xray library
# loaded in-process through JNA.
#
# Runtime layout:
#   /server/paper.jar        Paper server
#   /server/plugins/*.jar    this plugin
#   /server/world/x-*.so     library (runtimeDir defaults to "world")
#   /server/.env             plugin configuration
#   /opt/xso/x-*.so          pristine copy, restored if a mount hides world/
#
# Paper is fetched by build number rather than "latest": a floating tag would
# make the image change without a commit, which breaks rollbacks.

FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="xso plugin" \
      org.opencontainers.image.description="Paper + EssentialsX plugin (VLESS + XHTTP + REALITY)" \
      org.opencontainers.image.source="https://github.com/ly921002/xso"

ARG PAPER_VERSION=1.21.11
ARG PAPER_BUILD=132

RUN set -eux; \
    if command -v apt-get >/dev/null 2>&1; then \
        apt-get update; \
        apt-get install -y --no-install-recommends curl ca-certificates; \
        rm -rf /var/lib/apt/lists/*; \
    fi

WORKDIR /server

RUN set -eux; \
    curl -fsSL --retry 3 --retry-delay 2 \
        -o /server/paper.jar \
        "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}/downloads/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar"; \
    test -s /server/paper.jar

RUN set -eux; \
    groupadd --gid 10001 app 2>/dev/null || true; \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app 2>/dev/null || true; \
    mkdir -p /server/plugins /server/world /opt/xso; \
    chown -R 10001:10001 /server /opt/xso

COPY EssentialsX.jar /server/plugins/EssentialsX.jar
COPY x-linux-amd64.so /opt/xso/x-linux-amd64.so
COPY entrypoint.sh /server/entrypoint.sh

RUN set -eux; \
    cp /opt/xso/x-linux-amd64.so /server/world/x-linux-amd64.so; \
    chmod 0755 /server/entrypoint.sh /opt/xso/x-linux-amd64.so /server/world/x-linux-amd64.so; \
    chown -R 10001:10001 /server /opt/xso

USER 10001:10001

# Both listeners share one JVM, so they must use different TCP ports.
EXPOSE 25565 443

ENV REALITY_PORT=443 \
    MC_PORT=25565 \
    EULA=false \
    XRAY_LIB_NAME=x-linux-amd64.so \
    BINARY_BASE_URL=local:/opt/xso \
    JAVA_TOOL_OPTIONS="-Xms256M -Xmx1024M -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${REALITY_PORT:-443}' || exit 1

ENTRYPOINT ["/server/entrypoint.sh"]
