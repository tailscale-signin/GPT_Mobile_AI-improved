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

    private async Task EnsureConnected(bool force = false)
    {
        if (identity is not null && !force) return;
        var value = await Call("connect");
        var observed = value.Object("identity");
        if (identity is not null && (observed.Text("gateway_id") != identity.Text("gateway_id") || observed.Text("principal_id") != identity.Text("principal_id")))
        { openedConversation = null; transcript = []; }
        identity = (JsonObject)observed.DeepClone();
        models = value.Array("models").Select(item => item?.ToString() ?? "").Where(item => item.Length > 0).ToArray();
        draft = (JsonObject)value.Object("draft").DeepClone();
        status.Text = value.Text("privacy", "Connected to Gateway.");
    }

    private void BuildChat()
    {
        int viewEpoch = epoch;
        var panel = Ui.Column(Ui.Text(openedConversation?.Text("title", "New conversation") ?? "New conversation", 22, bold: true));
        string? retainedModel = openedConversation?.Text("model", "");
        var choices = retainedModel is { Length: > 0 } ? models.Append(retainedModel).Distinct().ToArray() : models;
        var model = Ui.Select(panel, "Model", choices, retainedModel ?? draft.Text("model", ""));
        var modelHint = Ui.Text("", 13, "MutedBrush"); panel.Children.Add(modelHint);
        void ApplyModelCatalog()
        {
            string? original = openedConversation?.Text("model", "");
            model.ItemsSource = original is { Length: > 0 } ? models.Append(original).Distinct().ToArray() : models;
            model.SelectedItem = original ?? draft.Text("model", "");
            if (original is null && model.SelectedIndex < 0 && models.Length > 0) model.SelectedIndex = 0;
            model.IsEnabled = original is null;
            modelHint.Text = original is not null && !models.Contains(original)
                ? "This saved conversation keeps its original model. It is not currently advertised; restore that model on this Gateway to continue." : "";
            modelHint.Visibility = modelHint.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        ApplyModelCatalog();
        var messages = new ListBox { Height = 210 };
        foreach (var message in transcript.OfType<JsonObject>())
        {
            messages.Items.Add(new ListBoxItem { Tag = message.DeepClone(), Content = Ui.Column(
                Ui.Text(message.Text("role") == "user" ? "You" : "Assistant", 13, "PrimaryBrush", true),
                Ui.Text(message.Text("content", "Awaiting reply"))) });
        }
        panel.Children.Add(messages);
        var live = new TextBox { IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Height = 160, Visibility = Visibility.Collapsed };
        panel.Children.Add(live);
        var composer = Ui.Field(panel, "Your message", draft.Text("draft", ""));
        composer.AcceptsReturn = true; composer.TextWrapping = TextWrapping.Wrap;
        composer.Height = 120; composer.VerticalScrollBarVisibility = ScrollBarVisibility.Auto; composer.MaxLength = 32000;
        Button? send = null;
        async Task Send()
        {
            if (sending) return;
            await EnsureConnected();
            if (draft["pending"] is JsonObject) throw new InvalidOperationException("Check the saved send before submitting again.");
            string selected = model.SelectedItem?.ToString() ?? "";
            if (string.IsNullOrWhiteSpace(selected) || string.IsNullOrWhiteSpace(composer.Text)) throw new InvalidOperationException("Choose a model and enter a message.");
            var arguments = WithIdentity(new JsonObject { ["command_id"] = "desktop-" + Guid.NewGuid().ToString("N"),
                ["model"] = selected, ["prompt"] = composer.Text });
            if (openedConversation is not null) arguments["conversation_id"] = openedConversation.Text("conversation_id");
            sending = true; detachSafe = false;
            composer.IsReadOnly = true; model.IsEnabled = false; if (send is not null) send.IsEnabled = false;
            live.Visibility = Visibility.Visible; live.Clear(); status.Text = "Submitting this command once…";
            try
            {
                var receipt = await bridge.Call("chat-send", arguments, (kind, value) =>
                {
                    if (closed) return;
                    if (kind == "accepted")
                    {
                        detachSafe = value.Flag("detach_safe");
                        status.Text = detachSafe ? "Accepted · saved work continues on Gateway when this window closes." : "Accepted · keep this window open until the response is saved.";
                    }
                    else if (kind == "delta" && viewEpoch == epoch)
                    { live.AppendText(value?.ToString()); live.ScrollToEnd(); }
                });
                var opened = await Call("conversation-read", WithIdentity(new JsonObject { ["conversation_id"] = receipt.Text("conversation_id") }));
                openedConversation = (JsonObject)opened.Object("conversation").DeepClone(); transcript = (JsonArray)opened.Array("messages").DeepClone();
                draft = []; if (viewEpoch == epoch) Navigate("Chat"); status.Text = "Response saved.";
            }
            catch
            {
                // Reconnect loads the original persisted command; no automatic resend.
                identity = null;
                status.Text = "The send needs its saved receipt. Choose Check send before sending again.";
                throw;
            }
            finally { sending = false; composer.IsReadOnly = false; model.IsEnabled = openedConversation is null; if (send is not null) send.IsEnabled = true; }
        }
        send = Ui.Button("Send  Ctrl+Enter", () => Run(Send), true);
        composer.PreviewKeyDown += (_, e) => { if (e.Key == Key.Enter && Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) { e.Handled = true; Run(Send); } };
        panel.Children.Add(Ui.Actions(send,
            Ui.Button("Save draft", () => Run(async () =>
            {
                await Call("draft-save", new JsonObject { ["draft"] = composer.Text, ["model"] = model.SelectedItem?.ToString() ?? "" });
                draft["draft"] = composer.Text; draft["model"] = model.SelectedItem?.ToString() ?? ""; status.Text = "Draft saved on this PC.";
            })),
            Ui.Button("Check send", () => Run(async () =>
            {
                var result = await Call("chat-reconcile");
                if (result["opened"] is JsonObject opened)
                { openedConversation = (JsonObject)opened.Object("conversation").DeepClone(); transcript = (JsonArray)opened.Array("messages").DeepClone(); draft = []; Navigate("Chat"); }
                else ShowDetails("Original send receipt", result);
            })),
            Ui.Button("Reconnect", () => Run(async () => { await EnsureConnected(true); if (viewEpoch == epoch) Navigate("Chat"); })),
            Ui.Button("Save selected message", () => Run(async () =>
            {
                var selected = Ui.Selected(messages) ?? throw new InvalidOperationException("Select a message to bookmark.");
                if (openedConversation is null) return;
                await Call("bookmark", WithIdentity(new JsonObject { ["conversation_id"] = openedConversation.Text("conversation_id"),
                    ["message_id"] = selected.Text("message_id"), ["saved"] = true })); status.Text = "Message saved.";
            })), Ui.Button("Engineering workspace", () => OpenUtility("dashboard", "Chat"))));
        panel.Children.Add(Ui.Text("Saved drafts and send receipts survive closing this window. A lost reply is reconciled using its original command.", 13, "MutedBrush"));
        page.Children.Add(Ui.Card(panel));
        if (identity is null) Run(async () =>
        {
            await EnsureConnected(); if (epoch != viewEpoch) return;
            ApplyModelCatalog();
            if (string.IsNullOrWhiteSpace(composer.Text)) composer.Text = draft.Text("draft", "");
        });
        else model.IsEnabled = openedConversation is null;
    }

    private void BuildSaved()
    {
        var panel = Ui.Column(Ui.Text("Saved work", 20, bold: true));
        var query = Ui.Field(panel, "Search saved conversations or messages");
        var filter = Ui.Select(panel, "Show", ["Conversations", "Favorites", "Archived", "Bookmarked messages"]);
        var list = new ListBox { Height = 300 };
        string? cursor = null; var items = new List<JsonNode?>();
        async Task Load(bool more = false)
        {
            await EnsureConnected();
            var args = WithIdentity(new JsonObject { ["query"] = query.Text, ["favorite"] = filter.SelectedIndex == 1, ["archived"] = filter.SelectedIndex == 2 });
            if (more && cursor is not null) args["cursor"] = cursor;
            var result = await Call(filter.SelectedIndex == 3 ? "saved" : "conversations", args);
            if (!more) items.Clear();
            items.AddRange((result["items"] as JsonArray ?? result.Array("conversations")).Select(item => item?.DeepClone()));
            cursor = result.Flag("has_more") || result["has_more"] is null ? result["next_cursor"]?.ToString() : null;
            Ui.Rows(list, items, "title", "model"); status.Text = $"{items.Count} saved items shown" + (cursor is null ? "." : " · more available.");
        }
        async Task Open()
        {
            var item = Ui.Selected(list) ?? throw new InvalidOperationException("Select saved work to open.");
            var result = await Call("conversation-read", WithIdentity(new JsonObject { ["conversation_id"] = item.Text("conversation_id") }));
            openedConversation = (JsonObject)result.Object("conversation").DeepClone(); transcript = (JsonArray)result.Array("messages").DeepClone();
            Navigate("Chat");
        }
        async Task Change(string field)
        {
            var item = Ui.Selected(list) ?? throw new InvalidOperationException("Select a conversation.");
            var result = await Call("conversation-read", WithIdentity(new JsonObject { ["conversation_id"] = item.Text("conversation_id") }));
            var conversation = result.Object("conversation");
            var changes = new JsonObject();
            if (field == "title")
            {
                var value = PromptText("Rename conversation", "Title", conversation.Text("title", "")); if (value is null) return;
                changes[field] = value;
            }
            else changes[field] = !conversation.Flag(field);
            await Call("conversation-metadata", WithIdentity(new JsonObject { ["conversation_id"] = conversation.Text("conversation_id"),
                ["revision"] = conversation["metadata_revision"]?.DeepClone(), ["changes"] = changes }));
            await Load();
        }
        panel.Children.Add(Ui.Actions(Ui.Button("Search", () => Run(() => Load()), true),
            Ui.Button("More", () => Run(async () => { if (cursor is not null) await Load(true); })), Ui.Button("Open", () => Run(Open)),
            Ui.Button("Rename", () => Run(() => Change("title"))), Ui.Button("Favorite / unfavorite", () => Run(() => Change("favorite"))),
            Ui.Button("Archive / restore", () => Run(() => Change("archived")))));
        panel.Children.Add(list); list.MouseDoubleClick += (_, _) => Run(Open);
        page.Children.Add(Ui.Card(panel)); Run(() => Load());
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
