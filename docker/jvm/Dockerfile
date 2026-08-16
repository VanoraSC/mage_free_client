# JVM / bridge build image. Bundles Maven + Temurin JDK 17 (matches the locked toolchain).
# Android :app builds run on the host, not here (see docs/build-environment.md).
FROM maven:3.9-eclipse-temurin-17

# Tools used by later layers (the upstream mage build) and general build ergonomics.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- Upstream mage layer (story 0021) ---
# Clone is its own layer so it stays cached across builds independent of the install step below —
# previously the two were one RUN, so any edit to the install command (flags, etc.) busted the
# clone too and re-fetched the whole magefree/mage repo for no reason. Split, a MAGE_REF bump is
# the only thing that invalidates this layer.
ARG MAGE_REPO=https://github.com/magefree/mage
ARG MAGE_REF=e0fe4b6f6a
RUN git clone --filter=tree:0 "${MAGE_REPO}" /tmp/mage \
    && git -C /tmp/mage checkout "${MAGE_REF}"

# Build and install org.mage:mage-common (the XMage client session library) into the image's
# local Maven repo, so the bridge (story 0003) can resolve it via mavenLocal(). Only Mage.Common
# and its reactor dependencies are built (-pl Mage.Common -am), so the huge card database
# (Mage.Sets) is skipped. The Java-1.8-era upstream builds cleanly on JDK 17 (verified).
RUN cd /tmp/mage && mvn -q -pl Mage.Common -am -DskipTests -B install \
    && rm -rf /tmp/mage

WORKDIR /workspace

# Gradle comes from the repo's committed wrapper; Maven is on PATH from the base image.
