param([ValidateSet('supervise','settings','github-settings','engineering-setup','stop','check','install','restart','remove-firewall','dashboard')][string]$Command = 'supervise', [switch]$Headless)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
try {
    do {
        $state = Get-Content -LiteralPath (Join-Path $root 'current.json') -Raw | ConvertFrom-Json
        if ($state.version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'Invalid installed version' }
        $exe = Join-Path $root ('releases\' + $state.version + '\Gateway.exe')
        $arguments = @($Command, '--root', $root)
        if ($Headless) { $arguments += '--headless' }
        & $exe @arguments
        $result = $LASTEXITCODE
    } while ($Command -eq 'supervise' -and $result -eq 75)
    exit $result
} catch {
    if ($Headless) {
        [Console]::Error.WriteLine('Gateway could not start. See the installed Gateway logs and status.')
        exit 1
    }
    Add-Type -AssemblyName PresentationFramework
    [System.Windows.MessageBox]::Show($_.Exception.Message, 'Gateway startup error') | Out-Null
    exit 1
}
