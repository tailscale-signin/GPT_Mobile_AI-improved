using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private sealed record ScheduleEntry(JsonObject Data)
    {
        public string Enabled => Data.Text("enabled")=="1" ? "On" : "Off";
        public string Title => Data.Text("title");
        public string Kind => Data.Text("kind");
        public string Next => Enabled=="Off" ? "Disabled" : LocalTime(Data.Number("due"));
        public string Recurrence => Data.Number("period") is >0 and double seconds ? $"{seconds/3600:g} h" : "Once";
        public string State => Data.Text("state");
        public string Last => Data.Text("result");
        public string Unread => Data.Text("unread");
    }
    private static string LocalTime(double? stamp) => stamp is >0 ? DateTimeOffset.FromUnixTimeSeconds((long)stamp.Value).ToLocalTime().ToString("g") : "—";
    private void BuildSchedules()
    {
        var panel=new DockPanel(); var actions=new WrapPanel(); DockPanel.SetDock(actions,Dock.Top);panel.Children.Add(actions);
        var note=Ui.Text("Next runs use "+TimeZoneInfo.Local.DisplayName,13,"MutedBrush");DockPanel.SetDock(note,Dock.Bottom);panel.Children.Add(note);
        var table=Table("Scheduled Gateway tasks");table.SelectionMode=DataGridSelectionMode.Single;
        foreach(var (title,property,width) in new[]{("Enabled","Enabled",65),("Task","Title",-2),("Kind","Kind",-2),("Next run","Next",125),("Repeats","Recurrence",68),("State","State",76),("Last result","Last",-2),("Alerts","Unread",55)})
            Column(table,title,property,width);
        panel.Children.Add(table); FillWindow(panel);
        ScheduleEntry? Selected()=>table.SelectedItem as ScheduleEntry;
        async Task Load()
        {
            var id=Selected()?.Data.Text("id");var result=await Call("schedule-list");
            var items=result.Array("items").OfType<JsonObject>().Select(j=>new ScheduleEntry(j)).ToList();
            table.ItemsSource=items;table.SelectedItem=items.FirstOrDefault(i=>i.Data.Text("id")==id);
            note.Text=$"{items.Count} schedules · {TimeZoneInfo.Local.DisplayName} · observed {DateTime.Now:T}";
        }
        async Task Edit(bool create)
        {
            var item=Selected(); if(!create&&item is null)return;
            var form=new StackPanel();
            var title=Ui.Field(form,"Task name",create?"":item!.Title);
            var kinds=new[]{"Reminder","Check saved job storage","Watch Crossref publications","Test approved repository mirror"};
            var kind=Ui.Select(form,"Task kind",kinds,create?kinds[0]:item!.Kind);kind.IsEnabled=create;
            var hours=Ui.Field(form,"Repeat interval in hours (0 runs once)",create?"0":((item!.Data.Number("period")??0)/3600).ToString("0"));
            TextBox? delay=null, query=null;
            if(create){delay=Ui.Field(form,"First run in minutes","60");query=Ui.Field(form,"Public search topic or approved repository");}
            if(!EditDialog(create?"Schedule task":"Edit schedule",form,"Save schedule"))return;
            if(!int.TryParse(hours.Text,out int repeat))throw new InvalidOperationException("Enter a repeat interval in hours.");
            var args=new JsonObject{["title"]=title.Text,["hours"]=repeat};
            if(create){if(!int.TryParse(delay!.Text,out int min))throw new InvalidOperationException("Enter the delay in minutes.");
                args["delay"]=min;args["kind"]=kind.SelectedItem?.ToString();args["query"]=query!.Text;}
            else{args["id"]=item!.Data.Text("id");args["revision"]=item.Data["revision"]?.DeepClone();}
            await Call(create?"schedule-add":"schedule-change",args);await Load();
        }
        var toggle=Ui.Button("Enable / disable",()=>Run(async()=>{
            if(Selected() is not {} item)return;
            await Call("schedule-change",new JsonObject{["id"]=item.Data.Text("id"),["revision"]=item.Data["revision"]?.DeepClone(),["enabled"]=item.Enabled!="On"});await Load();
        }));
        var edit=Ui.Button("Edit",()=>Run(()=>Edit(false)));
        var results=Ui.Button("Results and alerts",()=>Run(async()=>{
            if(Selected() is not {} item)return;
            var id=item.Data.Text("id");var result=await Call("schedule-results",new JsonObject{["id"]=id});
            ShowDetails(item.Title,result);
            if(result.Array("alerts").FirstOrDefault() is JsonNode alert && Confirm("Mark the displayed alerts as read?"))
                await Call("schedule-ack",new JsonObject{["id"]=id,["through"]=alert["due"]?.DeepClone()});
            await Load();
        }));
        var remove=Ui.Button("Remove",()=>Run(async()=>{
            if(Selected() is {} item && Confirm("Remove this schedule and its saved alerts?")){
                await Call("schedule-remove",new JsonObject{["id"]=item.Data.Text("id")});await Load();}
        }));
        actions.Children.Add(Ui.Button("Schedule task",()=>Run(()=>Edit(true)),true));actions.Children.Add(Ui.Button("Refresh",()=>Run(Load)));
        foreach(var b in new[]{toggle,edit,results,remove}){b.IsEnabled=false;actions.Children.Add(b);}
        table.SelectionChanged+=(_,_)=>{foreach(var b in new[]{toggle,edit,results,remove})b.IsEnabled=Selected()!=null;
            toggle.Content=Selected()?.Enabled=="On"?"Disable schedule":"Enable schedule";};
        Run(Load);
    }
    private bool EditDialog(string title,StackPanel body,string commit)
    {
        var dialog=new Window{Owner=this,Title=title,Width=Math.Min(640,SystemParameters.WorkArea.Width),MaxHeight=SystemParameters.WorkArea.Height*.85,
            SizeToContent=SizeToContent.Height,WindowStartupLocation=WindowStartupLocation.CenterOwner,Background=Ui.Brush("CanvasBrush")};
        body.Margin=new Thickness(18);
        body.Children.Add(Ui.Actions(Ui.Button(commit,()=>dialog.DialogResult=true,true),Ui.Button("Close",()=>dialog.DialogResult=false)));
        dialog.Content=new ScrollViewer{Content=body,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        return dialog.ShowDialog()==true;
    }
}
