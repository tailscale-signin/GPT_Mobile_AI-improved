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
var nativeReport = new JsonObject
{
    ["evidenceLane"] = "SYNTHETIC_ONLY", ["comparativeModelQuality"] = "REGISTERED_SUITE_ONLY",
    ["nativeComparison"] = new JsonObject { ["verdict"] = "SUPERIOR_ON_REGISTERED_SUITE" }
};
Check(NativeProductCampaignPresentation.Headline(nativeReport) == "Synthetic fixture · product quality remains unmeasured",
    "A synthetic verdict cannot become a product superiority claim");
nativeReport["evidenceLane"] = "NATIVE_OBSERVED";
nativeReport["comparativeModelQuality"] = "NOT_MEASURED";
Check(NativeProductCampaignPresentation.Headline(nativeReport) == "Comparative product quality · Unknown",
    "A verdict without measured product evidence remains unknown");
nativeReport["comparativeModelQuality"] = "REGISTERED_SUITE_ONLY";
Check(NativeProductCampaignPresentation.Headline(nativeReport) == "GPT Mobile AI superior on this registered suite",
    "A canonical registered-suite verdict remains scoped to its suite");
nativeReport.Object("nativeComparison")["verdict"] = "INCOMPLETE";
Check(NativeProductCampaignPresentation.Headline(nativeReport).Contains("missing evidence remains Unknown"),
    "An incomplete native comparison cannot become a ranking");
nativeReport.Object("nativeComparison")["verdict"] = "unrecognized";
Check(NativeProductCampaignPresentation.Headline(nativeReport) == "Inconclusive · no qualified winner",
    "An unknown native verdict cannot become a winner");
Check(NativeProductCampaignPresentation.Headline(new JsonObject()) == "Comparative product quality · Unknown",
    "An unmeasured campaign remains unknown");
foreach (JsonNode? value in new JsonNode?[] { null, JsonValue.Create("0"), JsonValue.Create(true), new JsonObject() })
    Check(NativeProductCampaignPresentation.Metric(new JsonObject { ["cost"] = value }, "cost") == "Unknown",
        "Missing or nonnumeric native measurements are never coerced to zero");
Check(NativeProductCampaignPresentation.Metric(new JsonObject { ["cost"] = 0 }, "cost") == "0",
    "An observed zero remains different from unknown");
Check(NativeProductCampaignPresentation.Metric(new JsonObject { ["time"] = double.NaN }, "time") == "Unknown",
    "Nonfinite native measurements are not displayed as observations");
Check(NativeProductCampaignPresentation.Rate(new JsonObject { ["verifiedRate"] = null }) == "Unknown",
    "Partial verification does not manufacture a completion rate");
Check(NativeProductCampaignPresentation.Rate(new JsonObject { ["verifiedRate"] = 0.25 }) == "25%",
    "A canonical verified completion ratio is displayed as a percentage");
Check(NativeProductCampaignPresentation.Rate(new JsonObject { ["verifiedRate"] = 1.1 }) == "Unknown",
    "An invalid completion ratio stays unknown");
var customCampaignDefinition = new JsonObject
{
    ["tasks"] = new JsonArray(new JsonObject(), new JsonObject()),
    ["products"] = new JsonArray("GPT Mobile AI", "ChatGPT", "Codex", "Claude Code"),
    ["repeats"] = 3, ["maxSeconds"] = 600, ["maxCostUsd"] = 2.5, ["cache"] = "warm", ["marginPoints"] = 8
};
string campaignDefinitionSummary = NativeProductCampaignPresentation.CampaignDefinitionSummary(customCampaignDefinition);
Check(campaignDefinitionSummary.Contains("24 scheduled trials") && campaignDefinitionSummary.Contains("600 s") &&
    campaignDefinitionSummary.Contains("USD 2.5") && campaignDefinitionSummary.Contains("warm") && campaignDefinitionSummary.Contains("8 points"),
    "The campaign preview uses the selected definition's actual time, cost, repeats, cache and margin");
customCampaignDefinition["maxCostUsd"] = 0;
Check(NativeProductCampaignPresentation.CampaignDefinitionSummary(customCampaignDefinition).Contains("cost ceiling: USD 0"),
    "A zero campaign cost is displayed as a ceiling rather than a free-usage observation");
