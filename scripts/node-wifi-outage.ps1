# Demo switch for Windows (run PowerShell as Administrator): cut ONLY the node's path to the server so it falls back to LoRa.
#   .\node-wifi-outage.ps1 on  192.168.1.11     # block the node (find its IP in your router's device list)
#   .\node-wifi-outage.ps1 off                  # restore
param([Parameter(Mandatory)][ValidateSet('on','off')][string]$Action, [string]$NodeIP)
$rule = 'DisasterDemoBlockNode'
if ($Action -eq 'on') {
  if (-not $NodeIP) { throw 'give the node IP, e.g. .\node-wifi-outage.ps1 on 192.168.1.11' }
  New-NetFirewallRule -DisplayName $rule -Direction Inbound -Action Block -Protocol TCP -LocalPort 8000 -RemoteAddress $NodeIP | Out-Null
  "node $NodeIP blocked: it will fall back to LoRa"
} else {
  Remove-NetFirewallRule -DisplayName $rule
  'node restored'
}
