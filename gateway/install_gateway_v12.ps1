param(
    [string]$Destination = 'D:\MCP\gateway\gateway.py',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$Source = Join-Path $PSScriptRoot 'gateway_v12.py'
if (!(Test-Path $Source)) { throw "Missing source: $Source" }
& $Python -m py_compile $Source
if ($LASTEXITCODE -ne 0) { throw 'Python syntax validation failed; installation stopped.' }
if (!(Select-String -Path $Source -Pattern '^GATEWAY_VERSION = "12\.')) {
    throw 'Source does not identify itself as gateway v12.'
}
$Parent = Split-Path -Parent $Destination
New-Item -ItemType Directory -Force -Path $Parent | Out-Null
if (Test-Path $Destination) {
    $Backup = "$Destination.$(Get-Date -Format 'yyyyMMdd-HHmmss-fff').bak"
    Copy-Item -LiteralPath $Destination -Destination $Backup
    Write-Host "Previous gateway saved to $Backup"
}
Copy-Item -LiteralPath $Source -Destination "$Destination.new"
Move-Item -LiteralPath "$Destination.new" -Destination $Destination -Force
foreach ($Name in @('gateway_security.py', 'requirements.txt')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $Name) -Destination (Join-Path $Parent $Name) -Force
}
Write-Host "Gateway installed at $Destination. Install requirements.txt, pair a device, then restart."
Write-Host 'Default bind is 127.0.0.1. Set GATEWAY_HOST to your private interface for phone access.'
