# Windows PowerShell 5.1 / PowerShell 7. Uses the installed Gateway JSONL bridge.
# Actions only manage/import supplied-answer campaigns; no product is launched.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GatewayExe,
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)]
    [ValidateSet('templates', 'keys', 'create', 'resume-setup', 'history', 'read',
        'import', 'import-inbox', 'grade', 'recover', 'retire', 'export', 'demo',
        'select-chatgpt', 'read-mobile')][string]$Action,
    [hashtable]$Arguments = @{},
    [string]$ArgumentsFile,
    [string]$OutputFile,
    [ValidateRange(1, 7200)][int]$TimeoutSeconds = 600
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function ConvertTo-ProcessArgument([string]$Value) {
    # Windows command-line quoting, including trailing backslashes and quotes.
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) { $slashes++; continue }
        if ($character -eq [char]34) {
            [void]$builder.Append([char]92, (2 * $slashes + 1))
            [void]$builder.Append([char]34)
        }
        else {
            [void]$builder.Append([char]92, $slashes)
            [void]$builder.Append($character)
        }
        $slashes = 0
    }
    [void]$builder.Append([char]92, (2 * $slashes))
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Compress-JsonText([string]$Value) {
    # Preserve numbers, dates, string escapes and literal content from a supplied
    # JSON object. ConvertFrom-Json may coerce ISO date strings on some versions.
    # Only insignificant whitespace is removed, so the bridge receives one line.
    $builder = New-Object System.Text.StringBuilder
    $quoted = $false
    $escaped = $false
    foreach ($character in $Value.ToCharArray()) {
        if ($quoted) {
            [void]$builder.Append($character)
            if ($escaped) { $escaped = $false }
            elseif ($character -eq [char]92) { $escaped = $true }
            elseif ($character -eq [char]34) { $quoted = $false }
        }
        elseif ($character -eq [char]34) {
            $quoted = $true
            [void]$builder.Append($character)
        }
        elseif ($character -notin @([char]32, [char]9, [char]10, [char]13)) {
            [void]$builder.Append($character)
        }
    }
    return $builder.ToString()
}

function Get-ReceiptJson([string]$Frame) {
    # Native frames have only protocol/event/value fields. Locate the value
    # object after validation and retain it verbatim instead of reserializing
    # date strings or large numeric values through PowerShell's object types.
    $marker = [regex]::Match($Frame, '"value"\s*:\s*')
    if (-not $marker.Success) { throw 'The adapter returned an invalid receipt field.' }
    $offset = $marker.Index + $marker.Length
    if ($offset -ge $Frame.Length -or $Frame[$offset] -ne [char]123) {
        throw 'The adapter returned an invalid receipt object.'
    }
    $depth = 0
    $quoted = $false
    $escaped = $false
    for ($index = $offset; $index -lt $Frame.Length; $index++) {
        $character = $Frame[$index]
        if ($quoted) {
            if ($escaped) { $escaped = $false }
            elseif ($character -eq [char]92) { $escaped = $true }
            elseif ($character -eq [char]34) { $quoted = $false }
        }
        elseif ($character -eq [char]34) { $quoted = $true }
        elseif ($character -in @([char]123, [char]91)) { $depth++ }
        elseif ($character -in @([char]125, [char]93)) {
            $depth--
            if ($depth -eq 0) { return $Frame.Substring($offset, $index - $offset + 1) }
        }
    }
    throw 'The adapter ended inside its receipt object.'
}

