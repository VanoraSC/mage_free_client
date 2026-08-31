# Card catalog generator. Emits the normalized cards.sqlite bundle consumed by
# :core:cards. Reproducible: it is assembled entirely from two committed images —
#   - mage-free-client/build        (docker/jvm/Dockerfile)     -> JDK 17 + javac + java
#   - mage-free-client/xmage-server (docker/server/Dockerfile)  -> the full XMage runtime classpath
#                                                                  (/opt/xmage/lib, incl. mage-sets,
#                                                                   h2, ormlite, sqlite-jdbc), built
#                                                                   from the pinned MAGE_REF.
# Both are pinned to MAGE_REF (default e0fe4b6f6a, XMage 1.4.60). Rebuild those images to bump XMage.
#
# See README.md for the exact regeneration command.

# The complete XMage server runtime jars (has mage-sets + the card classes + h2/ormlite/sqlite-jdbc).
FROM mage-free-client/xmage-server:latest AS xmage

# The JDK build image (has javac + java + git). We compile + run the generators here.
FROM mage-free-client/build:latest AS generator
COPY --from=xmage /opt/xmage/lib /xmage-lib

# The pinned upstream ref, recorded into both bundles for provenance.
ARG MAGE_REPO=https://github.com/magefree/mage
ARG MAGE_REF=e0fe4b6f6a
ENV MAGE_REF=${MAGE_REF}

# XMage keeps the per-format legality definitions (legal sets, banned/restricted lists,
# constraints) in mage.deck.* subclasses of Constructed that are NOT in the pinned org.mage:* jars —
# they live only in upstream source. Ephemerally, blob-less sparse-clone JUST that source path at the
# pinned ref (the same magefree/mage repo + ref the jvm/server images build from), so the format data
# is generated from XMage reproducibly with no GPL source committed into this repo. FormatGenerator
# compiles these against the pinned jars, instantiates each format, and reads its data reflectively.
RUN git clone --filter=blob:none --no-checkout "${MAGE_REPO}" /tmp/xsrc \
    && git -C /tmp/xsrc sparse-checkout set Mage.Server.Plugins/Mage.Deck.Constructed/src/mage/deck \
    && git -C /tmp/xsrc checkout "${MAGE_REF}" \
    && mkdir -p /gen/formats \
    && SRC=/tmp/xsrc/Mage.Server.Plugins/Mage.Deck.Constructed/src/mage/deck \
    && cp "$SRC/Standard.java" "$SRC/Pioneer.java" "$SRC/Modern.java" \
          "$SRC/Legacy.java" "$SRC/Vintage.java" "$SRC/Pauper.java" /gen/formats/ \
    && rm -rf /tmp/xsrc

COPY Generator.java /gen/Generator.java
COPY FormatGenerator.java /gen/FormatGenerator.java
# Compile both generators + the cloned format sources (package mage.deck) together.
RUN javac -cp "/xmage-lib/*" -d /gen /gen/Generator.java /gen/FormatGenerator.java /gen/formats/*.java

WORKDIR /work
# Default entrypoint: CardScanner writes a throwaway H2 db under ./db; the SQLite catalog
# bundle goes to the mounted /out. The format-legality bundle is produced by overriding the
# entrypoint to run FormatGenerator instead (see README.md) — it shares this one image.
ENTRYPOINT ["sh", "-c", "java -cp \"/xmage-lib/*:/gen\" Generator /out/cards.sqlite \"$MAGE_REF\""]
