using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public sealed class PublicPreview(string scopeId)
{
    public const int FadeInMs=1000, RefreshMs=500, ChangeIntervalMs=3000, FirstSummarySeconds=45, NextSummarySeconds=135;
    public const int ActiveAccentMs=1800, CollapsedLines=2;
    public static int ReadingMs(int lines) => lines <= 0 ? 0 : lines==1 ? 1500 : 1000*lines;
    public sealed record Source(string Url, string Title);
    public sealed record Line(string Key, string Text, double Time, string Kind, IReadOnlyList<string>? FactKeys = null,
        string? Revision = null, string? EvidenceId = null, IReadOnlyList<Source>? Sources = null);
    private readonly List<Line> lines = [];
    private readonly Dictionary<string, string> pendingFindings = [];
    private readonly Dictionary<string, (string Revision, string Value)> facts = [];
    private double? started, lastSummary;
    public long Cursor { get; private set; }
    public bool FinalStarted { get; private set; }
    public IReadOnlyList<Line> Lines => lines;

    public string Project(JsonNode? row)
    {
        if (row is not JsonObject || row.Number("sequence") is not double sequence ||
            !double.IsFinite(sequence) || sequence != Math.Truncate(sequence) || sequence <= Cursor || sequence >= long.MaxValue ||
            row.Number("observed_at") is not double time || !double.IsFinite(time)) return "suppressed";
        Cursor = (long)sequence;
        if (FinalStarted) return "suppressed";
        string kind = row.Text("event_type", "");
        if (kind == "final")
        {
            FinalStarted = true;
            pendingFindings.Clear();
            return "final";
        }
        string text = row.Text("public_text", ""), key = row.Text("coalescing_key", "");
        if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(text)) return "suppressed";
        if (kind is not ("activity" or "tool_start" or "finding" or "blocker" or "recovery")) return "suppressed";
        string? revisionId = null, evidenceId = null;
        if (kind == "finding" && row["fact"] is JsonObject fact)
        {
            string criterion = fact.Text("criterion_id", ""), subject = fact.Text("subject_id", "");
            string revision = fact.Text("revision_id", ""), value = fact.Text("value", "");
            if (scopeId.Length == 0 || fact.Text("scope_id", "") != scopeId ||
                new[]{criterion, subject, revision, value, fact.Text("evidence_id", "")}.Any(string.IsNullOrWhiteSpace))
                return "suppressed";
            // Length-prefix each component so a delimiter in a subject cannot alias another identity.
            key = $"fact:{criterion.Length}:{criterion}{subject}";
            if (facts.TryGetValue(key, out var previous))
            {
                if (previous == (revision, value)) return "suppressed";
                kind = "correction";
                text = (previous.Revision == revision ? "The result changed. " : "The revision changed. ") + text;
                lines.RemoveAll(line => line.Key == key || line.FactKeys?.Contains(key) == true);
            }
            revisionId = revision;
            evidenceId = fact.Text("evidence_id", "");
            facts[key] = (revision, value);
            pendingFindings[key] = text;
        }
        else if (kind == "finding")
        {
            // A saved legacy finding without an evidence binding cannot substantiate a new claim.
            return "suppressed";
        }
        started ??= time;
        var sources = row.Array("sources").Select(source => new Source(source.Text("url", ""), source.Text("title", "")))
            .Where(source => Uri.TryCreate(source.Url, UriKind.Absolute, out var uri) && (uri.Scheme is "http" or "https") && uri.UserInfo.Length == 0)
            .Take(20).ToArray();
        var entry = new Line(key, text, time, kind, Revision: revisionId, EvidenceId: evidenceId, Sources: sources);
        int index = lines.FindIndex(line => line.Key == key);
        if (index < 0) lines.Add(entry); else lines[index] = entry;
        Summarize(time);
        Trim();
        return kind == "tool_start" ? "activity" : kind;
    }

    public bool Apply(JsonNode? row) => Project(row) != "suppressed";

    public bool Summarize(double now)
    {
        if (FinalStarted || started is null || pendingFindings.Count == 0 ||
            (lastSummary.HasValue ? now-lastSummary.Value < NextSummarySeconds : now-started.Value < FirstSummarySeconds))
            return false;
        var included = pendingFindings.TakeLast(3).ToArray();
        lines.Add(new Line("summary-"+Cursor, string.Join(" ", included.Select(f => f.Value)), now, "summary",
            included.Select(f => f.Key).ToArray()));
        pendingFindings.Clear();
        lastSummary = now;
        Trim();
        return true;
    }

    private void Trim()
    {
        if (lines.Count > 20) lines.RemoveRange(0, lines.Count-20);
        // The durable event journal bounds replay to 4096 events per Gateway.
        if (facts.Count > 4096) facts.Remove(facts.Keys.First());
        if (pendingFindings.Count > 20) pendingFindings.Remove(pendingFindings.Keys.First());
    }
}
