# 0021 — Upstream mage build layer

- **Epic:** Build Infrastructure (see [`../build-environment.md`](../build-environment.md))
- **Depends on:** 0020
- **Status:** ready

## 1. Objective

Add the image layer that builds the upstream **`magefree/mage`** reactor and installs
**`org.mage:mage-common`** (and its deps) into the container's local Maven repo, so the bridge can
depend on it. This is the heavy, cached layer that unblocks story 0003 (embedding `SessionImpl`).

## 2. Context & background

- Story 0003 makes `:bridge` depend on `org.mage:mage-common:1.4.60` (the XMage client session
  library). That artifact isn't on any public repo we use — it must be built from the upstream
  reactor via Maven. Only `Mage.Common` and its reactor dependencies are built
  (`-pl Mage.Common -am`), so the large card database (`Mage.Sets`) is **skipped**. See
  [`../architecture.md`](../architecture.md) and [`../build-environment.md`](../build-environment.md).
- The upstream targets an older Java, but it builds cleanly on **JDK 17** in-container (verified) —
  no special flags or a second JDK needed.
- **Pin the upstream ref.** The bridge is version-locked to the server (architecture Decision #6);
  the mage build is pinned to the exact commit the local `../mage` reference is on (`e0fe4b6f6a`,
  pom version `1.4.60`).

## 3. Scope

**In scope**
- A Dockerfile layer (on the 0020 base) that clones `magefree/mage` at a **pinned commit**, runs
  `mvn -pl Mage.Common -am -DskipTests install` (Mage.Common + its reactor deps only — no card DB),
  and **bakes** `mage-common` (+ `org.mage:mage`) into the image's local Maven repo (`/root/.m2`).
  No `/root/.m2` volume mount (it would shadow the baked repo).
- A build arg for the ref (`MAGE_REF=e0fe4b6f6a`, overridable via `--build-arg`).
- Verification that a JVM build in the container can **resolve** `org.mage:mage-common:<version>`
  from `mavenLocal()`.

**Out of scope**
- Running an XMage **server** (that is **0022**).
- Changing `:bridge` to actually depend on `mage-common` (that is bridge story **0003**) — this
  story only makes the artifact *available*.
- Any host-side mage build.

## 4. Prerequisites & toolchain

Deltas from the [Project toolchain baseline](stories/README.md#project-toolchain-baseline):

- Requires 0020. Uses Maven + JDK 17. Pinned to commit `e0fe4b6f6a` (pom `1.4.60`). The first image
  build takes a while (clone + build the Mage engine + Mage.Common + dep downloads), cached as an
  image layer; the card DB is skipped, so it is far lighter than a full reactor build.
- JDK 17 builds the upstream cleanly (verified) — no special flags needed.

## 5. Design & approach

- **Layer:** `ARG MAGE_REF=e0fe4b6f6a`; `git clone --filter=tree:0 <repo> /tmp/mage`,
  `git checkout ${MAGE_REF}`, `mvn -pl Mage.Common -am -DskipTests install`, then remove the clone
  while the installed artifacts remain in `/root/.m2`. Its own layer so it caches independently of
  day-to-day changes. (`--filter=tree:0` keeps the clone cheap while allowing checkout of the pin.)
- **Resolution:** downstream Gradle builds in the container use `mavenLocal()` to find
  `org.mage:mage-common:1.4.60` from the baked `/root/.m2`.
- **Determinism:** the pinned ref + `-DskipTests` keeps the build reproducible; record the ref in
  the compose/Dockerfile and `../build-environment.md`.
- If the card-DB build makes the image very large, note the size; acceptable for a build image.

## 6. Implementation steps

1. Add the mage-build layer to `docker/jvm/Dockerfile` (pinned `MAGE_REF`, clone, `mvn install`).
2. Build the image; capture whether upstream builds on JDK 17 and any flags needed (document them).
3. Add a small in-container check that `org.mage:mage-common:1.4.60` resolves from `mavenLocal()`
   (e.g. a throwaway `dependencies` resolution or a tiny Gradle task).
4. Document the layer, the pin, and the first-build time/size in `docker/README.md` /
   `../build-environment.md`.

## 7. Testing & verification

- **Artifact availability:** in the build container, `org.mage:mage-common:1.4.60` resolves from
  `mavenLocal()` (a resolution check passes).
- **Reproducibility:** rebuilding the image at the same `MAGE_REF` yields the same artifacts.

## 8. Acceptance criteria

- [ ] The build image has a cached layer that installs `org.mage:mage-common:1.4.60` (+ deps) into
      its local Maven repo, built from a **pinned** upstream ref.
- [ ] A container build resolves `org.mage:mage-common:1.4.60` from `mavenLocal()`.
- [ ] Whether/how upstream builds on JDK 17 is documented (flags or a pinned JDK if needed).
- [ ] No XMage server yet; `:bridge` does not yet depend on `mage-common` (that's story 0003);
      Android host path unchanged.

## 9. References

- [`../architecture.md`](../architecture.md) — why `mage-common` is the client library and the version lock.
- [`0003-embed-client-session-connect-authenticate.md`](0003-embed-client-session-connect-authenticate.md) — the bridge story that will consume this artifact.
- [`../build-environment.md`](../build-environment.md) — the layered image design.
