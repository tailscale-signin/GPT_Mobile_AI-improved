param(
    [string]$Destination = 'D:\MCP\gateway\gateway.py',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$Names = @('gateway.py', 'gateway_v13.py', 'gateway_v13_runtime.py', 'gateway_v13_transport.py', 'gateway_multisearch.py', 'gateway_security.py', 'requirements.txt')
$Parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($Destination))
foreach ($Name in $Names) {
    $Source = Join-Path $PSScriptRoot $Name
    if (!(Test-Path -LiteralPath $Source)) { throw "Missing required file: $Source" }
    if ($Name.EndsWith('.py')) {
        & $Python -m py_compile $Source
        if ($LASTEXITCODE -ne 0) { throw "Python syntax validation failed: $Source" }
    }
}
if (!(Select-String -Path (Join-Path $PSScriptRoot 'gateway_v13.py') -Pattern '^GATEWAY_VERSION = "13\.')) {
    throw 'Source does not identify itself as gateway v13.'
}
New-Item -ItemType Directory -Force -Path $Parent | Out-Null
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
# Back up every replaced module before modifying any file. Leave databases/configuration intact.
foreach ($Name in $Names) {
    $Target = if ($Name -eq 'gateway.py') { $Destination } else { Join-Path $Parent $Name }
    if (Test-Path -LiteralPath $Target) {
        Copy-Item -LiteralPath $Target -Destination "$Target.$Stamp.bak"
    }
}
foreach ($Name in $Names) {
    $Target = if ($Name -eq 'gateway.py') { $Destination } else { Join-Path $Parent $Name }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $Name) -Destination "$Target.new"
    Move-Item -LiteralPath "$Target.new" -Destination $Target -Force
}
Write-Host "Gateway v13 installed at $Destination. Install requirements.txt and restart your existing service."
Write-Host 'Existing pairing databases and MCP configuration were retained. Default bind remains 127.0.0.1.'
