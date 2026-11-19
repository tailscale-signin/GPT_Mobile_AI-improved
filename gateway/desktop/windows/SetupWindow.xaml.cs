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
using System.Windows.Media;

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
        if (args.Length != 2 || args[0] is not ("--plan" or "--smoke") || !Path.IsPathFullyQualified(args[1])) return 2;
        var app = new Application();
        var window = new SetupWindow(args[1], args[0] == "--smoke");
        app.Run(window);
        return window.result;
    }

    public SetupWindow(string path, bool smokeMode)
    {
        InitializeComponent();
        planPath = path;
        smoke = smokeMode;
        pages = [EditionPage, ConnectionPage, ChecksPage, FinishPage];
        if (SystemParameters.HighContrast)
        {
            Resources["CanvasBrush"] = SystemColors.WindowBrush;
            Resources["PanelBrush"] = SystemColors.WindowBrush;
            Resources["TextBrush"] = SystemColors.WindowTextBrush;
            Resources["MutedBrush"] = SystemColors.WindowTextBrush;
            Resources["AccentBrush"] = SystemColors.WindowTextBrush;
            Resources["LineBrush"] = SystemColors.WindowTextBrush;
        }
        Listener.ItemsSource = Interfaces();
        Listener.SelectedIndex = 0;
        Port.TextChanged += (_, _) => DescribeListener();
        Listener.SelectionChanged += (_, _) => DescribeListener();
        DescribeListener();
        Closed += (_, _) => { checking?.Cancel(); client.Dispose(); };
        Loaded += async (_, _) =>
        {
            ShowPage(0);
            if (!smoke) return;
            try
            {
                // Exercise actual controls and validation. No installation or host changes occur.
                NextPage(this, new RoutedEventArgs());
                Port.Text = "1";
                NextPage(this, new RoutedEventArgs());
                if (page != 1 || Problem.Text.Length == 0) throw new InvalidOperationException();
                Port.Text = "8090";
                Backend.Text = "http://127.0.0.1:1";
                NextPage(this, new RoutedEventArgs());
                if (page != 2) throw new InvalidOperationException();
                await (activeCheck ?? Task.CompletedTask);
                if (!Checks.Text.Contains("Model catalog")) throw new InvalidOperationException();
                NextPage(this, new RoutedEventArgs());
                if (page != 3 || !Summary.Text.Contains("127.0.0.1:8090")) throw new InvalidOperationException();
                NextPage(this, new RoutedEventArgs());
            }
            catch { result = 3; Close(); }
        };
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
        return choices;
    }

    private InterfaceChoice CurrentInterface => (InterfaceChoice)Listener.SelectedItem;
    private void DescribeListener() =>
        ListenerHint.Text = IPAddress.IsLoopback(CurrentInterface.Address) ? "Only applications on this PC can connect."
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
        string plan = $"[setup]\r\nbackend={origin!.GetLeftPart(UriPartial.Authority)}\r\n" +
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
