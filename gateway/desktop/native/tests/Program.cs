using Gateway.Desktop.Core;
using System.Text.Json.Nodes;

int count = 0;
void Check(bool result, string name) { if (!result) throw new Exception(name); count++; }
void Reject(string line)
{
    try { BridgeFrame.Parse(line); }
    catch (InvalidOperationException error)
    {
        Check(!error.Message.Contains("PRIVATE_REPLY") && !error.Message.Contains("SECRET_TOKEN"), "Rejected payload remains private");
        count++; return;
    }
    throw new Exception("Malformed bridge frame was accepted");
}
string Frame(string kind, JsonNode? value) => new JsonObject { ["protocol"] = "gptmobile.desktop.v1", ["event"] = kind, ["value"] = value }.ToJsonString();
var delta = BridgeFrame.Parse(Frame("delta", JsonValue.Create("Unicode reply · 你好")));
Check(delta.Event == "delta" && delta.Value?.ToString() == "Unicode reply · 你好", "Streaming text survives transport");
var accepted = BridgeFrame.Parse(Frame("accepted", new JsonObject { ["command_id"] = "send-123", ["detach_safe"] = true }));
Check(accepted.Value.Flag("detach_safe"), "Detach permission comes from receipt");
Check(BridgeFrame.Parse(Frame("result", new JsonObject { ["revision"] = 2 })).Value.Number("revision") == 2, "Revision receipt survives transport");
Reject("PRIVATE_REPLY SECRET_TOKEN");
Reject("[]");
Reject("{\"protocol\":\"other\",\"event\":\"result\",\"value\":{}}");
Reject(Frame("execute", new JsonObject { ["shell"] = "SECRET_TOKEN" }));
Reject(Frame("accepted", JsonValue.Create("PRIVATE_REPLY")));
Reject(Frame("error", JsonValue.Create("PRIVATE_REPLY")));
Reject(Frame("delta", new JsonArray("PRIVATE_REPLY")));
Reject(new string('x', 20 * 1024 * 1024 + 1));
var lines = new List<string>();
string first = Frame("delta", JsonValue.Create(new string('x', 4090) + "你好"));
string last = Frame("result", new JsonObject { ["revision"] = 4 });
await foreach (string line in BridgeStream.Lines(new StringReader(first + "\r\n" + last + "\n"))) lines.Add(line);
Check(lines.SequenceEqual(new[] { first, last }), "Chunk boundaries and CRLF preserve complete frames");
var privateOutput = new RepeatedReader(128 * 1024 * 1024);
await BridgeStream.DrainPrivateOutput(privateOutput);
Check(privateOutput.Remaining == 0 && privateOutput.MaximumRead <= 4096, "Private output is discarded with a fixed buffer");
var oversized = new RepeatedReader(BridgeStream.MaximumFrameCharacters + 100000);
try
{
    await foreach (var _ in BridgeStream.Lines(oversized)) { }
    throw new Exception("Unbounded line was accepted");
}
catch (InvalidOperationException)
{
    Check(oversized.MaximumRead <= 4096 && oversized.Remaining > 0, "Oversized line stops before reading or retaining the full stream");
}
try
{
    await foreach (var _ in BridgeStream.Lines(new StringReader("PRIVATE_REPLY SECRET_TOKEN"))) { }
    throw new Exception("Truncated frame was accepted");
}
catch (InvalidOperationException error)
{
    Check(!error.Message.Contains("PRIVATE_REPLY") && !error.Message.Contains("SECRET_TOKEN"), "Truncated streams fail without exposing payload");
}
Console.WriteLine($"{count} native bridge contract assertions passed.");

sealed class RepeatedReader(int count) : TextReader
{
    public int Remaining { get; private set; } = count;
    public int MaximumRead { get; private set; }
    public override ValueTask<int> ReadAsync(Memory<char> buffer, CancellationToken cancellationToken = default)
    {
        MaximumRead = Math.Max(MaximumRead, buffer.Length);
        int take = Math.Min(Remaining, buffer.Length);
        buffer.Span[..take].Fill('x'); Remaining -= take;
        return ValueTask.FromResult(take);
    }
}
