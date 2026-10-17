#!/usr/bin/env bash
# Demo switch: cut ONLY the node's Wi-Fi path to the server, so it falls back to LoRa.
# The gateway keeps its path, so LoRa packets still reach the dashboard.
#   sudo scripts/node-wifi-outage.sh on     # node can no longer reach the server
#   sudo scripts/node-wifi-outage.sh off    # restore
#   scripts/node-wifi-outage.sh ip          # just show the detected node IP
# The node is found by MAC (see firmware: NODE_MAC), override with NODE_IP=... if it isn't in `ip neigh`.
set -euo pipefail
MAC=${NODE_MAC:-30:76:f5:06:4d:a4}
IP=${NODE_IP:-$(ip neigh | awk -v m="$MAC" 'tolower($5)==tolower(m){print $1}' | head -1)}
[ -n "$IP" ] || { echo "node not found on the network (is it powered and on Wi-Fi?). Set NODE_IP=..." >&2; exit 1; }
case "${1:-}" in
  on)  ufw insert 1 reject from "$IP" to any port 8000 proto tcp && echo "node $IP blocked: it will fall back to LoRa" ;;  # reject = instant failure, no 3 s timeouts
  off) ufw delete reject from "$IP" to any port 8000 proto tcp && echo "node $IP restored" ;;
  ip)  echo "$IP" ;;
  *)   echo "usage: $0 on|off|ip" >&2; exit 2 ;;
esac
