# syntax=docker/dockerfile:1
# Reference XMage server (story 0022) — the server the bridge's integration tests connect to.
# Built on the jvm build image (warm Maven cache from story 0021) so only the additional server
# modules + the card database (Mage.Sets) are compiled. Pinned to the same upstream commit.
FROM mage-free-client/build:latest AS build
ARG MAGE_REPO=https://github.com/magefree/mage
ARG MAGE_REF=e0fe4b6f6a
# Expensive, cached layer: clone the pinned ref and full-reactor install — every module incl. the
# card database (Mage.Sets) and the runtime plugins the server distribution bundles.
#
# The cache mount only speeds up *rebuilds* of this layer (a MAGE_REF bump, say) — it must not
# persist into the image, and it does not: only /opt/xmage (staged below) is copied into the final
# stage, so anything Maven downloads under /root/.m2 here is pure build-time cache, safe to keep out
# of the image layer entirely (unlike jvm/Dockerfile's own mvn install, which deliberately bakes
# mage-common into /root/.m2 for downstream images to inherit — do not add a cache mount there).
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    git clone --filter=tree:0 "${MAGE_REPO}" /tmp/mage \
    && git -C /tmp/mage checkout "${MAGE_REF}" \
    && (cd /tmp/mage && mvn -q -DskipTests -B install)
# Cheap layer: package the server + assemble the distribution in ONE invocation (so the project's
# own mage-server jar is attached and included — the assembly plugin isn't bound to a phase), then
# stage the unpacked distribution. Kept separate so assembly/runtime tweaks don't re-run the install.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    cd /tmp/mage \
    && mvn -q -pl Mage.Server -DskipTests -B package assembly:single \
    && mkdir -p /opt/xmage \
    && unzip -q Mage.Server/target/mage-server.zip -d /opt/xmage

FROM eclipse-temurin:17-jre
COPY --from=build /opt/xmage /opt/xmage
# Local reference config: authentication off (sign in with any username), bind all interfaces.
# The config comes from the distribution zip itself (the assembly's filtered `release/` fileSet), so
# its plugin `jar=` names carry the version — matching the versioned jars the assembly stages in
# plugins/. Do NOT overwrite it with Mage.Server/config/config.xml: that is the dev/IDE config with
# UNversioned jar names, and every plugin load then misses (no deck validators, no Human/AI player
# factories, no Two Player Duel), which makes createTable fail server-side.
RUN sed -i -E 's/authenticationActivated="[^"]*"/authenticationActivated="false"/; s/serverAddress="[^"]*"/serverAddress="0.0.0.0"/' \
    /opt/xmage/config/config.xml
WORKDIR /opt/xmage
EXPOSE 17171
# XMage's JBoss-serialization stack reflects into JDK internals during the client/server handshake;
# on JDK 17 the module system blocks that unless these packages are opened, so without the flags the
# server accepts the TCP socket then drops it mid-handshake (clients see an EOFException / "wrong
# client-server protocol"). This is the headless subset of the flags XMage's own startServer scripts
# pass on Java 17+ (the client mirrors them; see :bridge test task). Keep the two sets in sync.
ENTRYPOINT ["sh", "-c", "exec java -Xmx1024m \
    --add-opens=java.base/java.io=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    -jar lib/mage-server-1.4.60.jar"]
