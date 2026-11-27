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
        public bool HostReceipt => Data.Flag("host_receipt");
        public string State => Data.Text("state");
        public string CancellationStatus { get; private set; } = "";
        public string ReconciliationAction { get; private set; } = "";
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
        public string Next => Data.Flag("cancellation_requested") ? "Cancellation pending · waiting for the job to stop" :
            CancellationStatus.Length > 0 ? CancellationStatus : Data.Object("progress").Text("message", Data.Text("recovery_action",Data.Text("queue_reason", State switch {
            "needs_attention" => "Review receipt", "queued" => "Await worker", "running" => "Observe",
            "completed" => "View output", "failed" => "Inspect evidence", _ => "View receipt" }))).Replace('_',' ');
        public string Elapsed => Data.Number("accepted_at") is double start && (Data.Number("finished_at") ?? Data.Number("server_now")) is double end
            ? TimeSpan.FromSeconds(Math.Max(0, end-start)).ToString(end-start>=86400 ? @"d\.hh\:mm" : @"hh\:mm\:ss") : "—";
        public bool Allows(string action) => Data.Array("allowed_actions").Any(v => v?.ToString() == action);
        public event PropertyChangedEventHandler? PropertyChanged;
        public void ObserveCancellation(JsonObject receipt)
        {
            CancellationStatus = JobQueuePresentation.CancellationLabel(receipt);
            ObserveReconciliation(receipt);
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Next)));
        }
        public void ObserveReconciliation(JsonObject receipt) => ReconciliationAction = receipt.Flag("reconciliation_required") ? receipt.Text("reconciliation_action", "Inspect the original operation receipts.") : "";
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
    private bool catalogBusy, catalogMore, catalogOffline, syncingSelection, hostCatalogMore, hostCatalogUnavailable, jobControlBusy;
    private int catalogPages = 1, hostCatalogPages = 1;
    private string jobsQuery = "", jobsFilter = "All", inspectorTab = "Summary", focusedJob = "";
    private DataGrid? jobsTable;
    private Action? updateJobFooter, updateInspector;
    private TextBlock? catalogNote;
    private readonly HashSet<string> pins = [];
    private readonly HashSet<string> removedJobs = [];
    private readonly Dictionary<string, JsonObject> jobCancellationRequests = [];
    private readonly Dictionary<string,(string Id,double Horizontal)> tablePositions=[];
    private string TablePositionKey(string tab)=>tab+"|"+jobsFilter+"|"+jobsQuery;
    private void RememberJobsPosition(string tab)
    {
        if (jobsTable is null || !jobsTable.IsVisible) return;
        if (FullyVisibleRows(jobsTable).FirstOrDefault()?.Item is JobRow first)
            tablePositions[TablePositionKey(tab)]=(first.Id,Ui.Descendants<ScrollViewer>(jobsTable).FirstOrDefault()?.HorizontalOffset??0);
    }
    private static readonly string[] JobStates = ["All","Active","running","queued","needs_attention","completed","failed","cancelled","interrupted","Pinned","Host receipts","Removed"];

    private async Task RefreshCatalog()
    {
        if (catalogBusy || closed) return;
        catalogAttempt = DateTime.UtcNow; catalogBusy = true; queuePressure = "";
        try
        {
            await EnsureConnected();
            var source = identity!.DeepClone();
            var rows = jobs.Where(row => !row.HostReceipt).Select(row => (JsonObject)row.Data.DeepClone()).ToList();
            var hostRows = new List<JsonObject>();
            string observedQueuePressure = "";
            catalogOffline = false;
            try
            {
                var loaded = new List<JsonObject>(); var cursors = new HashSet<string>(); string? cursor = null; JsonObject? firstOwned = null;
                for (int i=0; i<catalogPages; i++)
                {
                    var result = await Call("job-catalog", WithIdentity(new JsonObject { ["cursor"] = cursor }));
                    if (!JsonNode.DeepEquals(source, identity)) return;
                    firstOwned ??= result;
                    loaded.AddRange(result.Array("items").OfType<JsonObject>());
                    cursor = result.Flag("has_more") ? result["next_cursor"]?.ToString() : null;
                    if (cursor is null) break;
                    if (!cursors.Add(cursor)) throw new InvalidOperationException("The Gateway repeated a catalog cursor.");
                }
                rows = loaded; catalogMore = cursor is not null;
                var pressure = firstOwned?.Object("queue_pressure");
                observedQueuePressure = pressure?.Flag("saturated") == true
                    ? $" · Pending generations {pressure.Text("occupied")}/{pressure.Text("capacity")}. Cancel an owned queued job or wait for a worker."
                    : "";
            }
            catch (Exception) { catalogOffline = true; }
            try
            {
                var cursors = new HashSet<string>(); string? cursor = null; JsonObject? first = null;
                for (int i=0; i<hostCatalogPages; i++)
                {
                    var result = await Call("job-owner-list", WithIdentity(new JsonObject { ["cursor"] = cursor }));
                    if (!JsonNode.DeepEquals(source, identity)) return;
                    if (result.Text("gateway_id", "") != identity.Text("gateway_id", "")) throw new InvalidOperationException("Host inventory changed Gateway.");
                    first ??= result;
                    hostRows.AddRange(result.Array("items").OfType<JsonObject>().Select(item => JobQueuePresentation.HostRow(item, item.Text("job_kind"), identity, result.Number("server_now"))));
                    cursor = result.Flag("has_more") ? result["next_cursor"]?.ToString() : null;
                    if (cursor is null) break;
                    if (!cursors.Add(cursor)) throw new InvalidOperationException("The Gateway repeated a host catalog cursor.");
                }
                hostCatalogMore = cursor is not null; hostCatalogUnavailable = false;
                jobCounts = first?.Flag("counts_complete") == true ? first.Object("state_counts") : [];
            }
            catch (Exception)
            {
                hostCatalogUnavailable = true; hostCatalogMore = false; jobCounts = [];
                hostRows.Clear();
                foreach (var item in snapshot.Array("jobs").OfType<JsonObject>())
                    hostRows.Add(JobQueuePresentation.HostRow(item, "generation", identity, snapshot.Number("capturedAt")));
                foreach (var item in snapshot.Array("engineering").OfType<JsonObject>())
                    hostRows.Add(JobQueuePresentation.HostRow(item, item.Text("execution_mode", "") == "copilot" ? "copilot" : "engineering", identity, snapshot.Number("capturedAt")));
                hostRows.AddRange(jobs.Where(row => row.HostReceipt).Select(row => (JsonObject)row.Data.DeepClone()));
            }
            if (!JsonNode.DeepEquals(source, identity)) return;
            catalogObserved = DateTime.UtcNow;
            queuePressure = observedQueuePressure;
            SyncJobs(rows, hostRows);
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

    private void SyncJobs(List<JsonObject> records, List<JsonObject> hostRecords)
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
                record["principal_id"] ??= identity?["principal_id"]?.DeepClone();
                if (knownJobs.TryGetValue(id, out var old)) old.Update(record);
                else { var row = new JobRow(record); knownJobs[id] = row; jobs.Add(row); }
                order.Add(knownJobs[id]);
            }
            // Host observations retain each original owner; a fresh manager read is required before cancellation.
            foreach (var retained in hostRecords)
            {
                string id = retained.Text("job_id");
                if (!seen.Add(id)) continue;
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
        if(!JobHistoryReady)
        {
            bar.Children.Add(new TextBlock { Text=jobHistory?.Error ?? "Saved cancellation history is unavailable.",MaxWidth=520,TextWrapping=TextWrapping.Wrap });
            bar.Children.Add(Ui.Button("Reload saved history",ReloadJobHistory));
        }
        var query = new TextBox { Text=jobsQuery, Width=195, ToolTip="Filter by task, repository, model or ID" };
        AutomationProperties.SetName(query,"Filter jobs"); bar.Children.Add(query);
        var filter = new ComboBox { ItemsSource=JobStates, SelectedItem=jobsFilter, Width=135 }; AutomationProperties.SetName(filter,"Job state"); bar.Children.Add(filter);
        bar.Children.Add(Ui.Button("Refresh", () => Run(RefreshCatalog)));
        var more=Ui.Button("Load more", () => { if (!catalogBusy && catalogMore) {catalogPages++; Run(RefreshCatalog);} }); bar.Children.Add(more);
        var hostMore=Ui.Button("Load more host jobs", () => { if (!catalogBusy && hostCatalogMore) {hostCatalogPages++; Run(RefreshCatalog);} }); bar.Children.Add(hostMore);
        grid.Children.Add(bar);
        var area = new Grid(); area.ColumnDefinitions.Add(new()); area.ColumnDefinitions.Add(new(){Width=new GridLength(275)});
        Grid.SetRow(area,1);grid.Children.Add(area);
        var table = jobsTable = Table(approvals ? "Approval queue" : "Gateway jobs"); table.RowHeight=preferences.Flag("comfortable") ? 34 : 24;
        if (preferences.Flag("comfortable")) table.FontSize=14;
        var view = CollectionViewSource.GetDefaultView(jobs);
        bool Visible(JobRow j) => (approvals ? j.Allows("approve") :
            JobQueuePresentation.Visible(j.Data, jobsFilter=="Active" ? "All" : jobsFilter, pins.Contains(j.Id), IsRemoved(j)) &&
            (jobsFilter!="Active" || j.State is "queued" or "running" or "needs_attention"))
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
            if (hostCatalogUnavailable) catalogNote.Text += " · Host inventory unavailable; retained observations shown";
            else if (hostCatalogMore) catalogNote.Text += " · More host jobs available";
            more.IsEnabled=catalogMore && !catalogBusy;
            hostMore.IsEnabled=hostCatalogMore && !catalogBusy;
            hostMore.Visibility=hostCatalogMore ? Visibility.Visible : Visibility.Collapsed;
        }
        updateJobFooter=Footer;
        table.AddHandler(ScrollViewer.ScrollChangedEvent,new ScrollChangedEventHandler((_,_)=>Footer()));
        void Inspect()
        {
            var row=knownJobs.GetValueOrDefault(focusedJob);
            string inspectionKey=$"{focusedJob}|{row?.Data.Text("revision")}|{row?.State}|{row?.Next}|{row?.ReconciliationAction}|{inspectorTab}|{string.Join(',',selectedJobs)}|{string.Join(',',pins)}|{jobControlBusy}|{jobCancellationRequests.Count}|{removedJobs.Count}|{jobsFilter}";
            if (inspector.Tag as string == inspectionKey) return;
            inspector.Tag=inspectionKey;
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
            if(row.ReconciliationAction.Length>0)inspector.Children.Add(Ui.Text(row.ReconciliationAction,13,"MutedBrush"));
            var pin=Ui.Button(pins.Contains(row.Id)?"Unpin task":"Pin task",()=>{
                if(!pins.Remove(row.Id)) pins.Add(row.Id); SavePreferences(); Inspect();});pin.IsEnabled=JobHistoryReady;
            inspector.Children.Add(Ui.Actions(pin,Ui.Button("View receipt",()=>Run(()=>JobDetail(row,"Summary")))));
            var cancel=Ui.Button("Cancel selected",()=>Run(CancelSelectedJobs));
            cancel.IsEnabled=!jobControlBusy && selectedJobs.Any(id=>knownJobs.TryGetValue(id,out var j) && CanRequestJobCancellation(j));
            inspector.Children.Add(cancel);
            if (JobKey(row) is { } cancellationKey && jobCancellationRequests.ContainsKey(cancellationKey))
            {
                var check=Ui.Button("Check cancellation receipt",()=>Run(()=>ReadJobCancellation(row)));
                check.IsEnabled=!jobControlBusy;inspector.Children.Add(check);
                if (!JobQueuePresentation.Terminal(row.Data) && !row.Data.Flag("cancellation_requested") &&
                    !JobQueuePresentation.DefinitivelyNotDispatched(jobCancellationRequests[cancellationKey],jobCancellationRequests[cancellationKey].Object("rejection")))
                {
                    var retry=Ui.Button("Retry original cancellation",()=>Run(()=>RetryJobCancellation(row)));
                    retry.IsEnabled=!jobControlBusy && JobHistoryReady;inspector.Children.Add(retry);
                }
                if(jobCancellationRequests[cancellationKey].Array("previous_requests").Count>0)
                    inspector.Children.Add(Ui.Button("Earlier cancellation receipts",()=>ShowDetails("Earlier cancellation receipts",jobCancellationRequests[cancellationKey].Array("previous_requests"))));
            }
            var remove=Ui.Button(jobsFilter=="Removed" ? "Restore selected to list" : "Remove finished from list",()=>Run(ChangeRemovedJobs));
            remove.IsEnabled=!jobControlBusy && JobHistoryReady && selectedJobs.Any(id=>knownJobs.TryGetValue(id,out var j) && (jobsFilter=="Removed" ? IsRemoved(j) : JobQueuePresentation.Terminal(j.Data)));
            inspector.Children.Add(remove);
            if (jobsFilter=="Removed") inspector.Children.Add(Ui.Text("Only the list is filtered. Original job receipts and cancellation evidence remain available.",12,"MutedBrush"));
            inspector.Children.Add(Ui.Button("Full debug export",()=>OpenReports(row.Id)));
            if(row.Data["conversation_id"] is JsonValue cv) inspector.Children.Add(Ui.Button("Open conversation",()=>Run(()=>OpenConversation(cv.ToString()))));
            if(row.Allows("approve")) inspector.Children.Add(Ui.Button("Review merge",()=>Run(()=>ReviewEngineering(row)),true));
            if(row.Kind=="engineering" && !row.HostReceipt) inspector.Children.Add(Ui.Button("Open agent workspace",()=>Run(()=>AgentWorkspace(row)),true));
        }
        table.SelectionChanged+=(_,e)=>{
            if(syncingSelection)return;
            foreach(JobRow removed in e.RemovedItems) selectedJobs.Remove(removed.Id);
            foreach(JobRow added in e.AddedItems){selectedJobs.Add(added.Id);focusedJob=added.Id;}
            if(e.AddedItems.Count==0 && !selectedJobs.Contains(focusedJob) && !pins.Contains(focusedJob)) focusedJob=selectedJobs.FirstOrDefault()??"";
            Inspect();Footer();
        };
        void Refilter(){syncingSelection=true;try{view.Refresh();foreach(JobRow j in view)if(selectedJobs.Contains(j.Id)&&!table.SelectedItems.Contains(j)) table.SelectedItems.Add(j);}finally{syncingSelection=false;}Footer();Inspect();}
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

    private string? JobKey(JobRow row, JsonObject? scope = null)
    {
        scope ??= identity;
        if (scope is null) return null;
        try { return JobQueuePresentation.ScopeKey(scope, row.Data); }
        catch (InvalidOperationException) { return null; }
    }

    private bool IsRemoved(JobRow row) => JobKey(row) is { } key && removedJobs.Contains(key);
    private bool CanRequestJobCancellation(JobRow row) => JobHistoryReady && JobQueuePresentation.CanRequestCancellation(row.Data) &&
        JobKey(row) is { } key && (!jobCancellationRequests.TryGetValue(key,out var request) ||
            JobQueuePresentation.DefinitivelyNotDispatched(request,request.Object("rejection")));

    private void RequireJobScope(JsonObject scope)
    {
        if (!JsonNode.DeepEquals(scope, identity)) throw new InvalidOperationException("Gateway connection changed. No further job controls were sent.");
    }

    private async Task<JsonObject> ReadJobOwner(JobRow row, JsonObject scope)
    {
        RequireJobScope(scope);
        var observed = await Call("job-owner-read", new JsonObject { ["job_id"] = row.Id, ["identity"] = scope.DeepClone() });
        RequireJobScope(scope);
        JobQueuePresentation.ValidateRead(observed, scope, row.Data);
        row.ObserveReconciliation(observed);
        return observed;
    }

    private bool ConfirmJobCancellation(JsonObject scope, IReadOnlyList<(JobRow Row, JsonObject Target, string Key)> targets, bool retry = false)
    {
        bool confirmed = false;
        var dialog = new Window { Owner=this, Title=retry ? "Retry original cancellation" : "Cancel selected jobs", Width=680, Height=480,
            MinWidth=480, MinHeight=360, WindowStartupLocation=WindowStartupLocation.CenterOwner };
        var list = new ListBox { Height=250, ItemsSource=targets.Select(item =>
            $"{item.Row.Title}\n{item.Target.Text("job_kind")} · {item.Row.Id} · owner {item.Target.Text("principal_id")} · revision {item.Target.Text("revision")}") };
        var body=Ui.Column(Ui.Text(retry ? "Retry the saved cancellation request?" : $"Request cancellation of {targets.Count} job(s)?",20,bold:true),
            Ui.Text($"Gateway {scope.Text("gateway_id")}. These jobs keep their original owners. Acknowledgement is not confirmation that work has stopped.",13,"MutedBrush"), list,
            Ui.Actions(Ui.Button("Keep jobs",()=>dialog.Close()), Ui.Button(retry ? "Retry original request" : "Request cancellation",()=>{confirmed=true;dialog.Close();},true)));
        body.Margin=new Thickness(20);dialog.Content=body;dialog.ShowDialog();return confirmed;
    }

    private async Task<JsonObject> SendJobCancellation(JobRow row, JsonObject scope, JsonObject request)
    {
        RequireJobScope(scope);
        var result = await Call("job-owner-cancel", new JsonObject { ["identity"]=scope.DeepClone(), ["job_id"]=row.Id,
            ["command_id"]=request["command_id"]?.DeepClone(), ["target"]=request["target"]?.DeepClone() });
        RequireJobScope(scope);
        if (result.Text("command_id", "") != request.Text("command_id", ""))
            throw new InvalidOperationException("The host returned a different cancellation receipt. Check the original request.");
        ObserveJobCancellation(row,scope,request,result);
        return result;
    }

    private void ObserveJobCancellation(JobRow row, JsonObject scope, JsonObject request, JsonObject result)
    {
        if(JobQueuePresentation.DefinitivelyNotDispatched(request,result))
        {
            // Retain the exact rejection proof; absence, timeout and an intent receipt cannot release a target.
            request["rejection"]=new JsonObject(result.Where(item=>item.Key is "gateway_id" or "principal_id" or "job_kind" or "job_id" or "command_id" or "dispatch_state" or "receipt")
                .Select(item=>new KeyValuePair<string,JsonNode?>(item.Key,item.Value?.DeepClone())));
            SavePreferences();
        }
        else JobQueuePresentation.ValidateRead(result,scope,row.Data);
        row.ObserveCancellation(result);
    }

    private async Task<JsonObject> FetchJobCancellationReceipt(JobRow row, JsonObject scope, JsonObject request)
    {
        RequireJobScope(scope);
        var receipt=await Call("job-owner-cancel-receipt",new JsonObject { ["identity"]=scope.DeepClone(),["job_id"]=row.Id,["command_id"]=request["command_id"]?.DeepClone() });
        RequireJobScope(scope);
        if(receipt.Text("command_id", "")!=request.Text("command_id", ""))throw new InvalidOperationException("The original cancellation receipt is unavailable.");
        ObserveJobCancellation(row,scope,request,receipt);
        return receipt;
    }

    private async Task<JsonObject> SubmitAndReadBackCancellation(JobRow row, JsonObject scope, JsonObject request)
    {
        try { return await SendJobCancellation(row,scope,request); }
        catch(Exception original)
        {
            // One bounded read settles a durable rejection or accepted stop without repeating the POST.
            try { return await FetchJobCancellationReceipt(row,scope,request); }
            catch { throw new InvalidOperationException("Cancellation remains unconfirmed. Keep the original request and check its receipt.",original); }
        }
    }

    private async Task CancelSelectedJobs()
    {
        if (jobControlBusy) return;
        jobControlBusy=true;updateInspector?.Invoke();
        try
        {
            await EnsureConnected();var scope=(JsonObject)identity!.DeepClone();
            var selected=selectedJobs.Where(knownJobs.ContainsKey).Select(id=>knownJobs[id]).ToArray();
            var targets=new List<(JobRow Row, JsonObject Target, string Key)>();var results=new JsonArray();
            foreach(var row in selected)
            {
                if (!CanRequestJobCancellation(row)) continue;
                try
                {
                    var observed=await ReadJobOwner(row,scope);
                    if (JobQueuePresentation.CancellationTarget(observed,scope,row.Data) is { } target)
                        targets.Add((row,target,JobKey(row,scope)!));
                    else results.Add(new JsonObject { ["job_id"]=row.Id,["outcome"]=JobQueuePresentation.CancellationLabel(observed),["receipt"]=observed });
                }
                catch(Exception error) { results.Add(new JsonObject { ["job_id"]=row.Id,["outcome"]="Read failed; no cancellation sent",["message"]=error.Message }); }
            }
            if (targets.Count > 0)
            {
                RequireJobScope(scope);
                if (!ConfirmJobCancellation(scope,targets)) return;
                RequireJobScope(scope);
                foreach(var item in targets)
                {
                    var previous=new JsonArray();
                    if(jobCancellationRequests.TryGetValue(item.Key,out var rejected))
                    {
                        foreach(var record in rejected.Array("previous_requests"))previous.Add(record?.DeepClone());
                        previous.Add(new JsonObject(rejected.Where(entry=>entry.Key!="previous_requests")
                            .Select(entry=>new KeyValuePair<string,JsonNode?>(entry.Key,entry.Value?.DeepClone()))));
                    }
                    jobCancellationRequests[item.Key]=new JsonObject { ["command_id"]=Guid.NewGuid().ToString("N"),["job_id"]=item.Row.Id,["target"]=item.Target.DeepClone(),["previous_requests"]=previous };
                }
                // The original command and target survive a lost response or a window restart.
                SavePreferences();
                foreach(var item in targets)
                {
                    try
                    {
                        var receipt=await SubmitAndReadBackCancellation(item.Row,scope,jobCancellationRequests[item.Key]);
                        results.Add(new JsonObject { ["job_id"]=item.Row.Id,["outcome"]=JobQueuePresentation.CancellationLabel(receipt),["receipt"]=receipt });
                    }
                    catch(Exception error)
                    {
                        item.Row.ObserveCancellation(new JsonObject());
                        results.Add(new JsonObject { ["job_id"]=item.Row.Id,["command_id"]=jobCancellationRequests[item.Key]["command_id"]?.DeepClone(),
                            ["outcome"]="Cancellation unconfirmed; read the original receipt",["message"]=error.Message });
                    }
                }
            }
            if(results.Count>0)ShowDetails("Cancellation receipts",results);
            await RefreshCatalog();
        }
        finally { jobControlBusy=false;updateInspector?.Invoke();updateJobFooter?.Invoke(); }
    }

    private async Task ReadJobCancellation(JobRow row)
    {
        if(jobControlBusy)return;
        jobControlBusy=true;updateInspector?.Invoke();
        try
        {
            await EnsureConnected();var scope=(JsonObject)identity!.DeepClone();
            if(JobKey(row,scope) is not { } key || !jobCancellationRequests.TryGetValue(key,out var request))return;
            var receipt=await FetchJobCancellationReceipt(row,scope,request);
            ShowDetails(JobQueuePresentation.CancellationLabel(receipt),receipt);await RefreshCatalog();
        }
        finally { jobControlBusy=false;updateInspector?.Invoke(); }
    }

    private async Task RetryJobCancellation(JobRow row)
    {
        if(jobControlBusy)return;
        jobControlBusy=true;updateInspector?.Invoke();
        try
        {
            await EnsureConnected();var scope=(JsonObject)identity!.DeepClone();
            if(JobKey(row,scope) is not { } key || !jobCancellationRequests.TryGetValue(key,out var request))return;
            var observed=await ReadJobOwner(row,scope);
            if(JobQueuePresentation.Terminal(observed.Object("job")) || observed.Text("cancellation_outcome", "")=="pending")
            { row.ObserveCancellation(observed);ShowDetails(JobQueuePresentation.CancellationLabel(observed),observed);return; }
            var original=request.Object("target");
            if(!ConfirmJobCancellation(scope,[(row,original,key)],retry:true))return;
            SavePreferences();
            var receipt=await SubmitAndReadBackCancellation(row,scope,request);
            ShowDetails(JobQueuePresentation.CancellationLabel(receipt),receipt);await RefreshCatalog();
        }
        finally { jobControlBusy=false;updateInspector?.Invoke(); }
    }

    private async Task ChangeRemovedJobs()
    {
        if(jobControlBusy)return;
        jobControlBusy=true;updateInspector?.Invoke();
        try
        {
            await EnsureConnected();var scope=(JsonObject)identity!.DeepClone();
            bool restore=jobsFilter=="Removed";
            var changed=new List<(string Id,string Key)>();var results=new JsonArray();
            foreach(var row in selectedJobs.Where(knownJobs.ContainsKey).Select(id=>knownJobs[id]).ToArray())
            {
                if(JobKey(row,scope) is not { } key)continue;
                if(restore) { if(removedJobs.Contains(key))changed.Add((row.Id,key));continue; }
                try
                {
                    var observed=await ReadJobOwner(row,scope);
                    if(JobQueuePresentation.CanRemove(observed,scope,row.Data))changed.Add((row.Id,key));
                    else results.Add(new JsonObject { ["job_id"]=row.Id,["outcome"]="Kept visible; a terminal result is not confirmed",["receipt"]=observed });
                }
                catch(Exception error) { results.Add(new JsonObject { ["job_id"]=row.Id,["outcome"]="Kept visible; readback unavailable",["message"]=error.Message }); }
            }
            RequireJobScope(scope);
            foreach(var item in changed) { if(restore)removedJobs.Remove(item.Key);else removedJobs.Add(item.Key); }
            try { SavePreferences(); }
            catch { foreach(var item in changed) { if(restore)removedJobs.Add(item.Key);else removedJobs.Remove(item.Key); }throw; }
            foreach(var item in changed) { selectedJobs.Remove(item.Id);if(focusedJob==item.Id)focusedJob=""; }
            CollectionViewSource.GetDefaultView(jobs).Refresh();
            status.Text=restore ? $"Restored {changed.Count} job(s) to the list." : $"Removed {changed.Count} finished job(s) from the list. Receipts remain available in Removed.";
            if(results.Count>0)ShowDetails("Jobs kept visible",results);
        }
        finally { jobControlBusy=false;updateInspector?.Invoke();updateJobFooter?.Invoke(); }
    }

    private bool Confirm(string message) => MessageBox.Show(this,message,"Gateway",MessageBoxButton.YesNo,MessageBoxImage.Question,MessageBoxResult.No)==MessageBoxResult.Yes;
    private async Task JobDetail(JobRow row,string tab)
    {
        await EnsureConnected();
        if(row.HostReceipt)
        {
            var observed=await ReadJobOwner(row,(JsonObject)identity!.DeepClone());
            ShowDetails(row.Title+" · "+tab,observed);return;
        }
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
