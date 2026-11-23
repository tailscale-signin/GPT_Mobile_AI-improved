using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private void BuildBenchmarks()
    {
        int viewEpoch = epoch;
        var body = Ui.Column(Ui.Text("Engineering benchmark workspace", 22, bold: true),
            Ui.Text("Tool contracts · Simulated workflows · Executable coding · Live GitHub", 13, "PrimaryBrush"));
        var pages = new Dictionary<string, StackPanel>();
        var navigation = new WrapPanel();
        body.Children.Add(navigation);
        void Destination(string name)
        {
            foreach (var item in pages) item.Value.Visibility = item.Key == name ? Visibility.Visible : Visibility.Collapsed;
        }
        foreach (string name in new[] { "Overview", "Compare", "Tasks", "Evidence", "History" })
        {
            var content = new StackPanel { Visibility = name == "Overview" ? Visibility.Visible : Visibility.Collapsed };
            pages[name] = content;
            navigation.Children.Add(Ui.Button(name, () => Destination(name)));
            body.Children.Add(content);
        }
        var overview = pages["Overview"];
        var model = Ui.Select(overview, "Exact Gateway model", models);
        var suite = Ui.Select(overview, "Suite", ["Quick check", "HTTP scheduling comparison", "Executable coding", "Repository native"]);
        var preset = Ui.Select(overview, "Coding extent", ["Quick · 12", "Standard · 36", "Deep · 100"]);
        suite.SelectionChanged += (_, _) => {
            int previous = Math.Max(preset.SelectedIndex, 0);
            preset.ItemsSource = suite.SelectedIndex == 3 ? new[] { "Quick · 4", "Standard · 8", "Deep · 12" } : new[] { "Quick · 12", "Standard · 36", "Deep · 100" };
            preset.SelectedIndex = previous;
        };
        overview.Children.Add(Ui.Text("Quick check keeps five short fixtures. Coding uses twelve problem families with seeded variants; Deep adds four capstones. The phone's general Quick/Full screen is unchanged. Coding results certify only the executed fixture contract.", 13, "MutedBrush"));
        var metric = Ui.Text("Choose a saved result to inspect measured completion and qualification.", 15, "MutedBrush");
        var graph = new StackPanel();
        overview.Children.Add(metric); overview.Children.Add(graph);
        var runs = new ListBox { MaxHeight = 330 };
        var tasks = new ListBox { MaxHeight = 480 };
        pages["Tasks"].Children.Add(Ui.Text("Task outcomes and retained attempts", 18, bold: true));
        pages["Tasks"].Children.Add(Ui.Text("Inspect first submission, final submitted candidate and best observed candidate separately. Best historical points do not establish reliable completion.", 13, "MutedBrush"));
        pages["Tasks"].Children.Add(tasks);
        var evidence = new TextBox { IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, MaxHeight = 560, MinHeight = 180 };
        pages["Evidence"].Children.Add(Ui.Text("Retained execution evidence", 18, bold: true));
        pages["Evidence"].Children.Add(evidence);
        JsonObject? observedRun = null, observedComparison = null, observedQualification = null;
        string? before = null;
        var history = new List<JsonNode?>();
        var comparisonRuns = new ListBox { MaxHeight = 250 };
        var qualificationRuns = new ListBox { MaxHeight = 200 };
        bool comparisonBusy = false;
        void Evidence(JsonObject value) => evidence.Text = value.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        async Task History(bool more = false)
        {
            var result = await Call("benchmark-history", more && before is not null ? new JsonObject { ["before"] = before } : null);
            if (epoch != viewEpoch) return;
            if (!more) history.Clear();
            history.AddRange(result.Array("runs").Select(row => row?.DeepClone()));
            before = result["next"]?.ToString();
            Ui.Rows(runs, history.Select(row => row is JsonObject obj ? new JsonObject(obj.Select(pair => KeyValuePair.Create(pair.Key, pair.Value?.DeepClone())))
            { ["title"] = obj.Object("request").Text("model") + " · " + obj.Object("request").Text("suite") } : row), "title", "state");
            var saved = await Call("benchmark-compare-history");
            if (epoch != viewEpoch) return;
            Ui.Rows(comparisonRuns, saved.Array("comparisons").OfType<JsonObject>().Select(row => new JsonObject
            {
                ["id"] = row.Text("id"), ["title"] = string.Join(" vs ", row.Object("manifest").Array("models").Select(value => value?.ToString())),
                ["state"] = row.Text("state"), ["detail"] = row.Object("summary").Text("status", "Not yet qualified")
            }));
            var live = await Call("benchmark-qualification-history");
            if (epoch != viewEpoch) return;
            Ui.Rows(qualificationRuns, live.Array("qualifications").OfType<JsonObject>().Select(row => new JsonObject {
                ["id"] = row.Text("localId"), ["title"] = row.Object("route").Text("model", "Frozen manifest route"),
                ["state"] = row.Text("state") }));
        }
        async Task Observe()
        {
            var selected = Ui.Selected(runs) ?? throw new InvalidOperationException("Select a saved benchmark run.");
            var result = await Call("benchmark-read", new JsonObject { ["id"] = selected.Text("id") });
            if (epoch != viewEpoch) return;
            observedRun = result["run"] as JsonObject ?? result;
            var summary = observedRun.Object("summary");
            string runSuite = observedRun.Object("request").Text("suite");
            string progress = $"{observedRun.Text("state")} · {observedRun.Text("completed", "0")} / {observedRun.Text("planned", "0")} trials";
            metric.Text = runSuite == "coding"
                ? progress + $" · {summary.Text("finalResolved", summary.Text("resolved", "0"))} final verified resolutions · " +
                  (summary.Flag("qualified") ? "Qualified for this fixture suite" : "Qualification incomplete") +
                  $"\nFirst {summary.Text("initialResolved", summary.Text("firstSubmissionResolved", "Unknown"))} · final {summary.Text("finalResolved", "Unknown")} · best {summary.Text("bestResolved", "Unknown")} · secondary rubric {summary.Text("score", "Unavailable")} / 1000"
                : runSuite == "batch_comparison" ? progress + " · HTTP scheduling only · " +
                  (summary.Flag("qualified") ? "Qualified matched scheduling comparison" : "Exploratory; no qualified speedup")
                : progress + $" · contract score {summary.Text("score", "Unavailable")} / {summary.Text("maximum", "100")}";
            graph.Children.Clear();
            foreach (var row in summary.Object("dimensions"))
            {
                if (row.Value is not JsonObject dimension) continue;
                graph.Children.Add(Ui.Text($"{row.Key} · {dimension.Text("value", "Unmeasured")} / {dimension.Text("weight", "—")} points", 13));
                if (dimension.Number("value") is { } value && dimension.Number("weight") is > 0)
                    graph.Children.Add(new ProgressBar { Minimum = 0, Maximum = dimension.Number("weight")!.Value, Value = value,
                        Height = 12, Margin = new Thickness(0, 0, 0, 10), Foreground = Ui.Brush("PrimaryBrush"), Background = Ui.Brush("RaisedBrush") });
            }
            Ui.Rows(tasks, observedRun.Array("cases"), "title", "status");
            Evidence(observedRun); Destination("Overview");
            status.Text = observedRun.Text("progress", "Saved benchmark loaded.");
        }
        tasks.SelectionChanged += (_, _) => { if (Ui.Selected(tasks) is { } task) { Evidence(task); Destination("Evidence"); } };
        overview.Children.Add(Ui.Actions(Ui.Button("Start benchmark", () => Run(async () =>
        {
            await EnsureConnected();
            await Call("benchmark-start", new JsonObject { ["model"] = model.SelectedItem?.ToString(),
                ["suite"] = new[] { "quick_check", "batch_comparison", "coding", "coding" }[Math.Max(suite.SelectedIndex, 0)],
                ["task_pack"] = suite.SelectedIndex == 3 ? "repository" : "python",
                ["preset"] = new[] { "quick", "standard", "deep" }[Math.Max(preset.SelectedIndex, 0)], ["request_id"] = "desktop-" + Guid.NewGuid().ToString("N") });
            status.Text = "Benchmark accepted by Gateway. Admitted work keeps running when this window closes."; await History();
        }), true), Ui.Button("Check original submission", () => Run(async () => { Evidence(await Call("benchmark-reconcile")); Destination("Evidence"); await History(); })),
            Ui.Button("Setup coding sandbox", () => Navigate("Tools/Environments")),
            Ui.Button("Live qualification", () => Destination("History"))));
        async Task StartQualification(bool execute)
        {
            var file = new Microsoft.Win32.OpenFileDialog { Filter = "Frozen disposable qualification manifest|*.json" };
            if (file.ShowDialog(this) != true) return;
            observedQualification = await Call("benchmark-qualification-start", new JsonObject { ["path"] = file.FileName, ["execute"] = execute });
            Evidence(observedQualification); Destination("Evidence");
            status.Text = "Qualification receipt retained. Check reads evidence; Resume submits only previously authorized missing jobs.";
            await History();
        }
        async Task ReadQualification(bool execute)
        {
            string? id = Ui.Selected(qualificationRuns)?.Text("id") ?? observedQualification?.Text("localId");
            observedQualification = await Call(execute ? "benchmark-qualification-resume" : "benchmark-qualification-read", new JsonObject { ["id"] = id });
            Evidence(observedQualification); Destination("Evidence");
            status.Text = observedQualification.Text("recovery", "Live qualification: " + observedQualification.Text("state"));
            await History();
        }

        var compare = pages["Compare"];
        compare.Children.Add(Ui.Text("Compare two models on matched work", 20, bold: true));
        var first = Ui.Select(compare, "Model A · exact Gateway model", models);
        var second = Ui.Select(compare, "Model B · exact Gateway model", models);
        var extent = Ui.Select(compare, "Evidence and extent", ["Coding Quick · 12", "Coding Standard · 36", "Coding Deep · 100", "Native Quick · 4", "Native Standard · 8", "Native Deep · 12", "Contract Quick · 5"]);
        var repetitions = Ui.Select(compare, "Fresh episodes per model", ["1", "3", "5", "10"], "3");
        var counterbalanced = Ui.Toggle(compare, "Counterbalance order; otherwise run A then B", false);
        compare.Children.Add(Ui.Text("Gateway settings, task seeds, route and repair allowance are frozen. These are Gateway model selections, not saved Android profiles. Use the phone to compare saved profiles or simulated workflows.", 13, "MutedBrush"));
        var comparisonStatus = Ui.Text("No matched comparison. One model or incomplete evaluator evidence produces an inconclusive result.", 14, "MutedBrush");
        var comparisonGraph = new StackPanel();
        void RenderComparison(JsonObject result)
        {
            observedComparison = result;
            var summary = result.Object("summary");
            var manifest = result.Object("manifest");
            var lines = new List<string> { result.Text("state"), BenchmarkComparisonPresentation.Headline(summary),
                $"{summary.Text("pairedTasks", "0")} matched variants · {summary.Text("independentTaskGroups", "0")} task families · {manifest.Text("repeats")} fresh episodes per model" };
            comparisonGraph.Children.Clear();
            foreach (var arm in summary.Array("models").OfType<JsonObject>())
            {
                var measured = arm.Object("measurements");
                lines.Add($"{arm.Text("model")}: {arm.Text("resolved", "0")}/{arm.Text("planned", "0")} final resolutions · first {arm.Text("initialResolved", "Unknown")} · best {arm.Text("bestResolved", "Unknown")} · {arm.Text("completedEpisodes", "0")}/{arm.Text("plannedEpisodes")} episodes\nAll-in wall time {measured.Text("endToEndMs", "Unknown")} ms · model-request time {measured.Text("totalMs", "Unknown")} ms · input/output tokens {measured.Text("inputTokens", "Unknown")}/{measured.Text("outputTokens", "Unknown")} · cost {measured.Text("cost", "Unknown")}");
                comparisonGraph.Children.Add(Ui.Text(arm.Text("model"), 13));
                if (arm.Number("planned") is > 0 && arm.Number("resolved") is { } resolved)
                    comparisonGraph.Children.Add(new ProgressBar { Minimum = 0, Maximum = arm.Number("planned")!.Value, Value = resolved,
                        Height = 12, Margin = new Thickness(0, 0, 0, 12), Foreground = Ui.Brush("PrimaryBrush"), Background = Ui.Brush("RaisedBrush") });
            }
            if (summary.Array("confidenceInterval95").Count == 2)
                lines.Add($"Family-weighted difference {summary.Text("difference")} · 95% interval {summary.Array("confidenceInterval95").ToJsonString()} · practical margin {summary.Text("practicalMargin")}");
            lines.AddRange(summary.Array("reasons").Select(reason => reason?.ToString() ?? ""));
            if (result.Text("recovery").Length > 0) lines.Add(result.Text("recovery"));
            lines.Add(manifest.Text("queueLifecycle"));
            comparisonStatus.Text = string.Join("\n\n", lines);
            Evidence(result);
        }
        async Task ComparisonStep()
        {
            if (comparisonBusy || observedComparison is null || epoch != viewEpoch) return;
            comparisonBusy = true;
            try { var result = await Call("benchmark-compare-advance", new JsonObject { ["id"] = observedComparison.Text("id") }); if (epoch == viewEpoch) RenderComparison(result); }
            finally { comparisonBusy = false; }
        }
        compare.Children.Add(Ui.Actions(Ui.Button("Start matched comparison", () => Run(async () =>
        {
            if (comparisonBusy) return;
            comparisonBusy = true;
            try
            {
                await EnsureConnected();
                int selected = Math.Max(extent.SelectedIndex, 0);
                var result = await Call("benchmark-compare-create", new JsonObject {
                    ["models"] = new JsonArray(first.SelectedItem?.ToString(), second.SelectedItem?.ToString()),
                    ["suite"] = selected == 6 ? "quick_check" : "coding", ["preset"] = new[] { "quick", "standard", "deep", "quick", "standard", "deep", "quick" }[selected],
                    ["task_pack"] = selected is >= 3 and <= 5 ? "repository" : "python",
                    ["repeats"] = int.Parse(repetitions.SelectedItem?.ToString() ?? "3"), ["ordering"] = counterbalanced.IsChecked == true ? "COUNTERBALANCED" : "SELECTED_ORDER" });
                if (epoch == viewEpoch) RenderComparison(result);
            }
            finally { comparisonBusy = false; }
        }), true), Ui.Button("Check / resume", () => Run(ComparisonStep)), Ui.Button("Stop comparison", () => Run(async () =>
        {
            if (observedComparison is null || comparisonBusy) return;
            comparisonBusy = true;
            try { RenderComparison(await Call("benchmark-compare-stop", new JsonObject { ["id"] = observedComparison.Text("id") })); }
            finally { comparisonBusy = false; }
        })), Ui.Button("Export comparison", () => Run(async () => {
            if (observedComparison is null) return;
            var file = new Microsoft.Win32.SaveFileDialog { Filter = "Comparison evidence|*.json", FileName = "engineering-comparison.json" };
            if (file.ShowDialog(this) == true) {
                await Call("benchmark-compare-export", new JsonObject { ["id"] = observedComparison.Text("id"), ["path"] = file.FileName });
                status.Text = "Complete retained comparison evidence exported.";
            }
        }))));
        compare.Children.Add(comparisonStatus); compare.Children.Add(comparisonGraph);
        pages["History"].Children.Add(Ui.Text("Saved matched comparisons", 18, bold: true));
        pages["History"].Children.Add(comparisonRuns);
        pages["History"].Children.Add(Ui.Button("Open saved comparison", () => Run(async () => {
            var selected = Ui.Selected(comparisonRuns) ?? throw new InvalidOperationException("Select a saved comparison.");
            RenderComparison(await Call("benchmark-compare-read", new JsonObject { ["id"] = selected.Text("id") })); Destination("Compare");
        })));
        pages["History"].Children.Add(Ui.Text("Live GitHub qualification", 18, bold: true));
        pages["History"].Children.Add(Ui.Text("Choose a frozen disposable-fixture manifest with exact repository, benchmark branches, base SHA, profile, model, route and settings. Read evidence submits no jobs. Execute admits its fixed requests; saved receipts survive closing the desktop.", 13, "MutedBrush"));
        pages["History"].Children.Add(qualificationRuns);
        pages["History"].Children.Add(Ui.Actions(Ui.Button("Read manifest evidence", () => Run(() => StartQualification(false))),
            Ui.Button("Execute frozen manifest", () => Run(() => StartQualification(true))),
            Ui.Button("Check saved submission", () => Run(() => ReadQualification(false))),
            Ui.Button("Resume authorized jobs", () => Run(() => ReadQualification(true)))));
        pages["History"].Children.Add(Ui.Text("Saved individual runs", 18, bold: true));
        pages["History"].Children.Add(runs);
        pages["History"].Children.Add(Ui.Actions(Ui.Button("Refresh history", () => Run(() => History())),
            Ui.Button("Earlier runs", () => Run(async () => { if (before is not null) await History(true); })),
            Ui.Button("Open result", () => Run(Observe)),
            Ui.Button("Export run", () => { if (observedRun is not null) SaveJson(observedRun, "gateway-benchmark.json"); }),
            Ui.Button("Stop selected run", () => Run(async () => {
                var selected = Ui.Selected(runs) ?? throw new InvalidOperationException("Select a benchmark run.");
                await Call("benchmark-stop", new JsonObject { ["id"] = selected.Text("id") }); await History();
            }))));
        var comparisonTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        comparisonTimer.Tick += (_, _) => {
            if (epoch != viewEpoch || closed) { comparisonTimer.Stop(); return; }
            if (observedComparison?.Text("state") is "READY" or "RUNNING" or "STOPPING") Run(ComparisonStep);
        };
        body.Unloaded += (_, _) => comparisonTimer.Stop();
        comparisonTimer.Start();
        page.Children.Add(Ui.Card(body));
        Run(async () => { await EnsureConnected(); if (epoch != viewEpoch) return;
            foreach (var select in new[] { model, first, second }) { select.ItemsSource = models; if (models.Length > 0) select.SelectedIndex = select == second && models.Length > 1 ? 1 : 0; }
            await History(); });
    }
}