customCampaignDefinition["maxCostUsd"] = null;
customCampaignDefinition["repeats"] = null;
Check(NativeProductCampaignPresentation.CampaignDefinitionSummary(customCampaignDefinition).Contains("USD Unknown") &&
    NativeProductCampaignPresentation.CampaignDefinitionSummary(customCampaignDefinition).Contains("Unknown scheduled trials"),
    "Unknown campaign controls cannot become a zero budget or an invented trial count");
var unmeasuredNativeRows = NativeProductCampaignPresentation.ProductRows(new JsonObject()).ToArray();
Check(unmeasuredNativeRows.Length == 4 && unmeasuredNativeRows.All(row => row.Text("timing").Contains("Unknown") && row.Text("cost").Contains("Unknown")),
    "The empty scoreboard retains all four products with unknown timing and cost");
Check(NativeProductCampaignPresentation.SeparateMeasurements(new JsonObject()).Contains("GitHub Intelligence / 100: Unknown"),
    "Native final-answer evidence does not manufacture production GitHub Intelligence scores");
var unresolvedOperation = new JsonObject { ["receipts"] = new JsonArray(
    new JsonObject { ["scheduleIndex"] = 0, ["status"] = "UNRESOLVED", ["reason"] = "PRIVATE_CANDIDATE SECRET_KEY", ["requestId"] = "extref_" + new string('a', 32) },
    new JsonObject { ["scheduleIndex"] = 1, ["status"] = "RUNTIME_CHANGED_READBACK_ONLY", ["submissionState"] = "OBSERVED", ["state"] = "COMPLETED" },
    new JsonObject { ["scheduleIndex"] = 2, ["submissionState"] = "NOT_OBSERVED", ["requestId"] = "PRIVATE_CANDIDATE SECRET_KEY" }) };
Check(NativeProductCampaignPresentation.OperationHeadline(unresolvedOperation).Contains("1 unresolved") &&
    NativeProductCampaignPresentation.OperationHeadline(unresolvedOperation).Contains("1 readback-only"),
    "Unresolved and revision-changed grading receipts remain visible in the action summary");
var nativeReceiptRows = NativeProductCampaignPresentation.OperationRows(unresolvedOperation).ToArray();
Check(nativeReceiptRows[0].Text("state").Contains("extref_" + new string('a', 32)) &&
    nativeReceiptRows[0].Text("detail").Contains("recover performs status only"),
    "Recovery guidance retains the original validated request identity");
Check(nativeReceiptRows[1].Text("detail").Contains("cannot sign a new grade"),
    "A runtime-changed readback does not imply a new independent grade");
Check(nativeReceiptRows.All(row => !row.ToJsonString().Contains("PRIVATE_CANDIDATE") && !row.ToJsonString().Contains("SECRET_KEY")),
    "Operation summaries omit raw error payloads and invalid request identities");
Check(nativeReceiptRows[2].Text("detail").Contains("do not repeat or replace"),
    "Unobserved admission cannot be presented as safe resubmission");
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
var preview = new PublicPreview("turn");
JsonObject Observation(int sequence,string key,string text,string kind,double time) => new() {
    ["sequence"]=sequence,["coalescing_key"]=key,["public_text"]=text,["event_type"]=kind,["observed_at"]=time,
    ["fact"] = kind == "finding" ? new JsonObject { ["scope_id"]="turn",["criterion_id"]="tests",["subject_id"]=key,
        ["revision_id"]="source",["value"]="observed",["evidence_id"]="receipt" } : null };
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
foreach (var scenario in contract.Array("projection_cases"))
{
    var replay = new PublicPreview(scenario.Text("scope_id"));
    foreach (var step in scenario.Array("events"))
    {
        Check(replay.Project(step) == step.Text("expected"), "Public projection: " + scenario.Text("name"));
        if (step.Text("expected") == "correction")
            Check(replay.Lines.Last().Kind == "correction" && replay.Lines.Last().Text.Contains(step.Text("public_text")),
                "A scoped revision correction is visible");
    }
}
var correction = new PublicPreview("turn");
correction.Apply(Observation(1, "run", "Reading the PR", "activity", 100));
correction.Apply(Observation(2, "ci", "CI passed on the prior commit.", "finding", 145));
Check(correction.Lines.Any(line => line.Kind == "summary"), "A due, supported finding is summarized");
var moved = Observation(3, "ci", "The new commit is waiting for CI.", "finding", 146);
moved["fact"]!["revision_id"] = "new-commit";
moved["fact"]!["value"] = "pending";
Check(correction.Project(moved) == "correction" && correction.Lines.All(line => !line.Text.Contains("prior commit")),
    "A revision correction withdraws stale summary text as well as its source finding");
