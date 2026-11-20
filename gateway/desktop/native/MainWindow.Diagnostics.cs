using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using Gateway.Desktop.Core;
using Microsoft.Win32;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private JsonObject readiness = [], workspaceReadiness = [];
    private DateTime readinessAttempt, readinessObserved;
    private bool readinessBusy;
    private Action? diagnosticUpdate;
    private string reportJob="";
    private int reportPeriod;
    private void OpenReports(string id="",int period=0)
    {
        reportJob=id;reportPeriod=period;Navigate("Diagnostics/Reports");
    }
    private async Task OpenLastReport()
    {
        string path=preferences.Text("lastReportPath","");
        if(path.Length==0){OpenReports();return;}
        if(!File.Exists(path))throw new InvalidOperationException("The exported diagnostic ZIP has moved.");
        using var file=File.OpenRead(path);
        string digest=Convert.ToHexString(await SHA256.HashDataAsync(file));
        if(!digest.Equals(preferences.Text("lastReportHash",""),StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("This ZIP has changed since export.");
        file.Position=0;
        using var zip=new System.IO.Compression.ZipArchive(file);
        var entry=zip.GetEntry("manifest.json")??throw new InvalidOperationException("The report manifest is missing.");
        using var stream=entry.Open();
        var manifest=await JsonNode.ParseAsync(stream);
        if(manifest!=null)ShowDetails("Exported report · "+System.IO.Path.GetFileName(path),manifest);
    }
    private static string Age(JsonNode? obj,string key) => LocalTime(obj.Number(key));
    private static string TimeOf(double? stamp) => stamp is >0 ? DateTimeOffset.FromUnixTimeSeconds((long)stamp.Value).ToLocalTime().ToString("T") : "unobserved";
    private void RefreshReadinessIfDue()
    {
        if(readinessBusy || (DateTime.UtcNow-readinessAttempt).TotalSeconds<30)return;
        readinessBusy=true; readinessAttempt=DateTime.UtcNow;
        Run(async()=>{try{readiness=await Call("control-settings-read",new JsonObject{["owner_principal"]=settingsOwner});readinessObserved=DateTime.UtcNow;
                workspaceReadiness=await Call("workspace-readiness");}
            finally{readinessBusy=false;if(selectedPage=="Overview")RefreshOverview();}});
    }

    private Border Card(string title, params UIElement[] items)
    {
        var body=Ui.Column(Ui.Text(title,18,bold:true));foreach(var item in items)body.Children.Add(item);
        var card=Ui.Card(body);card.Padding=new Thickness(14);card.CornerRadius=new CornerRadius(14);card.Margin=new Thickness(0,0,9,9);return card;
    }
    private void Pair(StackPanel parent, UIElement first, UIElement second)
    {
        var pair=new Grid();pair.ColumnDefinitions.Add(new());pair.ColumnDefinitions.Add(new());
        pair.RowDefinitions.Add(new(){Height=GridLength.Auto});pair.RowDefinitions.Add(new(){Height=GridLength.Auto});
        pair.Children.Add(first);pair.Children.Add(second);
        void Arrange(){bool wide=pair.ActualWidth>=765;Grid.SetColumn(second,wide?1:0);Grid.SetRow(second,wide?0:1);Grid.SetColumnSpan(first,wide?1:2);Grid.SetColumnSpan(second,wide?1:2);}
        pair.SizeChanged+=(_,_)=>Arrange();Arrange();parent.Children.Add(pair);
    }

    private void BuildOverview()
    {
        var manager=snapshot.Object("manager");var service=snapshot.Object("service");var effective=readiness.Object("effective");
        bool running=snapshot.Flag("fresh")&&manager.Text("state")=="running";bool ready=running&&manager.Text("backend")=="ready";
        var hero=Ui.Column(Ui.Text(Environment.MachineName+"   ·   Gateway "+snapshot.Text("version","—"),14,"PrimaryBrush",true),
            Ui.Text(ready?"Ready to connect":running?"Waiting for a model":snapshot.Count==0?"Connecting to Gateway":"Gateway "+manager.Text("state","offline"),26,bold:true),
            Ui.Text($"Runtime {manager.Text("state")}   ·   Backend {manager.Text("backend")}   ·   Model {effective.Text("model","unobserved")}   ·   Observed {TimeOf(snapshot.Number("capturedAt"))}",14,"MutedBrush"));
        hero.Children.Add(Ui.Actions(Ui.Button("Connect phone",()=>Navigate("Connect phone"),true),
            Ui.Button(ready?"Open chat":"Model settings",()=>Navigate(ready?"Chat":"Settings/Models")),
            Ui.Button("Connection settings",()=>Navigate("Settings/Connection"))));
        if(!running)hero.Children.Add(Ui.Button("Start Gateway",()=>Run(async()=>{await Call("runtime-start");await RefreshSnapshot();})));
        page.Children.Add(Ui.Card(hero,true));
        var metrics = new UniformGrid { Columns = 4 };
        metrics.SizeChanged += (_, e) =>
        {
            int columns = e.NewSize.Width >= 720 ? 4 : e.NewSize.Width >= 360 ? 2 : 1;
            if (metrics.Columns != columns) metrics.Columns = columns;
        };
        foreach(var (label,key) in new[]{("Running","running"),("Queued","queued"),("Needs attention","needs_attention"),("Workers","workers")})
        {
            string val=key=="workers"?service.Text("activeWorkers","—")+" / "+service.Text("maxWorkers","—"):jobCounts.Text(key,"—");
            var button=Ui.Button(label,()=>OpenJobs(key=="workers"?"running":key));
            button.ContentTemplate=null;
            button.Content=Ui.Column(Ui.Text(val,24,"PrimaryBrush",true),Ui.Text(label,15),Ui.Text(key=="workers"?TimeOf(snapshot.Number("capturedAt")):CatalogAge,13,"MutedBrush"));
            metrics.Children.Add(button);
        }
        page.Children.Add(metrics);
        var work=new StackPanel();foreach(var row in jobs.Where(j=>j.Kind!="host receipt"&&j.State is "running" or "queued").Take(4))
            work.Children.Add(Ui.Button($"{row.Title} · {row.Stage} · {row.Elapsed} · {row.Next}",()=>OpenJobs(row.State,row.Id)));
        if(work.Children.Count==0)work.Children.Add(Ui.Text(catalogObserved==default?"Waiting for the job catalog.":"No active tasks in the loaded catalog.",14,"MutedBrush"));
        Pair(page,UsageCard(),Card("Active work",Ui.Text(CatalogAge,12,"MutedBrush"),work));
        var attention=new StackPanel();
        if(!snapshot.Flag("fresh"))attention.Children.Add(Ui.Button("Gateway observations are stale · Connection",()=>Navigate("Settings/Connection")));
        if(!ready)attention.Children.Add(Ui.Button("Waiting for a model · Models",()=>Navigate("Settings/Models")));
        string Check(string name)
        {
            var observation=workspaceReadiness.Object(name);
            if(observation["ready"] is null)return "Unchecked";
            return (observation.Flag("ready")?"Ready":"Needs attention")+" · "+TimeOf(observation.Number("observed"));
        }
        var repository=workspaceReadiness.Object("repository").Text("subject","");
        attention.Children.Add(Ui.Button("Repository access · "+repository+" "+Check("repository"),()=>Navigate("Tools/GitHub")));
        string policyState=workspaceReadiness["validation_enabled"]==null?"Policy unobserved":workspaceReadiness.Flag("validation_enabled")?"Policy enabled":"Policy disabled";
        attention.Children.Add(Ui.Button("Validation environment · "+Check("environment")+" · "+policyState,()=>Navigate("Tools/Environments")));
        attention.Children.Add(Ui.Button("Engineering · "+(effective["engineering_enabled"] is null?"Unobserved":effective.Flag("engineering_enabled")?"Enabled":"Disabled")
            +" · "+(readinessObserved==default?"unobserved":readinessObserved.ToLocalTime().ToString("T")),()=>Navigate("Settings/Execution")));
        if(readiness.Array("pending").Count>0)attention.Children.Add(Ui.Button("Review pending settings",()=>Navigate("Settings/Execution")));
        foreach(var row in jobs.Where(j=>j.State is "needs_attention" or "failed").Take(3))
            attention.Children.Add(Ui.Button(row.Title+" · "+row.Next,()=>OpenJobs(row.State,row.Id)));
        var outcomes=new StackPanel();
        foreach(var row in jobs.Where(j=>j.State is "completed" or "cancelled").Take(4))outcomes.Children.Add(Ui.Button(row.Title+" · "+row.State,()=>OpenJobs(row.State,row.Id)));
        if(manager["updateState"]!=null)outcomes.Children.Add(Ui.Button("Gateway update · "+manager.Text("updateState"),()=>Navigate("Settings/Updates")));
        if(preferences["lastReport"] is JsonValue last)outcomes.Children.Add(Ui.Button("Exported "+last,()=>Run(OpenLastReport)));
        if(outcomes.Children.Count==0)outcomes.Children.Add(Ui.Text("Completed tasks and exports will appear here.",14,"MutedBrush"));
        Pair(page,Card("Needs attention and readiness",attention),Card("Recent outcomes",Ui.Text(CatalogAge,12,"MutedBrush"),outcomes));
    }

    private Border UsageCard()
    {
        var usage=snapshot.Object("usage");
        var days=usage.Array("days").TakeLast(7).ToArray();
        var values=days.Select(d=>d.Number("outputTokens")).ToArray();
        var card=Card("Measured output · seven days",Ui.Text(usage.Number("outputTokens") is double output ? $"{output:N0} tokens" : "Unavailable",23,"PrimaryBrush",true),
            Trend(values,"Output tokens by local day"), Ui.Text(string.Join("   ",days.Select(d=>d.Text("day","").Split('-').Last()+": "+(d.Number("outputTokens")?.ToString("N0")??"—"))),12),
            Ui.Text(usage.Text("unmeasuredRounds","—")+" rounds without reported usage · "+TimeOf(snapshot.Number("capturedAt")),12,"MutedBrush"));
        return card;
    }

    private static FrameworkElement Trend(double?[] values,string label)
    {
        var canvas=new Canvas{Height=52,ClipToBounds=true};AutomationProperties.SetName(canvas,label);
        canvas.SizeChanged+=(_,_)=>{
            canvas.Children.Clear();var nums=values.OfType<double>().ToArray();if(nums.Length==0)return;
            double max=Math.Max(1,nums.Max());Polyline? line=null;
            for(int i=0;i<values.Length;i++){
                if(values[i] is not double v){line=null;continue;}
                if(line==null){line=new Polyline{Stroke=Ui.Brush("PrimaryBrush"),StrokeThickness=2};canvas.Children.Add(line);}
                var point=new Point(3+(canvas.ActualWidth-6)*i/Math.Max(1,values.Length-1),49-v/max*44);line.Points.Add(point);
                var marker=new Ellipse{Width=5,Height=5,Fill=Ui.Brush("PrimaryBrush"),ToolTip=v.ToString("N1")};Canvas.SetLeft(marker,point.X-2.5);Canvas.SetTop(marker,point.Y-2.5);canvas.Children.Add(marker);
            }
        };return canvas;
    }

    private Border Composition()
    {
        var panel=new DockPanel();var ring=new Grid{Width=110,Height=110,Margin=new Thickness(0,0,14,0)};DockPanel.SetDock(ring,Dock.Left);panel.Children.Add(ring);
        var legend=new StackPanel();panel.Children.Add(legend);
        double total=jobCounts.Sum(k=>jobCounts.Number(k.Key)??0),angle=-90;int index=0;
        var colors=new[]{"PrimaryBrush","SecondaryBrush","MutedBrush","TextBrush","LineBrush","PrimaryBrush","MutedBrush"};
        foreach(var (state,_) in jobCounts){
            double n=jobCounts.Number(state)??0;if(n<=0)continue;
            var brush=Ui.Brush(colors[index++%colors.Length]);double end=angle+n/total*360;
            Point P(double a)=>new(55+42*Math.Cos(a*Math.PI/180),55+42*Math.Sin(a*Math.PI/180));
            Shape shape;
            if(n==total)shape=new Ellipse{Width=84,Height=84,StrokeThickness=12,Stroke=brush};
            else{
                var fig=new PathFigure{StartPoint=P(angle)};fig.Segments.Add(new ArcSegment(P(end),new Size(42,42),0,end-angle>180,SweepDirection.Clockwise,true));
                shape=new System.Windows.Shapes.Path{Data=new PathGeometry(new[]{fig}),Stroke=brush,StrokeThickness=12};
            }
            ring.Children.Add(shape);angle=end;
            legend.Children.Add(Ui.Button(state.Replace('_',' ')+"   "+n,()=>OpenJobs(state=="other"?"All":state)));
        }
        var count=Ui.Text(jobCounts.Count==0?"—":total.ToString("N0"),22,bold:true);count.HorizontalAlignment=HorizontalAlignment.Center;count.VerticalAlignment=VerticalAlignment.Center;ring.Children.Add(count);
        return Card("Jobs by state",panel,Ui.Text("Authorized catalog · "+CatalogAge,12,"MutedBrush"));
    }

    private void RefreshDiagnosticValues(){if(selectedPage=="Diagnostics" && Section is "Overview" or "Performance")diagnosticUpdate?.Invoke();}
    private void BuildDiagnostics()
    {
        diagnosticUpdate=null;
        if(Section=="Benchmarks"){BuildBenchmarks();return;}
        if(Section=="Reports"){BuildReports();return;}
        if(Section is "Failures" or "Events"){BuildEvents(Section=="Failures");return;}
        var charts=new StackPanel();page.Children.Add(charts);string section=Section;
        var window=Ui.Select(charts,"Measurement window",new[]{"Last hour","Last 15 minutes","Last 5 minutes"});
        var data=new StackPanel();charts.Children.Add(data);
        void Render(){
            data.Children.Clear();var service=snapshot.Object("service");double cutoff=DateTimeOffset.Now.ToUnixTimeSeconds()-new[]{3600,900,300}[Math.Max(0,window.SelectedIndex)];
            var history=snapshot.Array("measurements").Where(j=>j.Number("at")>=cutoff).ToArray();
            Border Series(string title,string key,string unit){
                var v=history.Select(j=>j.Number(key)).ToArray();var last=v.LastOrDefault();
                return Card(title,Ui.Text(last.HasValue?$"{last:N1} {unit}":"Unavailable",22,"PrimaryBrush",true),Trend(v,title),
                    Ui.Text($"{v.Count(i=>i.HasValue)} measured samples · {TimeOf(history.LastOrDefault()?.Number("at"))}",12,"MutedBrush"));
            }
            if(section=="Overview"){
                Pair(data,Composition(),Card("Gateway requests",Ui.Text(snapshot.Text("requests","—"),28,"PrimaryBrush",true),
                    Ui.Text(snapshot.Text("errors","—")+" HTTP errors · since runtime start",14),Ui.Text("Last observed "+TimeOf(snapshot.Number("capturedAt")),12,"MutedBrush"),
                    Ui.Button("Inspect captured events",()=>Navigate("Diagnostics/Events"))));
                Pair(data,Series("Open workload","openJobs","jobs"),Series("Worker occupancy","workers","active"));
                data.Children.Add(Ui.Actions(Ui.Button("Review failed tasks",()=>OpenJobs("failed")),Ui.Button("Full debug export",()=>OpenReports()),
                    Ui.Button("Model readiness",()=>Navigate("Settings/Models"))));
            }else{
                Pair(data,Series("Worker occupancy","workers","active"),Series("Queue depth","queued","waiting"));
                Pair(data,Series("Model output rate · all timed rounds","tokensPerSecond","tokens/s"),UsageCard());
                Pair(data,Series("Host CPU","cpuPercent","%"),Series("Host memory used","memoryGiB","GiB"));
                var gpu=new StackPanel();foreach(var adapter in history.LastOrDefault().Array("adapters"))
                    gpu.Children.Add(Ui.Text($"{adapter.Text("name")} · {adapter.Text("gpuPercent")}% GPU · {adapter.Text("vramMiB")} MiB VRAM"));
                if(gpu.Children.Count==0)gpu.Children.Add(Ui.Text("GPU and VRAM measurements unavailable for this adapter.",13,"MutedBrush"));
                var latency=new StackPanel();
                void Percentiles(string title, IEnumerable<double?> values){
                    var v=values.OfType<double>().Where(n=>n>=0).Order().ToArray();
                    string P(double q)=>v.Length==0?"—":v[(int)Math.Ceiling(q*v.Length)-1].ToString("N0");
                    latency.Children.Add(Ui.Text($"{title}  ·  p50 {P(.5)} ms  ·  p95 {P(.95)} ms  ·  n={v.Length}",13));
                }
                var rows=jobs.Where(j=>j.Kind!="host receipt" && j.Data.Number("accepted_at")>=cutoff).Select(j=>j.Data).ToArray();
                Percentiles("Queue wait",rows.Select(j=>(j.Number("started_at")-j.Number("queued_at"))*1000));
                Percentiles("Completion",rows.Select(j=>(j.Number("finished_at")-j.Number("accepted_at"))*1000));
                Percentiles("First public text",rows.Select(j=>j.Number("first_text_ms")));
                Pair(data,Card("Latency · loaded catalog",latency),Card("Graphics adapters",gpu));
            }
        }
        diagnosticUpdate=Render;window.SelectionChanged+=(_,_)=>Render();Render();
    }

    private void BuildEvents(bool failures)
    {
        var body=new DockPanel();var controls=new WrapPanel();DockPanel.SetDock(controls,Dock.Top);body.Children.Add(controls);
        var search=new TextBox{Width=190,ToolTip="Filter by event, component, job digest or status"};AutomationProperties.SetName(search,"Filter captured events");controls.Children.Add(search);
        var period=new ComboBox{ItemsSource=new[]{"All retained","Last hour","Last 24 hours"},SelectedIndex=0};controls.Children.Add(period);
        var note=Ui.Text("Reading retained transport events…",13,"MutedBrush");DockPanel.SetDock(note,Dock.Bottom);body.Children.Add(note);
        var table=Table(failures?"Grouped captured failures":"Captured transport events");body.Children.Add(table);
        foreach(var key in failures?new[]{"Category","Count","Last"}:new[]{"Time","Component","Event","Status","Sequence"})Column(table,key,key,-1);
        JsonArray rows=[]; JsonObject capture=[];
        void Filter(){
            var cut=period.SelectedIndex switch{1=>DateTimeOffset.Now.AddHours(-1).ToUnixTimeMilliseconds(),2=>DateTimeOffset.Now.AddDays(-1).ToUnixTimeMilliseconds(),_=>0};
            var filtered=rows.OfType<JsonObject>().Where(r=>r.Number("timestampMs")>=cut && (search.Text.Length==0 || r.ToJsonString().Contains(search.Text,StringComparison.OrdinalIgnoreCase))).Reverse().ToArray();
            if(failures)table.ItemsSource=filtered.Where(r=>r.Number("status")>=400 || r.Text("event","").Contains("fail",StringComparison.OrdinalIgnoreCase)).GroupBy(r=>r.Text("route",r.Text("event"))+" · "+r.Text("status","failure"))
                .Select(g=>new {Category=g.Key,Count=g.Count(),Last=TimeOf(g.First().Number("timestampMs")/1000),Receipt=(JsonNode)new JsonArray(g.Take(32).Select(j=>j.DeepClone()).ToArray())}).OrderByDescending(g=>g.Count).ToArray();
            else table.ItemsSource=filtered.Select(r=>new{Time=TimeOf(r.Number("timestampMs")/1000),Component=r.Text("route",r.Text("component","Gateway")),Event=r.Text("event"),Status=r.Text("status","—"),Sequence=r.Text("sequence"),Receipt=(JsonNode)r}).ToArray();
            note.Text=capture.Flag("available")?$"{filtered.Length} matching captured records · {rows.Count} loaded of {capture.Text("retained")} retained · successes sampled · observed {TimeOf(capture.Number("observed_at"))}":"No retained transport log. Enable debug recording in Settings → Advanced.";
        }
        async Task Load(){capture=await Call("report-events");rows=capture.Array("items");Filter();}
        void Open(){if(table.SelectedItem is {} s && s.GetType().GetProperty("Receipt")?.GetValue(s) is JsonNode r)ShowDetails("Captured evidence",r);}
        controls.Children.Add(Ui.Button("Refresh",()=>Run(Load)));controls.Children.Add(Ui.Button("Inspect evidence",Open));controls.Children.Add(Ui.Button("Full debug export",()=>OpenReports(period:Math.Max(0,period.SelectedIndex))));
        search.TextChanged+=(_,_)=>Filter();period.SelectionChanged+=(_,_)=>Filter();table.MouseDoubleClick+=(_,_)=>Open();FillWindow(body);Run(Load);
    }

    private void BuildReports()
    {
        var body=Ui.Column(Ui.Text("Diagnostic report",21,bold:true));
        var window=Ui.Select(body,"Coverage",new[]{"All retained","Last hour","Last 24 hours","Last seven days"});
        window.SelectedIndex=reportPeriod;
        var job=Ui.Field(body,"Limit to job ID (optional)",reportJob);
        var summary=Ui.Text("Prepare a preview to inspect included sources, redaction and coverage.",14,"MutedBrush");body.Children.Add(summary);
        JsonObject? preview=null;
        var export=Ui.Button("Export reviewed ZIP",()=>Run(async()=>{
            if(preview is null)return;
            var picker=new SaveFileDialog{FileName="gateway-debug.zip",Filter="Diagnostic ZIP|*.zip"};if(picker.ShowDialog(this)!=true)return;
            string sha=preview.Text("sha256");if(sha.Length!=64||!sha.All(Uri.IsHexDigit))throw new InvalidOperationException("Invalid report receipt.");
            using var source=File.OpenRead(System.IO.Path.Combine(root,"data","report-previews",sha+".zip"));
            if(!Convert.ToHexString(await SHA256.HashDataAsync(source)).Equals(sha,StringComparison.OrdinalIgnoreCase))throw new InvalidOperationException("Preview changed. Prepare it again.");
            source.Position=0;string temp=picker.FileName+"."+Guid.NewGuid().ToString("N")+".tmp";
            try{using(var target=File.Create(temp))await source.CopyToAsync(target);File.Move(temp,picker.FileName,true);}finally{File.Delete(temp);}
            preferences["lastReportPath"]=picker.FileName;preferences["lastReportHash"]=sha;
            preferences["lastReport"]=System.IO.Path.GetFileName(picker.FileName);SavePreferences();status.Text="Exported "+picker.FileName+" · SHA-256 "+sha;
        }),true);export.IsEnabled=false;
        int revision=0;
        void Reset(){revision++;preview=null;export.IsEnabled=false;}
        window.SelectionChanged+=(_,_)=>Reset();job.TextChanged+=(_,_)=>Reset();
        body.Children.Add(Ui.Actions(Ui.Button("Preview included data",()=>Run(async()=>{
            int version=++revision;export.IsEnabled=false;summary.Text="Preparing the retained sources…";
            var result=await Call("report-preview",new JsonObject{["hours"]=new[]{0,1,24,168}[window.SelectedIndex],["job"]=job.Text.Trim()});
            if(version!=revision)return;preview=result;var m=result.Object("manifest");
            summary.Text=string.Join("\n",m.Array("files").Select(f=>f.Text("path")+" · "+f.Text("records")+" records"))+
                $"\n{m.Text("redaction")} · {m.Array("versions").Count} recorded versions · missing: "+(m.Array("missingSources").Count==0?"none":m.Array("missingSources").ToJsonString())+
                "\nTransport success polls are sampled. Full retention and gap details appear in the manifest.";
            export.IsEnabled=true;
        })),Ui.Button("Inspect manifest",()=>{if(preview!=null)ShowDetails("Report manifest",preview.Object("manifest"));}),export));
        page.Children.Add(Ui.Card(body));
    }
}
