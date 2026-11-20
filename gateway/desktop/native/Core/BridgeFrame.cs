using System.Text.Json;
using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public sealed record BridgeFrame(string Event, JsonNode? Value)
{
    public static BridgeFrame Parse(string line)
    {
        if (line.Length > 20 * 1024 * 1024)
            throw new InvalidOperationException("The Gateway observation exceeded its display limit. Check the saved receipt.");
        JsonObject frame;
        try
        {
            frame = JsonNode.Parse(line) as JsonObject ?? throw new JsonException();
            if (frame.Text("protocol", "") != "gptmobile.desktop.v1" ||
                frame.Text("event", "") is not ("error" or "result" or "accepted" or "activity" or "delta")) throw new JsonException();
            if (frame.Text("event") == "delta" && frame["value"] is not JsonValue) throw new JsonException();
            if (frame.Text("event") is "error" or "accepted" or "activity" && frame["value"] is not JsonObject) throw new JsonException();
        }
        catch (JsonException)
        {
            throw new InvalidOperationException("The local adapter returned an invalid response. Repair the installation.");
        }
        return new(frame.Text("event"), frame["value"]?.DeepClone());
    }
}
