param(
    [Parameter(Mandatory=$true)][ValidatePattern('^GPTMobileGateway-[a-f0-9]{24}(-phone)?$')][string]$RuleName,
    [ValidateRange(1024,65535)][int]$Port = 8090,
    [ValidateRange(1024,65535)][int]$ControlPort,
    [string]$ControlAddress,
    [string]$BindAddress = '0.0.0.0',
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
if ($Remove) {
    Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    if (-not $RuleName.EndsWith('-phone')) {
        Get-NetFirewallRule -Name ($RuleName + '-phone') -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    }
    exit 0
}
$address = [System.Net.IPAddress]::Parse($BindAddress)
if ([System.Net.IPAddress]::IsLoopback($address)) { throw 'A loopback listener needs no firewall rule.' }
$localAddress = $BindAddress
if ($BindAddress -eq '0.0.0.0' -or $BindAddress -eq '::') { $localAddress = 'Any' }
$separateControl = $PSBoundParameters.ContainsKey('ControlAddress')
if ($separateControl) {
    if (-not $PSBoundParameters.ContainsKey('ControlPort') -or $RuleName.EndsWith('-phone')) { throw 'Invalid phone rule request.' }
    $control = [System.Net.IPAddress]::Parse($ControlAddress)
    if ([System.Net.IPAddress]::IsLoopback($control) -or $ControlAddress -eq '0.0.0.0' -or $ControlAddress -eq '::') {
        throw 'The phone control listener must use one private interface.'
    }
}
# Installation-specific rules, replaced only after an explicit user action.
# Do not leave a newly opened, untracked inference port if the phone rule fails.
$attemptedRules = @()
try {
    Get-NetFirewallRule -Name $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    $ports = @($Port)
    if ($PSBoundParameters.ContainsKey('ControlPort') -and -not $separateControl) { $ports += $ControlPort }
    $attemptedRules += $RuleName
    New-NetFirewallRule -Name $RuleName -DisplayName 'GPT Mobile Gateway (private LAN)' `
        -Direction Inbound -Action Allow -Enabled True -Profile Private `
        -Protocol TCP -LocalPort $ports -LocalAddress $localAddress `
        -RemoteAddress LocalSubnet -EdgeTraversalPolicy Block | Out-Null

    if ($separateControl) {
        $phoneRule = $RuleName + '-phone'
        Get-NetFirewallRule -Name $phoneRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        $attemptedRules += $phoneRule
        New-NetFirewallRule -Name $phoneRule -DisplayName 'GPT Mobile Gateway phone controls (private LAN)' `
            -Direction Inbound -Action Allow -Enabled True -Profile Private `
            -Protocol TCP -LocalPort $ControlPort -LocalAddress $ControlAddress `
            -RemoteAddress LocalSubnet -EdgeTraversalPolicy Block | Out-Null
    }
} catch {
    $failure = $_
    foreach ($attemptedRule in $attemptedRules) {
        try { Get-NetFirewallRule -Name $attemptedRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule } catch { }
    }
    throw $failure
}
