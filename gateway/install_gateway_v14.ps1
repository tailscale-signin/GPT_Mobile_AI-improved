param(
    [string]$Releases = 'D:\MCP\gateway-releases',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
# Stage only. This installer does not stop services, modify firewall rules,
# enable Funnel, copy live SQLite files, or overwrite an existing gateway.
& $Python -c 'import sys; assert sys.version_info >= (3,12), "Python 3.12+ required"'
if ($LASTEXITCODE -ne 0) { throw 'Unsupported Python' }
& $Python -c 'import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); from v14.package import validate_package; validate_package(Path(sys.argv[1]))' $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Incomplete or modified package' }
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$Manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'manifest-v14.json') -Raw | ConvertFrom-Json
$Stage = Join-Path $Releases "$($Manifest.version)-$Stamp"
if (Test-Path -LiteralPath $Stage) { throw 'Stage already exists' }
New-Item -ItemType Directory -Path $Stage -Force | Out-Null
foreach ($Property in $Manifest.files.PSObject.Properties) {
    $Target = Join-Path $Stage $Property.Name
    New-Item -ItemType Directory -Path (Split-Path -Parent $Target) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $Property.Name) -Destination $Target
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'manifest-v14.json') -Destination $Stage
& $Python -m venv (Join-Path $Stage '.venv')
if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed; prior gateway unchanged' }
$StagePython = Join-Path $Stage '.venv\Scripts\python.exe'
& $StagePython -m pip install --require-hashes -r (Join-Path $Stage 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Pinned dependency install failed; prior gateway unchanged' }
& $StagePython (Join-Path $Stage 'gateway.py') validate-config
if ($LASTEXITCODE -ne 0) { throw 'Staged validation failed; prior gateway unchanged' }
Write-Host "Validated stage: $Stage"
Write-Host 'Follow V14_UPGRADE.md to snapshot state, select explicit database/config paths, stop the old service and switch its launch target.'
Write-Host 'The running service and prior release were not changed.'
