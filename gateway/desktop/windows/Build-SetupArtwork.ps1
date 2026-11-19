param([Parameter(Mandatory)][string]$OutputFile)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$logo = [Drawing.Image]::FromFile((Resolve-Path (Join-Path $PSScriptRoot '../assets/gateway.png')))
$bitmap = [Drawing.Bitmap]::new(492, 942)
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$bounds = [Drawing.Rectangle]::new(0, 0, $bitmap.Width, $bitmap.Height)
$gradient = [Drawing.Drawing2D.LinearGradientBrush]::new($bounds,
    [Drawing.ColorTranslator]::FromHtml('#001219'), [Drawing.ColorTranslator]::FromHtml('#103239'), [single]70)
try {
    $graphics.FillRectangle($gradient, $bounds)
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($logo, [Drawing.Rectangle]::new(86, 311, 320, 320))
    $bitmap.Save($OutputFile, [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $gradient.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
    $logo.Dispose()
}
