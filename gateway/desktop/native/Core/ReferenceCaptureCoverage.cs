namespace Gateway.Desktop.Core;

internal static class ReferenceCaptureCoverage
{
    // WPF ApplyGreyScaleOver uses ((dstAlpha * (255 - glyphAlpha)) >> 8) + glyphAlpha.
    // Over an opaque destination, fractional glyph coverage therefore produces alpha 254.
    // https://github.com/dotnet/wpf/blob/main/src/Microsoft.DotNet.Wpf/src/WpfGfx/core/sw/swlib/swglyphpainter.cpp
    // This bounded tolerance does not replace the caller's complete-layout and clipping checks.
    public static int ValidatePbgra32(ReadOnlySpan<byte> pixels, int width, int height)
    {
        if (width <= 0 || height <= 0 || width > int.MaxValue / 4 ||
            height > int.MaxValue / (width * 4) || pixels.Length != width * height * 4)
            throw new InvalidOperationException("Reference capture pixel buffer does not match its complete dimensions.");

        int roundedGlyphPixels = 0;
        for (int offset = 3; offset < pixels.Length; offset += 4)
        {
            byte alpha = pixels[offset];
            if (alpha < 254)
                throw new InvalidOperationException(
                    $"Reference capture contains an unrendered or transparent pixel: x={offset / 4 % width}, y={offset / 4 / width}, alpha={alpha}, bitmap={width}x{height}.");
            if (alpha == 254) roundedGlyphPixels++;
        }
        return roundedGlyphPixels;
    }
}
