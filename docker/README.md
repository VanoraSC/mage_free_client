# Containerized build environment (heavy / JVM path)

The **bridge build**, the **upstream `magefree/mage` build**, and the **reference XMage server**
run in Docker containers. The Android `:app` build stays on the host. Full design:
[`../docs/build-environment.md`](../docs/build-environment.md).

## Prerequisites
- **Docker Desktop with the WSL2 backend** (`docker` + `docker compose` available in the shell).
- Run everything from the **repo root** via `./scripts/dev` (a thin wrapper over `docker compose`).

## Usage
```bash
# Build the JVM bridge in-container (JDK 17; Android modules are skipped via MAGE_JVM_ONLY).
./scripts/dev gradle :bridge:check

# Start / stop the reference XMage server (port 17171, authentication disabled).
./scripts/dev up xmage-server
./scripts/dev down

# Start the bridge itself against that server (story 0045). Publishes /v1/session on localhost:8080.
./scripts/dev up bridge
curl http://localhost:8080/health          # {"status":"ok","service":"mage-bridge"}

# Rebuild an image after a source change (`up` alone does not rebuild an existing image).
./scripts/dev build bridge
./scripts/dev up bridge

# A shell in the build container, or raw maven.
./scripts/dev sh
./scripts/dev mvn -version
```

Bridge integration tests reach the server over the compose network at **`XMAGE_SERVER=xmage-server:17171`**
(verified end-to-end: story 0003's `ConnectAuthenticateIT` completes the full XMage connect/auth
handshake from the build container). It is also published to the host on `localhost:17171`.

## Reaching the bridge from a phone

The app on a real device has to reach the bridge across the network, and the bridge runs in Docker
**inside WSL** — which forwards published container ports to the Windows loopback only. A port proxy
on the Windows host bridges that gap.

Run [`windows-host-networking.ps1`](windows-host-networking.ps1) once, in an elevated PowerShell. It
persists across reboots, so it is machine setup rather than something to repeat each session. The
device then connects to `http://<host LAN IP>:8080`.

**The proxy forwards to the IPv6 loopback, and that detail is load-bearing.** A `v4tov4` rule
forwarding to `127.0.0.1` cannot work: the wildcard IPv4 listener takes `127.0.0.1:8080` for itself,
so WSL's relay never binds it and the proxy ends up forwarding to itself. The symptom is easy to
misread — the TCP connection is *accepted* and then dies ("connection closed unexpectedly"), while
`http://localhost:8080` keeps working, because Windows tries `::1` first and that is still WSL's
relay. Forwarding to `::1` keeps the two on different address families, so they cannot collide.

Working looks like this:

```
0.0.0.0:8080 (IP Helper, IPv4) ──► ::1:8080 (wslrelay, IPv6) ──► WSL ──► container
```

**If a device cannot connect**, check in this order — the cheap steps catch almost everything:

1. `docker ps` shows the container `Up`, and `curl http://127.0.0.1:8080/health` **from inside WSL**
   answers. A container that has exited looks exactly like a networking failure when tested only
   from Windows.
2. `Get-NetTCPConnection -LocalPort 8080 -State Listen` shows **both** `0.0.0.0` (svchost/IP Helper)
   and `::1` (wslrelay). WSL's relay takes 10–15 seconds to bind after the containers start, so wait
   before reading anything into its absence.
3. `netsh interface portproxy show all` lists the rule. Listed here but missing from the listener
   list above means the socket was never opened — `Restart-Service iphlpsvc` rebuilds the listeners
   from the stored table. Re-adding the rule does not, because the add is a no-op.
4. The device itself. **Host self-tests prove nothing here**: the host can reach its own addresses
   perfectly while an external device cannot, so only a real device settles it.

When the answer is genuinely unclear, capture rather than guess — this ends the argument in one
step, and shows whether the packet arrives, and whether a reply goes back:

```bash
wsl -u root -e bash -lc "tcpdump -i any -nn 'tcp port 8080'"
```

**A machine with two adapters on one subnet should use the wired one.** Where both Ethernet and
Wi-Fi hold an address on the same network, prefer Ethernet: it is what the routing table picks
anyway, and a Wi-Fi address is both DHCP-assigned and, in at least one measured case here,
materially lossier. A DHCP reservation keeps it from moving.

**`networkingMode=mirrored` is not an alternative.** It looks ideal — WSL takes the host's own
interfaces, so container ports need no forwarding at all — and it does not work for this. Verified
here: every host address answered while an external device still could not connect, and a capture
showed the device's SYN arriving, Docker translating it to the container, the container replying,
and the reply never leaving the host. That is a known defect in return traffic for externally
originated connections translated onto a Docker bridge network
([microsoft/WSL#11819](https://github.com/microsoft/WSL/issues/11819),
[moby/moby#48201](https://github.com/moby/moby/issues/48201)). It is not only Docker: a plain
listener bound to `0.0.0.0` in WSL, with an inbound Hyper-V firewall rule in place, was equally
unreachable.

## Images
- **`mage-free-client/build`** — JDK 17 + Maven + `git`; a cached layer builds `magefree/mage` at a
  pinned commit and bakes `org.mage:mage-common:1.4.60` into `/root/.m2` (story 0021). Used for all
  JVM/bridge builds.
- **`mage-free-client/xmage-server`** — a multi-stage image that full-reactor-builds XMage, assembles
  the server distribution, and runs `mage.server.Main` on 17171 with `authenticationActivated=false`
  (story 0022). Launched with `--add-opens` for the JBoss-serialization handshake — required on JDK 17
  (see the server `Dockerfile`); the `:bridge` test task mirrors the same flags.
- **`mage-free-client/bridge`** — the runnable bridge (story 0045): a build stage on the `build` image
  assembles `:bridge:installDist`, and an `eclipse-temurin:17-jre` runtime carries the distribution.
  Upstream is `XMAGE_UPSTREAM=xmage-server:17171`; the port comes from `BRIDGE_PORT` (8080). Its
  `JAVA_OPTS` carry the **same** `--add-opens` set as the server image and the `:bridge` test task —
  keep all three in sync. The build context is the repo root, filtered by
  `bridge/Dockerfile.dockerignore`.

The app-side live integration tests (`:core:network`, story 0045) run **on the host** and take only a
URL: `BRIDGE_URL=localhost:8080`. Unset, they skip.

## Notes
- **First builds are slow, then cached.** The `build` image's mage layer takes minutes; the
  `xmage-server` image does the full reactor + card database (~30–60 min). Both cache as image layers.
- **Server startup** loads the card database (~30–60 s) before it listens on 17171.
- Caches persist in the `gradle-cache` volume (Gradle) and the images' baked `/root/.m2` (mage-common).
- The repo is bind-mounted from `/mnt/c/...` under WSL2; large builds may see some I/O overhead.
