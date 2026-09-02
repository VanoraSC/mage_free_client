<#
    Windows host networking: makes the bridge reachable from a phone or another LAN device.

    The bridge runs in Docker inside WSL, and WSL forwards published container ports to the Windows
    loopback only. These two commands put a port proxy in front of that forward and open the port.

    Run once, in an ELEVATED PowerShell. Both settings persist across reboots.

    See docker/README.md ("Reaching the bridge from a phone").
#>

# Accept on every host address and forward to the IPv6 loopback, where WSL's relay listens.
#
# It must be v4tov6 to ::1. A v4tov4 rule forwarding to 127.0.0.1 cannot work: the wildcard IPv4
# listener takes 127.0.0.1:8080 for itself, so WSL's relay never binds it and the proxy forwards to
# itself.
netsh interface portproxy add v4tov6 listenaddress=0.0.0.0 listenport=8080 connectaddress=::1 connectport=8080

# Let the traffic in. The listener is a Windows process (IP Helper), so this is an ordinary host
# firewall rule rather than a Hyper-V one.
New-NetFirewallRule -DisplayName "xmage bridge" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
