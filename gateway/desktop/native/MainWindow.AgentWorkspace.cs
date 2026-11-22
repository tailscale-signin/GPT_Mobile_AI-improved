using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private async Task AgentWorkspace(JobRow row)
    {
        int openedEpoch=epoch;
        var board=await Call("engineering-workspace",WithIdentity(new JsonObject{["job_id"]=row.Id}));
        if(epoch!=openedEpoch)return;
        var title=Ui.Text("",16,bold:true);
        var notice=Ui.Text("",13,"MutedBrush");
        var tabs=new TabControl{MinHeight=380,MaxHeight=620};
        var body=Ui.Column(title,notice,tabs);
        bool Allowed(string action)=>board.Array("available_actions").Any(value=>value?.ToString()==action);
        async Task Refresh()
        {
            var current=await Call("engineering-workspace",WithIdentity(new JsonObject{["job_id"]=row.Id}));
            if(epoch!=openedEpoch)return;
            board=current;Render();
        }
        async Task Send(string action,JsonObject arguments)
        {
            if(epoch!=openedEpoch||!Allowed(action))return;
            string command=Guid.NewGuid().ToString("N");
            var payload=new JsonObject{["command_id"]=command,["revision"]=board["revision"]?.DeepClone(),
                ["head_sha"]=board.Text("head_sha"),["action"]=action,["arguments"]=arguments};
            notice.Text="Saving workspace request…";
            try
            {
                var result=await Call("engineering-workspace-command",WithIdentity(new JsonObject{["job_id"]=row.Id,["payload"]=payload}));
                notice.Text="Request "+result.Text("status").ToLowerInvariant()+". Refresh to observe its retained outcome.";
            }
            catch
            {
                notice.Text="The original request is retained. Check its receipt before retrying.";
                throw;
            }
            finally {await Refresh();}
        }
        Button ActionButton(string label,string action,Func<JsonObject?> arguments,bool enabled=true)
        {
            var button=Ui.Button(label,()=>Run(async()=>{var value=arguments();if(value is not null)await Send(action,value);}));
            button.IsEnabled=Allowed(action)&&enabled;return button;
        }
        void AddTab(string name,StackPanel panel)
        {
            tabs.Items.Add(new TabItem{Header=name,Content=new ScrollViewer{Content=panel,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Padding=new Thickness(12)}});
        }
        JsonObject? WorkerForm()
        {
            var form=new StackPanel();var role=Ui.Select(form,"Role",new[]{"planner","retrieval","reviewer","editor"});
            var objective=Ui.Field(form,"Worker objective");var paths=Ui.Field(form,"Source paths (one per line)");
            paths.AcceptsReturn=true;paths.MinHeight=48;
            var budget=Ui.Select(form,"Maximum model calls",new[]{"2","4","6","8","12"},"6");
            if(!EditDialog("Start scoped worker",form,"Queue worker"))return null;
            return new JsonObject{["workers"]=new JsonArray(new JsonObject{["worker_id"]="worker-"+Guid.NewGuid().ToString("N")[..12],
                ["role"]=role.SelectedItem?.ToString(),["objective"]=objective.Text.Trim(),["budget"]=int.Parse(budget.SelectedItem!.ToString()!),
                ["paths"]=new JsonArray(paths.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(p=>(JsonNode?)JsonValue.Create(p)).ToArray())})};
        }
        void Render()
        {
            int selected=tabs.SelectedIndex;tabs.Items.Clear();
            title.Text=$"{board.Text("repository")} · {board.Text("branch")}\n{board.Text("head_sha")} · {board.Text("state")}";
            var workers=new StackPanel();
            workers.Children.Add(Ui.Text("Workers keep separate context and candidate changes. The parent task owns publication.",13,"MutedBrush"));
            foreach(var worker in board.Array("workers").OfType<JsonObject>())
                workers.Children.Add(Ui.Card(Ui.Column(Ui.Text(worker.Text("worker_id")+" · "+worker.Text("role"),15,bold:true),
                    Ui.Text(worker.Text("objective",""),13),Ui.Text($"{worker.Text("state")} · {worker.Text("calls")}/{worker.Text("budget")} calls",13),
                    Ui.Text(worker.Text("findings",worker.Text("last_action","Waiting")),13,"MutedBrush"))));
            workers.Children.Add(ActionButton("Start worker","worker_wave",WorkerForm));
            if(board.Array("workers").Count>0)workers.Children.Add(ActionButton("Advance ready workers","worker_wave",()=>new JsonObject{["workers"]=new JsonArray(board.Array("workers").OfType<JsonObject>().Where(w=>w.Text("state")=="ACTIVE").Select(w=>(JsonNode?)new JsonObject{["worker_id"]=w.Text("worker_id")}).ToArray())}));
            foreach(var terminal in board.Array("terminals").OfType<JsonObject>())
                workers.Children.Add(Ui.Text($"Checkout {terminal.Text("session_id")} · {(terminal.Flag("active")?"selected":"retained")} · {terminal.Text("head_sha")}",13));
            AddTab("Workers",workers);

            var agents=new StackPanel();
            agents.Children.Add(Ui.Text("Configured agents run under this task's source, account and allowed tools. Cloud agents receive the delegated repository task.",13,"MutedBrush"));
            foreach(var agent in board.Array("agents").OfType<JsonObject>())
            {
                string backend=agent.Text("backend_id",agent.Text("id"));
                var card=Ui.Column(Ui.Text(agent.Text("name",backend),16,bold:true),
                    Ui.Text(agent.Text("state",agent.Text("status"))+" · "+agent.Text("model_id",""),13));
                card.Children.Add(Ui.Text(agent.Text("reason",""),13,"MutedBrush"));
                card.Children.Add(ActionButton("Check readiness","agent_qualify",()=>new JsonObject{["backend"]=backend},agent.Flag("configured")));
                card.Children.Add(ActionButton("Delegate task","agent_start",()=>{
                    if(!Confirm("Delegate this accepted task to "+agent.Text("name",backend)+"?\n"+board.Text("objective")))return null;
                    return new JsonObject{["backend"]=backend,["model_id"]=agent.Text("model_id"),["objective"]=board.Text("objective"),["consent"]=true};
                },agent.Flag("ready")&&agent.Array("capabilities").Any(c=>c?.ToString()=="start")));agents.Children.Add(Ui.Card(card));
            }
            foreach(var run in board.Array("agent_runs").OfType<JsonObject>())
            {
                string id=run.Text("run_id",run.Text("id"));
                bool Supports(string capability)=>run.Text("source_sha")==board.Text("head_sha")&&run.Array("capabilities").Any(c=>c?.ToString()==capability);
                var card=Ui.Column(Ui.Text(run.Text("backend")+" · "+run.Text("state",run.Text("status")),15,bold:true),Ui.Text(id,12,"MutedBrush"));
                card.Children.Add(Ui.Actions(ActionButton("Observe","agent_status",()=>new JsonObject{["run_id"]=id},Supports("status")),
                    ActionButton("Read events","agent_events",()=>new JsonObject{["run_id"]=id},Supports("events")),
                    ActionButton("Cancel agent","agent_cancel",()=>new JsonObject{["run_id"]=id},Supports("cancel")),
                    ActionButton("Import candidate","agent_import",()=>new JsonObject{["run_id"]=id},Supports("import")&&run.Text("state")=="COMPLETED")));
                card.Children.Add(Ui.Actions(ActionButton("Read artifacts","agent_artifacts",()=>new JsonObject{["run_id"]=id},Supports("artifacts")),
                    ActionButton("Approve retained plan","agent_approve_plan",()=>Confirm("Approve the plan retained by this exact agent run?")?new JsonObject{["run_id"]=id}:null,Supports("approve_plan")&&run.Text("state")=="WAITING_FOR_INPUT")));
                card.Children.Add(ActionButton("Steer agent","agent_steer",()=>{
                    var form=new StackPanel();var message=Ui.Field(form,"Instruction within this task");
                    return EditDialog("Steer agent",form,"Queue instruction")?new JsonObject{["run_id"]=id,["message"]=message.Text,["expected_remote_run_id"]=run.Text("remote_run_id")}:null;
                },Supports("steer")));agents.Children.Add(Ui.Card(card));
            }
            agents.Children.Add(ActionButton("Compare configured agents","agent_compare",()=>{
                var form=new StackPanel();var choices=new List<(string Id,CheckBox Box)>();
                foreach(var item in board.Array("agents").OfType<JsonObject>().Where(a=>a.Flag("ready")))
                    choices.Add((item.Text("backend_id",item.Text("id")),Ui.Toggle(form,item.Text("name"),false)));
                form.Children.Add(Ui.Text("Each selected agent receives the same accepted task and pinned source. Compare agent systems separately from model benchmarks. Configured time, iteration and cost budgets must match.",13));
                if(!EditDialog("Compare agent systems",form,"Delegate comparison"))return null;
                var selected=choices.Where(c=>c.Box.IsChecked==true).Select(c=>(JsonNode?)JsonValue.Create(c.Id)).ToArray();
                if(selected.Length<2||selected.Length>8)throw new InvalidOperationException("Select two to eight qualified agents.");
                return new JsonObject{["backends"]=new JsonArray(selected),["objective"]=board.Text("objective"),["consent"]=true};
            },board.Array("agents").OfType<JsonObject>().Count(a=>a.Flag("ready"))>=2));
            if(board.Array("agents").Count==0)agents.Children.Add(Ui.Text("Configure an optional runtime in Tools → Coding agents.",13,"MutedBrush"));
            foreach(var reference in board.Array("product_references").OfType<JsonObject>())
                agents.Children.Add(Ui.Text(reference.Text("name")+" · "+reference.Text("state")+"\n"+reference.Text("feature"),12,"MutedBrush"));
            AddTab("Agents",agents);

            var review=new StackPanel();var candidates=board.Array("candidates").OfType<JsonObject>().ToArray();
            foreach(var candidate in candidates)
            {
                string check=candidate.Text("check_id");
                var card=Ui.Column(Ui.Text(check[..Math.Min(12,check.Length)]+" · "+candidate.Text("status"),15,bold:true),
                    Ui.Text(candidate.Text("source_sha"),12,"MutedBrush"));
                card.Children.Add(Ui.Button("Read diff and tests",()=>Run(async()=>{
                    await WorkspaceCandidate(row.Id,check,board.Text("head_sha"));
                })));review.Children.Add(Ui.Card(card));
            }
            review.Children.Add(Ui.Button("Browse pinned source",()=>Run(()=>WorkspaceSource(row.Id,board.Text("head_sha")))));
            if(candidates.Length>=2)review.Children.Add(ActionButton("Compare test evidence","candidate_rank",()=>new JsonObject{["check_ids"]=new JsonArray(candidates.Take(8).Select(c=>(JsonNode?)JsonValue.Create(c.Text("check_id"))).ToArray())}));
            review.Children.Add(ActionButton("Save review draft","review_draft",()=>{
                var form=new StackPanel();var text=Editor("Review draft");form.Children.Add(text);
                var file=Ui.Field(form,"Source path (optional)");
                if(!EditDialog("Private review draft",form,"Save draft"))return null;
                var args=new JsonObject{["draft_id"]="review-"+Guid.NewGuid().ToString("N"),["body"]=text.Text};
                if(file.Text.Trim().Length>0)args["path"]=file.Text.Trim();return args;
            }));
            foreach(var draft in board.Array("review_drafts").OfType<JsonObject>())
                review.Children.Add(Ui.Card(Ui.Column(Ui.Text("Private draft · "+draft.Text("path","General"),14,bold:true),Ui.Text(draft.Text("body"),14))));
            review.Children.Add(Ui.Button("Compare details",()=>ShowDetails("Candidate comparison",board.Object("candidate_comparison"))));
            AddTab("Review",review);

            var environment=Ui.Column(Ui.Text(board.Object("environment").Text("status"),17,bold:true),
                Ui.Text("Readiness checks run before model execution. Test results remain tied to the source and candidate that produced them.",13,"MutedBrush"));
            environment.Children.Add(ActionButton("Qualify environment","environment_qualify",()=>new JsonObject()));
            environment.Children.Add(Ui.Button("Environment evidence",()=>ShowDetails("Environment evidence",board.Object("environment"))));
            foreach(var requirement in board.Array("requirements").OfType<JsonObject>())
                environment.Children.Add(Ui.Card(Ui.Column(Ui.Text(requirement.Text("id"),15,bold:true),
                    Ui.Text(requirement.Object("verdict").Text("status","Not verified"),13),
                    Ui.Text(string.Join(", ",requirement.Array("paths").Select(p=>p?.ToString())),12,"MutedBrush"))));
            environment.Children.Add(Ui.Button("Task plan and evidence",()=>ShowDetails("Executable requirements",board.Object("task_plan"))));
            environment.Children.Add(ActionButton("Build task plan from requirements","task_plan",()=>{
                var accepted=board.Array("requirements").OfType<JsonObject>().ToArray();
                int size=Math.Max(1,(accepted.Length+79)/80);
                var items=accepted.Chunk(size).Select((group,index)=>(JsonNode?)new JsonObject{["id"]="requirements-"+(index+1),
                    ["requirement_ids"]=new JsonArray(group.Select(r=>(JsonNode?)JsonValue.Create(r.Text("id"))).ToArray()),
                    ["depends_on"]=new JsonArray(),["role"]="editor"}).ToArray();
                return new JsonObject{["items"]=new JsonArray(items)};
            },board.Array("requirements").Count>0&&board.Object("task_plan").Text("state")=="NOT_PLANNED"));
            foreach(var task in board.Object("task_plan").Array("items").OfType<JsonObject>().Where(t=>t.Flag("ready")))
                environment.Children.Add(ActionButton("Verify evidence for "+task.Text("id"),"task_plan_complete",()=>{
                    var form=new StackPanel();var hashes=Editor("Passing check receipt hashes, one per line");form.Children.Add(hashes);
                    if(!EditDialog("Observe task evidence",form,"Verify evidence"))return null;
                    return new JsonObject{["id"]=task.Text("id"),["receipt_sha256"]=new JsonArray(hashes.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(h=>(JsonNode?)JsonValue.Create(h)).ToArray())};
                }));
            AddTab("Requirements",environment);

            var tools=new StackPanel();
            var profile=Ui.Select(tools,"Task tools",new[]{"retrieve","plan","edit","test","review"});
            tools.Children.Add(ActionButton("Use task tool profile","tool_profile",()=>new JsonObject{["profile"]=profile.SelectedItem?.ToString()}));
            tools.Children.Add(ActionButton("Retain compact context","context_summary",()=>new JsonObject()));
            tools.Children.Add(ActionButton("Export selected source","context_pack",()=>{
                var form=new StackPanel();var paths=Editor("Source paths, one per line");form.Children.Add(paths);
                var format=Ui.Select(form,"Export format",new[]{"markdown","json","xml"});var engine=Ui.Select(form,"Exporter",new[]{"builtin","repomix"});
                if(!EditDialog("Selective source export",form,"Prepare private artifact"))return null;
                return new JsonObject{["paths"]=new JsonArray(paths.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(p=>(JsonNode?)JsonValue.Create(p)).ToArray()),
                    ["format"]=format.SelectedItem?.ToString(),["engine"]=engine.SelectedItem?.ToString(),["max_bytes"]=65536};
            }));
            tools.Children.Add(Ui.Button("Configured integrations",()=>ShowDetails("Versioned docs and browser tools",board.Object("integrations"))));
            tools.Children.Add(ActionButton("Discover configured tools","integration_status",()=>new JsonObject()));
            foreach(var integration in board.Object("integrations").Array("integrations").OfType<JsonObject>())
                foreach(var tool in integration.Array("tools").OfType<JsonObject>())
                    tools.Children.Add(ActionButton(integration.Text("id")+" · "+tool.Text("name"),"integration_call",()=>{
                        var form=new StackPanel();form.Children.Add(Ui.Text(tool.Object("input_schema").ToJsonString(Pretty),12));
                        var arguments=Editor("Tool arguments (JSON object)","{}",120);form.Children.Add(arguments);
                        if(!EditDialog("Run configured tool",form,"Queue tool"))return null;
                        var value=JsonNode.Parse(arguments.Text) as JsonObject??throw new InvalidOperationException("Enter a JSON object matching the observed tool schema.");
                        return new JsonObject{["integration"]=integration.Text("id"),["tool"]=tool.Text("name"),["arguments"]=value,["invocation_id"]="tool-"+Guid.NewGuid().ToString("N")};
                    },integration.Text("state")=="READY_FOR_DISPATCH"&&tool.Flag("advertised")&&tool["input_schema"] is JsonObject));
            tools.Children.Add(ActionButton("Suggest local next edit","next_edit",()=>{
                var form=new StackPanel();var path=Ui.Field(form,"Observed source path");var instruction=Editor("Edit instruction");form.Children.Add(instruction);
                if(!EditDialog("Local next edit",form,"Suggest candidate"))return null;
                return new JsonObject{["path"]=path.Text.Trim(),["instruction"]=instruction.Text};
            },board.Object("next_edit_readiness").Text("state")=="AVAILABLE"));
            foreach(var edit in board.Array("next_edits").OfType<JsonObject>())
            {
                tools.Children.Add(Ui.Button(edit.Text("path")+" · "+edit.Text("state"),()=>ShowDetails("Local edit proposal",edit)));
                tools.Children.Add(ActionButton("Validate "+edit.Text("path"),"next_edit_validate",()=>{
                    var form=new StackPanel();var checks=board.Object("environment").Array("checks").OfType<JsonObject>().ToArray();
                    var selectedCheck=Ui.Select(form,"Approved check",checks.Select(c=>c.Text("index")+" · "+c.Text("name")));
                    if(!EditDialog("Validate local candidate",form,"Run approved check")||selectedCheck.SelectedIndex<0)return null;
                    return new JsonObject{["next_edit_id"]=edit.Text("next_edit_id"),["check_index"]=checks[selectedCheck.SelectedIndex]["index"]?.DeepClone()};
                },edit.Text("state")=="PROPOSED"));
            }
            tools.Children.Add(Ui.Button("Instructions and summaries",()=>ShowDetails("Retained context",new JsonObject{["instructions"]=board["instructions"]?.DeepClone(),["summaries"]=board["summaries"]?.DeepClone()})));
            AddTab("Context",tools);

            var commands=new StackPanel();
            var observed=board.Array("commands").OfType<JsonObject>().Concat(board.Array("local_commands").OfType<JsonObject>())
                .DistinctBy(c=>c.Text("command_id"));
            foreach(var command in observed)
            {
                string id=command.Text("command_id");
                var card=Ui.Column(Ui.Text(command.Text("action","Workspace request")+" · "+command.Text("status"),14,bold:true),Ui.Text(id,12,"MutedBrush"));
                card.Children.Add(Ui.Button("Check original receipt",()=>Run(async()=>{
                    var result=await Call("engineering-workspace-receipt",WithIdentity(new JsonObject{["job_id"]=row.Id,["command_id"]=id}));
                    ShowDetails("Original command receipt",result);
                    if(result.Flag("retry_available")&&Confirm("Gateway confirms this request is absent. Retry the unchanged original request?"))
                        ShowDetails("Original request retry",await Call("engineering-workspace-retry",WithIdentity(new JsonObject{["job_id"]=row.Id,["command_id"]=id})));
                    if(result.Flag("dismiss_available")&&Confirm("Gateway confirms this request is absent and its original revision has moved. Dismiss the local pending request?"))
                        ShowDetails("Absent request dismissed",await Call("engineering-workspace-dismiss",WithIdentity(new JsonObject{["job_id"]=row.Id,["command_id"]=id})));
                    await Refresh();
                })));commands.Children.Add(Ui.Card(card));
            }
            AddTab("Commands",commands);
            tabs.SelectedIndex=Math.Clamp(selected,0,tabs.Items.Count-1);
        }
        body.Children.Add(Ui.Button("Refresh workspace",()=>Run(Refresh)));Render();
        EditDialog("Agent workspace",body,"Close");
    }

    private async Task WorkspaceCandidate(string jobId,string checkId,string headSha)
    {
        var text=Editor("Candidate diff and test output","",270);text.IsReadOnly=true;
        text.FontFamily=new System.Windows.Media.FontFamily("Consolas");
        var note=Ui.Text("",13,"MutedBrush");int? next=0;int offset=0;
        var panel=Ui.Column(note,text);
        async Task Read(int cursor)
        {
            var value=await Call("engineering-candidate",WithIdentity(new JsonObject{["job_id"]=jobId,["check_id"]=checkId,["offset"]=cursor}));
            if(value.Text("head_sha")!=headSha)throw new InvalidOperationException("The candidate source changed. Reopen the workspace.");
            offset=cursor;next=value.Number("next_offset") is double n?(int)n:null;
            note.Text=$"{value.Text("status")} · {headSha}\nDiff position {offset:N0}";
            text.Text=value.Text("diff","")+(cursor==0?"\n\n"+value.Text("output",""):"");
        }
        panel.Children.Add(Ui.Actions(Ui.Button("First diff page",()=>Run(()=>Read(0))),
            Ui.Button("Next diff page",()=>Run(async()=>{if(next is int n)await Read(n);}))));
        await Read(0);EditDialog("Candidate changes and tests",panel,"Close");
    }

    private async Task WorkspaceSource(string jobId,string headSha)
    {
        var panel=new StackPanel();var query=Ui.Field(panel,"Find source paths");
        var choices=new ComboBox{MinWidth=420};panel.Children.Add(choices);
        var note=Ui.Text(headSha,12,"MutedBrush");panel.Children.Add(note);
        var text=Editor("Pinned source","",270);text.IsReadOnly=true;text.FontFamily=new System.Windows.Media.FontFamily("Consolas");panel.Children.Add(text);
        int? nextPaths=null;int? nextLine=null;string openedPath="",observedQuery="";
        var sourceBlobs=new Dictionary<string,string>();
        async Task Paths(int offset)
        {
            string requestQuery=offset==0?query.Text:observedQuery;
            var value=await Call("engineering-workspace-tree",WithIdentity(new JsonObject{["job_id"]=jobId,["head_sha"]=headSha,["query"]=requestQuery,["offset"]=offset}));
            if(offset==0){choices.Items.Clear();sourceBlobs.Clear();observedQuery=requestQuery;}
            foreach(var path in value.Array("paths").OfType<JsonObject>())
            {
                sourceBlobs[path.Text("path")]=path.Text("blob_sha");choices.Items.Add(path.Text("path"));
            }
            if(choices.SelectedIndex<0&&choices.Items.Count>0)choices.SelectedIndex=0;
            nextPaths=value.Number("next_offset") is double n?(int)n:null;
            note.Text=$"{headSha} · {choices.Items.Count:N0} of {value.Text("total")} paths";
        }
        async Task Read(string path,int line)
        {
            var value=await Call("engineering-workspace-source",WithIdentity(new JsonObject{["job_id"]=jobId,["head_sha"]=headSha,["path"]=path,["start_line"]=line,["line_count"]=120}));
            if(value.Text("path")!=path||!sourceBlobs.TryGetValue(path,out var blob)||value.Text("blob_sha")!=blob||value.Number("start_line")!=line)
                throw new InvalidOperationException("The returned source no longer matches the selected path and blob.");
            openedPath=path;nextLine=value.Number("next_line") is double n?(int)n:null;
            note.Text=$"{path} · lines {value.Text("start_line")}–{value.Text("end_line")} of {value.Text("total_lines")}\n{value.Text("blob_sha")}";
            text.Text=value.Text("content","");
        }
        panel.Children.Add(Ui.Actions(Ui.Button("Find paths",()=>Run(()=>Paths(0))),
            Ui.Button("More paths",()=>Run(async()=>{if(nextPaths is int n)await Paths(n);})),
            Ui.Button("Read selected source",()=>Run(async()=>{if(choices.SelectedItem is string path)await Read(path,1);})),
            Ui.Button("Next source page",()=>Run(async()=>{if(nextLine is int n&&openedPath.Length>0)await Read(openedPath,n);}))));
        await Paths(0);EditDialog("Pinned source browser",panel,"Close");
    }

    private void BuildCodingAgents()
    {
        var panel=new StackPanel();var list=new StackPanel();JsonObject? saved=null;
        panel.Children.Add(Ui.Text("Optional coding runtimes",22,bold:true));
        panel.Children.Add(Ui.Text("Choose an installed runtime or cloud service, an exact model and the repositories and accounts it may use. Credentials remain on this PC.",14,"MutedBrush"));
        async Task Load()
        {
            saved=await Call("workspace-agent-config-load");list.Children.Clear();
            foreach(var entry in saved.Object("document").Object("agents"))
                list.Children.Add(Ui.Text($"{entry.Key} · {(entry.Value.Flag("enabled")?"enabled":"disabled")} · {entry.Value.Text("model_id")}",14));
        }
        panel.Children.Add(Ui.Actions(Ui.Button("Add or update runtime",()=>Run(async()=>{
            if(saved is null)await Load();var original=(JsonObject)saved!.DeepClone();
            var catalog=original.Array("catalog").OfType<JsonObject>().ToArray();
            var form=new StackPanel();var runtime=Ui.Select(form,"Runtime",catalog.Select(c=>c.Text("id")));
            var model=Ui.Field(form,"Exact model ID");var repo=Ui.Field(form,"Allowed repositories (one owner/name per line)");repo.AcceptsReturn=true;
            var principal=Ui.Field(form,"Allowed Gateway accounts (one per line)",identity?.Text("principal_id","")??"");principal.AcceptsReturn=true;
            var credential=Ui.Field(form,"Credential environment variable (optional)");
            var image=Ui.Field(form,"Installed container image with @sha256 digest (local runtimes)");
            var seconds=Ui.Field(form,"Maximum seconds (1–7200)","1800");
            var runs=Ui.Field(form,"Maximum runs per task (1–8)","3");
            var iterations=Ui.Field(form,"Maximum iterations (1–128)","24");
            var cost=Ui.Field(form,"Declared budget in USD (provider enforcement varies)","2.0");
            var enabled=Ui.Toggle(form,"Enable this runtime",false);
            void Fill()
            {
                var old=original.Object("document").Object("agents").Object(runtime.SelectedItem?.ToString()??"");
                model.Text=old.Text("model_id","");repo.Text=string.Join("\n",old.Array("repositories").Select(x=>x?.ToString()));
                principal.Text=string.Join("\n",old.Array("principals").Select(x=>x?.ToString()));
                if(principal.Text.Length==0)principal.Text=identity?.Text("principal_id","")??"";
                credential.Text=old.Text("credential_reference","").Replace("env:","");image.Text=old.Text("image","");enabled.IsChecked=old.Flag("enabled");
                seconds.Text=old.Text("max_seconds","1800");runs.Text=old.Text("max_runs","3");
                iterations.Text=old.Text("max_iterations","24");cost.Text=old.Text("max_budget_usd","2.0");
            }
            runtime.SelectionChanged+=(_,_)=>Fill();Fill();
            if(!EditDialog("Configure coding runtime",form,"Save runtime"))return;
            string selected=runtime.SelectedItem!.ToString()!;
            var entry=(JsonObject)original.Object("document").Object("agents").Object(selected).DeepClone();
            entry["enabled"]=enabled.IsChecked==true;entry["model_id"]=model.Text.Trim();
            entry["repositories"]=new JsonArray(repo.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(p=>(JsonNode?)JsonValue.Create(p)).ToArray());
            entry["principals"]=new JsonArray(principal.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(p=>(JsonNode?)JsonValue.Create(p)).ToArray());
            entry["max_seconds"]=int.Parse(seconds.Text);entry["max_runs"]=int.Parse(runs.Text);
            entry["max_iterations"]=int.Parse(iterations.Text);entry["max_budget_usd"]=double.Parse(cost.Text,System.Globalization.CultureInfo.InvariantCulture);
            if(credential.Text.Trim().Length>0)entry["credential_reference"]="env:"+credential.Text.Trim();else entry.Remove("credential_reference");
            if(image.Text.Trim().Length>0)entry["image"]=image.Text.Trim();else entry.Remove("image");
            var document=(JsonObject)original.Object("document").DeepClone();document.Object("agents")[selected]=entry;
            await Call("workspace-agent-config-save",new JsonObject{["revision"]=original.Text("revision"),["document"]=document});
            await Load();status.Text="Runtime saved. Restart Gateway when idle, then check readiness in a task workspace.";
        }),true),Ui.Button("Refresh runtimes",()=>Run(Load))));
        panel.Children.Add(list);page.Children.Add(Ui.Card(panel));Run(Load);
    }
}
