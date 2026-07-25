# JVM / bridge build image. Bundles Maven + Temurin JDK 17 (matches the locked toolchain).
# Android :app builds run on the host, not here (see docs/build-environment.md).
FROM maven:3.9-eclipse-temurin-17

# Tools used by later layers (the upstream mage build) and general build ergonomics.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Gradle comes from the repo's committed wrapper; Maven is on PATH from the base image.