$process = $null
$started = $false
try {
    if (-not (Test-Path -LiteralPath $GatewayExe -PathType Leaf)) { throw 'Select the installed Gateway.exe runtime.' }
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { throw 'Select the existing Gateway state directory.' }
    if (-not [string]::IsNullOrWhiteSpace($ArgumentsFile)) {
        if ($Arguments.Count -ne 0) { throw 'Choose either Arguments or ArgumentsFile.' }
        $strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
        $argumentText = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ArgumentsFile).Path, $strictUtf8)
        if ([Text.Encoding]::UTF8.GetByteCount($argumentText) -gt 262144) { throw 'Arguments exceed the 256 KiB limit.' }
        $payload = ConvertFrom-Json -InputObject $argumentText
        if ($null -eq $payload -or $payload -is [Array] -or $payload -is [string] -or $payload -is [ValueType]) {
            throw 'ArgumentsFile must contain one JSON object.'
        }
        $argumentJson = Compress-JsonText $argumentText
    }
    else { $argumentJson = ConvertTo-Json -InputObject $Arguments -Depth 90 -Compress }
    $request = '{"protocol":"gptmobile.desktop.v1","action":"benchmark-campaign-' + $Action + '","args":' + $argumentJson + '}'
    if ([Text.Encoding]::UTF8.GetByteCount($request) -gt 262144) { throw 'Arguments exceed the 256 KiB limit.' }
    if (-not [string]::IsNullOrWhiteSpace($OutputFile) -and (Test-Path -LiteralPath $OutputFile)) {
        throw 'OutputFile already exists; choose a new receipt file.'
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = (Resolve-Path -LiteralPath $GatewayExe).Path
    $start.Arguments = 'native-bridge --root ' + (ConvertTo-ProcessArgument (Resolve-Path -LiteralPath $Root).Path) + ' --quiet'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = $utf8
    $start.StandardErrorEncoding = $utf8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    if (-not $process.Start()) { throw 'The installed Gateway adapter did not start.' }
    $started = $true
    # Drain both pipes concurrently; private stderr is never printed or exported.
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    # StandardInputEncoding is unavailable on some .NET Framework versions used
    # by Windows PowerShell 5.1. Write exact UTF-8 bytes without a console codepage.
    $requestBytes = $utf8.GetBytes($request + "`n")
    $process.StandardInput.BaseStream.Write($requestBytes, 0, $requestBytes.Length)
    $process.StandardInput.BaseStream.Flush()
    $process.StandardInput.BaseStream.Close()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill()
        throw 'The adapter timed out. Preserve the campaign and use recover; an uncertain grading submission must not be repeated.'
    }
    $raw = $stdout.GetAwaiter().GetResult()
    [void]$stderr.GetAwaiter().GetResult()
    if ($raw.Length -gt 20971520) { throw 'The observation exceeded the display limit. Read the saved campaign files.' }
    if (-not $raw.EndsWith("`n")) { throw 'The adapter ended inside a frame. Preserve the original identity and recover.' }
    $result = $null
    $failure = $null
    foreach ($line in ($raw -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $frame = ConvertFrom-Json -InputObject $line
        if (@($frame.PSObject.Properties.Name | Where-Object { $_ -notin @('protocol', 'event', 'value') }).Count -ne 0) {
            throw 'Unexpected local bridge frame fields.'
        }
        if ($frame.protocol -ne 'gptmobile.desktop.v1') { throw 'Unexpected local bridge protocol.' }
        if ($frame.event -eq 'result') {
            if ($null -ne $result -or $null -eq $frame.value -or $frame.value -is [Array] -or
                $frame.value -is [string] -or $frame.value -is [ValueType]) {
                throw 'The adapter returned an invalid or duplicate campaign receipt.'
            }
            $result = $frame.value
            $resultJson = Get-ReceiptJson $line
        }
        elseif ($frame.event -eq 'error') { $failure = $frame.value.message }
        elseif ($frame.event -notin @('accepted', 'activity', 'delta')) { throw 'Unexpected local bridge event.' }
    }
    if ($null -ne $failure) { throw $failure }
    if ($process.ExitCode -ne 0 -or $null -eq $result) {
        throw 'No complete receipt was returned. Preserve the original identity and recover before another action.'
    }
    $json = $resultJson
    if (-not [string]::IsNullOrWhiteSpace($OutputFile)) {
        $target = [IO.Path]::GetFullPath($OutputFile)
        $stream = New-Object IO.FileStream($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $bytes = $utf8.GetBytes($json + "`n")
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush()
        }
        finally { $stream.Dispose() }
    }
    $json
}
finally {
    if ($null -ne $process) {
        if ($started -and -not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}
