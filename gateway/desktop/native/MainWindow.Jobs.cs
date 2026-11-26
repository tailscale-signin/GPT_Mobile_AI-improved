using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Media;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private sealed class JobRow(JsonObject value) : INotifyPropertyChanged
    {
        public JsonObject Data { get; private set; } = value;
        public string Id => Data.Text("job_id");
        public string Title => Data.Text("title", Id);
        public string Kind => Data.Text("job_kind");
        public string State => Data.Text("state");
        public string Stage
        {
            get
            {
                string phase=Data.Text("phase",State).Replace('_',' ');
                var batch=Data.Object("batch_progress");
                return batch.Number("completed") is double done && batch.Number("total") is double total
                    && total>0 && done>=0 && done<=total ? $"{phase} · {done:N0}/{total:N0}" : phase;
            }
        }
        public string Repository => string.Join(" · ", new[] {Data.Text("repository",""),Data.Text("branch","")}.Where(s => s.Length > 0));
        public string Model => Data.Text("profile_name",Data.Text("model","—"));
        public string Next => Data.Text("recovery_action",Data.Text("queue_reason", State switch {
            "needs_attention" => "Review receipt", "queued" => "Await worker", "running" => "Observe",
            "completed" => "View output", "failed" => "Inspect evidence", _ => "View receipt" })).Replace('_',' ');
        public string Elapsed => Data.Number("accepted_at") is double start && (Data.Number("finished_at") ?? Data.Number("server_now")) is double end
            ? TimeSpan.FromSeconds(Math.Max(0, end-start)).ToString(end-start>=86400 ? @"d\.hh\:mm" : @"hh\:mm\:ss") : "—";
        public bool Allows(string action) => Data.Array("allowed_actions").Any(v => v?.ToString() == action);
        public event PropertyChangedEventHandler? PropertyChanged;
        public void Update(JsonObject row)
        {
            if (JsonNode.DeepEquals(row,Data)) return;
            Data = row;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(null));
        }
    }

    private readonly ObservableCollection<JobRow> jobs = [];
    private readonly Dictionary<string, JobRow> knownJobs = [];
    private readonly HashSet<string> selectedJobs = [];
    private JsonObject jobCounts = [];
    private string queuePressure = "";
    private DateTime catalogAttempt, catalogObserved;
    private bool catalogBusy, catalogMore, catalogOffline, syncingSelection;
    private int catalogPages = 1;
    private string jobsQuery = "", jobsFilter = "All", inspectorTab = "Summary", focusedJob = "";
    private DataGrid? jobsTable;
    private Action? updateJobFooter, updateInspector;
    private TextBlock? catalogNote;
    private readonly HashSet<string> pins = [];
    private readonly Dictionary<string,(string Id,double Horizontal)> tablePositions=[];
    private string TablePositionKey(string tab)=>tab+"|"+jobsFilter+"|"+jobsQuery;
    private void RememberJobsPosition(string tab)
    {
        if (jobsTable is null || !jobsTable.IsVisible) return;
        if (FullyVisibleRows(jobsTable).FirstOrDefault()?.Item is JobRow first)
            tablePositions[TablePositionKey(tab)]=(first.Id,Ui.Descendants<ScrollViewer>(jobsTable).FirstOrDefault()?.HorizontalOffset??0);
    }
    private static readonly string[] JobStates = ["All","Active","running","queued","needs_attention","completed","failed","cancelled","Pinned","Host receipts"];

    private async Task RefreshCatalog()
    {
        if (catalogBusy || closed) return;
        catalogAttempt = DateTime.UtcNow; catalogBusy = true;
        try
        {
            await EnsureConnected();
            var source = identity!.DeepClone(); var rows = new List<JsonObject>(); var cursors = new HashSet<string>();
            JsonObject? first = null; string? cursor = null;
            for (int i=0; i<catalogPages; i++)
            {
                var result = await Call("job-catalog", WithIdentity(new JsonObject { ["cursor"] = cursor }));
                if (!JsonNode.DeepEquals(source, identity)) return;
                first ??= result;
                rows.AddRange(result.Array("items").OfType<JsonObject>());
                cursor = result.Flag("has_more") ? result["next_cursor"]?.ToString() : null;
                if (cursor is null) break;
                if (!cursors.Add(cursor)) throw new InvalidOperationException("The Gateway repeated a catalog cursor.");
            }
            catalogMore = cursor is not null; catalogOffline = false;
            catalogObserved = DateTime.UtcNow;
            jobCounts = first?.Flag("counts_complete") == true ? first.Object("state_counts") : [];
            var pressure = first?.Object("queue_pressure");
            queuePressure = pressure?.Flag("saturated") == true
                ? $" · Pending generations {pressure.Text("occupied")}/{pressure.Text("capacity")}. Cancel an owned queued job or wait for a worker."
                : "";
            SyncJobs(rows);
        }
        catch (Exception error) { catalogOffline = true; if (selectedPage == "Jobs") status.Text = error.Message; }
        finally
        {
            catalogBusy = false;
            if (selectedPage == "Jobs") { updateJobFooter?.Invoke(); updateInspector?.Invoke(); }
            if (selectedPage == "Overview") RefreshOverview();
            if (selectedPage == "Diagnostics") RefreshDiagnosticValues();
        }
    }

    private void SyncJobs(List<JsonObject> records)
    {
        var seen = new HashSet<string>();
        var table = selectedPage == "Jobs" ? jobsTable : null;
        var view = CollectionViewSource.GetDefaultView(jobs);
        var scroll = table == null ? null : Ui.Descendants<ScrollViewer>(table).FirstOrDefault();
        var visibleBefore = table == null ? [] : view.Cast<JobRow>().Select(j => j.Id).ToArray();
        var anchor = table is null ? null : FullyVisibleRows(table).FirstOrDefault()?.Item as JobRow;
        double offset = scroll?.HorizontalOffset ?? 0;
        var order = new List<JobRow>();
        syncingSelection = true;
        try
        {
            foreach (var record in records)
            {
                var id = record.Text("job_id"); if (!seen.Add(id)) continue;
                if (knownJobs.TryGetValue(id, out var old)) old.Update(record);
                else { var row = new JobRow(record); knownJobs[id] = row; jobs.Add(row); }
                order.Add(knownJobs[id]);
            }
            // Manager-owned observations can include other phones. They are read-only and have their own filter.
            foreach (var item in snapshot.Array("engineering").OfType<JsonObject>())
            {
                string id = item.Text("id");
                if (!seen.Add(id)) continue;
                var retained = new JsonObject { ["job_id"]=id, ["title"]=item.Text("title"), ["state"]=item.Text("status").ToLowerInvariant(),
                    ["job_kind"]="host receipt", ["phase"]=item.Text("status"), ["branch"]=item.Text("branch",""), ["allowed_actions"]=new JsonArray("open") };
                if (knownJobs.TryGetValue(id, out var existing)) existing.Update(retained);
                else { var row = new JobRow(retained); jobs.Add(row); knownJobs[id]=row; }
                order.Add(knownJobs[id]);
            }
            foreach (var id in knownJobs.Keys.Where(id => !seen.Contains(id)).ToArray()) { jobs.Remove(knownJobs[id]); knownJobs.Remove(id); }
            for (int index = 0; index < order.Count; index++)
                if (jobs[index] != order[index]) jobs.Move(jobs.IndexOf(order[index]),index);
            if (table is not null)
            {
                var visibleIds = view.Cast<JobRow>().Select(row => row.Id).ToHashSet();
                if (jobs.Any(row => (view.Filter?.Invoke(row) ?? true) != visibleIds.Contains(row.Id))) view.Refresh();
                foreach (JobRow row in view)
                    if (selectedJobs.Contains(row.Id) && !table.SelectedItems.Contains(row)) table.SelectedItems.Add(row);
                if (anchor != null && scroll != null && !visibleBefore.SequenceEqual(view.Cast<JobRow>().Select(row => row.Id)))
                {
                    int anchorIndex = view.Cast<JobRow>().TakeWhile(row => row.Id != anchor.Id).Count();
                    if (anchorIndex < table.Items.Count) scroll.ScrollToVerticalOffset(anchorIndex);
                    scroll.ScrollToHorizontalOffset(offset);
                }
            }
        }
        finally { syncingSelection = false; }
    }

    private void OpenJobs(string filter="All", string id="")
    {
        jobsFilter=filter; focusedJob=id;
        if (id.Length>0) { selectedJobs.Clear(); selectedJobs.Add(id); }
        Navigate("Jobs/Tasks");
    }

    private static DataGrid Table(string label)
    {
        var table = new DataGrid { AutoGenerateColumns=false, CanUserAddRows=false, CanUserDeleteRows=false, IsReadOnly=true,
            EnableRowVirtualization=true, EnableColumnVirtualization=true, HeadersVisibility=DataGridHeadersVisibility.Column,
            GridLinesVisibility=DataGridGridLinesVisibility.Horizontal, RowHeaderWidth=0, Margin=new Thickness(0,4,0,0),
            HorizontalGridLinesBrush=Ui.Brush("LineBrush"), SelectionMode=DataGridSelectionMode.Extended,
            SelectionUnit=DataGridSelectionUnit.FullRow, FontSize=12.5, RowHeight=24, ColumnHeaderHeight=27,
            Background=Ui.Brush("PanelBrush"), Foreground=Ui.Brush("TextBrush"), RowBackground=Ui.Brush("PanelBrush"),
            AlternatingRowBackground=Ui.Brush("CanvasBrush"), BorderBrush=Ui.Brush("LineBrush") };
        VirtualizingPanel.SetVirtualizationMode(table, VirtualizationMode.Recycling);
        ScrollViewer.SetCanContentScroll(table,true); AutomationProperties.SetName(table,label);
        return table;
    }
    private static DataGridRow[] FullyVisibleRows(DataGrid table)
    {
        if (!table.IsVisible) return [];
        var viewport = Ui.Descendants<ScrollContentPresenter>(table).FirstOrDefault();
        if (viewport == null || viewport.ActualHeight <= 0) return [];
        return Ui.Descendants<DataGridRow>(viewport)
            .Where(row => row.IsVisible && row.ActualHeight > 0 && row.Item is JobRow)
            .Select(row => (row, top: row.TranslatePoint(new Point(0,0),viewport).Y))
            .Where(pair => pair.top >= -0.5 && pair.top + pair.row.ActualHeight <= viewport.ActualHeight + 0.5)
            .OrderBy(pair => pair.top).Select(pair => pair.row).ToArray();
    }

    private static DataGridTextColumn Column(DataGrid table, string title, string prop, double width=90)
    {
        var col = new DataGridTextColumn { Header=title, Binding=new Binding(prop), Width=width < 0 ? new DataGridLength(-width,DataGridLengthUnitType.Star) : new DataGridLength(width),
            MinWidth=38 };
        table.Columns.Add(col); return col;
    }
    private string CatalogAge => catalogObserved == default ? "Catalog unavailable" :
        $"{(catalogOffline || (DateTime.UtcNow-catalogObserved).TotalSeconds>20 ? "Last known" : "Observed")} {catalogObserved.ToLocalTime():T}";

    private void BuildJobs()
    {
        bool approvals = Section == "Approvals";
        var grid = new Grid();
        grid.RowDefinitions.Add(new(){Height=GridLength.Auto}); grid.RowDefinitions.Add(new()); grid.RowDefinitions.Add(new(){Height=GridLength.Auto});
        var bar = new WrapPanel();
        var query = new TextBox { Text=jobsQuery, Width=195, ToolTip="Filter by task, repository, model or ID" };
        AutomationProperties.SetName(query,"Filter jobs"); bar.Children.Add(query);
        var filter = new ComboBox { ItemsSource=JobStates, SelectedItem=jobsFilter, Width=135 }; AutomationProperties.SetName(filter,"Job state"); bar.Children.Add(filter);
        bar.Children.Add(Ui.Button("Refresh", () => Run(RefreshCatalog)));
        var more=Ui.Button("Load more", () => { if (!catalogBusy && catalogMore) {catalogPages++; Run(RefreshCatalog);} }); bar.Children.Add(more);
        grid.Children.Add(bar);
        var area = new Grid(); area.ColumnDefinitions.Add(new()); area.ColumnDefinitions.Add(new(){Width=new GridLength(275)});
        Grid.SetRow(area,1);grid.Children.Add(area);
        var table = jobsTable = Table(approvals ? "Approval queue" : "Gateway jobs"); table.RowHeight=preferences.Flag("comfortable") ? 34 : 24;
        if (preferences.Flag("comfortable")) table.FontSize=14;
        var view = CollectionViewSource.GetDefaultView(jobs);
        bool Visible(JobRow j) => (approvals ? j.Allows("approve") : jobsFilter=="Host receipts" ? j.Kind=="host receipt" : jobsFilter=="Pinned" ? pins.Contains(j.Id) :
            j.Kind!="host receipt" && (jobsFilter=="All" || j.State==jobsFilter || jobsFilter=="Active" && (j.State is "queued" or "running" or "needs_attention")))
            && (jobsQuery.Length==0 || new[]{j.Title,j.Id,j.State,j.Repository,j.Model,j.Stage}.Any(x=>x.Contains(jobsQuery,StringComparison.OrdinalIgnoreCase)));
        view.Filter = obj => obj is JobRow j && Visible(j); table.ItemsSource = view;
        var mark = new FrameworkElementFactory(typeof(CheckBox));
        mark.SetBinding(ToggleButton.IsCheckedProperty,new Binding("IsSelected"){RelativeSource=new RelativeSource(RelativeSourceMode.FindAncestor,typeof(DataGridRow),1),Mode=BindingMode.TwoWay});
        mark.SetValue(AutomationProperties.NameProperty,"Select task"); mark.SetValue(FrameworkElement.HorizontalAlignmentProperty,HorizontalAlignment.Center);
        table.Columns.Add(new DataGridTemplateColumn { Header="✓", Width=33, CellTemplate=new DataTemplate{VisualTree=mark} });
        Column(table,"Task","Title",-2); Column(table,"Type","Kind",76); Column(table,"State","State",100); Column(table,"Stage","Stage",-1);
        var repository=Column(table,"Repository / branch","Repository",-2); var model=Column(table,"Profile / model","Model",-1);
        var elapsed=Column(table,"Elapsed","Elapsed",76); Column(table,"Next action","Next",-1);
        area.Children.Add(table);
        var inspector = new StackPanel { Margin=new Thickness(10,0,0,0) };
        var side = new ScrollViewer{Content=inspector,VerticalScrollBarVisibility=ScrollBarVisibility.Auto}; Grid.SetColumn(side,1);area.Children.Add(side);
        bool narrowDetail=false;
        var details=new Button{Content="Show inspector"}; details.Click+=(_,_)=>{narrowDetail=!narrowDetail; Resize();}; bar.Children.Add(details);
        void Resize()
        {
            bool narrow=area.ActualWidth < 760;
            details.Visibility=narrow ? Visibility.Visible : Visibility.Collapsed;
            details.Content=narrowDetail?"Back to table":"Show inspector"; side.Visibility=!narrow || narrowDetail ? Visibility.Visible : Visibility.Collapsed;
            table.Visibility=narrow && narrowDetail ? Visibility.Collapsed : Visibility.Visible;
            area.ColumnDefinitions[1].Width=new GridLength(narrow ? 0 : 275); Grid.SetColumn(side,narrow ? 0 : 1);
            repository.Visibility=area.ActualWidth<950 ? Visibility.Collapsed : Visibility.Visible;
            model.Visibility=area.ActualWidth<1050 ? Visibility.Collapsed : Visibility.Visible;
            elapsed.Visibility=area.ActualWidth<700 ? Visibility.Collapsed : Visibility.Visible;
        }
        area.SizeChanged+=(_,_)=>Resize();
        catalogNote=Ui.Text("",12,"MutedBrush"); Grid.SetRow(catalogNote,2); grid.Children.Add(catalogNote);
        void Footer()
        {
            int visible=FullyVisibleRows(table).Length;
            catalogNote.Text=$"{visible} fully visible · {table.Items.Count} matching · {selectedJobs.Count} selected · {CatalogAge}{queuePressure}";
            more.IsEnabled=catalogMore && !catalogBusy;
        }
        updateJobFooter=Footer;
        table.AddHandler(ScrollViewer.ScrollChangedEvent,new ScrollChangedEventHandler((_,_)=>Footer()));
        void Inspect()
        {
            var row=knownJobs.GetValueOrDefault(focusedJob);
            if (inspector.Tag as string == $"{focusedJob}|{row?.Data.Text("revision")}|{row?.State}|{row?.Next}|{inspectorTab}|{string.Join(',',selectedJobs)}|{string.Join(',',pins)}") return;
            inspector.Tag=$"{focusedJob}|{row?.Data.Text("revision")}|{row?.State}|{row?.Next}|{inspectorTab}|{string.Join(',',selectedJobs)}|{string.Join(',',pins)}";
            inspector.Children.Clear();
            var choices=selectedJobs.Concat(pins).Distinct().Where(knownJobs.ContainsKey).Select(id=>knownJobs[id]).ToList();
            if (choices.Count>0)
            {
                var selector=new ComboBox{ItemsSource=choices,DisplayMemberPath="Title",SelectedItem=row}; AutomationProperties.SetName(selector,"Inspect selected or pinned task");
                selector.SelectionChanged+=(_,_)=>{if(selector.SelectedItem is JobRow pick){focusedJob=pick.Id;Inspect();}};inspector.Children.Add(selector);
            }
            if (row is null) {inspector.Children.Add(Ui.Text("Select a task to inspect its activity and output.",14,"MutedBrush"));return;}
            inspector.Children.Add(Ui.Text(row.Title,18,bold:true));
            inspector.Children.Add(Ui.Text(row.Repository+"  "+row.Model,12,"MutedBrush"));
            var tabs=new WrapPanel();
            foreach(string tab in new[]{"Summary","Activity","Output","Evidence","Checkpoints","Compare"})
                tabs.Children.Add(Ui.Button(tab,()=>{inspectorTab=tab;Inspect();},inspectorTab==tab));
            inspector.Children.Add(tabs);
            if(inspectorTab=="Compare") foreach(var job in choices) inspector.Children.Add(Ui.Text($"{job.Title} · {job.State} · {job.Next}",13));
            else if(inspectorTab=="Summary") inspector.Children.Add(Ui.Text($"{row.Stage} · {row.Elapsed}\n{row.Next}",14));
            else if(inspectorTab=="Activity")
            {
                foreach(var a in snapshot.Array("activities").Where(a=>a.Text("job")==row.Id)) inspector.Children.Add(Ui.Text(a.Text("title")+" · "+a.Text("status"),13));
                inspector.Children.Add(Ui.Button("Read recorded activity",()=>Run(()=>JobDetail(row,"Activity"))));
            }
            else inspector.Children.Add(Ui.Button("Open "+inspectorTab.ToLowerInvariant(),()=>Run(()=>JobDetail(row,inspectorTab))));
            inspector.Children.Add(Ui.Actions(Ui.Button(pins.Contains(row.Id)?"Unpin task":"Pin task",()=>{
                if(!pins.Remove(row.Id)) pins.Add(row.Id); SavePreferences(); Inspect();}),
                Ui.Button("View receipt",()=>Run(()=>JobDetail(row,"Summary")))));
            var cancel=Ui.Button("Cancel selected",()=>Run(async()=>{
                var selected=selectedJobs.Where(knownJobs.ContainsKey).Select(id=>knownJobs[id]).ToArray();
                var eligible=selected.Where(j=>j.Allows("cancel")&&!j.Data.Flag("cancellation_requested")).ToArray();
                if(eligible.Length==0 || !Confirm($"Request cancellation of {eligible.Length} selected task(s)?"))return;
                var results=new JsonArray();
                foreach(var j in selected)
                {
                    if(!eligible.Contains(j)){results.Add(new JsonObject{["job"]=j.Id,["outcome"]="unsupported"});continue;}
                    try{var receipt=await Call("job-cancel",WithIdentity(new JsonObject{["job_id"]=j.Id,["revision"]=j.Data["revision"]?.DeepClone()}));
                        results.Add(new JsonObject{["job"]=j.Id,["outcome"]="request acknowledged",["receipt"]=receipt});}
                    catch(Exception e){results.Add(new JsonObject{["job"]=j.Id,["outcome"]="unconfirmed",["message"]=e.Message});}
                }
                ShowDetails("Cancellation receipts",results);await RefreshCatalog();
            }));
            cancel.IsEnabled=selectedJobs.Any(id=>knownJobs.TryGetValue(id,out var j) && j.Allows("cancel")&&!j.Data.Flag("cancellation_requested"));
            inspector.Children.Add(cancel);
            inspector.Children.Add(Ui.Button("Full debug export",()=>OpenReports(row.Id)));
            if(row.Data["conversation_id"] is JsonValue cv) inspector.Children.Add(Ui.Button("Open conversation",()=>Run(()=>OpenConversation(cv.ToString()))));
            if(row.Allows("approve")) inspector.Children.Add(Ui.Button("Review merge",()=>Run(()=>ReviewEngineering(row)),true));
            if(row.Kind=="engineering") inspector.Children.Add(Ui.Button("Open agent workspace",()=>Run(()=>AgentWorkspace(row)),true));
        }
        table.SelectionChanged+=(_,e)=>{
            if(syncingSelection)return;
            foreach(JobRow removed in e.RemovedItems) selectedJobs.Remove(removed.Id);
            foreach(JobRow added in e.AddedItems){selectedJobs.Add(added.Id);focusedJob=added.Id;}
            if(e.AddedItems.Count==0 && !selectedJobs.Contains(focusedJob) && !pins.Contains(focusedJob)) focusedJob=selectedJobs.FirstOrDefault()??"";
            Inspect();Footer();
        };
        void Refilter(){syncingSelection=true;try{view.Refresh();foreach(JobRow j in view)if(selectedJobs.Contains(j.Id)&&!table.SelectedItems.Contains(j)) table.SelectedItems.Add(j);}finally{syncingSelection=false;}Footer();}
        query.TextChanged+=(_,_)=>{jobsQuery=query.Text.Trim();Refilter();}; filter.SelectionChanged+=(_,_)=>{jobsFilter=filter.SelectedItem?.ToString()??"All";Refilter();};
        updateInspector=Inspect; Refilter();Inspect();FillWindow(grid);
        string positionKey=TablePositionKey(Section);
        table.Loaded+=(_,_)=>{
            if(tablePositions.TryGetValue(positionKey,out var position) && knownJobs.TryGetValue(position.Id,out var top) && view.Contains(top)){
                int index=view.Cast<JobRow>().TakeWhile(row=>row!=top).Count();
                var scroll=Ui.Descendants<ScrollViewer>(table).FirstOrDefault();
                scroll?.ScrollToVerticalOffset(index); scroll?.ScrollToHorizontalOffset(position.Horizontal);
            } else if(focusedJob.Length>0 && knownJobs.TryGetValue(focusedJob,out var focus)) table.ScrollIntoView(focus);
        };
    }

    private bool Confirm(string message) => MessageBox.Show(this,message,"Gateway",MessageBoxButton.YesNo,MessageBoxImage.Question,MessageBoxResult.No)==MessageBoxResult.Yes;
    private async Task JobDetail(JobRow row,string tab)
    {
        await EnsureConnected();
        if(row.Kind=="engineering" && tab=="Checkpoints"){await Checkpoints(row);return;}
        if(row.Kind=="engineering" && tab is "Evidence" or "Activity")
        {
            var result=await Call("engineering-inspect",WithIdentity(new JsonObject{["job_id"]=row.Id,["section"]=tab}));
            ShowDetails(row.Title+" · "+tab,result);return;
        }
        var receipt=await Call("job-read",WithIdentity(new JsonObject{["job_id"]=row.Id}));
        ShowDetails(row.Title+" · "+tab,tab=="Output" ? receipt["result"]??receipt["workspace_outputs"]??receipt : receipt);
    }
}
