using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
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
        BuildUpdateCard();
        page.Children.Add(Ui.Card(Ui.Column(Ui.Text("Reading confirmed settings…", 20, bold: true),
            Ui.Text("The Gateway owns the effective values. Running jobs keep their accepted configuration.", color: "MutedBrush"),
            Ui.Actions(Ui.Button("Host connection settings", () => OpenUtility("settings")), Ui.Button("Start Gateway", () => Run(async () => { await Call("runtime-start"); Navigate("Advanced settings"); }))))));
        Run(async () =>
        {
            var devices = await Call("control-devices");
            var current = await Call("control-settings-read", new JsonObject { ["owner_principal"] = settingsOwner });
            if (viewEpoch != epoch) return;
            page.Children.Clear(); BuildUpdateCard();
            var effective = current.Object("effective"); var desired = current.Object("desired");
            var writable = current.Array("writable_fields").Select(item => item?.ToString()).ToHashSet();
            var body = Ui.Column(Ui.Text("Confirmed configuration", 22, bold: true),
                Ui.Text($"Revision {current.Text("revision")} · changed by {current.Text("changed_by")} · installation {current.Text("installation_id")}", 13, "MutedBrush"));
            var owners = new List<OwnerChoice> { new("local:single-user", "This desktop") };
            owners.AddRange(devices.Array("items").OfType<JsonObject>().Where(device => !device.Flag("revoked") && device.Text("role") == "owner")
                .Select(device => new OwnerChoice(device.Text("principal_id"), device.Text("name", "Paired owner"))));
            body.Children.Add(Ui.Text("Engineering owner", color: "MutedBrush"));
            var owner = new ComboBox { ItemsSource = owners, SelectedItem = owners.FirstOrDefault(item => item.Principal == settingsOwner) };
            body.Children.Add(owner);
            owner.SelectionChanged += (_, _) =>
            {
                if (owner.SelectedItem is OwnerChoice choice && choice.Principal != settingsOwner) { settingsOwner = choice.Principal; Navigate("Advanced settings"); }
            };
            body.Children.Add(Ui.Text("Engineering applies only to the selected owner. Capacity, model route, startup and update settings apply to this PC.", 13, "MutedBrush"));
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
                if (viewEpoch == epoch) Navigate("Advanced settings");
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
                catch { if (viewEpoch == epoch) Navigate("Advanced settings"); throw; }
                status.Text = "Settings receipt · " + result.Text("status").Replace('_', ' ') + " · revision " + result.Text("revision");
                if (viewEpoch == epoch) Navigate("Advanced settings");
            }), true), Ui.Button("Reload", () => Navigate("Advanced settings")),
                Ui.Button("Read original receipt", () => Run(async () =>
                {
                    var pending = current.Object("native_pending");
                    if (pending.Count == 0) throw new InvalidOperationException("No unconfirmed desktop settings command is retained. Reload to check again.");
                    var result = await Call("control-settings-command", new JsonObject { ["command_id"] = pending.Text("command_id"),
                        ["owner_principal"] = pending.Text("owner_principal", "local:single-user") });
                    ShowDetails("Original settings receipt", result); Navigate("Advanced settings");
                })), retryOriginal, Ui.Button("Host connection and firewall", () => OpenUtility("settings"))));
            page.Children.Add(Ui.Card(body));
        });
    }

    private void BuildConnectPhone()
    {
        int viewEpoch = epoch;
        var form = Ui.Column(Ui.Text("Connect to this PC", 22, bold: true));
        page.Children.Add(Ui.Card(form, true));
        Run(async () =>
        {
            var current = await Call("control-settings-read");
            if (epoch != viewEpoch) return;
            string direct = current.Text("account_connection_url", "");
            if (direct.Length > 0)
            {
                var url = Ui.Field(form, "Gateway address for your Tailscale devices", direct);
                url.IsReadOnly = true;
                form.Children.Add(Ui.Text("Use this address in your phone’s Gateway profile. Devices signed into your PC’s Tailscale account connect automatically.", 13, "MutedBrush"));
                form.Children.Add(Ui.Button("Copy Gateway address", () => { Clipboard.SetText(direct); status.Text = "Gateway address copied."; }, true));
            }
            var listener = current.Object("listener");
            string endpoint = listener.Text("endpoint", "");
            bool listening = Uri.TryCreate(endpoint, UriKind.Absolute, out var active) && !active.IsLoopback;
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(item => item.OperationalStatus == OperationalStatus.Up)
                .SelectMany(item => item.GetIPProperties().UnicastAddresses).Select(item => item.Address)
                .Where(ip => ip.AddressFamily == AddressFamily.InterNetwork && !System.Net.IPAddress.IsLoopback(ip))
                .Select(ip => ip.ToString()).Distinct().ToArray();
            string[] repos = current.Array("connection_repositories").Select(item => item?.ToString() ?? "").ToArray();
            if (direct.Length > 0)
            {
                var otherNetwork = new StackPanel();
                form.Children.Add(new Expander { Header = "Connect on another network", Content = otherNetwork, Foreground = Ui.Brush("TextBrush") });
                form = otherNetwork;
            }
            ComboBox? host = null;
            if (!listening && addresses.Length > 1) host = Ui.Select(form, "PC network address", addresses);
            if (!listening && addresses.Length == 0)
            { form.Children.Add(Ui.Text("Connect this PC to your network, then try again.", color: "MutedBrush")); return; }
            var repository = Ui.Field(form, "Repository (owner/name)", repos.Length == 1 ? repos[0] : "");
            form.Children.Add(Ui.Text("Engineering starts on for this repository. Scan the private link with your phone.", 13, "MutedBrush"));
            var connect = Ui.Button("Show connection code", () => Run(async () =>
            {
                string repo = repository.Text.Trim().ToLowerInvariant();
                if (repo.Length == 0) throw new InvalidOperationException("Enter the repository to connect.");
                if (!listening)
                {
                    string address = host?.SelectedItem?.ToString() ?? addresses[0];
                    await Call("control-enable", new JsonObject { ["host"] = address, ["port"] = 8091 });
                    listening = true;
                }
                var value = await Call("control-connect-create", new JsonObject { ["name"] = "Android phone", ["role"] = "owner",
                    ["fields"] = new JsonArray(JsonValue.Create("engineering_enabled")), ["repositories"] = new JsonArray(JsonValue.Create(repo)) });
                if (epoch == viewEpoch) ShowPairingInvitation(value, "owner", repo);
            }), true);
            form.Children.Add(connect);
        });
    }

    private void BuildAndroid()
    {
        int viewEpoch = epoch;
        var setup = Ui.Column(Ui.Text("Owner controls", 22, bold: true),
            Ui.Text("Pairing uses a separate authenticated TLS channel. A device on the ordinary LAN listener does not inherit owner rights.", 13, "MutedBrush"));
        var addresses = new List<string> { "127.0.0.1" };
        try
        {
            addresses.AddRange(NetworkInterface.GetAllNetworkInterfaces().Where(item => item.OperationalStatus == OperationalStatus.Up)
                .SelectMany(item => item.GetIPProperties().UnicastAddresses).Select(item => item.Address)
                .Where(item => item.AddressFamily == AddressFamily.InterNetwork && !System.Net.IPAddress.IsLoopback(item)).Select(item => item.ToString()));
        }
        catch (NetworkInformationException) { }
        var host = Ui.Select(setup, "Control listener interface", addresses.Distinct());
        setup.Children.Add(Ui.Text("This PC only (127.0.0.1) cannot pair a phone. Deliberately select your reachable LAN or VPN interface, enable it, then create an invitation.", 13, "MutedBrush"));
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
        page.Children.Add(Ui.Card(setup));
        var grant = Ui.Column(Ui.Text("Pair a phone", 20, bold: true));
        var name = Ui.Field(grant, "Device name", "My Android phone");
        var access = Ui.Select(grant, "Phone access", ["Owner", "Viewer (read-only)"], "Owner");
        string AccessRole() => access.SelectedItem?.ToString() switch
        {
            "Owner" => "owner", "Viewer (read-only)" => "viewer",
            _ => throw new InvalidOperationException("Choose Owner or Viewer access for this phone.")
        };
        var accessDescription = Ui.Text("Owner can change its Engineering gate and the shared settings you explicitly grant below.", 13, "MutedBrush");
        grant.Children.Add(accessDescription);
        var repositories = Ui.Field(grant, "Allowed repositories (one owner/name per line)");
        repositories.AcceptsReturn = true; repositories.Height = 85; repositories.TextWrapping = TextWrapping.Wrap;
        var shared = Ui.Toggle(grant, "Allow shared controls", false);
        access.SelectionChanged += (_, _) =>
        {
            bool owner = AccessRole() == "owner";
            shared.IsEnabled = owner;
            if (!owner) shared.IsChecked = false;
            accessDescription.Text = owner ? "Owner can change its Engineering gate and the shared settings you explicitly grant below." :
                "Viewer can read settings and status. This invitation grants no writable settings or Engineering controls.";
        };
        grant.Children.Add(Ui.Text("Also grant capacity, model, public repository maps, startup, stable update checks and installation, and diagnostics settings, selection of PC-approved backend origins and validation profiles, and requests for PC review.", 13, "MutedBrush"));
        grant.Children.Add(Ui.Actions(Ui.Button("Create pairing invitation", () => Run(async () =>
        {
            var confirmed = await Call("control-settings-read");
            var endpoint = confirmed.Object("listener").Text("endpoint", "");
            if (!Uri.TryCreate(endpoint, UriKind.Absolute, out var controlUri) || controlUri.IsLoopback)
                throw new InvalidOperationException("Enable the owner control listener on a reachable LAN or VPN interface before pairing a phone.");
            string role = AccessRole();
            string[] fields = role == "viewer" ? [] : shared.IsChecked == true ? ["engineering_enabled", "workers", "active_chats", "batch_mode", "local_validation_workers",
                "backend", "model", "public_repository_maps", "start_at_login", "update_mode", "update_interval_hours", "debug_mode", "validation_profile", "host_request"] : ["engineering_enabled"];
            var result = await Call("control-pairing-create", new JsonObject { ["name"] = name.Text.Trim(), ["role"] = role,
                ["fields"] = new JsonArray(fields.Select(value => JsonValue.Create(value)).ToArray()),
                ["repositories"] = new JsonArray(repositories.Text.Split(['\r', '\n', ','], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                    .Select(value => JsonValue.Create(value.ToLowerInvariant())).ToArray()) });
            ShowPairingInvitation(result, role);
            status.Text = "Invitation created. Compare the phone key fingerprint before approving its claim below.";
        }), true)));
        page.Children.Add(Ui.Card(grant));
        var approvals = Ui.Column(Ui.Text("Pending device approvals", 20, bold: true));
        var pendingList = new ListBox { Height = 180 }; approvals.Children.Add(pendingList);
        async Task Pending() => Ui.Rows(pendingList, (await Call("control-pairing-list")).Array("items"), "device_name", "state");
        async Task Confirm(bool approved)
        {
            var selected = Ui.Selected(pendingList) ?? throw new InvalidOperationException("Select a pending phone claim.");
            ShowDetails("Compare this phone's identity and granted scope", selected);
            if (approved && MessageBox.Show(this, "Does this device name and public-key fingerprint match the phone you are pairing?", "Approve this device", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            await Call("control-pairing-confirm", new JsonObject { ["pairing_id"] = selected.Text("pairing_id"), ["approved"] = approved }); await Pending();
        }
        approvals.Children.Add(Ui.Actions(Ui.Button("Refresh claims", () => Run(Pending)), Ui.Button("Review and approve", () => Run(() => Confirm(true)), true),
            Ui.Button("Deny", () => Run(() => Confirm(false)))));
        page.Children.Add(Ui.Card(approvals));
        var owners = Ui.Column(Ui.Text("Paired devices", 20, bold: true)); var deviceList = new ListBox { Height = 180 }; owners.Children.Add(deviceList);
        async Task Devices() => Ui.Rows(deviceList, (await Call("control-devices")).Array("items"), "name", "role");
        owners.Children.Add(Ui.Actions(Ui.Button("Refresh devices", () => Run(Devices)),
            Ui.Button("Edit this owner's settings", () =>
            { if (Ui.Selected(deviceList) is { } device) { settingsOwner = device.Text("principal_id"); Navigate("Advanced settings"); } }),
            Ui.Button("Revoke access", () => Run(async () =>
            {
                var selected = Ui.Selected(deviceList) ?? throw new InvalidOperationException("Select a paired device.");
                if (MessageBox.Show(this, "Revoke management access for " + selected.Text("name") + "?", "Revoke device", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
                await Call("control-device-revoke", new JsonObject { ["device_id"] = selected.Text("device_id") }); await Devices();
            }))));
        page.Children.Add(Ui.Card(owners));
        var refreshReviews = BuildHostReviews(viewEpoch);
        Run(async () =>
        {
            await Pending(); await Devices(); await refreshReviews();
            var confirmed = await Call("control-settings-read"); if (epoch != viewEpoch) return;
            var listener = confirmed.Object("listener");
            listenerState.Text = listener.Flag("enabled") ? "Enabled · " + listener.Text("endpoint") : "Disabled · choose a reachable interface to pair a phone.";
            if (listener.Flag("enabled") && Uri.TryCreate(listener.Text("endpoint"), UriKind.Absolute, out var endpoint))
            { host.SelectedItem = endpoint.Host; port.Text = endpoint.Port.ToString(); }
        });
    }

    private Func<Task> BuildHostReviews(int viewEpoch)
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
            if (epoch != viewEpoch) return;
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
        profiles.Children.Add(Ui.Actions(Ui.Button("Open repository validation", () => OpenUtility("github-settings", "Repository Validation")),
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
            if (epoch != viewEpoch) return;
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

    private void ShowPairingInvitation(JsonObject value, string role, string? repository = null)
    {
        var dialog = new Window { Owner = this, Title = repository == null ? "Device invitation" : "Connect phone", Width = 720, Height = 560,
            MinWidth = 500, MinHeight = 450, WindowStartupLocation = WindowStartupLocation.CenterOwner };
        var body = Ui.Column(Ui.Text(repository == null ? "Device invitation" : "Connect phone", 26, bold: true),
            Ui.Text(repository != null ? repository : role == "viewer" ? "Viewer access · read-only settings and status · no writable controls" : "Owner access · only the granted settings and repository scope", 13, "PrimaryBrush"),
            Ui.Text(repository != null ? "Scan with your phone or copy the link to your phone. It expires in two minutes." : "Open this private invitation in GPT Mobile. Return here to compare and approve the phone's public-key fingerprint.", color: "MutedBrush"));
        body.Margin = new Thickness(24);
        if (value.Text("qr_png_base64", "") is { Length: > 0 and < 1048576 } encoded)
        {
            try
            {
                using var png = new MemoryStream(Convert.FromBase64String(encoded));
                var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.StreamSource = png; bitmap.EndInit(); bitmap.Freeze();
                var qr = new Image { Source = bitmap, Width = 280, Height = 280, Margin = new Thickness(0, 0, 0, 12), HorizontalAlignment = HorizontalAlignment.Left };
                RenderOptions.SetBitmapScalingMode(qr, BitmapScalingMode.NearestNeighbor); body.Children.Add(qr);
            }
            catch (Exception error) when (error is FormatException or IOException or NotSupportedException) { }
        }
        var link = Ui.Field(body, "Private connection link", value.Text("link", "")); link.IsReadOnly = true; link.TextWrapping = TextWrapping.Wrap; link.Height = 120;
        body.Children.Add(Ui.Text("Endpoint: " + value.Text("endpoint"), 13));
        body.Children.Add(Ui.Text("Server certificate fingerprint: " + value.Text("fingerprint"), 13));
        if (value.Number("expires_at") is { } expiry)
            body.Children.Add(Ui.Text("Expires: " + DateTimeOffset.FromUnixTimeSeconds((long)expiry).ToLocalTime().ToString("g"), 13, "MutedBrush"));
        body.Children.Add(Ui.Actions(Ui.Button("Copy link", () => { Clipboard.SetText(link.Text); status.Text = "Private pairing invitation copied. It expires automatically."; }, true),
            Ui.Button("Close", () => dialog.Close())));
        dialog.Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        dialog.PreviewKeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) dialog.Close(); }; dialog.ShowDialog();
    }
}
