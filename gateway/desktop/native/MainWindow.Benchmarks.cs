using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private void BuildBenchmarks()
    {
        int viewEpoch = epoch;
        var body = Ui.Column(Ui.Text("Measure a model through this Gateway", 20, bold: true));
        var model = Ui.Select(body, "Exact model", models);
        var suite = Ui.Select(body, "Suite", ["Quick check", "Batch comparison", "Repository coding"]);
        var preset = Ui.Select(body, "Run length", ["quick", "standard", "deep"]);
        body.Children.Add(Ui.Text("Quick check and HTTP batch comparison need no Docker. Repository coding runs isolated fixtures in an approved sandbox. Scores do not certify live GitHub or native GPU batching.", 13, "MutedBrush"));
        var runs = new ListBox { Height = 220 }; JsonObject? observedRun = null; string? before = null;
        var metric = Ui.Text("Select a saved run for its measured score and evidence.", 15, "MutedBrush");
        var graph = new StackPanel();
        var history = new List<JsonNode?>();
        async Task History(bool more = false)
        {
            var result = await Call("benchmark-history", more && before is not null ? new JsonObject { ["before"] = before } : null);
            if (!more) history.Clear();
            history.AddRange(result.Array("runs").Select(row => row?.DeepClone()));
            before = result["next"]?.ToString();
            Ui.Rows(runs, history.Select(row => row is JsonObject obj ? new JsonObject(obj.Select(pair => KeyValuePair.Create(pair.Key, pair.Value?.DeepClone())))
            { ["title"] = obj.Object("request").Text("model") + " · " + obj.Object("request").Text("suite") } : row), "title", "state");
        }
        async Task Observe()
        {
            var selected = Ui.Selected(runs) ?? throw new InvalidOperationException("Select a benchmark run.");
            var result = await Call("benchmark-read", new JsonObject { ["id"] = selected.Text("id") });
            observedRun = result["run"] as JsonObject ?? result;
            var summary = observedRun.Object("summary");
            bool batch = observedRun.Object("request").Text("suite") == "batch_comparison";
            string progress = $"{observedRun.Text("state")} · {observedRun.Text("completed", "0")} / {observedRun.Text("planned", "0")} trials";
            string ratio = summary.Number("observedSpeedup") is { } measuredRatio ? measuredRatio.ToString("0.00") + "×" : "Unavailable";
            metric.Text = batch ? progress + $" · observed makespan ratio {ratio} · " +
                (summary.Flag("qualified") ? "Qualified matched comparison" : "Exploratory · qualification incomplete") + $" · {summary.Text("pairedCount", "0")} complete pairs"
                : progress + $" · score {summary.Text("score", "Ungraded")} / {summary.Text("maximum", "—")}";
            graph.Children.Clear();
            if (batch)
            {
                var makespan = summary.Object("makespanMs");
                var measurements = new[] { "sequential", "concurrent" }.Select(arm => new
                { Arm = arm, Count = makespan.Object(arm).Number("count") ?? 0, Median = makespan.Object(arm).Number("median") }).ToArray();
                double maximum = Math.Max(1, measurements.Where(item => item.Count > 0 && item.Median is >= 0).Select(item => item.Median!.Value).DefaultIfEmpty().Max());
                foreach (var item in measurements)
                {
                    if (item.Count <= 0 || item.Median is not >= 0)
                    { graph.Children.Add(Ui.Text(item.Arm + " · median unavailable · no measured samples", 13, "MutedBrush")); continue; }
                    graph.Children.Add(Ui.Text($"{item.Arm} · {item.Median.Value:N1} ms median · {item.Count:N0} samples", 13));
                    graph.Children.Add(new ProgressBar { Minimum = 0, Maximum = maximum,
                        Value = item.Median.Value, Height = 12, Margin = new Thickness(0, 0, 0, 12),
                        Foreground = Ui.Brush("PrimaryBrush"), Background = Ui.Brush("RaisedBrush") });
                }
            }
            status.Text = observedRun.Text("progress", "Saved benchmark loaded.");
        }
        body.Children.Add(Ui.Actions(Ui.Button("Start benchmark", () => Run(async () =>
        {
            await EnsureConnected();
            var result = await Call("benchmark-start", new JsonObject { ["model"] = model.SelectedItem?.ToString(),
                ["suite"] = new[] { "quick_check", "batch_comparison", "coding" }[Math.Max(suite.SelectedIndex, 0)],
                ["preset"] = preset.SelectedItem?.ToString(), ["request_id"] = "desktop-" + Guid.NewGuid().ToString("N") });
            status.Text = "Benchmark accepted by Gateway. Closing this window keeps it running."; await History();
        }), true), Ui.Button("Check original submission", () => Run(async () => { ShowDetails("Benchmark receipt", await Call("benchmark-reconcile")); await History(); })),
            Ui.Button("Setup coding sandbox", () => Navigate("Tools/Environments")),
            Ui.Button("Production qualification", () => Run(async () => {
                var file=new Microsoft.Win32.OpenFileDialog{Filter="Qualification manifest|*.json"};
                if(file.ShowDialog(this)==true)ShowDetails("Production qualification",await Call("workspace-qualification",new JsonObject{["path"]=file.FileName}));
            }))));
        body.Children.Add(runs); body.Children.Add(metric); body.Children.Add(graph);
        body.Children.Add(Ui.Actions(Ui.Button("Refresh history", () => Run(() => History())),
            Ui.Button("Earlier runs", () => Run(async () => { if (before is not null) await History(true); })),
            Ui.Button("Open result", () => Run(Observe)),
            Ui.Button("Evidence", () => { if (observedRun is not null) ShowDetails("Benchmark evidence", observedRun); }),
            Ui.Button("Export", () => { if (observedRun is not null) SaveJson(observedRun, "gateway-benchmark.json"); }),
            Ui.Button("Stop selected run", () => Run(async () =>
            {
                var selected = Ui.Selected(runs) ?? throw new InvalidOperationException("Select a benchmark run.");
                await Call("benchmark-stop", new JsonObject { ["id"] = selected.Text("id") }); await History();
            }))));
        page.Children.Add(Ui.Card(body));
        Run(async () => { await EnsureConnected(); if (epoch != viewEpoch) return; model.ItemsSource = models; if (models.Length > 0) model.SelectedIndex = 0; await History(); });
    }
}
