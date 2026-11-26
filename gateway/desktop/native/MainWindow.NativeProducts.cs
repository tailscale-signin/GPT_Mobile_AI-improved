using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using Gateway.Desktop.Core;
using Microsoft.Win32;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private Action BuildNativeProductCampaigns(StackPanel host, int viewEpoch)
    {
        host.Children.Add(Ui.Text("Compare native products", 22, bold: true));
        host.Children.Add(Ui.Text("GPT Mobile AI · ChatGPT · Codex · Claude Code", 14, "PrimaryBrush"));
        host.Children.Add(Ui.Text("Freeze matched tasks and controls, collect native exports, then grade supplied answers independently. Product sessions are run by the observer; these controls make no model-generation requests.", 13, "MutedBrush"));

        bool busy = false, loaded = false, configDirty = false, campaignDirty = false, measurementsDirty = false;
        JsonObject? current = null;
        string observerPublicKey = "", graderPublicKey = "", displayedTrial = "";
        var operationStatus = Ui.Text("Choose a saved campaign or prepare a new one.", 14, "MutedBrush");
        AutomationProperties.SetLiveSetting(operationStatus, AutomationLiveSetting.Polite);
        host.Children.Add(operationStatus);
        var keyBody = new StackPanel();
        var observerKey = Ui.Field(keyBody, "Observer private key file");
        var graderKey = Ui.Field(keyBody, "Independent grader private key file");
        keyBody.Children.Add(Ui.Text("Select two independently held keys. Distinct keys alone do not prove independent observation. Only public keys belong in the campaign configuration. Private key paths are used for the action you choose and are not included in scoreboard exports.", 13, "MutedBrush"));
        var keyInfo = Ui.Text("No keys generated in this view.", 13, "MutedBrush");
        var keyExpander = new Expander { Header = "Observer and independent grader keys", Content = keyBody,
            Margin = new Thickness(0, 8, 0, 12) };
        host.Children.Add(keyExpander);

        var sections = new Dictionary<string, StackPanel>();
        var navigation = new WrapPanel();
        host.Children.Add(navigation);
        void Show(string name)
        {
            foreach (var item in sections) item.Value.Visibility = item.Key == name ? Visibility.Visible : Visibility.Collapsed;
        }
        foreach (string name in new[] { "Campaigns", "Run trials", "Scoreboard" })
        {
            var section = new StackPanel { Visibility = name == "Campaigns" ? Visibility.Visible : Visibility.Collapsed };
            sections[name] = section;
            navigation.Children.Add(Ui.Button(name, () => Show(name)));
            host.Children.Add(section);
        }

        void Work(string label, Func<Task> action) => Run(async () =>
        {
            if (busy || epoch != viewEpoch) return;
            busy = true; host.IsEnabled = false; operationStatus.Text = label;
            try { await action(); }
            catch (Exception error)
            {
                if (epoch == viewEpoch) operationStatus.Text = error.Message;
                throw;
            }
            finally { busy = false; host.IsEnabled = true; }
        });
        void Notice(string message)
        {
            if (epoch != viewEpoch) return;
            operationStatus.Text = message; status.Text = message;
        }
        JsonObject CampaignArgs() => new() { ["id"] = current?.Text("id", "") is { Length: > 0 } id
            ? id : throw new InvalidOperationException("Open a saved campaign first.") };
        string KeyPath(TextBox field, string role) => string.IsNullOrWhiteSpace(field.Text)
            ? throw new InvalidOperationException($"Select the {role} private key in Observer and independent grader keys.") : field.Text.Trim();
        string? PickFile(string title, string filter)
        {
            var dialog = new OpenFileDialog { Title = title, Filter = filter, CheckFileExists = true, Multiselect = false };
            return dialog.ShowDialog(this) == true ? dialog.FileName : null;
        }
        string? PickNewDirectory(string title, string prefix)
        {
            var dialog = new OpenFolderDialog { Title = title, Multiselect = false };
            return dialog.ShowDialog(this) == true
                ? System.IO.Path.Combine(dialog.FolderName, prefix + "-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..6]) : null;
        }
        TextBox JsonEditor(string label, int height = 220)
        {
            var editor = new TextBox { AcceptsReturn = true, AcceptsTab = true, TextWrapping = TextWrapping.NoWrap,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                MinHeight = 140, MaxHeight = height, FontFamily = new System.Windows.Media.FontFamily("Consolas"), FontSize = 13 };
            AutomationProperties.SetName(editor, label);
            return editor;
        }
        async Task LoadJson(TextBox path, TextBox editor)
        {
            string filename = path.Text.Trim();
            if (new FileInfo(filename).Length > 1024 * 1024)
                throw new InvalidOperationException("The selected JSON file exceeds the 1 MB editor limit.");
            string text = await File.ReadAllTextAsync(filename);
            var document = JsonNode.Parse(text) as JsonObject
                ?? throw new InvalidOperationException("Select a JSON object configuration or measurements file.");
            if (epoch == viewEpoch) editor.Text = document.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
        }
        async Task<bool> SaveJsonEditor(TextBox path, TextBox editor, string filename, string title)
        {
            if (Encoding.UTF8.GetByteCount(editor.Text) > 1024 * 1024)
                throw new InvalidOperationException("The JSON editor exceeds its 1 MB limit.");
            var document = JsonNode.Parse(editor.Text) as JsonObject
                ?? throw new InvalidOperationException("The editor must contain a JSON object.");
            var dialog = new SaveFileDialog { Title = title, Filter = "JSON file|*.json", FileName = filename, OverwritePrompt = true };
            if (path.Text.Length > 0 && System.IO.Path.GetDirectoryName(path.Text) is { } directory && Directory.Exists(directory))
                dialog.InitialDirectory = directory;
            if (dialog.ShowDialog(this) != true) return false;
            await File.WriteAllTextAsync(dialog.FileName, document.ToJsonString(new JsonSerializerOptions { WriteIndented = true }) + "\n", new UTF8Encoding(false));
            if (epoch == viewEpoch) path.Text = dialog.FileName;
            return true;
        }
        async Task GenerateKey(string role)
        {
            string? directory = PickNewDirectory("Choose where to keep the " + role + " key", "benchmark-" + role + "-key");
            if (directory is null) return;
            var result = await Call("benchmark-campaign-keys", new JsonObject { ["role"] = role, ["path"] = directory });
            if (epoch != viewEpoch) return;
            if (role == "observer") { observerKey.Text = result.Text("privateKey", ""); observerPublicKey = result.Text("publicKey", ""); }
            else { graderKey.Text = result.Text("privateKey", ""); graderPublicKey = result.Text("publicKey", ""); }
            keyInfo.Text = role + " public key fingerprint: " + result.Text("publicKeyFingerprint", "Unknown");
            Notice("Key files created. Add the public key to the configuration and keep the private key with its independent signer.");
        }
        keyBody.Children.Add(Ui.Actions(
            Ui.Button("Choose observer key", () => { if (PickFile("Observer private key", "PEM key|*.pem|All files|*.*") is { } file) observerKey.Text = file; }),
            Ui.Button("Choose grader key", () => { if (PickFile("Independent grader private key", "PEM key|*.pem|All files|*.*") is { } file) graderKey.Text = file; }),
            Ui.Button("Create observer key", () => Work("Creating a local observer key…", () => GenerateKey("observer"))),
            Ui.Button("Create grader key", () => Work("Creating a separate local grader key…", () => GenerateKey("grader")))));
        keyBody.Children.Add(keyInfo);

        var setup = sections["Campaigns"];
        var setupBody = Ui.Column(Ui.Text("Prepare a matched campaign", 18, bold: true),
            Ui.Text("Record actual product versions, tool access, hardware, cache policy, time and cost accounting before freezing. Unobserved provider identity and settings stay null. Quick and Comprehensive use the same independent grading infrastructure.", 13, "MutedBrush"));
        var extent = Ui.Select(setupBody, "Campaign", ["Quick", "Comprehensive"]);
        var presetFiles = new Dictionary<string, string>();
        string SelectedPreset() => extent.SelectedIndex == 1 ? "comprehensive" : "quick";
        var campaignPath = Ui.Field(setupBody, "Campaign definition JSON · optional; selected file overrides the preset");
        campaignPath.IsReadOnly = true;
        var campaignEditor = JsonEditor("Campaign budget and trial definition editor", 330);
        var campaignSummary = Ui.Text("", 14, "PrimaryBrush");
        void SummarizeDefinition()
        {
            if (campaignPath.Text.Length == 0)
            {
                campaignSummary.Text = extent.SelectedIndex == 1
                    ? "Bundled Comprehensive · 112 tasks × 3 repeats × 4 products = 1,344 scheduled trials\nPer-trial time ceiling: 1,200 s · cost ceiling: USD 0\nCache: cold · preregistered margin: 5 points"
                    : "Bundled Quick · 16 tasks × 1 repeat × 4 products = 64 scheduled trials\nPer-trial time ceiling: 300 s · cost ceiling: USD 0\nCache: cold · preregistered margin: 5 points";
                return;
            }
            try
            {
                var definition = JsonNode.Parse(campaignEditor.Text) as JsonObject;
                campaignSummary.Text = (campaignDirty ? "Unsaved campaign draft\n" : "Selected campaign file\n") +
                    (definition is null ? "A valid JSON campaign definition is required." : NativeProductCampaignPresentation.CampaignDefinitionSummary(definition));
            }
            catch (JsonException) { campaignSummary.Text = "Campaign draft is incomplete JSON. Correct it and save before freezing."; }
        }
        async Task LoadDefinition(string filename)
        {
            campaignPath.Text = filename; await LoadJson(campaignPath, campaignEditor); campaignDirty = false; SummarizeDefinition();
        }
        campaignEditor.TextChanged += (_, _) => { campaignDirty = true; SummarizeDefinition(); };
        extent.SelectionChanged += (_, _) =>
        {
            bool exportedPresetSelected = presetFiles.Values.Contains(campaignPath.Text);
            if (exportedPresetSelected && !campaignDirty && presetFiles.TryGetValue(SelectedPreset(), out var filename))
                Work("Loading the selected campaign definition…", () => LoadDefinition(filename));
            else SummarizeDefinition();
        };
        SummarizeDefinition();
        setupBody.Children.Add(campaignSummary);
        setupBody.Children.Add(Ui.Text("USD 0 is a cost ceiling, not a claim that a product is free. Set the actual permitted time, cost, repeats, cache and margin before freezing. Observed cost may remain null; a missing cost cannot establish a verified completion within budget.", 13, "MutedBrush"));
        var campaignEditorPanel = Ui.Column(Ui.Text("Edit maxSeconds, maxCostUsd, repeats, cache and marginPoints here. Task and product selections are frozen with this definition. Save it before starting the campaign.", 13, "MutedBrush"), campaignEditor);
        var campaignExpander = new Expander { Header = "Edit campaign budgets and trials in this app", Content = campaignEditorPanel,
            Margin = new Thickness(0, 8, 0, 8) };
        setupBody.Children.Add(Ui.Actions(
            Ui.Button("Choose campaign definition", () => Work("Loading a saved campaign definition…", async () =>
            {
                if (campaignDirty) throw new InvalidOperationException("Save the current campaign draft before choosing another definition.");
                if (PickFile("Choose campaign limits and trials", "Campaign definition|*.json") is not { } file) return;
                await LoadDefinition(file); campaignExpander.IsExpanded = true;
                Notice("Selected campaign file overrides the preset. Review its limits and matched trial counts.");
            })),
            Ui.Button("Save campaign definition", () => Work("Saving the campaign limits and matched trials…", async () =>
            {
                if (await SaveJsonEditor(campaignPath, campaignEditor, SelectedPreset() + "-campaign.json", "Save campaign definition"))
                { campaignDirty = false; SummarizeDefinition(); Notice("Campaign definition saved. These time, cost and repeat limits will be frozen."); }
            })),
            Ui.Button("Use bundled preset", () => Run(() =>
            {
                if (campaignDirty) throw new InvalidOperationException("Save the current campaign draft before returning to the bundled preset.");
                campaignPath.Text = ""; campaignEditor.Text = ""; campaignDirty = false; SummarizeDefinition();
                Notice("The selected bundled preset will be frozen with its displayed limits."); return Task.CompletedTask;
            }))));
        setupBody.Children.Add(campaignExpander);
        var configPath = Ui.Field(setupBody, "Lab configuration JSON");
        var configEditor = JsonEditor("Lab configuration editor", 360);
        configEditor.TextChanged += (_, _) => configDirty = true;
        var editorPanel = Ui.Column(Ui.Text("Edit the required controls and versions, then save the configuration before freezing.", 13, "MutedBrush"), configEditor);
        var editorExpander = new Expander { Header = "Edit configuration in this app", Content = editorPanel, Margin = new Thickness(0, 8, 0, 8) };
        setupBody.Children.Add(Ui.Actions(
            Ui.Button("Export setup templates", () => Work("Preparing configuration and campaign templates…", async () =>
            {
                if (configDirty || campaignDirty) throw new InvalidOperationException("Save the edited lab and campaign definitions before loading new templates.");
                string? directory = PickNewDirectory("Choose a parent folder for setup templates", "native-benchmark-setup");
                if (directory is null) return;
                var result = await Call("benchmark-campaign-templates", new JsonObject { ["path"] = directory });
                if (epoch != viewEpoch) return;
                configPath.Text = result.Text("configPath", ""); await LoadJson(configPath, configEditor);
                configDirty = false; editorExpander.IsExpanded = true;
                foreach (string name in new[] { "quick", "comprehensive" })
                    if (result.Object("campaigns").Text(name, "") is { Length: > 0 } path) presetFiles[name] = path;
                if (presetFiles.TryGetValue(SelectedPreset(), out var campaignFile)) await LoadDefinition(campaignFile);
                campaignExpander.IsExpanded = true;
                Notice("Setup templates exported to " + result.Text("directory", directory) + ". Edit and save the lab controls and campaign budgets before freezing.");
            })),
            Ui.Button("Choose configuration", () => Work("Loading the selected configuration…", async () =>
            {
                if (PickFile("Choose a lab configuration", "Lab configuration|*.json") is not { } file) return;
                configPath.Text = file; await LoadJson(configPath, configEditor); configDirty = false; editorExpander.IsExpanded = true;
                Notice("Configuration loaded. Review its product versions and controls before freezing.");
            })),
            Ui.Button("Save configuration", () => Work("Saving the edited configuration…", async () =>
            {
                if (await SaveJsonEditor(configPath, configEditor, "lab.json", "Save lab configuration"))
                { configDirty = false; Notice("Configuration saved to " + configPath.Text); }
            }))));
        editorPanel.Children.Add(Ui.Button("Use generated public keys", () => Run(() =>
        {
            var config = JsonNode.Parse(configEditor.Text) as JsonObject ?? throw new InvalidOperationException("Load a configuration first.");
            if (observerPublicKey.Length == 0 && graderPublicKey.Length == 0)
                throw new InvalidOperationException("Generate a key in this view, or enter the independently supplied public key paths in the configuration.");
            if (observerPublicKey.Length > 0) config["observerPublicKey"] = observerPublicKey;
            if (graderPublicKey.Length > 0) config["graderPublicKey"] = graderPublicKey;
            configEditor.Text = config.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
            Notice("Public key paths inserted. Save the edited configuration before freezing.");
            return Task.CompletedTask;
        })));
        setupBody.Children.Add(editorExpander);
        var campaigns = new ListBox { MaxHeight = 260, MinHeight = 80 };
        AutomationProperties.SetName(campaigns, "Saved native product campaigns");

        var trial = sections["Run trials"];
        var nextTitle = Ui.Text("Open a campaign to see its next matched trial.", 18, bold: true);
        var nextDetails = Ui.Text("A public task packet contains the candidate-facing prompt and output contract.", 13, "MutedBrush");
        var prompt = JsonEditor("Public task packet", 310); prompt.IsReadOnly = true; prompt.TextWrapping = TextWrapping.Wrap;
        var schedule = new ListBox { MaxHeight = 270, MinHeight = 80 };
        var scheduleInfo = Ui.Text("No schedule loaded.", 13, "MutedBrush");
        AutomationProperties.SetName(schedule, "Counterbalanced native trial schedule");
        trial.Children.Add(nextTitle); trial.Children.Add(nextDetails); trial.Children.Add(prompt);
        trial.Children.Add(Ui.Actions(Ui.Button("Copy public task", () => Run(() =>
        {
            if (prompt.Text.Length == 0) throw new InvalidOperationException("The selected campaign has no pending public packet.");
            Clipboard.SetText(prompt.Text); Notice("Public task copied. Run it on the scheduled native product surface."); return Task.CompletedTask;
        })), Ui.Button("Save public task", () => Work("Saving the public task packet…", async () =>
        {
            if (prompt.Text.Length == 0) throw new InvalidOperationException("The selected campaign has no pending public packet.");
            var file = new SaveFileDialog { Filter = "Public task packet|*.txt", FileName = "native-trial-task.txt" };
            if (file.ShowDialog(this) == true) { await File.WriteAllTextAsync(file.FileName, prompt.Text, new UTF8Encoding(false)); Notice("Public packet saved."); }
        }))));
        var scheduleBody = Ui.Column(scheduleInfo, schedule, Ui.Button("Save full schedule CSV", () => Work("Saving the complete registered schedule…", async () =>
        {
            string source = current?.Text("schedulePath", "") ?? "";
            if (source.Length == 0 || !File.Exists(source)) throw new InvalidOperationException("Open a frozen campaign with a retained schedule.");
            if (new FileInfo(source).Length > 8 * 1024 * 1024) throw new InvalidOperationException("The retained schedule exceeds its export size limit.");
            var file = new SaveFileDialog { Filter = "Complete registered schedule|*.csv", FileName = "native-trial-schedule.csv", OverwritePrompt = true };
            if (file.ShowDialog(this) != true) return;
            await File.WriteAllBytesAsync(file.FileName, await File.ReadAllBytesAsync(source));
            Notice("Complete registered schedule saved to " + file.FileName);
        })));
        trial.Children.Add(new Expander { Header = "Matched schedule and retained outcomes", Content = scheduleBody, Margin = new Thickness(0, 8, 0, 14) });
        var captureBody = Ui.Column(Ui.Text("Import the observed native result", 18, bold: true),
            Ui.Text("Use an isolated final-answer export from the scheduled product. Record the actual duration, cost or null, interventions, origin evidence and terminal state. Product failure, harness failure and unsupported routes remain separate.", 13, "MutedBrush"));
        var capturePath = Ui.Field(captureBody, "Native export file · optional for a trial without a delivered answer");
        var measurementsPath = Ui.Field(captureBody, "Measurements and provenance JSON");
        var measurementsEditor = JsonEditor("Native trial measurements and provenance editor", 290);
        measurementsEditor.TextChanged += (_, _) => measurementsDirty = true;
        captureBody.Children.Add(Ui.Actions(
            Ui.Button("Choose native export", () => { if (PickFile("Choose the native product export", "Native export|*.json;*.jsonl|All files|*.*") is { } file) capturePath.Text = file; }),
            Ui.Button("Choose measurements", () => Work("Loading observer measurements…", async () =>
            {
                if (PickFile("Choose observed measurements and provenance", "Measurements|*.json") is not { } file) return;
                measurementsPath.Text = file; await LoadJson(measurementsPath, measurementsEditor); measurementsDirty = false;
                Notice("Observed measurements loaded. Confirm that they describe the scheduled native trial.");
            })),
            Ui.Button("Save measurements", () => Work("Saving observer measurements…", async () =>
            {
                if (await SaveJsonEditor(measurementsPath, measurementsEditor, "measurements.json", "Save trial measurements and provenance"))
                { measurementsDirty = false; Notice("Measurements saved. Import after independently confirming the observed origin and controls."); }
            }))));
        captureBody.Children.Add(new Expander { Header = "Edit measurements and provenance", Content = measurementsEditor,
            IsExpanded = true, Margin = new Thickness(0, 8, 0, 8) });
        var inboxPath = Ui.Field(captureBody, "Prepared inbox folder · optional for consecutive imports");
        captureBody.Children.Add(Ui.Button("Choose prepared inbox", () =>
        {
            var folder = new OpenFolderDialog { Title = "Choose the prepared native-trial inbox", Multiselect = false };
            if (folder.ShowDialog(this) == true) inboxPath.Text = folder.FolderName;
        }));
        var attestOrigin = Ui.Toggle(captureBody, "I independently observed the imported native trials and confirm their origin evidence and actual matched controls.", false);
        foreach (var input in new[] { capturePath, measurementsPath, measurementsEditor, inboxPath, observerKey })
            input.TextChanged += (_, _) => attestOrigin.IsChecked = false;
        trial.Children.Add(Ui.Card(captureBody));
        var captureHelpers = Ui.Column(Ui.Text("Use the exact conversation identity from an existing export or saved Gateway conversation. These helpers only read delivered answers.", 13, "MutedBrush"));
        var conversationId = Ui.Field(captureHelpers, "Native conversation ID");
        captureHelpers.Children.Add(Ui.Actions(
            Ui.Button("Select ChatGPT conversation", () => Work("Selecting an existing ChatGPT conversation export…", async () =>
            {
                string? export = PickFile("Choose the ChatGPT conversations export", "ChatGPT export|*.json");
                if (export is null) return;
                var file = new SaveFileDialog { Title = "Save isolated conversation to a new file", Filter = "Isolated ChatGPT conversation|*.json", FileName = "chatgpt-conversation.json", OverwritePrompt = true };
                if (file.ShowDialog(this) != true) return;
                var result = await Call("benchmark-campaign-select-chatgpt", new JsonObject
                { ["exportPath"] = export, ["conversationId"] = conversationId.Text.Trim(), ["path"] = file.FileName });
                if (epoch == viewEpoch) capturePath.Text = result.Text("output", file.FileName);
                Notice("Isolated conversation saved. Record and attest the actual trial before importing.");
            })),
            Ui.Button("Read GPT Mobile saved answer", () => Work("Reading an existing Gateway conversation…", async () =>
            {
                var args = CampaignArgs();
                var file = new SaveFileDialog { Title = "Save an existing Gateway answer to a new file", Filter = "GPT Mobile native capture|*.json", FileName = "gpt-mobile-capture.json", OverwritePrompt = true };
                if (file.ShowDialog(this) != true) return;
                args["conversationId"] = conversationId.Text.Trim(); args["path"] = file.FileName;
                var result = await Call("benchmark-campaign-read-mobile", args);
                if (epoch == viewEpoch) capturePath.Text = result.Text("output", file.FileName);
                Notice("Saved GPT Mobile answer read. Its origin and matched controls still require independent observation.");
            }))));
        trial.Children.Add(new Expander { Header = "Native export helpers", Content = captureHelpers, Margin = new Thickness(0, 8, 0, 12) });

        var scoreboard = sections["Scoreboard"];
        var headline = Ui.Text("Comparative product quality · Unknown", 21, "PrimaryBrush", true);
        var campaignInfo = Ui.Text("No registered campaign selected.", 13, "MutedBrush");
        var operationHeadline = Ui.Text("No grading operation has been performed in this view.", 14, "MutedBrush");
        var operationReceipts = new ListBox { MaxHeight = 220 };
        AutomationProperties.SetName(operationReceipts, "Original native grading request receipts");
        var operationPanel = Ui.Column(Ui.Text("Last action receipts", 17, bold: true), operationHeadline, operationReceipts);
        var products = new StackPanel();
        var pairs = new StackPanel();
        var separate = Ui.Text("GitHub Intelligence / 100: Unknown\nFirst attempt comparison: Unknown\nBest within budget comparison: Unknown", 14, "MutedBrush");
        var boundaries = new StackPanel();
        scoreboard.Children.Add(headline); scoreboard.Children.Add(campaignInfo);
        scoreboard.Children.Add(Ui.Text("Verified final answers, observed time and recorded cost", 17, bold: true));
        scoreboard.Children.Add(Ui.Text("Missing outcomes and cost remain Unknown. Partial observations do not establish a verified completion rate or a product ranking.", 13, "MutedBrush"));
        scoreboard.Children.Add(products);
        scoreboard.Children.Add(Ui.Text("Paired comparison against GPT Mobile AI", 17, bold: true));
        scoreboard.Children.Add(pairs);
        scoreboard.Children.Add(Ui.Text("Separate evidence requirements", 17, bold: true));
        scoreboard.Children.Add(separate); scoreboard.Children.Add(boundaries);

        async Task RefreshHistory()
        {
            var result = await Call("benchmark-campaign-history");
            if (epoch != viewEpoch) return;
            Ui.Rows(campaigns, result.Array("campaigns").OfType<JsonObject>().Select(row => new JsonObject
            {
                ["id"] = row.Text("id"), ["title"] = row.Text("title", row.Text("id")),
                ["state"] = row.Text("state"), ["detail"] = row.Text("evidenceLane") + " · " + row.Text("verdict", "Unknown") +
                    " · " + NativeProductCampaignPresentation.Metric(row, "observed") + " / " + NativeProductCampaignPresentation.Metric(row, "slots") + " observations"
            }));
        }
        void Render(JsonObject result)
        {
            if (epoch != viewEpoch) return;
            bool campaignChanged = current?.Text("id", "") != result.Text("id", "");
            current = result;
            if (campaignChanged)
            {
                operationHeadline.Text = "No grading operation has been performed in this view.";
                operationReceipts.Items.Clear();
            }
            var report = result.Object("report");
            headline.Text = NativeProductCampaignPresentation.Headline(report);
            campaignInfo.Text = result.Text("title", result.Text("id")) + " · " + result.Text("state", "Unknown") + " · " + result.Text("evidenceLane", "Unknown") +
                "\nPreregistered margin: " + NativeProductCampaignPresentation.Metric(report.Object("nativeComparison"), "marginPoints") +
                " points · " + report.Object("nativeComparison").Text("cache", "Unknown") + " cache" +
                "\nSource revision: " + report.Text("sourceCommit", "Unknown") + "\nPlan digest: " + report.Object("nativeComparison").Text("planDigest", "Unknown");
            products.Children.Clear();
            foreach (var row in NativeProductCampaignPresentation.ProductRows(report))
                products.Children.Add(Ui.Card(Ui.Column(Ui.Text(row.Text("title"), 17, bold: true),
                    Ui.Text(row.Text("observations") + " · " + row.Text("verified"), 14),
                    Ui.Text(row.Text("timing"), 13, "MutedBrush"), Ui.Text(row.Text("cost"), 13, "MutedBrush"),
                    Ui.Text(row.Text("coverage"), 12, "MutedBrush"))));
            pairs.Children.Clear();
            foreach (string line in NativeProductCampaignPresentation.PairLines(report)) pairs.Children.Add(Ui.Text(line, 13));
            if (pairs.Children.Count == 0) pairs.Children.Add(Ui.Text("Matched pair evidence · Unknown", 13, "MutedBrush"));
            separate.Text = NativeProductCampaignPresentation.SeparateMeasurements(report);
            boundaries.Children.Clear();
            foreach (var boundary in report.Array("boundaries")) boundaries.Children.Add(Ui.Text(boundary?.ToString() ?? "", 12, "MutedBrush"));
            var next = result.Object("next");
            var slot = next.Object("slot");
            bool pending = next.Text("status", "") == "AWAITING_NATIVE_TRIAL" && slot.Number("scheduleIndex") is >= 0;
            nextTitle.Text = pending ? "Next trial · " + slot.Text("product") + " · " + slot.Text("taskId")
                : result.Text("state", "") == "SETUP_INCOMPLETE" ? "Campaign setup needs recovery" : "No native trial is awaiting capture";
            nextDetails.Text = pending
                ? $"Slot {NativeProductCampaignPresentation.Metric(slot, "scheduleIndex")} · repeat {NativeProductCampaignPresentation.Metric(slot, "repeat")} · {slot.Text("family")} · {slot.Text("cache")} cache\nVersion: {slot.Text("version")}\n" + next.Text("note", "Run the supplied public packet on the scheduled native surface.")
                : result.Text("message", next.Text("nextAction", "Open a campaign, then inspect its retained schedule and evidence."));
            if (next.Text("measurementsError", "").Length > 0)
                nextDetails.Text += "\nSaved measurement draft needs repair: " + next.Text("measurementsError", "");
            prompt.Text = pending ? next.Text("promptText", "") : "";
            string trialIdentity = pending ? result.Text("id") + ":" + NativeProductCampaignPresentation.Metric(slot, "scheduleIndex") : "";
            if (trialIdentity != displayedTrial)
            {
                displayedTrial = trialIdentity;
                measurementsPath.Text = pending ? next.Text("measurementsPath", "") : "";
                measurementsEditor.Text = pending ? (next["measurements"] as JsonObject ?? next.Object("measurementsTemplate"))
                    .ToJsonString(new JsonSerializerOptions { WriteIndented = true }) : "";
                measurementsDirty = false; attestOrigin.IsChecked = false; capturePath.Text = "";
            }
            Ui.Rows(schedule, result.Array("schedule").OfType<JsonObject>().Select(row => new JsonObject
            {
                ["scheduleIndex"] = row["scheduleIndex"]?.DeepClone(),
                ["title"] = $"{NativeProductCampaignPresentation.Metric(row, "scheduleIndex")} · {row.Text("product")} · {row.Text("taskId")} · repeat {NativeProductCampaignPresentation.Metric(row, "repeat")}",
                ["state"] = row.Text("observationState", "MISSING_OBSERVATION"),
                ["detail"] = "Independent grade: " + row.Text("independentGrade", "Unknown") + " · " + row.Text("family")
            }));
            scheduleInfo.Text = result.Flag("scheduleTruncated")
                ? $"Showing the first {result.Array("schedule").Count} of {NativeProductCampaignPresentation.Metric(result, "scheduleTotal")} scheduled trials. Save the full schedule CSV for every row. The next-trial card shows the current pending slot."
                : $"{result.Array("schedule").Count} scheduled trials shown. The saved CSV preserves the complete registered order.";
            if (result["operation"] is JsonObject operation)
            {
                operationHeadline.Text = NativeProductCampaignPresentation.OperationHeadline(operation);
                var receiptRows = NativeProductCampaignPresentation.OperationRows(operation).ToArray();
                Ui.Rows(operationReceipts, receiptRows.Take(200));
                if (receiptRows.Length > 200) operationHeadline.Text += $" · Showing the first 200 of {receiptRows.Length} receipts; every original receipt remains on disk.";
                Notice(operationHeadline.Text);
            }
            else Notice(result.Text("message", "Campaign evidence loaded. No model-generation requests were made."));
        }
        async Task ReadCampaign(string id)
        {
            Render(await Call("benchmark-campaign-read", new JsonObject { ["id"] = id }));
            if (epoch == viewEpoch) Show(current?.Text("state", "") == "SETUP_INCOMPLETE" ? "Campaigns" : "Run trials");
        }
        setupBody.Children.Add(Ui.Button("Freeze matched campaign", () => Work("Exporting public tasks and freezing the matched schedule…", async () =>
        {
            if (configDirty) throw new InvalidOperationException("Save the edited lab configuration before freezing.");
            if (campaignDirty) throw new InvalidOperationException("Save the edited campaign budgets and trial definition before freezing.");
            if (string.IsNullOrWhiteSpace(configPath.Text)) throw new InvalidOperationException("Choose a completed lab configuration.");
            var args = new JsonObject
            {
                ["configPath"] = configPath.Text.Trim(), ["preset"] = SelectedPreset(),
                ["observerKeyPath"] = KeyPath(observerKey, "observer")
            };
            if (!string.IsNullOrWhiteSpace(campaignPath.Text)) args["campaignPath"] = campaignPath.Text.Trim();
            var result = await Call("benchmark-campaign-create", args);
            Render(result); await RefreshHistory(); if (epoch == viewEpoch) Show("Run trials");
        }), true));
        setup.Children.Add(Ui.Card(setupBody));
        setup.Children.Add(Ui.Text("Saved campaigns", 18, bold: true)); setup.Children.Add(campaigns);
        setup.Children.Add(Ui.Actions(
            Ui.Button("Refresh campaigns", () => Work("Reading saved campaign identities…", async () => { await RefreshHistory(); Notice("Saved campaign list refreshed."); })),
            Ui.Button("Open campaign", () => Work("Validating saved campaign evidence…", async () =>
            {
                var selected = Ui.Selected(campaigns) ?? throw new InvalidOperationException("Select a saved campaign.");
                await ReadCampaign(selected.Text("id"));
            })),
            Ui.Button("Resume setup", () => Work("Resuming the saved public-task setup…", async () =>
            {
                var selected = Ui.Selected(campaigns);
                var args = selected is null ? CampaignArgs() : new JsonObject { ["id"] = selected.Text("id") };
                args["observerKeyPath"] = KeyPath(observerKey, "observer");
                Render(await Call("benchmark-campaign-resume-setup", args)); await RefreshHistory();
                if (epoch == viewEpoch) Show("Run trials");
            }))));
        setup.Children.Add(Ui.Button("Run offline synthetic check", () => Work("Exercising the controller with synthetic fixtures…", async () =>
        {
            string? directory = PickNewDirectory("Choose a parent folder for synthetic fixture reports", "native-benchmark-synthetic");
            if (directory is null) return;
            var result = await Call("benchmark-campaign-demo", new JsonObject { ["path"] = directory });
            Notice("Synthetic controller check completed. Product quality remains unmeasured. Fixture scoreboard: " + result.Text("scoreboard", directory));
        })));
        captureBody.Children.Add(Ui.Button("Import observed trial", () => Work("Validating and signing the observed native trial…", async () =>
        {
            if (measurementsDirty) throw new InvalidOperationException("Save the edited measurements before importing.");
            if (attestOrigin.IsChecked != true) throw new InvalidOperationException("An independent observer must explicitly confirm the native origin and actual matched controls.");
            var next = current?.Object("next") ?? [];
            double? index = next.Object("slot").Number("scheduleIndex");
            if (next.Text("status", "") != "AWAITING_NATIVE_TRIAL" || index is null || index < 0 || index > int.MaxValue || index != Math.Truncate(index.Value))
                throw new InvalidOperationException("Open a campaign with a pending native trial.");
            var args = CampaignArgs(); args["slotIndex"] = (int)index.Value; args["measurementsPath"] = measurementsPath.Text.Trim();
            args["observerKeyPath"] = KeyPath(observerKey, "observer"); args["attestOrigin"] = true;
            if (!string.IsNullOrWhiteSpace(capturePath.Text)) args["capturePath"] = capturePath.Text.Trim();
            attestOrigin.IsChecked = false;
            Render(await Call("benchmark-campaign-import", args));
            if (epoch == viewEpoch) capturePath.Text = "";
            await RefreshHistory();
        }), true));
        captureBody.Children.Add(Ui.Button("Import observed inbox", () => Work("Importing consecutive independently observed trials…", async () =>
        {
            if (attestOrigin.IsChecked != true) throw new InvalidOperationException("Independently confirm the origin and actual matched controls of every trial to be imported.");
            if (string.IsNullOrWhiteSpace(inboxPath.Text)) throw new InvalidOperationException("Choose the prepared inbox before confirming its observations.");
            var args = CampaignArgs(); args["inbox"] = inboxPath.Text.Trim();
            args["observerKeyPath"] = KeyPath(observerKey, "observer"); args["attestOrigin"] = true;
            attestOrigin.IsChecked = false;
            Render(await Call("benchmark-campaign-import-inbox", args)); await RefreshHistory();
        })));
        async Task Grade(bool recover)
        {
            var args = CampaignArgs(); args["graderKeyPath"] = KeyPath(graderKey, "independent grader");
            Render(await Call(recover ? "benchmark-campaign-recover" : "benchmark-campaign-grade", args));
            await RefreshHistory(); if (epoch == viewEpoch) Show("Scoreboard");
        }
        var gradingActions = Ui.Actions(
            Ui.Button("Grade imported answers", () => Work("Submitting supplied answers to the independent sandbox grader…", () => Grade(false)), true),
            Ui.Button("Recover grade receipts", () => Work("Reading retained grading receipts without resubmission…", () => Grade(true))),
            Ui.Button("Refresh evidence", () => Work("Revalidating saved evidence…", async () => Render(await Call("benchmark-campaign-read", CampaignArgs())))));
        scoreboard.Children.Insert(2, gradingActions);
        scoreboard.Children.Insert(3, Ui.Text("Grading submits captured answers to the configured independent sandbox. Recover checks original receipts; it does not repeat uncertain submissions. Neither action generates a product answer.", 13, "MutedBrush"));
        scoreboard.Children.Insert(4, Ui.Card(operationPanel));
        trial.Children.Add(Ui.Button("Review scoreboard and grading", () => Show("Scoreboard")));
        scoreboard.Children.Add(Ui.Button("Grade source retrieval trace", () => Work("Checking frozen source ranges…", async () =>
        {
            var file = PickFile("Select a frozen source QA capture", "Source QA capture|*.json");
            if (file is null) return;
            var result = await Call("benchmark-campaign-source-qa", new JsonObject { ["path"] = file });
            ShowDetails("Source retrieval measurements", result);
        })));
        scoreboard.Children.Add(Ui.Button("Export readable scoreboard", () => Work("Exporting measured results and evidence boundaries…", async () =>
        {
            var args = CampaignArgs();
            string? directory = PickNewDirectory("Choose a parent folder for the scoreboard export", "native-benchmark-report");
            if (directory is null) return;
            args["path"] = directory;
            var result = await Call("benchmark-campaign-export", args);
            Notice("Scoreboard exported: " + result.Text("scoreboard", result.Text("directory", directory)));
        })));
        return () =>
        {
            if (loaded) return;
            loaded = true;
            Work("Reading saved native product campaigns…", async () => { await RefreshHistory(); Notice("Choose a saved campaign or prepare a new matched campaign."); });
        };
    }
}
