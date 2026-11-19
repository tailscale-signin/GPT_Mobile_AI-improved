using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace GatewayDesktop;

public partial class SetupWindow : Window
{
    private sealed record InterfaceChoice(string Label, IPAddress Address);

    private static readonly string[] Titles = ["Choose setup", "Connect a model", "Check this PC", "Finish setup"];
    private readonly string planPath;
    private readonly bool smoke;
    private readonly FrameworkElement[] pages;
    private readonly HttpClient client = new(new HttpClientHandler { AllowAutoRedirect = false })
        { Timeout = TimeSpan.FromSeconds(4), MaxResponseContentBufferSize = 256 * 1024 };
    private CancellationTokenSource? checking;
    private int page;
    private int result = 1;

    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length is not (2 or 3) || args[0] is not ("--plan" or "--smoke") || !Path.IsPathFullyQualified(args[1])) return 2;
        long owner = 0;
        if (args.Length == 3 && (!long.TryParse(args[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out owner) || owner == 0)) return 2;
        var app = new Application();
        app.Resources.MergedDictionaries.Add(new ResourceDictionary { Source = new Uri("Theme.xaml", UriKind.Relative) });
        if (SystemParameters.HighContrast)
        {
            app.Resources["CanvasBrush"] = SystemColors.WindowBrush;
            app.Resources["PanelBrush"] = SystemColors.WindowBrush;
            app.Resources["TextBrush"] = SystemColors.WindowTextBrush;
            app.Resources["MutedBrush"] = SystemColors.WindowTextBrush;
            app.Resources["RaisedBrush"] = SystemColors.WindowBrush;
            app.Resources["PrimaryBrush"] = SystemColors.HighlightBrush;
            app.Resources["PrimaryTextBrush"] = SystemColors.HighlightTextBrush;
            app.Resources["SecondaryBrush"] = SystemColors.HighlightBrush;
            app.Resources["LineBrush"] = SystemColors.WindowTextBrush;
        }
        var window = new SetupWindow(args[1], args[0] == "--smoke");
        if (owner != 0)
        {
            new WindowInteropHelper(window).Owner = new IntPtr(owner);
            window.WindowStartupLocation = WindowStartupLocation.CenterOwner;
            window.ShowInTaskbar = false;
        }
        app.Run(window);
        return window.result;
    }

    public SetupWindow(string path, bool smokeMode)
    {
        InitializeComponent();
        var workArea = SystemParameters.WorkArea;
        MinWidth = Math.Min(MinWidth, workArea.Width);
        MinHeight = Math.Min(MinHeight, workArea.Height);
        Width = Math.Min(Width, workArea.Width);
        Height = Math.Min(Height, workArea.Height);
        planPath = path;
        smoke = smokeMode;
        pages = [EditionPage, ConnectionPage, ChecksPage, FinishPage];
        Listener.ItemsSource = Interfaces();
        Listener.SelectedIndex = 0;
        Port.TextChanged += (_, _) => DescribeListener();
        Listener.SelectionChanged += (_, _) => DescribeListener();
        if (!smoke && File.Exists(planPath)) RestorePlan();
        DescribeListener();
        Closed += (_, _) => { checking?.Cancel(); client.Dispose(); };
        Loaded += async (_, _) =>
        {
            ShowPage(0);
            if (!smoke) return;
            try
            {
                Width = MinWidth;
                Height = MinHeight;
                CapturePage("edition");
                // Exercise actual controls and validation. No installation or host changes occur.
                NextPage(this, new RoutedEventArgs());
                CapturePage("connection");
                Port.Text = "1";
                NextPage(this, new RoutedEventArgs());
                if (page != 1 || Problem.Text.Length == 0) throw new InvalidOperationException();
                Port.Text = "8090";
                Backend.Text = "http://127.0.0.1:1";
                NextPage(this, new RoutedEventArgs());
                if (page != 2) throw new InvalidOperationException();
                await (activeCheck ?? Task.CompletedTask);
                CapturePage("checks");
                if (!Checks.Text.Contains("Model catalog")) throw new InvalidOperationException();
                NextPage(this, new RoutedEventArgs());
                if (page != 3 || !Summary.Text.Contains("127.0.0.1:8090")) throw new InvalidOperationException();
                PreviousPage(this, new RoutedEventArgs());
                await (activeCheck ?? Task.CompletedTask);
                NextPage(this, new RoutedEventArgs());
                Advanced.IsChecked = true;
                Workers.Text = "7";
                Chats.Text = "2";
                NextPage(this, new RoutedEventArgs());
                if (page != 3 || Problem.Text.Length == 0 || File.Exists(planPath)) throw new InvalidOperationException();
                Chats.Text = "11";
                Grace.Text = "42";
                Updates.SelectedIndex = 1;
                Grace.BringIntoView();
                CapturePage("advanced");
                NextPage(this, new RoutedEventArgs());
            }
            catch { result = 3; Close(); }
        };
    }

    private void CapturePage(string name)
    {
        UpdateLayout();
        if (!Next.IsVisible || Next.ActualWidth < 60 || PageScroll.ViewportHeight < 100)
            throw new InvalidOperationException("Setup controls clipped at minimum window size.");
        var dpi = VisualTreeHelper.GetDpi(this);
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(ActualWidth * dpi.DpiScaleX),
            (int)Math.Ceiling(ActualHeight * dpi.DpiScaleY), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bitmap.Render(this);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(Path.GetDirectoryName(planPath)!, $"setup-{name}.png"));
        encoder.Save(file);
    }

    private void RestorePlan()
    {
        // Back from the installer lets the user edit the same temporary plan.
        // Every restored value still passes normal validation before the next save.
        if (new FileInfo(planPath).Length > 16384) return;
        var values = new Dictionary<string, string>();
        foreach (string line in File.ReadAllLines(planPath, Encoding.Unicode))
        {
            int equal = line.IndexOf('=');
            if (equal > 0) values[line[..equal]] = line[(equal + 1)..];
        }
        Backend.Text = values.GetValueOrDefault("backend", Backend.Text);
        Port.Text = values.GetValueOrDefault("port", Port.Text);
        var choices = (List<InterfaceChoice>)Listener.ItemsSource;
        Listener.SelectedItem = choices.FirstOrDefault(c => c.Address.ToString() == values.GetValueOrDefault("bind")) ?? choices[0];
        Engineering.IsChecked = values.GetValueOrDefault("engineeringSetup") == "1";
        Standard.IsChecked = Engineering.IsChecked != true;
        Advanced.IsChecked = values.GetValueOrDefault("advanced") == "1";
        Mcp.Text = values.GetValueOrDefault("mcp", "");
        Workers.Text = values.GetValueOrDefault("workers", Workers.Text);
        Chats.Text = values.GetValueOrDefault("chats", Chats.Text);
        Grace.Text = values.GetValueOrDefault("grace", Grace.Text);
        if (int.TryParse(values.GetValueOrDefault("updates"), out int mode) && mode is >= 0 and <= 2) Updates.SelectedIndex = mode;
    }

    private static List<InterfaceChoice> Interfaces()
    {
        var choices = new List<InterfaceChoice> { new("This PC only", IPAddress.Loopback) };
        try
        {
            foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces().Where(a =>
                         a.OperationalStatus == OperationalStatus.Up && a.NetworkInterfaceType != NetworkInterfaceType.Loopback))
                foreach (var ip in adapter.GetIPProperties().UnicastAddresses.Select(i => i.Address))
                    if (ip.AddressFamily == AddressFamily.InterNetwork && choices.All(c => !c.Address.Equals(ip)))
                        choices.Add(new InterfaceChoice($"{adapter.Name} · {ip}", ip));
        }
        catch (NetworkInformationException) { /* Loopback remains usable offline. */ }
        choices.Add(new InterfaceChoice("All IPv4 interfaces · trusted networks only", IPAddress.Any));
        return choices;
    }

    private InterfaceChoice CurrentInterface => (InterfaceChoice)Listener.SelectedItem;
    private void DescribeListener() =>
        ListenerHint.Text = IPAddress.IsLoopback(CurrentInterface.Address) ? "Only applications on this PC can connect."
            : CurrentInterface.Address.Equals(IPAddress.Any) ? $"Reachable devices can use enabled tools. Use this PC’s LAN or VPN address and port {Port.Text} on the phone."
            : $"Trusted network only. Reachable devices can use enabled tools. Phone URL: http://{CurrentInterface.Address}:{Port.Text}/v1";

    private bool ValidConnection(out Uri? origin)
    {
        string value = Backend.Text.Trim();
        if (!Uri.TryCreate(value, UriKind.Absolute, out origin) || value.Any(char.IsControl)
            || !origin.IsWellFormedOriginalString() || origin.AbsolutePath != "/" || origin.UserInfo.Length != 0
            || origin.Query.Length != 0 || origin.Fragment.Length != 0
            || (origin.Scheme != "https" && !(origin.Scheme == "http" && origin.IsLoopback)))
        {
            Problem.Text = "Enter a local HTTP origin or an HTTPS origin, without credentials, path or query.";
            Backend.Focus();
            return false;
        }
        if (!int.TryParse(Port.Text, NumberStyles.None, CultureInfo.InvariantCulture, out int port) || port is < 1024 or > 65535)
        {
            Problem.Text = "Choose a Gateway port from 1024 to 65535.";
            Port.Focus();
            return false;
        }
        if (origin.IsLoopback && origin.Port == port)
        {
            Problem.Text = "Choose a Gateway port different from the model backend port.";
            Port.Focus();
            return false;
        }
        return true;
    }

    private Task? activeCheck;
    private void ShowPage(int index)
    {
        checking?.Cancel();
        page = index;
        for (int i = 0; i < pages.Length; ++i)
            pages[i].Visibility = i == page ? Visibility.Visible : Visibility.Collapsed;
        PageScroll.ScrollToTop();
        Heading.Focus();
        StepNumber.Text = $"{page + 1} of {pages.Length}";
        Heading.Text = Titles[page];
        Problem.Text = "";
        Back.Visibility = page > 0 ? Visibility.Visible : Visibility.Hidden;
        Next.Content = page == 3 ? "Use these settings" : "Continue";
        Next.IsEnabled = true;
        if (page == 2) activeCheck = CheckPc();
        if (page == 3)
        {
            Summary.Text = $"{(Engineering.IsChecked == true ? "GitHub Engineering" : "Standard Gateway")}\n" +
                $"Model backend: {Backend.Text.Trim()}\nGateway listener: {CurrentInterface.Address}:{Port.Text}\n\n" +
                "Choose startup, shortcut and optional firewall actions in the installer. You can change these settings later.";
        }
    }

    private async Task CheckPc()
    {
        checking?.Cancel();
        using var run = new CancellationTokenSource();
        checking = run;
        Retry.IsEnabled = false;
        Next.IsEnabled = false;
        Checks.Text = "Reading the model catalog…";
        string catalog;
        try
        {
            // A credential is never sent during setup. A catalog does not establish that a model is loaded.
            var uri = new Uri(new Uri(Backend.Text.Trim()), "/v1/models");
            using var response = await client.GetAsync(uri, run.Token);
            response.EnsureSuccessStatusCode();
            using var data = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(run.Token));
            catalog = data.RootElement.ValueKind == JsonValueKind.Object && data.RootElement.TryGetProperty("data", out var models) && models.ValueKind == JsonValueKind.Array
                ? $"Model catalog: {models.GetArrayLength()} models advertised." : "Model catalog: response format unavailable.";
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or JsonException)
        {
            catalog = "Model catalog unavailable. You can install now and connect the model later.";
        }
        finally
        {
            if (ReferenceEquals(checking, run)) checking = null;
        }
        if (run.IsCancellationRequested || page != 2) return;
        var lines = new List<string> { catalog, $"Windows: {Environment.OSVersion.Version} · {(Environment.Is64BitOperatingSystem ? "64-bit" : "32-bit")}" };
        if (Engineering.IsChecked == true)
            lines.Add("Engineering setup will verify Git, WSL 2, Docker and the repository sandbox after installation.");
        Checks.Text = string.Join("\n\n", lines);
        Next.IsEnabled = Retry.IsEnabled = true;
    }

    private void RetryChecks(object sender, RoutedEventArgs e) => activeCheck = CheckPc();
    private void PreviousPage(object sender, RoutedEventArgs e) => ShowPage(page - 1);
    private void NextPage(object sender, RoutedEventArgs e)
    {
        if (page == 1 && !ValidConnection(out _)) return;
        if (page < 3) { ShowPage(page + 1); return; }
        if (!ValidConnection(out var origin)) { ShowPage(1); return; }
        bool advanced = Advanced.IsChecked == true;
        string mcp = advanced ? Mcp.Text.Trim() : "";
        if (mcp.Length > 0 && (mcp.Any(char.IsControl) || !Path.IsPathFullyQualified(mcp) || !File.Exists(mcp)))
        {
            Problem.Text = "Choose an existing MCP configuration by its full path.";
            Mcp.Focus();
            return;
        }
        if (!int.TryParse(advanced ? Workers.Text : "2", NumberStyles.None, CultureInfo.InvariantCulture, out int workers) || workers is < 1 or > 16 ||
            !int.TryParse(advanced ? Chats.Text : "8", NumberStyles.None, CultureInfo.InvariantCulture, out int chats) || chats < workers || chats > 64 ||
            !int.TryParse(advanced ? Grace.Text : "30", NumberStyles.None, CultureInfo.InvariantCulture, out int grace) || grace is < 10 or > 120)
        {
            Problem.Text = "Check the worker, admitted chat and shutdown ranges. Admitted chats must cover every worker.";
            Workers.Focus();
            return;
        }
        string plan = $"[setup]\r\nbackend={origin!.GetLeftPart(UriPartial.Authority)}\r\n" +
            $"mcp={mcp}\r\nworkers={workers}\r\nchats={chats}\r\ngrace={grace}\r\nupdates={Updates.SelectedIndex}\r\n" +
            $"port={Port.Text}\r\nbind={CurrentInterface.Address}\r\nengineeringSetup={(Engineering.IsChecked == true ? 1 : 0)}\r\nadvanced={(Advanced.IsChecked == true ? 1 : 0)}\r\n";
        try
        {
            // Unicode INI is readable by the native installer at non-ASCII paths.
            File.WriteAllText(planPath, plan, Encoding.Unicode);
            result = 0;
            Close();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Problem.Text = "The installer could not save these settings. Check access to the temporary folder and retry.";
            if (smoke) { result = 3; Close(); }
        }
    }
}
