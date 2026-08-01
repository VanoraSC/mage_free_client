# Card catalog generator (story 0030). Emits the normalized cards.sqlite bundle consumed by
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

# The JDK build image (has javac + java). We compile + run the generator here.
FROM mage-free-client/build:latest AS generator
COPY --from=xmage /opt/xmage/lib /xmage-lib
COPY Generator.java /gen/Generator.java
RUN javac -cp "/xmage-lib/*" -d /gen /gen/Generator.java

# The pinned upstream ref, recorded into the bundle's meta table for provenance.
ARG MAGE_REF=e0fe4b6f6a
ENV MAGE_REF=${MAGE_REF}

WORKDIR /work
# CardScanner writes a throwaway H2 db under ./db; the SQLite bundle goes to the mounted /out.
ENTRYPOINT ["sh", "-c", "java -cp \"/xmage-lib/*:/gen\" Generator /out/cards.sqlite \"$MAGE_REF\""]