correction.Apply(Observation(4, "answer", "", "final", 150));
Check(!correction.Summarize(1000), "The start of the final answer seals pending public narration");
var motion = contract.Object("motion");
Check(PublicPreview.FadeInMs==motion.Number("entry_ms") && PublicPreview.RefreshMs==motion.Number("refresh_ms")
    && PublicPreview.ChangeIntervalMs==motion.Number("change_interval_ms") && PublicPreview.ActiveAccentMs==motion.Number("active_accent_ms")
    && PublicPreview.CollapsedLines==motion.Number("collapsed_lines"), "Windows preview motion matches the shared public event contract");
Check(1000*PublicPreview.FirstSummarySeconds==motion.Number("first_finding_ms")
    && 1000*PublicPreview.NextSummarySeconds==motion.Number("later_finding_ms"), "Findings use the shared summary cadence");
foreach(var example in contract.Array("reading"))
    Check(PublicPreview.ReadingMs((int)example!.Number("lines")!)==example.Number("duration_ms"), "Rendered-line reading example matches Android");
// Reproduce WPF's software glyph-over blend, rather than assuming every rendered alpha is 255.
for (int glyphAlpha = 1; glyphAlpha < 255; glyphAlpha++)
{
    byte renderedAlpha = (byte)(((255 * (255 - glyphAlpha)) >> 8) + glyphAlpha);
    byte[] glyphPixel = [0, 18, 25, renderedAlpha];
    Check(ReferenceCaptureCoverage.ValidatePbgra32(glyphPixel, 1, 1) == 1,
        "Fractional glyph coverage over an opaque background is fully rendered");
}
byte[] mixedCapture = [0, 18, 25, 255, 1, 2, 3, 254];
byte[] unchangedCapture = (byte[])mixedCapture.Clone();
Check(ReferenceCaptureCoverage.ValidatePbgra32(mixedCapture, 2, 1) == 1,
    "Capture validation reads alpha, not color channels");
Check(mixedCapture.SequenceEqual(unchangedCapture), "Capture validation preserves raw rendered pixels");
void RejectCapture(byte[] pixels, int width, int height, string name)
{
    try { ReferenceCaptureCoverage.ValidatePbgra32(pixels, width, height); }
    catch (InvalidOperationException) { Check(true, name); return; }
    throw new Exception(name);
}
foreach (byte alpha in new byte[] { 0, 1, 127, 253 })
    RejectCapture([0, 18, 25, 255, 0, 18, 25, alpha], 2, 1,
        "Pixels outside the documented glyph-alpha allowance are rejected");
byte repeatedGlyphAlpha = (byte)(((254 * (255 - 1)) >> 8) + 1);
RejectCapture([0, 18, 25, repeatedGlyphAlpha], 1, 1, "The allowance stays limited to a single glyph blend");
RejectCapture([0, 18, 25, 255], -1, 1, "Negative bitmap dimensions fail closed");
RejectCapture([0, 18, 25], 1, 1, "An incomplete final pixel fails closed");
RejectCapture(null!, 1, 1, "A missing bitmap buffer fails closed");
RejectCapture([], 0, 0, "An empty bitmap cannot pass reference coverage");
RejectCapture([0, 18, 25, 255], 2, 1, "A truncated bitmap cannot pass reference coverage");
RejectCapture(mixedCapture, 1, 1, "Extra pixel bytes cannot hide a dimension mismatch");
RejectCapture([], int.MaxValue, int.MaxValue, "Overflowing bitmap dimensions fail closed");

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
