using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private string settingsOwner = "local:single-user";
    private sealed record OwnerChoice(string Principal, string Name)
    {
        public override string ToString() => Name;
    }
    private sealed record ValidationProfileChoice(string Id, string Name)
    {
        public override string ToString() => Name;
    }

    private static string ProfileName(JsonObject profiles, string id) => id switch
    {
        "disabled" => "Disabled on this PC",
        "invalid" => "Unavailable — review the saved host policy",
        _ => profiles.Text(id, "Unavailable approved profile") + " · " + id
    };

    private void BuildSettings()
    {
        int viewEpoch = epoch;
        string tab=Section, currentRoute=route;
        if(tab=="Updates")BuildUpdateCard();
        var body = Ui.Column(Ui.Text("Reading confirmed settings…",16,"MutedBrush"));
        page.Children.Add(Ui.Card(body));
        Run(async () =>
        {
            var principal=settingsOwner;
            var current = await Call("control-settings-read", new JsonObject { ["owner_principal"] = principal });
            var devices = tab=="Execution" ? await Call("control-devices") : new JsonObject();
            body.Children.Clear();
            var effective = current.Object("effective"); var desired = current.Object("desired");
            var writable = current.Array("writable_fields").Select(item => item?.ToString()).ToHashSet();
            var visible = tab switch
            {
                "Models" => new[]{"backend","model"}, "Execution" => new[]{"engineering_enabled","workers","active_chats","batch_mode","local_validation_workers","validation_profile"},
                "Updates" => new[]{"update_mode","update_interval_hours"}, "General" => new[]{"start_at_login"},
                _ => new[]{"debug_mode","public_repository_maps"}
            };
            writable.IntersectWith(visible);
            body.Children.Add(Ui.Text($"{tab} · confirmed revision {current.Text("revision")}",20,bold:true));
            body.Children.Add(Ui.Text($"Changed by {current.Text("changed_by")} · {current.Text("principal_id")}",13,"MutedBrush"));
            if(tab=="Execution"){
            var owners = new List<OwnerChoice> { new("local:single-user", "This desktop") };
            owners.AddRange(devices.Array("items").OfType<JsonObject>().Where(device => !device.Flag("revoked") && device.Text("role") == "owner")
                .Select(device => new OwnerChoice(device.Text("principal_id"), device.Text("name", "Paired owner"))));
            body.Children.Add(Ui.Text("Engineering owner", color: "MutedBrush"));
            var owner = new ComboBox { ItemsSource = owners, SelectedItem = owners.FirstOrDefault(item => item.Principal == settingsOwner) };
            body.Children.Add(owner);
            owner.SelectionChanged += (_, _) =>
            {
                if (owner.SelectedItem is OwnerChoice choice && choice.Principal != settingsOwner) { settingsOwner = choice.Principal; Navigate(currentRoute); }
            };
            body.Children.Add(Ui.Text("Engineering applies only to the selected owner. Capacity, model route, startup and update settings apply to this PC.", 13, "MutedBrush"));
            }
            var readers = new Dictionary<string, Func<JsonNode?>>();
            void Toggle(string key, string name)
            {
                if (!writable.Contains(key)) return;
                var input = Ui.Toggle(body, name + " · effective " + (effective.Flag(key) ? "On" : "Off"), desired.Flag(key));
                readers[key] = () => JsonValue.Create(input.IsChecked == true);
            }
            void Integer(string key, string name)
            {
                if (!writable.Contains(key)) return;
                var input = Ui.Field(body, name + " · effective " + effective.Text(key), desired.Text(key, ""));
                readers[key] = () => int.TryParse(input.Text, out int number) ? JsonValue.Create(number) : throw new InvalidOperationException(name + " must be a whole number.");
            }
            void Selection(string key, string name, string[] values)
            {
                if (!writable.Contains(key)) return;
                var input = Ui.Select(body, name + " · effective " + effective.Text(key), values, desired.Text(key));
                readers[key] = () => JsonValue.Create(input.SelectedItem?.ToString());
            }
            Toggle("engineering_enabled", "Allow new engineering jobs for this owner");
            Integer("workers", "Concurrent workers (1–16)"); Integer("active_chats", "Admitted chats (workers–64)");
            Selection("batch_mode", "Batch mode", ["standard", "smart", "full"]);
            Integer("local_validation_workers", "Local validation workers (1–4)");
            if (writable.Contains("validation_profile"))
            {
                var profiles = current.Object("validation_profiles");
                string selected = desired.Text("validation_profile", "disabled");
                var choices = profiles.Select(item => new ValidationProfileChoice(item.Key, ProfileName(profiles, item.Key))).ToList();
                if (!choices.Any(item => item.Id == selected)) choices.Add(new(selected, ProfileName(profiles, selected)));
                body.Children.Add(Ui.Text("Repository validation profile · effective " + ProfileName(profiles, effective.Text("validation_profile", "disabled")), 13, "MutedBrush"));
                var input = new ComboBox { ItemsSource = choices, SelectedItem = choices.Single(item => item.Id == selected) };
                AutomationProperties.SetName(input, "Approved repository validation profile");
                body.Children.Add(input);
                body.Children.Add(Ui.Text("Choose a policy explicitly approved on this PC. Existing jobs retain their accepted policy. Approve additional profiles in Android controls; use Repository Validation to change the host's approval or disable validation.", 13, "MutedBrush"));
                readers["validation_profile"] = () => input.SelectedItem is ValidationProfileChoice choice
                    ? JsonValue.Create(choice.Id) : throw new InvalidOperationException("Choose an approved validation profile.");
            }
            if (writable.Contains("backend"))
            {
                var input = Ui.Field(body, "Model backend origin · effective " + effective.Text("backend"), desired.Text("backend", ""));
                readers["backend"] = () => JsonValue.Create(input.Text.Trim());
            }
            if (writable.Contains("model"))
            {
                var input = Ui.Field(body, "Exact model ID (blank keeps the configured route)", desired.Text("model", ""));
                readers["model"] = () => JsonValue.Create(input.Text.Trim());
            }
            Toggle("public_repository_maps", "Public repository maps"); Toggle("start_at_login", "Start Gateway when I sign in");
            Toggle("debug_mode", "Record sanitized debug diagnostics");
            Selection("update_mode", "Gateway updates", ["notify", "auto", "off"]); Integer("update_interval_hours", "Update interval (hours)");
            if (current.Array("pending").Count > 0)
            {
                body.Children.Add(Ui.Text("Pending activation", 17, "SecondaryBrush", true));
                foreach (var pending in current.Array("pending"))
                    body.Children.Add(Ui.Text(pending.Text("field").Replace('_', ' ') + ": " +
                        pending.Text("reason", "Awaiting a confirmed runtime change").Replace('_', ' ') +
                        (pending.Array("blocking_jobs").Count > 0 ? $" · {pending.Array("blocking_jobs").Count} blocking jobs" : ""), 13, "MutedBrush"));
            }
            var savedCommand = current.Object("native_pending");
            var retryOriginal = Ui.Button("Review / retry original change", () => Run(async () =>
            {
                ShowDetails("Review the original settings change", savedCommand.Object("payload"));
                if (MessageBox.Show(this, "Check this original command's receipt, and retry its exact saved change only if Gateway confirms the command was never recorded?",
                        "Retry original settings change", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
                var result = await Call("control-settings-retry", new JsonObject
                {
                    ["command_id"] = savedCommand.Text("command_id"), ["installation_id"] = savedCommand.Text("installation_id"),
                    ["owner_principal"] = savedCommand.Text("owner_principal")
                });
                ShowDetails("Original settings outcome", result);
                status.Text = "Original settings receipt · " + result.Text("status").Replace('_', ' ') + " · revision " + result.Text("revision");
                if (route == currentRoute) Navigate(currentRoute);
            }));
            retryOriginal.IsEnabled = savedCommand.Object("payload").Count > 0;
            if (savedCommand.Count > 0)
                body.Children.Add(Ui.Text(retryOriginal.IsEnabled
                    ? "An original settings change needs confirmation. Read its receipt or review the exact saved change before retrying."
                    : "An older pending reference supports receipt lookup only; it did not retain a change that can safely be retried.", 13, "MutedBrush"));
            body.Children.Add(Ui.Actions(Ui.Button("Apply settings", () => Run(async () =>
            {
                var changes = new JsonObject();
                foreach (var (key, read) in readers)
                { var value = read(); if (!JsonNode.DeepEquals(value, desired[key])) changes[key] = value; }
                if (changes.Count == 0) { status.Text = "No settings changed."; return; }
                JsonObject result;
                try { result = await Call("control-settings-patch", new JsonObject
                {
                    ["owner_principal"] = current.Text("principal_id"), ["installation_id"] = current.Text("installation_id"),
                    ["expected_revision"] = current["revision"]?.DeepClone(), ["command_id"] = "desktop-" + Guid.NewGuid().ToString("N"), ["changes"] = changes
                }); }
                catch (Exception error) {
                    var latest=await Call("control-settings-read",new JsonObject{["owner_principal"]=current.Text("principal_id")});
                    status.Text=$"{error.Message} · observed revision {latest.Text("revision")}. Your proposed values are still shown.";
                    savedCommand=latest.Object("native_pending");retryOriginal.IsEnabled=savedCommand.Object("payload").Count>0;
                    throw new InvalidOperationException(status.Text,error);
                }
                status.Text = "Settings receipt · " + result.Text("status").Replace('_', ' ') + " · revision " + result.Text("revision");
                if (route == currentRoute) Navigate(currentRoute);
            }), true), Ui.Button("Reload", () => Navigate(currentRoute)),
                Ui.Button("Read original receipt", () => Run(async () =>
                {
                    var pending = savedCommand;
                    if (pending.Count == 0) throw new InvalidOperationException("No unconfirmed desktop settings command is retained. Reload to check again.");
                    var result = await Call("control-settings-command", new JsonObject { ["command_id"] = pending.Text("command_id"),
                        ["owner_principal"] = pending.Text("owner_principal", "local:single-user") });
                    ShowDetails("Original settings receipt", result); Navigate(currentRoute);
                })), retryOriginal, Ui.Button("Host connection and firewall", () => Navigate("Settings/Connection"))));
            if(tab=="Models")body.Children.Add(Ui.Button("Discover exact model IDs",()=>Run(async()=>{
                await EnsureConnected(true);ShowDetails("Advertised models",new JsonArray(models.Select(m=>(JsonNode?)JsonValue.Create(m)).ToArray()));
            })));
        });
    }

    private void BuildConnectPhone()
    {
        int viewEpoch = epoch;
        var form = Ui.Column(Ui.Text("Gateway address", 22, bold: true));
        var hint = Ui.Text("Reading this PC’s connection…", 15, "MutedBrush"); form.Children.Add(hint);
        page.Children.Add(Ui.Card(form, true));
        Run(async () =>
        {
            var current = await Call("control-settings-read");
            if (epoch != viewEpoch) return;
            string direct = current.Text("account_connection_url", "");
            string host = current.Text("gateway_host", "");
            if (!System.Net.IPAddress.TryParse(host, out var ip) || System.Net.IPAddress.IsLoopback(ip))
            {
                hint.Text = "This Gateway is listening only on this PC. Choose your private network interface, then restart Gateway.";
                form.Children.Add(Ui.Button("Network settings", () => Navigate("Settings/Connection"), true));
                return;
            }
            ComboBox? adapter = null;
            if (ip.Equals(System.Net.IPAddress.Any) || ip.Equals(System.Net.IPAddress.IPv6Any))
            {
                var choices = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up)
                    .SelectMany(n => n.GetIPProperties().UnicastAddresses).Select(a => a.Address)
                    .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !System.Net.IPAddress.IsLoopback(a))
                    .Select(a => a.ToString()).Distinct().ToArray();
                if (choices.Length == 0) { hint.Text = "Connect this PC to your private network and reopen this page."; return; }
                adapter = Ui.Select(form, "PC interface", choices);
            }
            hint.Text = direct.Length > 0 ? "Enter this PC’s IP in GPT Mobile. Your Tailscale account identifies your phone."
                : "Enter this PC’s IP in GPT Mobile. For first-time Wi-Fi settings access, allow your phone’s current Wi-Fi IP below.";
            string SelectedHost() => adapter?.SelectedItem?.ToString() ?? host;
            string Address() => new UriBuilder("http", SelectedHost(), (int)(current.Number("gateway_port") ?? 8090), "v1").Uri.AbsoluteUri;
            var field = Ui.Field(form, "Gateway address", direct.Length > 0 ? direct : Address());
            field.IsReadOnly = true;
            if (adapter is not null) adapter.SelectionChanged += (_, _) => field.Text = Address();
            form.Children.Add(Ui.Button("Copy address", () => { Clipboard.SetText(field.Text); status.Text = "Gateway address copied."; }));
            if (direct.Length == 0)
            {
                var phone = Ui.Field(form, "Phone Wi-Fi IP");
                form.Children.Add(Ui.Button("Allow this phone to connect", () => Run(async () =>
                {
                    await Call("control-connect-address", new JsonObject { ["host"] = SelectedHost(), ["phone_ip"] = phone.Text.Trim() });
                    if (epoch == viewEpoch) hint.Text = "Tap Connect on the phone within five minutes. Future connections use its saved device key.";
                }), true));
                form.Children.Add(Ui.Button("Network and firewall settings", () => Navigate("Settings/Connection")));
            }
            var availability = Ui.Column(Ui.Text("Engineering", 20, bold: true),
                Ui.Text("New connections start with Engineering enabled. Saved jobs use the GitHub account and repositories configured on this PC.", color: "MutedBrush"),
                Ui.Actions(Ui.Button("GitHub connection", () => Navigate("Tools/GitHub")),
                    Ui.Button("Repositories", () => Navigate("Tools/Repositories"))));
            page.Children.Add(Ui.Card(availability));
        });
    }

    private void BuildAndroid()
    {
        var setup = Ui.Column(Ui.Text("Owner controls", 22, bold: true),
            Ui.Text("Manage which phones can change settings on this PC.", 13, "MutedBrush"));
        var addresses = new List<string> { "127.0.0.1" };
        try
        {
            addresses.AddRange(NetworkInterface.GetAllNetworkInterfaces().Where(item => item.OperationalStatus == OperationalStatus.Up)
                .SelectMany(item => item.GetIPProperties().UnicastAddresses).Select(item => item.Address)
                .Where(item => item.AddressFamily == AddressFamily.InterNetwork && !System.Net.IPAddress.IsLoopback(item)).Select(item => item.ToString()));
        }
        catch (NetworkInformationException) { }
        var host = Ui.Select(setup, "Control listener interface", addresses.Distinct());
        setup.Children.Add(Ui.Text("Use the private interface reachable from your phone. Connect phone configures the TLS listener when you allow a Wi-Fi address.", 13, "MutedBrush"));
        var port = Ui.Field(setup, "Control TLS port", "8091");
        var listenerState = Ui.Text("Reading owner control listener…", 13, "MutedBrush"); setup.Children.Add(listenerState);
        bool ReachableSelection() => System.Net.IPAddress.TryParse(host.SelectedItem?.ToString(), out var selected) && !System.Net.IPAddress.IsLoopback(selected);
        var enable = Ui.Button("Enable owner control endpoint", () => Run(async () =>
        {
            if (!ReachableSelection()) throw new InvalidOperationException("Choose a reachable LAN or VPN interface before enabling phone controls.");
            if (!int.TryParse(port.Text, out int number)) throw new InvalidOperationException("Enter a valid control port.");
            var result = await Call("control-enable", new JsonObject { ["host"] = host.SelectedItem?.ToString(), ["port"] = number });
            listenerState.Text = result.Flag("enabled") ? "Enabled · " + result.Text("endpoint") : "Control listener is not ready.";
            ShowDetails("Owner control endpoint", result);
        }), true);
        enable.IsEnabled = ReachableSelection(); host.SelectionChanged += (_, _) => enable.IsEnabled = ReachableSelection();
        async Task Firewall(bool remove)
        {
            if (MessageBox.Show(this, remove ? "Remove only this installation's phone-control firewall rule?" :
                    "Allow the saved owner-control TLS port on this interface for the Windows Private profile and local subnet? Windows may ask for administrator approval.",
                    "Phone control firewall", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            await Call("control-firewall", new JsonObject { ["remove"] = remove });
            status.Text = remove ? "Phone-control firewall rule removed." : "Phone-control private-subnet firewall rule confirmed.";
        }
        setup.Children.Add(Ui.Actions(enable, Ui.Button("Allow private control-port access", () => Run(() => Firewall(false))),
            Ui.Button("Remove control-port rule", () => Run(() => Firewall(true)))));
        page.Children.Add(Ui.Button("Connect a phone by address",()=>Navigate("Connect phone"),true));
        page.Children.Add(new Expander{Header="Advanced control listener and private firewall",Content=Ui.Card(setup)});
        var approvals = Ui.Column(Ui.Text("Pending device approvals", 20, bold: true));
        var pendingList = new ListBox { Height = 180 }; approvals.Children.Add(pendingList);
        var legacyClaims = Ui.Card(approvals);
        legacyClaims.Visibility = Visibility.Collapsed;
        async Task Pending()
        {
            var claims = (await Call("control-pairing-list")).Array("items").OfType<JsonObject>()
                .Where(item => item.Text("state") == "awaiting_confirmation").ToArray();
            Ui.Rows(pendingList, claims, "device_name", "state");
            legacyClaims.Visibility = claims.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        }
        async Task Confirm(bool approved)
        {
            var selected = Ui.Selected(pendingList) ?? throw new InvalidOperationException("Select a pending phone claim.");
            ShowDetails("Compare this phone's identity and granted scope", selected);
            if (approved && MessageBox.Show(this, "Does this device name and public-key fingerprint match the phone you are pairing?", "Approve this device", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            await Call("control-pairing-confirm", new JsonObject { ["pairing_id"] = selected.Text("pairing_id"), ["approved"] = approved }); await Pending();
        }
        approvals.Children.Add(Ui.Actions(Ui.Button("Refresh claims", () => Run(Pending)), Ui.Button("Review and approve", () => Run(() => Confirm(true)), true),
            Ui.Button("Deny", () => Run(() => Confirm(false)))));
        page.Children.Add(legacyClaims);
        var owners = Ui.Column(Ui.Text("Connected devices", 20, bold: true)); var deviceList = new ListBox { Height = 180 }; owners.Children.Add(deviceList);
        async Task Devices() => Ui.Rows(deviceList, (await Call("control-devices")).Array("items"), "name", "role");
        owners.Children.Add(Ui.Actions(Ui.Button("Refresh devices", () => Run(Devices)),
            Ui.Button("Edit this owner's settings", () =>
            { if (Ui.Selected(deviceList) is { } device) { settingsOwner = device.Text("principal_id"); Navigate("Advanced settings"); } }),
            Ui.Button("Revoke access", () => Run(async () =>
            {
                var selected = Ui.Selected(deviceList) ?? throw new InvalidOperationException("Select a connected device.");
                if (MessageBox.Show(this, "Revoke management access for " + selected.Text("name") + "?", "Revoke device", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
                await Call("control-device-revoke", new JsonObject { ["device_id"] = selected.Text("device_id") }); await Devices();
            }))));
        page.Children.Add(Ui.Card(owners));
        var refreshReviews = BuildHostReviews();
        Run(async () =>
        {
            await Pending(); await Devices(); await refreshReviews();
            var confirmed = await Call("control-settings-read");
            var listener = confirmed.Object("listener");
            listenerState.Text = listener.Flag("enabled") ? "Enabled · " + listener.Text("endpoint") : "Disabled · choose a reachable interface for your phone.";
            if (listener.Flag("enabled") && Uri.TryCreate(listener.Text("endpoint"), UriKind.Absolute, out var endpoint))
            { host.SelectedItem = endpoint.Host; port.Text = endpoint.Port.ToString(); }
        });
    }

    private Func<Task> BuildHostReviews()
    {
        var profiles = Ui.Column(Ui.Text("Approved validation profiles", 20, bold: true),
            Ui.Text("Prepare and save the policy in Repository Validation, then approve its immutable snapshot here. Granted phones can select only profiles approved on this PC.", 13, "MutedBrush"));
        var profileList = Ui.Column(Ui.Text("Reading approved profiles…", color: "MutedBrush"));
        profiles.Children.Add(profileList);
        var backends = Ui.Column(Ui.Text("Approved model backends", 20, bold: true),
            Ui.Text("Approve an exact backend origin after Gateway checks its available models. Granted phones can select approved origins. Approval does not switch the active model route.", 13, "MutedBrush"));
        var backendOrigin = Ui.Field(backends, "Backend origin (for example http://127.0.0.1:8080)");
        var backendList = Ui.Column(Ui.Text("Reading approved backends…", color: "MutedBrush"));
        backends.Children.Add(backendList);
        string installationId = "";
        async Task Profiles()
        {
            var confirmed = await Call("control-settings-read");

            installationId = confirmed.Text("installation_id", "");
            profileList.Children.Clear();
            var choices = confirmed.Object("validation_profiles");
            if (choices.Count == 0) profileList.Children.Add(Ui.Text("No validation profiles have been approved for phone selection.", 13, "MutedBrush"));
            foreach (var choice in choices)
                profileList.Children.Add(Ui.Text(ProfileName(choices, choice.Key), 13, "MutedBrush"));
            backendList.Children.Clear();
            var origins = confirmed.Array("approved_backends");
            if (origins.Count == 0) backendList.Children.Add(Ui.Text("No backend origins have been approved for phone selection.", 13, "MutedBrush"));
            foreach (var origin in origins)
                backendList.Children.Add(Ui.Text(origin?.ToString() ?? "Unavailable origin", 13, "MutedBrush"));
        }
        profiles.Children.Add(Ui.Actions(Ui.Button("Open repository validation", () => Navigate("Tools/Repositories")),
            Ui.Button("Approve current validation profile", () => Run(async () =>
            {
                if (MessageBox.Show(this, "Approve the repository validation policy currently saved on this PC as an immutable choice for granted phone controls? Existing jobs keep their original policy.",
                        "Approve this host policy", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
                var result = await Call("control-validation-approve");
                ShowDetails("Validation profile approval", result);
                status.Text = result.Flag("approved") ? "Validation profile approved · " + result.Text("label") : "Profile approval was not confirmed.";
                await Profiles();
            })), Ui.Button("Refresh profiles", () => Run(Profiles))));
        page.Children.Add(Ui.Card(profiles));
        backends.Children.Add(Ui.Actions(Ui.Button("Check and approve backend", () => Run(async () =>
        {
            string origin = backendOrigin.Text.Trim();
            if (origin.Length == 0) throw new InvalidOperationException("Enter the exact model backend origin to approve.");
            if (MessageBox.Show(this, "Check and approve this model backend for selection by granted phones?\n\n" + origin +
                    "\n\nThis does not switch the active model route.", "Approve model backend", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            var result = await Call("control-backend-approve", new JsonObject { ["backend"] = origin });
            status.Text = result.Flag("approved") ? "Backend origin approved · " + result.Text("backend") : "Backend approval was not confirmed.";
            await Profiles();
        })), Ui.Button("Refresh approved backends", () => Run(Profiles))));
        page.Children.Add(Ui.Card(backends));

        var requests = Ui.Column(Ui.Text("Phone requests for PC review", 20, bold: true),
            Ui.Text("Phones may ask you to review network access, engineering setup or a pending restart. Marking a request reviewed only records your acknowledgement. Use the existing host controls separately for any action you choose.", 13, "MutedBrush"));
        var requestList = new ListBox { Height = 220 }; requests.Children.Add(requestList);
        var requestState = Ui.Text("Reading requests…", 13, "MutedBrush"); requests.Children.Add(requestState);
        async Task Requests()
        {
            var result = await Call("control-host-requests");

            var items = result.Array("items").OfType<JsonObject>().Select(item =>
            {
                var displayed = (JsonObject)item.DeepClone();
                displayed["detail"] = "Owner: " + item.Text("principal_id") + " · command: " + item.Text("command_id");
                return displayed;
            }).ToArray();
            Ui.Rows(requestList, items, "request");
            requestState.Text = items.Length == 0 ? "No requests have been received." :
                $"{items.Count(item => item.Text("status") == "waiting_pc")} awaiting PC review · {items.Count(item => item.Text("status") == "reviewed")} reviewed";
        }
        requests.Children.Add(Ui.Actions(Ui.Button("Refresh requests", () => Run(Requests)),
            Ui.Button("Review selected request", () =>
            {
                if (Ui.Selected(requestList) is { } selected) ShowDetails("Phone request", selected);
                else status.Text = "Select a phone request to review.";
            }), Ui.Button("Mark reviewed", () => Run(async () =>
            {
                var selected = Ui.Selected(requestList) ?? throw new InvalidOperationException("Select a phone request.");
                if (selected.Text("status") != "waiting_pc") { status.Text = "This request has already been reviewed."; return; }
                if (installationId.Length == 0) throw new InvalidOperationException("Refresh profiles to confirm this installation before recording a review.");
                if (MessageBox.Show(this, "Mark this request from " + selected.Text("principal_id") + " as reviewed?\n\n" + selected.Text("request") +
                        "\n\nThis records your acknowledgement; it does not change network access, install software or restart Gateway.",
                        "Mark phone request reviewed", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
                var result = await Call("control-host-request-review", new JsonObject
                {
                    ["installation_id"] = installationId, ["principal_id"] = selected.Text("principal_id"), ["command_id"] = selected.Text("command_id")
                });
                status.Text = "PC review receipt · " + result.Text("status").Replace('_', ' ');
                await Requests();
            }))));
        page.Children.Add(Ui.Card(requests));
        return async () => { await Profiles(); await Requests(); };
    }
}
