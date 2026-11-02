param(
    [Parameter(Mandatory=$true)][ValidatePattern('^GPTMobileGateway-[a-f0-9]{24}$')][string]$RuleName,
    [ValidateRange(1024,65535)][int]$Port = 8090,
    [string]$BindAddress = '0.0.0.0',
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
if ($Remove) {
    Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    exit 0
}
$address = [System.Net.IPAddress]::Parse($BindAddress)
if ([System.Net.IPAddress]::IsLoopback($address)) { throw 'A loopback listener needs no firewall rule.' }
$localAddress = $BindAddress
if ($BindAddress -eq '0.0.0.0' -or $BindAddress -eq '::') { $localAddress = 'Any' }
# One installation-specific rule, replaced only after an explicit user action.
Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name $RuleName -DisplayName 'GPT Mobile Gateway (private LAN)' `
    -Direction Inbound -Action Allow -Enabled True -Profile Private `
    -Protocol TCP -LocalPort $Port -LocalAddress $localAddress `
    -RemoteAddress LocalSubnet -EdgeTraversalPolicy Block | Out-Null
