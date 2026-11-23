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
const string inconclusive = "Inconclusive · no qualified winner";
foreach (JsonNode? winner in new JsonNode?[] { null, JsonValue.Create(""), JsonValue.Create(" "), JsonValue.Create(42) })
    Check(BenchmarkComparisonPresentation.Headline(new JsonObject { ["status"] = "SUPERIOR_ON_MATCHED_SUITE", ["winner"] = winner }) == inconclusive,
        "Missing or invalid winner cannot become a superiority headline");
Check(BenchmarkComparisonPresentation.Headline(new JsonObject { ["status"] = "INCONCLUSIVE", ["winner"] = "model-a" }) == inconclusive,
    "Unqualified retained model name cannot become a winner");
Check(BenchmarkComparisonPresentation.Headline(new JsonObject { ["status"] = "INCONCLUSIVE", ["winner"] = null }) == inconclusive,
    "Normal inconclusive backend summary stays inconclusive");
Check(BenchmarkComparisonPresentation.Headline(new JsonObject { ["winner"] = "model-a" }) == inconclusive,
    "Missing qualification status cannot become a winner");
Check(BenchmarkComparisonPresentation.Headline(new JsonObject { ["status"] = "SUPERIOR_ON_MATCHED_SUITE", ["winner"] = "model-a" }) == "Superior on this matched suite: model-a",
    "Qualified matched-suite winner remains visible");
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
var preview = new PublicPreview();
JsonObject Observation(int sequence,string key,string text,string kind,double time) => new() {
    ["sequence"]=sequence,["coalescing_key"]=key,["public_text"]=text,["event_type"]=kind,["observed_at"]=time };
Check(preview.Apply(Observation(1,"tool","Reading settings","tool_start",100)), "First public update is accepted");
Check(preview.Apply(Observation(2,"scope","Reviewed 3 settings.","finding",110)), "Substantive findings are retained");
Check(!preview.Apply(Observation(1,"tool","Stale","activity",112)), "Replaying an older event leaves the timeline intact");
preview.Apply(Observation(3,"tool","Read settings","activity",120));
Check(preview.Lines[0].Key=="tool" && preview.Lines[0].Text=="Read settings", "Refreshing a preview preserves chronology");
Check(!preview.Summarize(144.9) && preview.Summarize(145), "First substantive summary waits 45 seconds");
Check(!preview.Summarize(300), "Elapsed time alone cannot repeat a finding");
preview.Apply(Observation(4,"check","Two checks passed.","finding",200));
Check(!preview.Summarize(279.9) && preview.Summarize(280), "Later substantive summaries wait 135 seconds");
for(int i=5;i<=205;i++) preview.Apply(Observation(i,"phase-"+i,"Stage "+i,"activity",300+i));
Check(preview.Cursor==205 && preview.Lines.Count==20, "Public replay memory is bounded through a burst");
var contract = JsonNode.Parse(await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory,"public-preview-v1.json")))!;
var motion = contract.Object("motion");
Check(PublicPreview.FadeInMs==motion.Number("entry_ms") && PublicPreview.RefreshMs==motion.Number("refresh_ms")
    && PublicPreview.ChangeIntervalMs==motion.Number("change_interval_ms") && PublicPreview.ActiveAccentMs==motion.Number("active_accent_ms")
    && PublicPreview.CollapsedLines==motion.Number("collapsed_lines"), "Windows preview motion matches the shared public event contract");
Check(1000*PublicPreview.FirstSummarySeconds==motion.Number("first_finding_ms")
    && 1000*PublicPreview.NextSummarySeconds==motion.Number("later_finding_ms"), "Findings use the shared summary cadence");
foreach(var example in contract.Array("reading"))
    Check(PublicPreview.ReadingMs((int)example!.Number("lines")!)==example.Number("duration_ms"), "Rendered-line reading example matches Android");
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
