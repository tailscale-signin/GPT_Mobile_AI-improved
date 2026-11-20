using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private JsonObject? openedConversation;
    private JsonArray transcript = [];
    private JsonObject draft = [];

    private readonly SemaphoreSlim connectLock=new(1);
    private async Task EnsureConnected(bool force = false)
    {
        await connectLock.WaitAsync();
        try{await ConnectCore(force);}finally{connectLock.Release();}
    }
    private (string Gateway,string Principal)? loadedScope;
    private async Task ConnectCore(bool force)
    {
        if (identity is not null && !force) return;
        var value = await Call("connect");
        var observed = value.Object("identity");
        var scope=(observed.Text("gateway_id"),observed.Text("principal_id"));
        if (loadedScope is not null && loadedScope.Value != scope)
        { openedConversation = null; transcript = []; previews.Clear(); jobTurns.Clear(); readingPositions.Clear(); eventCursors.Clear(); activityGaps.Clear(); knownJobs.Clear(); jobs.Clear(); selectedJobs.Clear(); jobCounts=[];catalogObserved=default; views.Clear(); }
        loadedScope=scope;
        identity = (JsonObject)observed.DeepClone();
        models = value.Array("models").Select(item => item?.ToString() ?? "").Where(item => item.Length > 0).ToArray();
        draft = (JsonObject)value.Object("draft").DeepClone();
        status.Text = value.Text("privacy", "Connected to Gateway.");
    }

    private string? PromptText(string title, string label, string initial = "")
    {
        string? result = null;
        var dialog = new Window { Owner = this, Title = title, Width = 520, SizeToContent = SizeToContent.Height, WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var body = Ui.Column(Ui.Text(title, 22, bold: true)); body.Margin = new Thickness(24);
        var input = Ui.Field(body, label, initial);
        body.Children.Add(Ui.Actions(Ui.Button("Save", () => { if (input.Text.Trim().Length > 0) { result = input.Text.Trim(); dialog.Close(); } }, true),
            Ui.Button("Cancel", () => dialog.Close())));
        dialog.Content = body; dialog.Loaded += (_, _) => { input.Focus(); input.SelectAll(); };
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) dialog.Close(); }; dialog.ShowDialog();
        return result;
    }
}
