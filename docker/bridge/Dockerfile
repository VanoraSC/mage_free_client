# Runnable bridge service (story 0045) — the Ktor app from `:bridge`, pointed at the reference
# XMage server, so the **app's** networking stack (`:core:network`'s KtorBridgeClient) has something
# real to talk to. Until now `:bridge` was only ever *built* and *tested* in a container; nothing ran it.
#
# Layered like `server/Dockerfile`: a build stage on the shared build image (whose /root/.m2 already
# carries org.mage:mage-common from story 0021 — the bridge resolves it via mavenLocal()), then a thin
# eclipse-temurin JRE runtime carrying only the assembled distribution.
#
# The build context is the **repo root** (see docker-compose.yml), so the COPYs below are repo-relative.
FROM mage-free-client/build:latest AS build

# Same switch the `build` compose service sets: there is no Android SDK in this image, so
# settings.gradle.kts must skip the Android modules or configuration fails. Only :protocol, :bridge and
# :core:model are needed to assemble the bridge, and they are the only sources copied in.
ENV MAGE_JVM_ONLY=1

WORKDIR /workspace
# Build definition first (its own layer, so a source-only change does not re-resolve the whole build).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY build-logic/ build-logic/
# The three modules `:bridge` needs — :core:model is included unconditionally by settings.gradle.kts.
COPY protocol/ protocol/
COPY core/model/ core/model/
COPY bridge/ bridge/
# `installDist` (not `run`): the application plugin's start script honours JAVA_OPTS, which is how the
# JDK-17 `--add-opens` flags below reach the process without touching bridge/build.gradle.kts.
RUN sh ./gradlew --no-daemon --console=plain :bridge:installDist

FROM eclipse-temurin:17-jre
COPY --from=build /workspace/bridge/build/install/bridge /opt/bridge
# XMage's JBoss-Remoting/JBoss-serialization client stack (which the bridge's XMageUpstreamSession
# drives through SessionImpl) reflects into JDK internals during the connect/auth handshake; on JDK 17
# the module system blocks that unless these packages are opened, so without the flags the upstream
# connect fails mid-handshake ("Wrong java version" / EOFException) and every login would be reported
# as an auth failure. This is the same set the `:bridge` test task passes and the mirror of the
# server image's own entrypoint flags — keep all three in sync.
ENV JAVA_OPTS="\
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.text=ALL-UNNAMED"
# Ktor reads the port from application.conf, overridable with BRIDGE_PORT; the upstream server comes
# from XMAGE_UPSTREAM (UpstreamTarget.fromEnv, default xmage-server:17171). Both are set in compose.
EXPOSE 8080
ENTRYPOINT ["/opt/bridge/bin/bridge"]
