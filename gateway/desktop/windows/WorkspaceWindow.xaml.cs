using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Microsoft.Win32;

namespace GatewayDesktop;

public partial class WorkspaceWindow : Window
{
    private static readonly string[] Pages = ["Overview", "Chat", "Saved", "Jobs", "Tools", "Benchmarks", "Android controls", "Diagnostics", "Settings"];
    private readonly string root;
    private readonly long opened = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(2) };
    private string page = "Overview";
    private DateTime showRequest;
    private readonly Dictionary<string, Process> editors = new();
    public int Result { get; private set; }

    public WorkspaceWindow(string installRoot, bool smoke)
    {
        root = installRoot;
        InitializeComponent();
        foreach (string item in Pages)
        {
            var link = new Button { Content = item, Margin = new Thickness(0, 2, 0, 2), MinHeight = 36 };
            link.Click += (_, _) => { page = item; Refresh(); };
            Navigation.Children.Add(link);
        }
        timer.Tick += (_, _) => Refresh();
        Loaded += (_, _) => {
            Refresh();
            if (smoke) {
                foreach (string item in Pages) {
                    page = item; Refresh(); UpdateLayout();
                    if (TitleText.Text != item || string.IsNullOrWhiteSpace(Action.Content?.ToString())) Result = 3;
                }
                Close();
            } else timer.Start();
        };
        Closed += (_, _) => { timer.Stop(); foreach (var proc in editors.Values) proc.Dispose(); };
    }

    private JsonElement Read(string name)
    {
        try {
            var file = new FileInfo(Path.Combine(root, name));
            if (!file.Exists || file.Length > 2 * 1024 * 1024) return default;
            using var data = JsonDocument.Parse(File.ReadAllBytes(file.FullName));
            return data.RootElement.Clone();
        } catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException) { return default; }
    }

    private static JsonElement Field(JsonElement value, string key) =>
        value.ValueKind == JsonValueKind.Object && value.TryGetProperty(key, out var result) ? result : default;

    private static string Value(JsonElement data, string key) =>
        Field(data, key).ValueKind is JsonValueKind.String or JsonValueKind.Number ? Field(data, key).ToString() : "Unavailable";

    private static IEnumerable<JsonElement> Rows(JsonElement parent, string key)
    {
        var rows = Field(parent, key);
        return rows.ValueKind == JsonValueKind.Array ? rows.EnumerateArray().Take(10).ToArray() : [];
    }

    private void Refresh()
    {
        if (Field(Read("close-desktop.json"), "requestedAt") is var closing &&
            closing.ValueKind == JsonValueKind.Number && closing.GetDouble() * 1000 >= opened) { Close(); return; }
        var request = File.GetLastWriteTimeUtc(Path.Combine(root, "show-dashboard.json"));
        if (request > showRequest) {
            showRequest = request;
            if (new DateTimeOffset(request).ToUnixTimeMilliseconds() >= opened) {
                if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
                Activate();
            }
        }
        var state = Read("status.json");
        var data = Read("dashboard.json");
        var service = Field(data, "service");
        var stamp = Field(data, "capturedAt");
        double age = stamp.ValueKind == JsonValueKind.Number ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() - stamp.GetDouble() : 999;
        bool live = Value(state, "state") == "running" && age >= -1 && age <= 10;
        string status = live ? "Live" : "Offline or awaiting a fresh observation";
        TitleText.Text = page;
        StatusText.Text = status + "  ·  " + Environment.MachineName;
        (Section.Text, Summary.Text, Action.Content) = page switch {
            "Chat" => ("New conversation", "Choose an installed model, stream responses and resume a saved conversation.", "Open chat"),
            "Saved" => ("Conversation library", "Find a conversation, bookmark a message or reopen an archived thread.", "Open saved messages"),
            "Jobs" => ("Gateway work", "Inspect durable work, saved approvals and the original job receipts.", "Manage jobs"),
            "Tools" => ("Capabilities", "Inspect repository maps, MCP services, GitHub access and validation setup.", "Inspect tools"),
            "Benchmarks" => ("Model performance", "Compare batch and single runs, time to first token and repository engineering results.", "Open benchmarks"),
            "Android controls" => ("Paired phones", "Create an invitation, review each device’s permissions and manage its Engineering setting.", "Manage paired phones"),
            "Diagnostics" => ("Diagnostic preview", "The export contains only the runtime fields shown below.", "Save diagnostic summary"),
            "Settings" => ("Installation settings", "Manage startup, updates, repository validation, network and performance.", "Edit settings"),
            _ => ("Runtime", $"Gateway   {Value(state,"state")}\nModel backend   {Value(state,"backend")}\nActive workers   {(live ? Value(service,"activeWorkers") : "Unavailable")}", "View activity")
        };
        foreach (Button link in Navigation.Children) link.FontWeight = (string)link.Content == page ? FontWeights.Bold : FontWeights.Normal;
        ListHeading.Text = page == "Tools" ? "Recent tools" : page == "Diagnostics" ? "Exported fields" : "Recent jobs";
        var lines = page == "Tools" ? Rows(Field(data, "capabilityInventory"), "tools").Select(x => Value(x,"name")) :
            Rows(data, "engineering").Concat(Rows(data, "jobs")).Take(10).Select(x => Value(x,"id") + "    " + Value(x,"status"));
        Details.Text = page == "Diagnostics" ? $"Gateway version   {Value(state,"version")}\nService   {Value(state,"state")}\nObservation   {status}" :
            string.Join(Environment.NewLine, lines.DefaultIfEmpty(live ? "No records in the current observation." : "No retained records available."));
        PreviewCard.Visibility = page is "Overview" or "Jobs" or "Tools" or "Diagnostics" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OpenWorkspace(object sender, RoutedEventArgs e)
    {
        try {
            if (page == "Diagnostics") {
                var save = new SaveFileDialog { FileName = "gateway-diagnostics.txt", Filter = "Text file|*.txt" };
                if (save.ShowDialog(this) == true) File.WriteAllText(save.FileName, Details.Text);
                return;
            }
            string destination = page == "Overview" ? "Jobs" : page;
            if (editors.TryGetValue(destination, out var active)) {
                if (!active.HasExited) { Microsoft.VisualBasic.Interaction.AppActivate(active.Id); return; }
                active.Dispose(); editors.Remove(destination);
            }
            var start = new ProcessStartInfo(Path.Combine(AppContext.BaseDirectory, "Gateway.exe")) { UseShellExecute = false };
            start.ArgumentList.Add(destination switch { "Settings" => "settings", "Android controls" => "phone-settings", _ => "workspace" });
            start.ArgumentList.Add("--root"); start.ArgumentList.Add(root);
            if (destination is not ("Settings" or "Android controls")) { start.ArgumentList.Add("--page"); start.ArgumentList.Add(destination); }
            var child = Process.Start(start);
            if (child != null) editors[destination] = child;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or Win32Exception or ArgumentException or InvalidOperationException) {
            MessageBox.Show(this, error.Message, "Gateway workspace");
        }
    }
}
