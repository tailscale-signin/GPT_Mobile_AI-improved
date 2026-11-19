using System.Runtime.CompilerServices;
using System.Text;

namespace Gateway.Desktop.Core;

public static class BridgeStream
{
    public const int MaximumFrameCharacters = 20 * 1024 * 1024;

    public static async Task DrainPrivateOutput(TextReader reader)
    {
        // Never retain a diagnostic stream containing private runtime output.
        var buffer = new char[4096];
        while (await reader.ReadAsync(buffer.AsMemory()) != 0) { }
    }

    public static async IAsyncEnumerable<string> Lines(TextReader reader,
        [EnumeratorCancellation] CancellationToken cancellation = default)
    {
        var buffer = new char[4096];
        var frame = new StringBuilder();
        int count;
        while ((count = await reader.ReadAsync(buffer.AsMemory(), cancellation)) != 0)
        {
            for (int offset = 0; offset < count; offset++)
            {
                char character = buffer[offset];
                if (character == '\n')
                {
                    if (frame.Length > 0 && frame[^1] == '\r') frame.Length--;
                    yield return frame.ToString();
                    frame.Clear();
                }
                else
                {
                    if (frame.Length == MaximumFrameCharacters)
                        throw new InvalidOperationException("The Gateway observation exceeded its display limit. Check the saved receipt.");
                    frame.Append(character);
                }
            }
        }
        if (frame.Length != 0)
            throw new InvalidOperationException("The Gateway observation ended inside a frame. Check the saved receipt.");
    }
}
