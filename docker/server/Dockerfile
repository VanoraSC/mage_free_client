# Reference XMage server (story 0022) — the server the bridge's integration tests connect to.
# Built on the jvm build image (warm Maven cache from story 0021) so only the additional server
# modules + the card database (Mage.Sets) are compiled. Pinned to the same upstream commit.
FROM mage-free-client/build:latest AS build
ARG MAGE_REPO=https://github.com/magefree/mage
ARG MAGE_REF=e0fe4b6f6a
# Expensive, cached layer: clone the pinned ref and full-reactor install — every module incl. the
# card database (Mage.Sets) and the runtime plugins the server distribution bundles.
RUN git clone --filter=tree:0 "${MAGE_REPO}" /tmp/mage \
    && git -C /tmp/mage checkout "${MAGE_REF}" \
    && (cd /tmp/mage && mvn -q -DskipTests -B install)
# Cheap layer: package the server + assemble the distribution in ONE invocation (so the project's
# own mage-server jar is attached and included — the assembly plugin isn't bound to a phase), then
# stage the unpacked distribution. Kept separate so assembly/runtime tweaks don't re-run the install.
RUN cd /tmp/mage \
    && mvn -q -pl Mage.Server -DskipTests -B package assembly:single \
    && mkdir -p /opt/xmage /opt/xmage/config \
    && unzip -q Mage.Server/target/mage-server.zip -d /opt/xmage \
    && cp Mage.Server/config/config.xml /opt/xmage/config/config.xml

FROM eclipse-temurin:17-jre
COPY --from=build /opt/xmage /opt/xmage
# Local reference config: authentication off (sign in with any username), bind all interfaces.
RUN sed -i -E 's/authenticationActivated="[^"]*"/authenticationActivated="false"/; s/serverAddress="[^"]*"/serverAddress="0.0.0.0"/' \
    /opt/xmage/config/config.xml
WORKDIR /opt/xmage
EXPOSE 17171
ENTRYPOINT ["sh", "-c", "exec java -Xmx1024m -jar lib/mage-server-1.4.60.jar"]
