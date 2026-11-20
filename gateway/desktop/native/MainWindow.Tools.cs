using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using Microsoft.Win32;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private static readonly JsonSerializerOptions Pretty=new(){WriteIndented=true};
    private string analysisRevision="", selectedAssistant="";
    private static TextBox Editor(string label,string text="",double height=110)
    {
        var box=new TextBox{Text=text,AcceptsReturn=true,AcceptsTab=true,MinHeight=height,MaxHeight=height*2,
            TextWrapping=TextWrapping.Wrap,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        AutomationProperties.SetName(box,label);return box;
    }
    private void BuildTools()
    {
        switch(Section)
        {
            case "GitHub":BuildGitHub();return;
            case "Repositories":case "Tests and release checks":BuildRepositories(Section!="Repositories");return;
            case "Environments":BuildEnvironment();return;
            case "Add-ons / MCP":BuildAddons();return;
            case "Analysis":BuildAnalysis();return;
        }
        var panel=new DockPanel();var controls=new StackPanel();DockPanel.SetDock(controls,Dock.Top);panel.Children.Add(controls);
        var query=Ui.Field(controls,"Find a capability");var category=Ui.Select(controls,"Availability",new[]{"All","Available","Unavailable","Unknown"});
        var list=new ListBox();panel.Children.Add(list);
        void Load(){var inventory=snapshot.Object("capabilityInventory");Ui.Rows(list,inventory.Array("tools").Where(r=>
            (query.Text.Length==0||r?.ToJsonString().Contains(query.Text,StringComparison.OrdinalIgnoreCase)==true)
            &&(category.SelectedIndex==0||r.Text("availability").Equals(category.SelectedItem?.ToString(),StringComparison.OrdinalIgnoreCase))),"name","availability");}
        controls.Children.Add(Ui.Actions(Ui.Button("Refresh inventory",()=>Run(async()=>{await RefreshSnapshot();Load();})),
            Ui.Button("Capability evidence",()=>{if(Ui.Selected(list) is {} tool)ShowDetails(tool.Text("name"),tool);})));
        list.MouseDoubleClick+=(_,_)=>{if(Ui.Selected(list) is {} tool)ShowDetails(tool.Text("name"),tool);};
        query.TextChanged+=(_,_)=>Load();category.SelectionChanged+=(_,_)=>Load();Load();FillWindow(panel);
    }

    private void BuildGitHub()
    {
        var panel=new StackPanel();var info=Ui.Text("Reading host configuration…",14,"MutedBrush");panel.Children.Add(info);
        var repo=Ui.Field(panel,"Repository to test (owner/name)");panel.Children.Add(Ui.Text("GitHub token",13,"MutedBrush"));
        var token=new PasswordBox{MaxLength=512,Margin=new Thickness(0,0,0,8)};AutomationProperties.SetName(token,"GitHub token");panel.Children.Add(token);
        var copilot=Ui.Toggle(panel,"Supervise explicitly delegated Copilot cloud tasks",false);
        async Task Load(){var result=await Call("workspace-github-read");info.Text=$"Credential source · {result.Text("source","Unconfigured")}   ·   Public maps {(result.Flag("public_maps")?"enabled":"disabled")}";
            copilot.IsChecked=result.Flag("copilot");}
        panel.Children.Add(Ui.Actions(Ui.Button("Connect",()=>Run(async()=>{
            if(string.IsNullOrWhiteSpace(token.Password))throw new InvalidOperationException("Enter a GitHub token.");
            var result=await Call("workspace-github-connect",new JsonObject{["repository"]=repo.Text.Trim(),["token"]=token.Password});
            token.Clear();await Load();status.Text=result.Text("message");
        }),true),Ui.Button("Test access",()=>Run(async()=>{var r=await Call("workspace-github-test",new JsonObject{["repository"]=repo.Text.Trim()});info.Text=r.Text("message");})),
            Ui.Button("Remove stored token",()=>Run(async()=>{if(Confirm("Remove the Gateway credential from Windows Credential Manager?")){await Call("workspace-github-remove");await Load();}})),
            Ui.Button("Create token",()=>OpenWeb("https://github.com/settings/personal-access-tokens/new"))));
        panel.Children.Add(Ui.Actions(Ui.Button("Save Copilot setting",()=>Run(async()=>{
            await Call("workspace-github-copilot",new JsonObject{["enabled"]=copilot.IsChecked==true});await Load();status.Text="Saved. Restart the Gateway when idle to apply this host preference.";
        })),Ui.Button("Public maps",()=>Navigate("Settings/Advanced")),Ui.Button("Host task receipts",()=>OpenJobs("Host receipts"))));
        page.Children.Add(Card("GitHub connection",panel));Run(Load);
    }

    private static void OpenWeb(string url)
    {
        if(Uri.TryCreate(url,UriKind.Absolute,out var target)&& target.Scheme is "https" or "http" && target.UserInfo.Length==0)
            Process.Start(new ProcessStartInfo(target.AbsoluteUri){UseShellExecute=true});
    }

    private void BuildAddons()
    {
        var body=new StackPanel();var path=Ui.Field(body,"MCP configuration file",snapshot.Object("settings").Text("mcp_config",""));
        JsonObject? observed=null;var services=new StackPanel();var toggles=new Dictionary<string,CheckBox>();
        async Task Load(){
            var result=await Call("workspace-addons-load",new JsonObject{["path"]=path.Text});observed=result;
            path.Text=result.Text("path");toggles.Clear();services.Children.Clear();
            foreach(var pair in result.Object("enabled"))toggles[pair.Key]=Ui.Toggle(services,pair.Key,result.Object("enabled").Flag(pair.Key));
        }
        body.Children.Add(Ui.Actions(Ui.Button("Choose file",()=>{var f=new OpenFileDialog{Filter="MCP configuration|*.json"};if(f.ShowDialog(this)==true){path.Text=f.FileName;Run(Load);}}),
            Ui.Button("Reload",()=>Run(Load)),Ui.Button("Save services and use file",()=>Run(async()=>{
                if(observed==null||path.Text!=observed.Text("path"))throw new InvalidOperationException("Reload the selected file before saving.");
                var enabled=new JsonObject();foreach(var (key,toggle) in toggles)enabled[key]=toggle.IsChecked==true;
                observed=await Call("workspace-addons-save",new JsonObject{["path"]=path.Text,["revision"]=observed.Text("revision"),["enabled"]=enabled});
                var original=(await Call("workspace-connection-read")).Object("settings");
                await Call("workspace-connection-save",new JsonObject{["original"]=original.DeepClone(),["changes"]=new JsonObject{["mcp_config"]=path.Text}});
                status.Text="Service selection saved. Restart when idle to apply.";
            }),true)));
        body.Children.Add(services);page.Children.Add(Card("MCP services",body));Run(Load);
    }

    private void BuildEnvironment()
    {
        var panel=new StackPanel();var checks=new StackPanel();panel.Children.Add(checks);
        async Task Check(){var r=await Call("workspace-env-check");checks.Children.Clear();foreach(var pair in r.Object("checks")){
            var values=pair.Value as JsonArray;checks.Children.Add(Ui.Text($"{pair.Key} · {values?[0]}   {values?[1]}",14));}}
        panel.Children.Add(Ui.Actions(Ui.Button("Check PC",()=>Run(Check),true),
            Ui.Button("Prepare and verify",()=>Run(async()=>{var r=await Call("workspace-env-prepare");ShowDetails("Environment preparation",r);await Check();})),
            Ui.Button("Repair credential helper PATH",()=>Run(async()=>{if(Confirm("Add the detected Docker credential helper to your Windows user PATH?")){
                status.Text=(await Call("workspace-env-repair-path",new JsonObject{["approved"]=true})).Text("message");await Check();}}))));
        var apps=new WrapPanel();foreach(var kind in new[]{"git","wsl","docker"})apps.Children.Add(Ui.Button("Install / update "+kind,()=>Run(async()=>{
            if(Confirm("Run the Windows installation or update command for "+kind+"?")){status.Text=(await Call("workspace-env-install",new JsonObject{["kind"]=kind,["approved"]=true})).Text("message");await Check();}
        })));panel.Children.Add(apps);panel.Children.Add(Ui.Button("Windows setup guide",()=>OpenWeb("https://learn.microsoft.com/windows/wsl/install")));
        page.Children.Add(Card("Validation environment",panel));
        var sandbox=new StackPanel();var image=Ui.Field(sandbox,"Approved sandbox image digest");var repairs=Ui.Select(sandbox,"Repair attempts",new[]{"0","1","2"});
        JsonObject original=[];
        async Task Read(){original=await Call("workspace-sandbox-read");image.Text=original.Text("sandbox_image","");repairs.SelectedItem=original.Text("repair_attempts","1");}
        sandbox.Children.Add(Ui.Actions(Ui.Button("Prepare sandbox",()=>Run(async()=>{await Call("workspace-sandbox-prepare",new JsonObject{["original"]=original.DeepClone()});await Read();})),
            Ui.Button("Verify offline",()=>Run(async()=>{ShowDetails("Sandbox verification",await Call("workspace-sandbox-test",new JsonObject{["image"]=image.Text}));})),
            Ui.Button("Save sandbox",()=>Run(async()=>{var config=(JsonObject)original.DeepClone();config["sandbox_image"]=image.Text;config["repair_attempts"]=repairs.SelectedIndex;
                original=await Call("workspace-sandbox-save",new JsonObject{["original"]=original.DeepClone(),["settings"]=config});status.Text="Sandbox settings saved.";}))));
        page.Children.Add(Card("Benchmark sandbox",sandbox));Run(Read);
    }

    private void BuildRepositories(bool tests)
    {
        var panel=new StackPanel();var repo=Ui.Field(panel,"Repository (owner/name)");var known=new ComboBox();AutomationProperties.SetName(known,"Configured repositories");panel.Children.Add(known);
        var enabled=Ui.Toggle(panel,"Enable repository validation",false);var fields=new StackPanel();var actions=new WrapPanel();
        var note=Ui.Text("Load a repository to see its configuration and revision.",13,"MutedBrush");panel.Children.Add(note);
        JsonObject options=[];string revision="",loaded="";
        var inputs=new Dictionary<string,Func<JsonNode?>>();
        JsonObject Values(){var value=(JsonObject)options.DeepClone();foreach(var (key,read) in inputs)value[key]=read();return value;}
        void Render(){
            fields.Children.Clear();inputs.Clear();
            foreach(var key in tests?new[]{"post_merge","allowed_checks","commands","acceptance_map","acceptance_templates"}:
                new[]{"mirror","profile","image_source","image","timeout_seconds","workspace_terminal","commands","parallel_command_groups","allowed_checks","acceptance_map","acceptance_templates","post_merge"}){
                if(options[key] is JsonArray or JsonObject){
                    var box=Editor(key,options[key]!.ToJsonString(Pretty),70);
                    fields.Children.Add(new Expander{Header=key.Replace('_',' '),Content=box,IsExpanded=tests&&key=="post_merge"});
                    inputs[key]=()=>JsonNode.Parse(box.Text);
                }else if(key=="workspace_terminal"){var toggle=Ui.Toggle(fields,"Offline task terminal",options.Flag(key));inputs[key]=()=>JsonValue.Create(toggle.IsChecked==true);}
                else{var box=Ui.Field(fields,key.Replace('_',' '),options.Text(key,""));inputs[key]=()=>key=="timeout_seconds"?JsonValue.Create(int.Parse(box.Text)):JsonValue.Create(box.Text.Trim());}
            }
            if(!tests){
                fields.Children.Add(Ui.Button("Browse mirror",()=>{var f=new OpenFolderDialog();if(f.ShowDialog(this)==true){options=Values();options["mirror"]=f.FolderName;Render();}}));
                fields.Children.Add(Ui.Button("Refresh image pin on next Prepare",()=>{options=Values();options["image"]="";Render();}));
            }
        }
        async Task Load(){
            var r=await Call("workspace-repo-load",new JsonObject{["repository"]=repo.Text.Trim()});
            options=r.Object("options");loaded=options.Text("repository");revision=r.Text("revision");enabled.IsChecked=r.Flag("enabled");Render();
            note.Text="Editing "+loaded+" · policy "+revision[..Math.Min(12,revision.Length)];
        }
        JsonObject Args(){if(repo.Text.Trim()!=loaded||revision.Length==0)throw new InvalidOperationException("Load this repository first.");
            return new JsonObject{["repository"]=loaded,["revision"]=revision,["enabled"]=enabled.IsChecked==true,["options"]=Values()};}
        async Task Submit(string operation){var result=await Call("workspace-repo-"+operation,Args());if(operation!="check")await Load();ShowDetails("Repository "+operation,result);}
        known.SelectionChanged+=(_,_)=>{if(known.SelectedItem is string name){repo.Text=name;Run(Load);}};
        actions.Children.Add(Ui.Button("Load / add",()=>Run(Load),true));actions.Children.Add(Ui.Button("Prepare and save",()=>Run(()=>Submit("prepare"))));
        actions.Children.Add(Ui.Button("Check setup",()=>Run(()=>Submit("check"))));actions.Children.Add(Ui.Button("Save policy",()=>Run(()=>Submit("save"))));
        actions.Children.Add(Ui.Button("Discard edits and reload",()=>Run(Load)));panel.Children.Add(actions);panel.Children.Add(fields);
        if(tests){
            var path=Ui.Field(panel,"Find test filename or path");var sha=Ui.Field(panel,"Source SHA");var matches=new ComboBox{DisplayMemberPath="Label"};panel.Children.Add(matches);
            panel.Children.Add(Ui.Button("Find tests",()=>Run(async()=>{var args=Args();args["query"]=path.Text;args["sha"]=sha.Text;
                var r=await Call("workspace-repo-discover",args);matches.ItemsSource=r.Array("candidates").OfType<JsonObject>().Select(v=>new Choice(v.Text("case_id")+" · "+v.Text("path"),v)).ToList();matches.SelectedIndex=0;note.Text="Discovered at "+r.Text("head_sha");})));
            var objective=Ui.Field(panel,"Task objective");var requirement=Ui.Field(panel,"Requirement ID");var covered=Ui.Field(panel,"Covered source path");var check=Ui.Field(panel,"Required GitHub check");
            panel.Children.Add(Ui.Button("Add test mapping to draft",()=>Run(async()=>{if(matches.SelectedItem is not Choice match)return;
                var args=Args();args["candidate"]=match.Value.DeepClone();args["mapping"]=new JsonObject{["objective"]=objective.Text,["requirement_id"]=requirement.Text,["source_path"]=covered.Text,["ci_check"]=check.Text};
                options=(await Call("workspace-repo-map",args)).Object("options");Render();note.Text="Mapping added to the draft. Save policy to apply.";})));
        }
        page.Children.Add(Card(tests?"Tests and release verification":"Repository validation",panel));
        Run(async()=>{var r=await Call("workspace-repositories");known.ItemsSource=r.Object("repositories").Select(k=>k.Key).ToArray();});
    }
    private sealed record Choice(string Label,JsonObject Value);
    private void BuildAnalysis()
    {
        var body=new StackPanel();var code=Editor("Python analysis","def handle(data):\n    return data",145);var input=Editor("JSON input","[]",95);
        var output=Editor("Analysis output","",125);output.IsReadOnly=true;
        Pair(body,Card("Python",code),Card("Input data",input));body.Children.Add(Card("Result",output));
        string? Pick(bool save,string filter){if(save){var f=new SaveFileDialog{Filter=filter};return f.ShowDialog(this)==true?f.FileName:null;}
            var o=new OpenFileDialog{Filter=filter};return o.ShowDialog(this)==true?o.FileName:null;}
        JsonObject Payload()=>new(){["code"]=code.Text,["input"]=input.Text};
        body.Children.Add(Ui.Actions(Ui.Button("Open data",()=>Run(async()=>{var path=Pick(false,"Data|*.json;*.csv");if(path!=null)input.Text=(await Call("workspace-analysis-import",new JsonObject{["path"]=path})).Text("input");})),
            Ui.Button("Run in sandbox",()=>Run(async()=>{output.Text=(await Call("workspace-analysis-run",Payload())).Text("output");}),true),
            Ui.Button("Export result",()=>Run(async()=>{var file=Pick(true,"JSON|*.json|Spreadsheet|*.xlsx|Presentation|*.pptx|Plot|*.svg");if(file!=null)await Call("workspace-analysis-export",new JsonObject{["path"]=file,["output"]=output.Text});})),
            Ui.Button("Save analysis",()=>Run(async()=>{var file=Pick(true,"Gateway analysis|*.json");if(file!=null){var a=Payload();a["path"]=file;await Call("workspace-analysis-save",a);}})),
            Ui.Button("Open analysis",()=>Run(async()=>{var file=Pick(false,"Gateway analysis|*.json");if(file!=null){var r=await Call("workspace-analysis-open",new JsonObject{["path"]=file});code.Text=r.Text("code");input.Text=r.Text("input");}})),
            Ui.Button("Add result to chat",()=>Run(()=>AppendDraft(output.Text))),
            Ui.Button("Request revision in chat",()=>Run(async()=>{analysisRevision=code.Text;await AppendDraft("Revise this Python analysis:\n```python\n"+code.Text+"\n```");})),
            Ui.Button("Use selected revision",()=>{
                var match=System.Text.RegularExpressions.Regex.Match(selectedAssistant,@"```(?:python|py)\s*\r?\n([\s\S]*?)```",System.Text.RegularExpressions.RegexOptions.IgnoreCase);
                if(!match.Success)throw new InvalidOperationException("Select a Python response in Chat first.");code.Text=match.Groups[1].Value.TrimEnd();
            })));
        page.Children.Add(body);
    }
}
