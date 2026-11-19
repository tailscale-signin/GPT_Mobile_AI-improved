using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using Gateway.Desktop.Core;
using Microsoft.Win32;

namespace Gateway.Desktop;

public partial class MainWindow : Window
{
    internal static readonly string[] Pages = ["Overview", "Chat", "Saved", "Jobs", "Tools", "Benchmarks", "Android controls", "Diagnostics", "Settings"];
    private readonly string root;
    private readonly BridgeClient bridge;
    private readonly StackPanel page = new();
    private readonly TextBlock heading = Ui.Text("Overview", 34, bold: true);
    private readonly TextBlock subtitle = Ui.Text("Your local workspace", color: "MutedBrush");
    private readonly TextBlock status = Ui.Text("Reading this installation…", 13, "MutedBrush");
    private readonly TextBlock connection = Ui.Text("Connecting", 13, "MutedBrush");
    private readonly ScrollViewer pageScroll;
    private readonly Dictionary<string, Button> navigation = [];
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly double openedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000d;
    private JsonObject snapshot = [];
    private JsonObject? identity;
    private string[] models = [];
    private string selectedPage = "Overview";
    private TextBlock? updateInfo;
    private Button? checkUpdate, installUpdate;
    private bool requestingUpdate;
    private bool refreshing;
    private bool closed;
    private bool sending;
    private bool detachSafe;
    private bool suppressMotion;
    private int epoch;
    private readonly Dictionary<string, double> handledWindowRequests = [];
    private readonly HashSet<Task> uiWork = [];

