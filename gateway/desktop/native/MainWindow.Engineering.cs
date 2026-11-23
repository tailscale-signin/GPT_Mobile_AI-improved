using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private async Task StartEngineering(JsonObject args,JsonObject? original)
    {
        int viewEpoch=epoch;
        var form=Ui.Column(Ui.Text(args.Text("model")+" · "+args.Text("prompt"),15));
        var values=new JsonObject{["objective"]=args.Text("prompt")};
        var fields=new Dictionary<string,TextBox>();ComboBox? provider=null;ComboBox? contextVariant=null;
        if(original==null){
            args.Remove("conversation_id");
            foreach(var (key,label,value) in new[]{("repository","Repository",""),("source_branch","Source branch","main"),("source_sha","Exact source commit",""),("branch","Working branch",""),("base","Target branch","main"),
                ("pr","Adopt existing PR number (optional)",""),("adopt_base","Reviewed PR base SHA","")})fields[key]=Ui.Field(form,label,value);
            provider=Ui.Select(form,"Local model provider",new[]{"LLAMA","OLLAMA"});
            contextVariant=Ui.Select(form,"Repository context",new[]{"Task context (recommended)","Current context baseline","Repository reference","Task context with local similarity"});
            form.Children.Add(Ui.Text("Task context selects source ranges, repository guidance and test candidates. Local similarity adds semantic ranking within the selected source and permissions.",12,"MutedBrush"));
        }
        var scope=Ui.Select(form,"Task scope",new[]{"implement","plan","read"});
        var procedure=Ui.Field(form,"Procedure (optional)");
        if(!EditDialog("Prepare engineering task",form,"Check prerequisites")||epoch!=viewEpoch)return;
        values["provider"]=provider?.SelectedItem?.ToString();values["task_scope"]=scope.SelectedItem?.ToString();
        if(contextVariant!=null)values["context_variant"]=new[]{"TASK","CURRENT","REFERENCE","SEMANTIC"}[Math.Max(0,contextVariant.SelectedIndex)];
        if(procedure.Text.Trim().Length>0)values["objective"]="/"+procedure.Text.Trim().TrimStart('/')+" "+args.Text("prompt");
        foreach(var (key,box) in fields)if(key is not ("pr" or "adopt_base"))values[key]=box.Text.Trim();
        if(fields.TryGetValue("pr",out var pr) && (pr.Text.Length>0||fields["adopt_base"].Text.Length>0)){
            if(!int.TryParse(pr.Text,out int n)||n<1)throw new InvalidOperationException("Enter the existing pull request number.");
            values["adopt_pr"]=new JsonObject{["number"]=n,["head_sha"]=values["source_sha"]?.DeepClone(),["base_sha"]=fields["adopt_base"].Text.Trim()};
        }
        args["values"]=values;
        var prep=await Call("engineering-prepare",args);var payload=prep.Object("payload");var check=prep.Object("preflight");
        if(epoch!=viewEpoch)return;
        if(!check.Flag("ready")){
            var acceptance=check.Object("acceptance_preparation");var candidates=acceptance.Array("candidates").OfType<JsonObject>()
                .SelectMany(c=>c.Array("requirement_ids").Select(r=>(c,r))).ToArray();
            if(candidates.Length>0 && acceptance.Text("source_sha")==payload.Text("source_sha")){
                var mapping=new StackPanel();var choices=Ui.Select(mapping,"Approved test and requirement",candidates.Select(p=>p.c.Text("case_id")+" · "+p.r));
                var paths=Ui.Field(mapping,"Changed source paths (one per line)");paths.AcceptsReturn=true;paths.MinHeight=54;
                var required=Ui.Select(mapping,"Required check",acceptance.Array("allowed_checks").Select(x=>x!.ToString()));
                if(!EditDialog("Review task acceptance",mapping,"Check acceptance")||epoch!=viewEpoch)return;
                var chosen=candidates[choices.SelectedIndex];
                payload["acceptance"]=new JsonArray(new JsonObject{["id"]=chosen.r?.DeepClone(),["paths"]=new JsonArray(paths.Text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).Select(p=>(JsonNode?)JsonValue.Create(p)).ToArray()),
                    ["checks"]=new JsonArray(required.SelectedItem?.ToString()),["evidence"]=new JsonArray(new JsonObject{["kind"]="behavior_test",["name"]=chosen.c.Text("case_id")})});
                check=await Call("engineering-preflight",WithIdentity(new JsonObject{["payload"]=payload.DeepClone()}));
            }
        }
        if(!check.Flag("ready")){ShowDetails("Preparation blockers",check);return;}
        if(epoch!=viewEpoch || !Confirm($"Submit {payload.Text("task_scope")} work to {payload.Text("repository")} on {payload.Text("branch")}?\nSource {payload.Text("source_sha")}\nModel {payload.Text("model")}"))return;
        sending=true;detachSafe=false;
        var result=await Call("engineering-submit",WithIdentity(new JsonObject{["payload"]=payload.DeepClone()}));
        detachSafe=result.Flag("detach_safe");var receipt=await Call("conversation-read",WithIdentity(new JsonObject{["conversation_id"]=result.Text("conversation_id")}));
        openedConversation=receipt.Object("conversation");transcript=receipt.Array("messages");draft=[];chatMode="Engineering";Navigate("Chat");
        status.Text="Engineering task accepted · "+result.Text("id");
    }
    private async Task ReviewEngineering(JobRow row)
    {
        var args=WithIdentity(new JsonObject{["job_id"]=row.Id,["section"]="Review"});
        var reviewed=await Call("engineering-inspect",args);
        if(!reviewed.Flag("merge_ready")){ShowDetails("Engineering review",reviewed);return;}
        string summary=$"{reviewed.Text("repository")} · {reviewed.Text("branch")} → {reviewed.Text("base")}\nPR #{reviewed.Text("pr")} · revision {reviewed.Text("revision")}\nCommit {reviewed.Text("head_sha")}";
        var form=Ui.Column(Ui.Text(summary));var action=Ui.Select(form,"Decision",new[]{"Request changes","Approve exact merge"});
        if(!EditDialog("Review pull request",form,"Continue"))return;
        bool approve=action.SelectedIndex==1;if(!Confirm((approve?"Approve this exact merge?":"Request changes on this reviewed revision?")+"\n"+summary))return;
        var result=await Call(approve?"engineering-approve":"engineering-request-changes",WithIdentity(new JsonObject{["job_id"]=reviewed.Text("id"),["reviewed"]=reviewed.DeepClone(),["revision"]=reviewed["revision"]?.DeepClone()}));
        ShowDetails("Review receipt",result);await RefreshCatalog();
    }
    private async Task Checkpoints(JobRow row)
    {
        var r=await Call("engineering-inspect",WithIdentity(new JsonObject{["job_id"]=row.Id,["section"]="Checkpoints"}));var history=r.Object("history");var job=r.Object("job");
        var panel=Ui.Column(Ui.Text(history.Text("head_sha")+" · "+history.Text("state"),15));
        var choices=history.Array("candidates").OfType<JsonObject>().ToList();var selection=Ui.Select(panel,"Candidate",choices.Select(c=>c.Text("attempt_id",c.Text("check_id"))+" · "+c.Text("status")));
        string Id()=>choices[selection.SelectedIndex].Text("check_id");
        var inspect=Ui.Button("Inspect candidate",()=>Run(async()=>{if(selection.SelectedIndex>=0)ShowDetails("Candidate evidence",await Call("engineering-candidate",WithIdentity(new JsonObject{["job_id"]=job.Text("id"),["check_id"]=Id()})));}));
        inspect.IsEnabled=choices.Count>0;panel.Children.Add(inspect);
        bool eligible=!history.Flag("published")&&job.Text("state")=="NEEDS_INPUT"&&choices.Count>0;
        if(!eligible){panel.Children.Add(Ui.Text("Restoration requires an unpublished candidate paused for input.",13,"MutedBrush"));EditDialog("Checkpoint history",panel,"Done");return;}
        if(!EditDialog("Checkpoint history",panel,"Restore selected candidate")||!Confirm("Restore the unpublished candidate and rerun validation and review?"))return;
        ShowDetails("Restore receipt",await Call("engineering-restore",WithIdentity(new JsonObject{["job_id"]=job.Text("id"),["check_id"]=Id(),["revision"]=job["revision"]?.DeepClone()})));
    }
}
