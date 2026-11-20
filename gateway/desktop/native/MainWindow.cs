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
    internal static readonly string[] Pages = ["Overview", "Chat", "Saved", "Jobs", "Tools", "Benchmarks", "Android controls", "Diagnostics", "Settings", "Connect phone", "Advanced settings"];
    private readonly string root;
    private readonly BridgeClient bridge;
    private StackPanel page = new();
    private readonly WrapPanel sections = new();
    private static readonly Dictionary<string, string[]> Sections = new()
    {
        ["Jobs"] = ["Tasks", "Schedules", "Approvals"],
        ["Diagnostics"] = ["Overview", "Performance", "Failures", "Events", "Benchmarks", "Reports"],
        ["Tools"] = ["Capabilities", "GitHub", "Repositories", "Tests and release checks", "Environments", "Add-ons / MCP", "Analysis"],
        ["Settings"] = ["General", "Connection", "Models", "Execution", "Devices", "Updates", "Appearance", "Advanced"]
    };
    private readonly Dictionary<string, string> lastSection = [];
    private readonly Dictionary<string, (StackPanel Body, object Content, double Offset)> views = [];
    private string route = "";
    private string Section => lastSection.GetValueOrDefault(selectedPage, "");
    private bool ReducedMotion => suppressMotion || preferences.Flag("reduceMotion") || !SystemParameters.ClientAreaAnimation || SystemParameters.HighContrast;
    private JsonObject preferences = [];
    private void FillWindow(UIElement child) { pageScroll.VerticalScrollBarVisibility = ScrollBarVisibility.Disabled; pageScroll.Content = child; }
    private readonly TextBlock heading = Ui.Text("Overview", 34, bold: true);
    private readonly TextBlock subtitle = Ui.Text("Your local workspace", color: "MutedBrush");
    private readonly TextBlock status = Ui.Text(string.Empty, 13, "MutedBrush");
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
        SetResourceReference(BackgroundProperty, "CanvasBrush");
        SetResourceReference(ForegroundProperty, "TextBrush");
        FontFamily = new FontFamily("Segoe UI");
        ReadPreferences();
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var layout = new Grid();
        layout.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(210) });
        layout.ColumnDefinitions.Add(new ColumnDefinition());
        var mark = new Image { Width = 44, Height = 44, HorizontalAlignment = HorizontalAlignment.Left,
            Source = new System.Windows.Media.Imaging.BitmapImage(new Uri("pack://application:,,,/gateway.png")) };
        var brand = new DockPanel { Margin = new Thickness(0, 0, 0, 24) };
        DockPanel.SetDock(mark, Dock.Left); mark.Margin = new Thickness(0, 0, 10, 0);
        brand.Children.Add(mark);
        brand.Children.Add(Ui.Column(Ui.Text("GPT Mobile", 13, "MutedBrush"), Ui.Text("Gateway", 22, bold: true)));
        var rail = Ui.Column(brand);
        var collapse = new Button { Content="Collapse menu" };
        System.Windows.Automation.AutomationProperties.SetName(collapse,"Toggle navigation menu");
        collapse.Click+=(_,_)=>SetRail(layout.ColumnDefinitions[0].Width.Value > 100);
        rail.Children.Add(collapse);
        void SetRail(bool small)
        {
            layout.ColumnDefinitions[0].Width = new GridLength(small ? 76 : 186);
            brand.Visibility = small ? Visibility.Collapsed : Visibility.Visible;
            collapse.Content = small ? "☰" : "Collapse menu";
            foreach (var (name, button) in navigation)
            {
                string title = name == "Overview" ? "Home" : name;
                button.Content = small ? title[..1] : title; button.ToolTip = title;
                button.Padding = new Thickness(small ? 4 : 12, 7, 4, 7);
            }
        }
        rail.Margin = new Thickness(16, 24, 16, 20);
        foreach (string name in new[] { "Overview", "Chat", "Saved", "Jobs", "Diagnostics", "Tools", "Settings" })
        {
            var button = Ui.Button(name == "Overview" ? "Home" : name, () => Navigate(name));
            button.HorizontalContentAlignment = HorizontalAlignment.Left;
            button.Margin = new Thickness(0, 2, 0, 2);
            navigation[name] = button; rail.Children.Add(button);
        }
        SizeChanged += (_, _) => { if (Width < 1000) SetRail(true); };
        SetRail(Width < 1000);
        var railScroll = new ScrollViewer { Content = rail, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        railScroll.SetResourceReference(BackgroundProperty, "PanelBrush");
        layout.Children.Add(railScroll);
        var workspace = new Grid { Margin = new Thickness(20, 12, 16, 8) };
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        workspace.RowDefinitions.Add(new RowDefinition());
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var titleLine = new DockPanel();
        DockPanel.SetDock(connection, Dock.Right); titleLine.Children.Add(connection); titleLine.Children.Add(heading);
        var header = Ui.Column(titleLine, sections);
        workspace.Children.Add(header);
        pageScroll = new ScrollViewer { Content = page, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Padding = new Thickness(0, 8, 12, 0) };
        Grid.SetRow(pageScroll, 1); workspace.Children.Add(pageScroll);
        status.Margin = new Thickness(0, 3, 0, 0);
        status.MaxHeight = 38;
        System.Windows.Automation.AutomationProperties.SetLiveSetting(status, System.Windows.Automation.AutomationLiveSetting.Polite);
        Grid.SetRow(status, 2); workspace.Children.Add(status);
        Grid.SetColumn(workspace, 1); layout.Children.Add(workspace); Content = layout;
        Loaded += async (_, _) =>
        {
            Navigate(Arg(args, "--view") ?? "Overview"); await RefreshSnapshot(); timer.Start();
            if (args.Contains("--smoke-ui")) await Smoke();
        };
        timer.Tick += async (_, _) => { PollWindowRequests(); await RefreshSnapshot(); };
        Closing += (_, e) =>
        {
            if (!allowWindowExit && preferences.Flag("tray") && !args.Contains("--smoke-ui")) {e.Cancel=true;Hide();return;}
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
        name = name switch { "Advanced settings" => "Settings/Advanced", "Android controls" => "Settings/Devices",
            "Benchmarks" => "Diagnostics/Benchmarks", "Home" => "Overview", _ => name };
        var parts = name.Split('/', 2);
        string destination = parts[0];
        if (!Pages.Contains(destination)) destination = "Overview";
        string? section = null;
        if (Sections.TryGetValue(destination, out var options))
        {
            section = parts.Length == 2 && options.Contains(parts[1]) ? parts[1] : lastSection.GetValueOrDefault(destination, options[0]);
            lastSection[destination] = section;
        }
        string next = destination + (section is null ? "" : "/" + section);
        if (route != next && route.Length > 0 && CacheRoute(route) && (uiWork.Count==0 || route.StartsWith("Settings/") && !route.EndsWith("/Devices")))
            views[route] = (page, pageScroll.Content, pageScroll.VerticalOffset);
        bool repeat = next == route;
        route = next; epoch++; selectedPage = destination;
        heading.Text = destination == "Overview" ? "Home" : destination;
        foreach (var (key, button) in navigation)
        {
            bool active = key == destination || key == "Settings" && destination == "Connect phone";
            button.SetResourceReference(BackgroundProperty, active ? "RaisedBrush" : "PanelBrush");
            button.SetResourceReference(ForegroundProperty, active ? "PrimaryBrush" : "MutedBrush");
        }
        sections.Children.Clear();
        foreach (string tab in options ?? [])
        {
            var button = Ui.Button(tab, () => Navigate(destination + "/" + tab), tab == section);
            button.Padding = new Thickness(9, 3, 9, 3); button.MinHeight = 26;
            sections.Children.Add(button);
        }
        updateInfo = null; checkUpdate = installUpdate = null;
        if (!repeat && views.Remove(route, out var saved))
        {
            page = saved.Body;
            pageScroll.VerticalScrollBarVisibility = saved.Content == page ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled;
            pageScroll.Content = saved.Content; pageScroll.ScrollToVerticalOffset(saved.Offset);
        }
        else
        {
            page = new(); pageScroll.Content = page; pageScroll.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
            pageScroll.ScrollToTop();
            switch (destination)
            {
                case "Overview": BuildOverview(); break;
                case "Chat": BuildChat(); break;
                case "Saved": BuildSaved(); break;
                case "Jobs": if (section == "Schedules") BuildSchedules(); else BuildJobs(); break;
                case "Tools": BuildTools(); break;
                case "Diagnostics": BuildDiagnostics(); break;
                case "Settings": BuildCategory(); break;
                case "Connect phone": BuildConnectPhone(); break;
            }
        }
        if (!ReducedMotion) page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(150)));
        else { page.BeginAnimation(OpacityProperty, null); page.Opacity = 1; }
        if (destination == "Jobs" && section != "Schedules") Run(RefreshCatalog);
    }

    private static bool CacheRoute(string path) => path.StartsWith("Tools/") || path.StartsWith("Settings/") && !path.EndsWith("/Updates")
        || path is "Saved" or "Jobs/Schedules";

    private async Task RefreshSnapshot()
    {
        if (refreshing || closed) return;
        refreshing = true;
        try
        {
            var previous = OverviewData(snapshot);
            try { snapshot = await Call("snapshot"); }
            catch { snapshot["fresh"] = false; }
            RefreshUpdateCard();
            if (selectedPage is "Jobs" or "Overview" or "Diagnostics" && (DateTime.UtcNow - catalogAttempt).TotalSeconds >= 8)
                Run(RefreshCatalog);
            if (selectedPage == "Chat") Run(RefreshChatActivity);
            if (selectedPage == "Diagnostics") RefreshDiagnosticValues();
            if (selectedPage == "Overview") RefreshReadinessIfDue();
            connection.Text = snapshot.Flag("fresh") ? "●  Live" : "○  Offline · last known status";
            if (selectedPage == "Overview")
            {
                overviewRefreshPending |= !JsonNode.DeepEquals(previous, OverviewData(snapshot));
                if (overviewRefreshPending && Mouse.LeftButton != MouseButtonState.Pressed)
                { overviewRefreshPending = false; RefreshOverview(); }
            }
        }
        catch (Exception error) { if (!closed) status.Text = error.Message; }
        finally { refreshing = false; }
    }

    private bool overviewRefreshPending;
    private static JsonArray OverviewData(JsonObject data) => new(
        data.Object("manager")["state"]?.DeepClone(), data.Object("manager")["backend"]?.DeepClone(),
        data["fresh"]?.DeepClone(), data["openJobs"]?.DeepClone(), data["usage"]?.DeepClone(),
        data.Object("service")["activeWorkers"]?.DeepClone(), data.Object("service")["maxWorkers"]?.DeepClone(),
        data["activities"]?.DeepClone(), data.Object("manager")["version"]?.DeepClone());

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
            var command = new JsonObject { ["action"] = action };
            if (action == "install") command["target"] = snapshot.Object("manager").Text("availableVersion", "");
            await Call("runtime-control", command);
            await RefreshSnapshot();
        }
        finally { requestingUpdate = false; RefreshUpdateCard(); }
    }

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
                if (name == "close-desktop.json") { allowWindowExit=true;Close(); return; }
                Show(); if(WindowState==WindowState.Minimized)WindowState=WindowState.Normal; Activate();
                Navigate(name == "show-native.json" ? value.Text("view", "Overview") : "Overview");
            }
            catch (IOException) { }
            catch (JsonException) { }
        }
    }

    private async Task SettleSmokeReads()
    {
        try { await Task.WhenAll(uiWork.ToArray()).WaitAsync(TimeSpan.FromSeconds(45)); }
        catch (InvalidOperationException) { /* An offline dataset has its own empty/error state. */ }
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
                await SettleSmokeReads();
                await Task.Delay(250); UpdateLayout();
                if (pageScroll.ActualWidth < 300 || pageScroll.ActualHeight < 200) throw new InvalidOperationException("Native page is not usable at minimum size: " + name);
                Ui.Capture(this, System.IO.Path.Combine(root, "native-" + name.ToLowerInvariant().Replace(' ', '-') + ".png"));
            }
            // Reference screen with room reserved for the taskbar and non-client chrome.
            Width=1920; Height=1040;
            layout.Width=1904; layout.Height=1000;
            Navigate("Jobs/Tasks"); await SettleSmokeReads(); await RefreshCatalog();
            UpdateLayout(); await Dispatcher.InvokeAsync(()=>{},System.Windows.Threading.DispatcherPriority.ContextIdle);
            var table=jobsTable ?? throw new InvalidOperationException("Jobs table unavailable.");
            var visible=FullyVisibleRows(table);
            if(visible.Length<30 || visible.Select(row=>((JobRow)row.Item).Id).Distinct().Count()!=visible.Length)
                throw new InvalidOperationException($"Compact table exposed {visible.Length} distinct full rows at reference size.");
            var first=(JobRow)visible[0].Item; var second=(JobRow)visible[1].Item;
            table.SelectedItems.Add(first);table.SelectedItems.Add(second);pins.Add(first.Id);
            var scroll=Ui.Descendants<ScrollViewer>(table).First();
            scroll.ScrollToVerticalOffset(8);UpdateLayout();
            var anchor=((JobRow)FullyVisibleRows(table)[0].Item).Id;
            catalogPages++;await RefreshCatalog();UpdateLayout();
            bool observed=table.SelectedItems.Contains(first) && table.SelectedItems.Contains(second) && pins.Contains(first.Id)
                && FullyVisibleRows(table).Any(row=>((JobRow)row.Item).Id==anchor);
            int receiptCount=knownJobs.Count;
            if(!observed || receiptCount<105)throw new InvalidOperationException("Catalog refresh lost the selection, pin, viewport or next page.");
            var peer=System.Windows.Automation.Peers.UIElementAutomationPeer.CreatePeerForElement(table);
            if(peer?.GetPattern(System.Windows.Automation.Peers.PatternInterface.Selection)==null)
                throw new InvalidOperationException("Jobs does not expose UI Automation selection.");
            Ui.Capture(layout,System.IO.Path.Combine(root,"native-jobs-reference.png"));
            pins.Remove(first.Id);selectedJobs.Clear();
            layout.Width=double.NaN;layout.Height=double.NaN;Width=900;Height=600;
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
            await SettleSmokeReads();
            UpdateLayout();
            if (page.Opacity != 1) throw new InvalidOperationException("Reduced-motion view is still animating.");
            Ui.Capture(this, System.IO.Path.Combine(root, "native-high-contrast-palette.png"));
            ((App)Application.Current).ApplyContrast();
            layout.LayoutTransform = new ScaleTransform(1.5, 1.5);
            Navigate("Settings");
            await SettleSmokeReads();
            UpdateLayout();
            if (pageScroll.ActualWidth < 160 || pageScroll.ActualHeight < 80)
                throw new InvalidOperationException("Scaled native content has no usable scroll viewport.");
            Ui.Capture(this, System.IO.Path.Combine(root, "native-simulated-scale-150.png"));
            layout.LayoutTransform = Transform.Identity; suppressMotion = false;
            File.WriteAllText(System.IO.Path.Combine(root, "native-ui-smoke.json"), JsonSerializer.Serialize(new
            { pages = Pages, width = ActualWidth, height = ActualHeight, highContrast = SystemParameters.HighContrast, animations = SystemParameters.ClientAreaAnimation,
                visibleCompactRows = visible.Length, catalogReceipts = receiptCount, selectionAndPinsPreserved = observed,
                highContrastPaletteExercised = true, simulatedLayoutScale = 1.5, reducedMotionExercised = true, focusAndScrollPreserved = true, runtime = "WPF", passed = true }));
            allowWindowExit=true;Close();
        }
        catch (Exception error) { File.WriteAllText(System.IO.Path.Combine(root, "native-ui-failure.txt"), error.Message); Application.Current.Shutdown(1); }
        finally { layout.LayoutTransform = Transform.Identity; suppressMotion = false; ((App)Application.Current).ApplyContrast(); }
    }
}