    public MainWindow(string[] args)
    {
        static string? Arg(string[] arguments, string name)
        {
            int index = Array.IndexOf(arguments, name);
            return index >= 0 && index + 1 < arguments.Length ? arguments[index + 1] : null;
        }
        root = System.IO.Path.GetFullPath(Arg(args, "--root") ?? throw new ArgumentException("An installed Gateway folder is required."));
        string runtime = System.IO.Path.GetFullPath(Arg(args, "--runtime") ?? System.IO.Path.Combine(AppContext.BaseDirectory, "..", "Gateway.exe"));
        if (!File.Exists(System.IO.Path.Combine(root, "current.json"))) throw new ArgumentException("This Gateway is not configured. Run Setup first.");
        bridge = new BridgeClient(root, runtime);
        Title = "GPT Mobile · Gateway";
        var workArea = SystemParameters.WorkArea;
        MinWidth = Math.Min(740, workArea.Width); MinHeight = Math.Min(480, workArea.Height);
        Width = Math.Min(1220, workArea.Width); Height = Math.Min(820, workArea.Height);
        UseLayoutRounding = SnapsToDevicePixels = true;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var layout = new Grid();
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(210) });
        layout.ColumnDefinitions.Add(new ColumnDefinition());
        var rail = Ui.Column(Ui.Text("GPT MOBILE", 12, "PrimaryBrush", true), Ui.Text("Gateway", 28, bold: true),
            Ui.Text(Environment.MachineName, 13, "MutedBrush"));
        rail.Margin = new Thickness(16, 24, 16, 20);
        rail.Children.Add(Ui.Button("＋ New prompt", () => { openedConversation = null; transcript = []; Navigate("Chat"); }, true));
        rail.Children.Add(Ui.Text("WORKSPACE", 11, "MutedBrush"));
        foreach (string name in Pages)
        {
            var button = Ui.Button(name, () => Navigate(name));
            button.HorizontalContentAlignment = HorizontalAlignment.Left;
            button.Margin = new Thickness(0, 2, 0, 2);
            navigation[name] = button; rail.Children.Add(button);
        }
        rail.Children.Add(Ui.Text("Closing this window keeps accepted Gateway work running.", 12, "MutedBrush"));
        var railScroll = new ScrollViewer { Content = rail, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        railScroll.SetResourceReference(BackgroundProperty, "PanelBrush");
        layout.Children.Add(railScroll);
        var workspace = new Grid { Margin = new Thickness(28, 24, 28, 16) };
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        workspace.RowDefinitions.Add(new RowDefinition());
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var header = Ui.Column(connection, heading, subtitle);
        workspace.Children.Add(header);
        pageScroll = new ScrollViewer { Content = page, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(0, 8, 12, 0) };
        Grid.SetRow(pageScroll, 1); workspace.Children.Add(pageScroll);
        status.Margin = new Thickness(0, 12, 0, 0);
        System.Windows.Automation.AutomationProperties.SetLiveSetting(status, System.Windows.Automation.AutomationLiveSetting.Polite);
        Grid.SetRow(status, 2); workspace.Children.Add(status);
        Grid.SetColumn(workspace, 1); layout.Children.Add(workspace); Content = layout;
        Loaded += async (_, _) =>
        {
            await RefreshSnapshot(); Navigate(Arg(args, "--view") ?? "Overview"); timer.Start();
            if (args.Contains("--smoke-ui")) await Smoke();
        };
        timer.Tick += async (_, _) => { PollWindowRequests(); await RefreshSnapshot(); };
        Closing += (_, e) =>
        {
            if (sending && !detachSafe && MessageBox.Show(this,
                    "Gateway has not confirmed that this send can continue without its connection. Close the window and check its saved receipt later?",
                    "Send still connecting", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
            { e.Cancel = true; return; }
            closed = true; timer.Stop();
        };
        PreviewKeyDown += (_, e) => { if (e.Key == Key.F5) { e.Handled = true; Run(RefreshSnapshot); } };
    }

    private async void Run(Func<Task> action)
    {
        Task? work = null;
        try { work = action(); uiWork.Add(work); await work; }
        catch (Exception error) { if (!closed) status.Text = error.Message; }
        finally { if (work is not null) uiWork.Remove(work); }
    }

    private async Task<JsonObject> Call(string action, JsonObject? args = null)
    {
        var result = await bridge.Call(action, args);
        return result;
    }

    private JsonObject WithIdentity(JsonObject? value = null)
    {
        if (identity is null) throw new InvalidOperationException("Connect to this Gateway before continuing.");
        value ??= [];
        value["identity"] = identity.DeepClone();
        return value;
    }

    private void Navigate(string name)
    {
        if (!Pages.Contains(name)) name = "Overview";
        updateInfo = null; checkUpdate = installUpdate = null;
        selectedPage = name; epoch++; page.Children.Clear(); pageScroll.ScrollToTop(); heading.Text = name;
        foreach (var (key, button) in navigation)
        {
            button.SetResourceReference(BackgroundProperty, key == name ? "RaisedBrush" : "PanelBrush");
            button.SetResourceReference(ForegroundProperty, key == name ? "PrimaryBrush" : "MutedBrush");
        }
        subtitle.Text = name switch
        {
            "Overview" => "Measured activity and health for " + Environment.MachineName,
            "Chat" => "Your selected model · saved on this Gateway",
            "Saved" => "Conversations, favorites and bookmarked messages",
            "Jobs" => "Accepted work stays with the Gateway when this window closes",
            "Tools" => "Capabilities, connections and host-approved resources",
            "Benchmarks" => "Measure this route using real runs and saved evidence",
            "Android controls" => "Approve owner devices and mirror confirmed settings",
            "Diagnostics" => "Observed state, recovery and redacted exports",
            _ => "Shared settings · applies to " + Environment.MachineName
        };
        switch (name)
        {
            case "Overview": BuildOverview(); break;
            case "Chat": BuildChat(); break;
            case "Saved": BuildSaved(); break;
            case "Jobs": BuildJobs(); break;
            case "Tools": BuildTools(); break;
            case "Benchmarks": BuildBenchmarks(); break;
            case "Android controls": BuildAndroid(); break;
            case "Diagnostics": BuildDiagnostics(); break;
            case "Settings": BuildSettings(); break;
        }
        if (!suppressMotion && SystemParameters.ClientAreaAnimation && !SystemParameters.HighContrast)
            page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150)));
        else { page.BeginAnimation(OpacityProperty, null); page.Opacity = 1; }
    }

    private async Task RefreshSnapshot()
    {
        if (refreshing || closed) return;
        refreshing = true;
        try
        {
            var previous = snapshot;
            snapshot = await Call("snapshot");
            RefreshUpdateCard();
            bool fresh = snapshot.Flag("fresh");
            connection.Text = $"{Environment.MachineName.ToUpperInvariant()}  /  {snapshot.Object("manager").Text("state", "Unavailable")}  ·  {(fresh ? "Live observation" : "Offline or stale observation")}";
            if (selectedPage == "Overview")
            {
                overviewRefreshPending |= !JsonNode.DeepEquals(OverviewData(previous), OverviewData(snapshot));
                if (overviewRefreshPending && Mouse.LeftButton != MouseButtonState.Pressed)
                { overviewRefreshPending = false; RefreshOverview(); }
            }
        }
        catch { connection.Text = "Offline · last confirmed values retained"; }
        finally { refreshing = false; }
    }

    private bool overviewRefreshPending;
    private static JsonArray OverviewData(JsonObject data) => new(
        data.Object("manager")["state"]?.DeepClone(), data.Object("manager")["backend"]?.DeepClone(),
        data["fresh"]?.DeepClone(), data["openJobs"]?.DeepClone(), data["usage"]?.DeepClone(),
        data.Object("service")["activeWorkers"]?.DeepClone(), data.Object("service")["maxWorkers"]?.DeepClone(),
        data["activities"]?.DeepClone());

    private void RefreshOverview()
    {
        var focused = Keyboard.FocusedElement as Button;
        int focusIndex = focused is not null && page.IsAncestorOf(focused) ? Ui.Descendants<Button>(page).ToList().IndexOf(focused) : -1;
        string? focusName = focusIndex >= 0 ? System.Windows.Automation.AutomationProperties.GetName(focused!) : null;
        double offset = pageScroll.VerticalOffset;
        page.Children.Clear(); BuildOverview(); UpdateLayout();
        if (focusIndex >= 0)
        {
            var buttons = Ui.Descendants<Button>(page).ToArray();
            (buttons.FirstOrDefault(button => System.Windows.Automation.AutomationProperties.GetName(button) == focusName)
                ?? buttons.ElementAtOrDefault(focusIndex))?.Focus();
        }
        pageScroll.ScrollToVerticalOffset(offset);
    }

    private void BuildOverview()
    {
        var manager = snapshot.Object("manager");
        bool running = manager.Text("state") == "running";
        string backend = manager.Text("backend", "Not observed");
        var hero = Ui.Column(Ui.Text(running ? "Gateway is running" : "Gateway · " + manager.Text("state", "not observed"), 28, bold: true),
            Ui.Text(running && backend != "ready" ? "Gateway installed · model offline or not ready. Update the model connection in Settings." : "Model backend: " + backend, color: "MutedBrush"),
            Ui.Actions(Ui.Button("New prompt", () => Navigate("Chat"), true),
                Ui.Button(running ? "Restart when idle" : "Start Gateway", () => Run(async () =>
                { await Call(running ? "runtime-control" : "runtime-start", running ? new JsonObject { ["action"] = "restart" } : null); status.Text = "Request acknowledged. Runtime health will confirm the result."; })),
                Ui.Button("Connection settings", () => Navigate("Settings"))));
        page.Children.Add(Ui.Card(hero, true));
        var usage = snapshot.Object("usage"); var service = snapshot.Object("service");
        var tiles = new WrapPanel();
        void Metric(string label, string value, string note)
        {
            var card = Ui.Card(Ui.Column(Ui.Text(label, 13, "MutedBrush"), Ui.Text(value, 30, "PrimaryBrush", true), Ui.Text(note, 12, "MutedBrush")));
            card.Width = 226; card.Margin = new Thickness(0, 0, 12, 16); tiles.Children.Add(card);
        }
        Metric("Measured output", usage.Number("outputTokens")?.ToString("N0") ?? "Not measured", "Model-reported tokens");
        Metric("Active workers", snapshot.Flag("fresh") ? service.Number("activeWorkers")?.ToString("N0") ?? "Unavailable" : "Stale", "Capacity " + service.Text("maxWorkers", "not observed"));
        Metric("Open jobs", snapshot.Flag("fresh") ? snapshot.Number("openJobs")?.ToString("N0") ?? "Unavailable" : "Stale", "Accepted work, including waits");
        page.Children.Add(tiles);
        var history = Ui.Column(Ui.Text("Token output", 20, bold: true),
            Ui.Text($"Measured output only · {usage.Text("unmeasuredRounds", "0")} rounds without usage. Includes reasoning when counted by the model.", 13, "MutedBrush"));
        var days = usage.Array("days");
        if (days.Count == 0) history.Children.Add(Ui.Text("Your measured token history will appear after model requests complete.", color: "MutedBrush"));
        else
        {
            double largest = Math.Max(1, days.Select(day => day.Number("outputTokens") ?? 0).Max());
            foreach (var day in days.TakeLast(7))
            {
                history.Children.Add(Ui.Text(day.Text("day") + "  ·  " + (day.Number("outputTokens")?.ToString("N0") ?? "Unavailable") + " tokens", 13));
                history.Children.Add(new ProgressBar { Minimum = 0, Maximum = largest, Value = day.Number("outputTokens") ?? 0,
                    Height = 8, Foreground = Ui.Brush("PrimaryBrush"), Background = Ui.Brush("RaisedBrush"), Margin = new Thickness(0, 0, 0, 12) });
            }
        }
        page.Children.Add(Ui.Card(history));
        var activity = new ListBox { MaxHeight = 250 };
        Ui.Rows(activity, snapshot.Array("activities").Reverse().Take(8), "name");
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Recent activity", 20, bold: true),
            activity.Items.Count == 0 ? Ui.Text("No recent activity has been observed.", color: "MutedBrush") : activity)));
    }

    private void BuildJobs()
    {
        var panel = Ui.Column(Ui.Text("Jobs and schedules", 20, bold: true));
        var query = Ui.Field(panel, "Filter by job, repository, state or ID");
        var list = new ListBox { Height = 290 };
        var detail = Ui.Text("Select a job to inspect its current receipt.", 13, "MutedBrush");
        void Fill()
        {
            var all = snapshot.Array("jobs").Concat(snapshot.Array("engineering"));
            Ui.Rows(list, all.Where(row => string.IsNullOrWhiteSpace(query.Text) || row?.ToJsonString().Contains(query.Text, StringComparison.OrdinalIgnoreCase) == true));
        }
        query.TextChanged += (_, _) => Fill(); Fill(); panel.Children.Add(list);
        list.SelectionChanged += (_, _) => { var item = Ui.Selected(list); detail.Text = item is null ? "Select a job." : "ID: " + item.Text("id") + " · " + item.Text("status"); };
        panel.Children.Add(detail);
        panel.Children.Add(Ui.Actions(
            Ui.Button("Refresh jobs", () => Run(async () => { await RefreshSnapshot(); Fill(); })),
            Ui.Button("Open receipt", () => Run(async () =>
            {
                var item = Ui.Selected(list) ?? throw new InvalidOperationException("Choose a saved job."); await EnsureConnected();
                ShowDetails("Job receipt", await Call("job-read", WithIdentity(new JsonObject { ["job_id"] = item.Text("id") })));
            })),
            Ui.Button("Cancel selected job", () => Run(async () =>
            {
                var item = Ui.Selected(list) ?? throw new InvalidOperationException("Choose a saved job.");
                if (MessageBox.Show(this, "Request cancellation of " + item.Text("id") + "?", "Cancel this job", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
                await EnsureConnected(); await Call("job-cancel", WithIdentity(new JsonObject { ["job_id"] = item.Text("id") }));
                status.Text = "Cancellation requested. Refresh to observe the final state.";
            })),
            Ui.Button("Schedules and alerts", () => OpenUtility("schedules")),
            Ui.Button("Checkpoints and approvals", () => OpenUtility("dashboard", "Chat"))));
        page.Children.Add(Ui.Card(panel));
        var activities = new ListBox { MaxHeight = 220 }; Ui.Rows(activities, snapshot.Array("activities").Reverse().Take(30), "name");
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Tool activity", 20, bold: true),
            Ui.Text("Recent tool calls are observations within their owning jobs.", 13, "MutedBrush"), activities)));
        var devices = new ListBox { MaxHeight = 200 }; Ui.Rows(devices, snapshot.Array("devices"), "address", "state");
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Observed devices", 20, bold: true),
            Ui.Text("Network observations do not grant owner settings access.", 13, "MutedBrush"), devices)));
    }

    private void BuildTools()
    {
        var panel = Ui.Column(Ui.Text("Capability inventory", 20, bold: true));
        var query = Ui.Field(panel, "Find a capability");
        var list = new ListBox { Height = 300 };
        void Fill() => Ui.Rows(list, snapshot.Object("capabilityInventory").Array("tools").Where(row =>
            string.IsNullOrWhiteSpace(query.Text) || row?.ToJsonString().Contains(query.Text, StringComparison.OrdinalIgnoreCase) == true), "name", "availability");
        query.TextChanged += (_, _) => Fill(); Fill(); panel.Children.Add(list);
        panel.Children.Add(Ui.Actions(Ui.Button("Inspect capability", () => { if (Ui.Selected(list) is { } tool) ShowDetails("Capability evidence", tool); }),
            Ui.Button("GitHub connection and maps", () => OpenUtility("github-settings")),
            Ui.Button("Repository validation", () => OpenUtility("github-settings", "Repository Validation")),
            Ui.Button("MCP add-ons", () => OpenUtility("settings", "Add-ons")),
            Ui.Button("Engineering setup", () => OpenUtility("engineering-setup")),
            Ui.Button("Analysis workspace", () => OpenUtility("dashboard", "Chat"))));
        panel.Children.Add(Ui.Text("Host setup opens the existing validated utility. Its resource approvals and repair actions remain authoritative.", 13, "MutedBrush"));
        page.Children.Add(Ui.Card(panel));
    }

    private void BuildDiagnostics()
    {
        BuildUpdateCard();
        var manager = snapshot.Object("manager"); var service = snapshot.Object("service"); var usage = snapshot.Object("usage");
        var preview = new JsonObject
        {
            ["schema"] = "gptmobile.desktop.diagnostics.v1", ["fresh"] = snapshot.Flag("fresh"),
            ["state"] = manager.Text("state"), ["version"] = snapshot.Text("version"), ["backend_state"] = manager.Text("backend"),
            ["observed_at"] = snapshot["capturedAt"]?.DeepClone(), ["workers"] = service["activeWorkers"]?.DeepClone(),
            ["capacity"] = service["maxWorkers"]?.DeepClone(), ["open_jobs"] = snapshot["openJobs"]?.DeepClone(),
            ["measured_output_tokens"] = usage["outputTokens"]?.DeepClone(), ["unmeasured_rounds"] = usage["unmeasuredRounds"]?.DeepClone()
        };
        var output = new TextBox { Text = preview.ToJsonString(new JsonSerializerOptions { WriteIndented = true }),
            IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, Height = 270, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Diagnostic preview", 20, bold: true),
            Ui.Text("Only runtime state and counters are included. Prompts, credentials, device addresses and repository contents are excluded.", 13, "MutedBrush"), output,
            Ui.Actions(Ui.Button("Export this summary", () => SaveJson(preview, "gateway-diagnostics.json"), true),
                Ui.Button("Full debug export", () => OpenUtility("settings")),
                Ui.Button("Repair host prerequisites", () => OpenUtility("engineering-setup"))))));
    }

    private void BuildUpdateCard()
    {
        updateInfo = Ui.Text("", color: "MutedBrush");
        System.Windows.Automation.AutomationProperties.SetLiveSetting(updateInfo, System.Windows.Automation.AutomationLiveSetting.Polite);
        checkUpdate = Ui.Button("Check for updates", () => Run(() => RequestUpdate("check")));
        installUpdate = Ui.Button("Install when idle", () => Run(() => RequestUpdate("install")), true);
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Gateway updates", 20, bold: true), updateInfo, Ui.Actions(checkUpdate, installUpdate))));
        RefreshUpdateCard();
    }

    private void RefreshUpdateCard()
    {
        if (updateInfo is null || checkUpdate is null || installUpdate is null) return;
        var manager = snapshot.Object("manager");
        string state = manager.Text("updateState", "unchecked");
        string available = manager.Text("availableVersion", "");
        string installed = manager.Text("version", snapshot.Text("version"));
        string detail = state switch
        {
            "checking" => "Checking published Gateway releases…",
            "available" => $"Version {available} is available.",
            "downloading" => $"Downloading and verifying version {available}…",
            "waiting-for-idle" => $"Version {available} will install when current work finishes.",
            "validating" => $"Starting version {available} and checking its health…",
            "installed" => "Update installed and runtime health confirmed.",
            "up-to-date" => "No newer stable Gateway release found.",
            "rolled-back" or "startup-failed" => $"Version {manager.Text("failedVersion")} could not start. Restored the previous version.",
            "error" => manager.Text("updateError", "Update failed. Check the Gateway log and retry."),
            _ => "Updates have not been checked yet."
        };
        updateInfo.Text = $"Installed {installed}  ·  {detail}";
        bool ready = !requestingUpdate && manager.Text("state") == "running" && state is not ("checking" or "downloading" or "validating" or "waiting-for-idle");
        checkUpdate.IsEnabled = ready;
        installUpdate.IsEnabled = ready && state == "available";
    }

    private async Task RequestUpdate(string action)
    {
        if (requestingUpdate) return;
        requestingUpdate = true; RefreshUpdateCard();
        try
        {
            await Call("runtime-control", new JsonObject { ["action"] = action });
            await RefreshSnapshot();
        }
        finally { requestingUpdate = false; RefreshUpdateCard(); }
    }

    private void OpenUtility(string command, string? view = null) => Run(async () =>
    {
        var args = new JsonObject { ["command"] = command }; if (view is not null) args["view"] = view;
        await Call("open-utility", args); status.Text = "Opened the host utility. Its saved resources stay with this installation.";
    });

    private void SaveJson(JsonNode value, string name)
    {
        var picker = new SaveFileDialog { FileName = name, Filter = "JSON document|*.json", DefaultExt = ".json" };
        if (picker.ShowDialog(this) == true) { File.WriteAllText(picker.FileName, value.ToJsonString(new JsonSerializerOptions { WriteIndented = true })); status.Text = "Export saved."; }
    }

    private void ShowDetails(string title, JsonNode value)
    {
        var viewer = new TextBox { Text = value.ToJsonString(new JsonSerializerOptions { WriteIndented = true }), IsReadOnly = true,
            AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Height = 380 };
        var dialog = new Window { Owner = this, Title = title, Width = 700, Height = 550, MinWidth = 500, MinHeight = 400,
            WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var content = Ui.Column(Ui.Text(title, 24, bold: true), viewer,
            Ui.Actions(Ui.Button("Export", () => SaveJson(value, "gateway-evidence.json")), Ui.Button("Close", () => dialog.Close())));
        content.Margin = new Thickness(24); dialog.Content = content;
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) dialog.Close(); };
        dialog.ShowDialog();
    }

    private void PollWindowRequests()
    {
        foreach (var name in new[] { "close-desktop.json", "show-native.json", "show-dashboard.json" })
        {
            try
            {
                string file = System.IO.Path.Combine(root, name); if (!File.Exists(file) || new FileInfo(file).Length > 4096) continue;
                var value = JsonNode.Parse(File.ReadAllText(file));
                if (value.Number("requestedAt") is not { } stamp || stamp < openedAt ||
                    handledWindowRequests.TryGetValue(name, out double handled) && stamp <= handled) continue;
                handledWindowRequests[name] = stamp;
                if (name == "close-desktop.json") { Close(); return; }
                WindowState = WindowState.Normal; Activate();
                if (name == "show-native.json") Navigate(value.Text("view", "Overview"));
            }
            catch (IOException) { }
            catch (JsonException) { }
        }
    }

    private async Task Smoke()
    {
        var layout = (FrameworkElement)Content;
        try
        {
            Width = 900; Height = 600;
            foreach (string name in Pages)
            {
                Navigate(name);
                if (uiWork.Count > 0) await Task.WhenAll(uiWork.ToArray()).WaitAsync(TimeSpan.FromSeconds(30));
                await Task.Delay(250); UpdateLayout();
                if (page.ActualWidth < 300 || pageScroll.ActualHeight < 200) throw new InvalidOperationException("Native page is not usable at minimum size: " + name);
                Ui.Capture(this, System.IO.Path.Combine(root, "native-" + name.ToLowerInvariant().Replace(' ', '-') + ".png"));
            }
            Navigate("Overview"); UpdateLayout();
            var focused = Ui.Descendants<Button>(page).First(); focused.Focus();
            string focusName = System.Windows.Automation.AutomationProperties.GetName(focused);
            pageScroll.ScrollToVerticalOffset(120); UpdateLayout(); double offset = pageScroll.VerticalOffset;
            RefreshOverview(); UpdateLayout();
            if (Keyboard.FocusedElement is not Button retained || System.Windows.Automation.AutomationProperties.GetName(retained) != focusName || Math.Abs(pageScroll.VerticalOffset - offset) > 1)
                throw new InvalidOperationException("Overview refresh lost keyboard focus or reading position.");

            // Exercise the same resources used for the OS preference, without
            // changing the runner's Windows accessibility settings.
            ((App)Application.Current).ApplyContrast(true); suppressMotion = true;
            Navigate("Settings");
            if (uiWork.Count > 0) await Task.WhenAll(uiWork.ToArray()).WaitAsync(TimeSpan.FromSeconds(30));
            UpdateLayout();
            if (page.Opacity != 1) throw new InvalidOperationException("Reduced-motion view is still animating.");
            Ui.Capture(this, System.IO.Path.Combine(root, "native-high-contrast-palette.png"));
            ((App)Application.Current).ApplyContrast();
            layout.LayoutTransform = new ScaleTransform(1.5, 1.5);
            Navigate("Settings");
            if (uiWork.Count > 0) await Task.WhenAll(uiWork.ToArray()).WaitAsync(TimeSpan.FromSeconds(30));
            UpdateLayout();
            if (pageScroll.ActualWidth < 160 || pageScroll.ActualHeight < 80)
                throw new InvalidOperationException("Scaled native content has no usable scroll viewport.");
            Ui.Capture(this, System.IO.Path.Combine(root, "native-simulated-scale-150.png"));
            layout.LayoutTransform = Transform.Identity; suppressMotion = false;
            File.WriteAllText(System.IO.Path.Combine(root, "native-ui-smoke.json"), JsonSerializer.Serialize(new
            { pages = Pages, width = ActualWidth, height = ActualHeight, highContrast = SystemParameters.HighContrast, animations = SystemParameters.ClientAreaAnimation,
                highContrastPaletteExercised = true, simulatedLayoutScale = 1.5, reducedMotionExercised = true, focusAndScrollPreserved = true, runtime = "WPF", passed = true }));
            Close();
        }
        catch (Exception error) { File.WriteAllText(System.IO.Path.Combine(root, "native-ui-failure.txt"), error.Message); Application.Current.Shutdown(1); }
        finally { layout.LayoutTransform = Transform.Identity; suppressMotion = false; ((App)Application.Current).ApplyContrast(); }
    }
}
