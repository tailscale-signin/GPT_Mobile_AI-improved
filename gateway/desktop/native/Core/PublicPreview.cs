using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public sealed class PublicPreview
{
    public const int FadeInMs=1000, RefreshMs=500, ChangeIntervalMs=3000, FirstSummarySeconds=45, NextSummarySeconds=135;
    public const int ActiveAccentMs=1800, CollapsedLines=2;
    public static int ReadingMs(int lines) => lines <= 0 ? 0 : lines==1 ? 1500 : 1000*lines;
    public sealed record Line(string Key,string Text,double Time,string Kind);
    private readonly List<Line> lines=[];
    private readonly List<string> findings=[];
    private readonly HashSet<string> knownFindings=[];
    private double? started, lastSummary;
    public long Cursor { get; private set; }
    public IReadOnlyList<Line> Lines=>lines;
    public bool Apply(JsonNode? row)
    {
        if(row is not JsonObject || row.Number("sequence") is not double sequence || sequence<=Cursor ||
            row.Number("observed_at") is not double time || row.Text("public_text","").Length==0)return false;
        Cursor=(long)sequence;started??=time;var key=row.Text("coalescing_key");string text=row.Text("public_text");
        var line=new Line(key,text,time,row.Text("event_type"));int index=lines.FindIndex(l=>l.Key==key);
        if(index<0)lines.Add(line);else lines[index]=line;
        if(line.Kind=="finding" && knownFindings.Add(text))findings.Add(text);
        Summarize(time);
        if(lines.Count>20)lines.RemoveRange(0,lines.Count-20);return true;
    }
    public bool Summarize(double now)
    {
        if(started is null || findings.Count==0 || (lastSummary.HasValue ? now-lastSummary.Value<NextSummarySeconds : now-started.Value<FirstSummarySeconds))return false;
        lines.Add(new Line("summary-"+Cursor,string.Join(" ",findings.TakeLast(3)),now,"summary"));
        findings.Clear();lastSummary=now;if(lines.Count>20)lines.RemoveAt(0);return true;
    }
}
