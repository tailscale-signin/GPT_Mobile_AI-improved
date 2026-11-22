param(
    [ValidateRange(1024, 65535)][int]$Port,
    [string]$BindAddress,
    [ValidatePattern('^GPTMobileGateway-[a-f0-9]{24}$')][string]$RuleName
)
# This script performs observations only. It never enables adapters, creates
# firewall rules, starts services or stores identities/addresses in its output.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
$result = [ordered]@{}
try {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port })
    $result.listener_count = $listeners.Count
    $result.listener_state = if ($listeners.Count) { 'PRESENT' } else { 'NOT_OBSERVED' }
    $requested = [System.Net.IPAddress]::Parse($BindAddress)
    $matching = @($listeners | Where-Object {
        $_.LocalAddress -eq $BindAddress -or
        ($_.LocalAddress -eq '0.0.0.0' -and $requested.AddressFamily -eq 'InterNetwork') -or
        ($_.LocalAddress -eq '::' -and $requested.AddressFamily -eq 'InterNetworkV6')
    })
    $result.listener_binding = if (!$listeners.Count) { 'NOT_OBSERVED' } elseif ($matching.Count) { 'MATCHED' } else { 'DIFFERENT_BIND' }
} catch { $result.listener_state = 'UNKNOWN'; $result.listener_binding = 'UNKNOWN' }
try {
    $routes = @(Get-NetRoute -ErrorAction Stop | Where-Object { $_.DestinationPrefix -in @('0.0.0.0/0', '::/0') })
    $result.ipv4_default_route_count = @($routes | Where-Object { $_.DestinationPrefix -eq '0.0.0.0/0' }).Count
    $result.ipv6_default_route_count = @($routes | Where-Object { $_.DestinationPrefix -eq '::/0' }).Count
    $result.default_route_state = if ($routes.Count) { 'PRESENT' } else { 'NOT_OBSERVED' }
} catch { $result.default_route_state = 'UNKNOWN' }
try {
    $adapters = @(Get-NetAdapter -IncludeHidden -ErrorAction Stop | Where-Object { $_.Status -eq 'Up' })
    $tunnels = @($adapters | Where-Object {
        $_.InterfaceType -in @(23, 53, 131) -or
        $_.InterfaceDescription -match '(?i)tailscale|wireguard|openvpn|vpn|tunnel'
    })
    $result.tunnel_adapter_count = $tunnels.Count
    $result.tunnel_adapter_signal = if ($tunnels.Count) { 'PRESENT' } else { 'NOT_OBSERVED' }
} catch { $result.tunnel_adapter_signal = 'UNKNOWN' }
try {
    $result.active_network_profiles = @(Get-NetConnectionProfile -ErrorAction Stop | ForEach-Object { [string]$_.NetworkCategory } | Sort-Object -Unique)
} catch { }
try {
    # Enumerate the active store so a missing rule is distinguishable from a
    # failed -Name query. Discard every unrelated rule before making observations.
    $rules = @(Get-NetFirewallRule -PolicyStore ActiveStore -ErrorAction Stop | Where-Object { $_.Name -eq $RuleName })
    $result.firewall_rule_state = if ($rules.Count) { 'PRESENT' } else { 'NOT_OBSERVED' }
    if ($rules.Count) {
        $actions = @($rules | ForEach-Object { [string]$_.Action } | Sort-Object -Unique)
        $result.firewall_rule_action = if ($actions.Count -ne 1) { 'OTHER' } elseif ($actions[0] -eq 'Allow') { 'ALLOW' } elseif ($actions[0] -eq 'Block') { 'BLOCK' } else { 'OTHER' }
        $enabled = @($rules | ForEach-Object { [string]$_.Enabled } | Sort-Object -Unique)
        $result.firewall_rule_enabled = if ($enabled.Count -ne 1) { 'MIXED' } elseif ($enabled[0] -eq 'True') { 'ENABLED' } elseif ($enabled[0] -eq 'False') { 'DISABLED' } else { 'UNKNOWN' }
        $result.firewall_rule_profiles = @($rules | ForEach-Object { ([string]$_.Profile).Split(',').Trim() } | Where-Object { $_ -in @('Private', 'Public', 'DomainAuthenticated') } | Sort-Object -Unique)
        $ports = @($rules | Get-NetFirewallPortFilter -ErrorAction Stop)
        $matchingPorts = @($ports | Where-Object { ([string]$_.Protocol -in @('TCP', '6')) -and ([string]$_.LocalPort -eq [string]$Port) })
        $result.firewall_rule_port = if ($matchingPorts.Count) { 'MATCHED' } else { 'DIFFERENT_PORT' }
    }
} catch { $result.firewall_rule_state = 'UNKNOWN' }
$result | ConvertTo-Json -Depth 3 -Compress
