using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using Gateway.Desktop.Core;
using Microsoft.Win32;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private string PreferencesPath => System.IO.Path.Combine(root,"data","desktop-appearance.json");
    private bool allowWindowExit;
    private void ReadPreferences()
    {
        try {
            if(File.Exists(PreferencesPath) && new FileInfo(PreferencesPath).Length<=65536)
                preferences=JsonNode.Parse(File.ReadAllText(PreferencesPath)) as JsonObject??[];
        } catch(Exception ex) when(ex is IOException or System.Text.Json.JsonException or UnauthorizedAccessException) {}
        foreach(var id in preferences.Array("pins").Take(128))if(id!=null)pins.Add(id.ToString());
        ((App)Application.Current).Appearance=preferences.Text("theme","Dark");((App)Application.Current).ApplyContrast();
    }
    private void SavePreferences()
    {
        preferences["pins"]=new JsonArray(pins.Take(128).Select(id=>(JsonNode?)JsonValue.Create(id)).ToArray());
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(PreferencesPath)!);
        string tmp=PreferencesPath+"."+Guid.NewGuid().ToString("N")+".tmp";
        try{File.WriteAllText(tmp,preferences.ToJsonString());File.Move(tmp,PreferencesPath,true);}
        finally{File.Delete(tmp);}
    }
    private void BuildCategory()
    {
        if(Section=="Devices"){BuildAndroid();var list=new ListBox{MaxHeight=160};Ui.Rows(list,snapshot.Array("devices"),"address","state");
            page.Children.Add(Card("Observed network activity",Ui.Text("Recent socket observations · "+TimeOf(snapshot.Number("capturedAt")),13,"MutedBrush"),list));return;}
        if(Section=="Connection"){BuildConnection();return;}
        if(Section=="Appearance")
        {
            var panel=Ui.Column(Ui.Text("Appearance",21,bold:true));
            var theme=Ui.Select(panel,"Color theme",new[]{"System","Dark","Light"},preferences.Text("theme","Dark"));
            var comfortable=Ui.Toggle(panel,"Comfortable job rows",preferences.Flag("comfortable"));
            var reduced=Ui.Toggle(panel,"Reduce motion",preferences.Flag("reduceMotion"));
            var enter=Ui.Toggle(panel,"Send with Enter (Shift+Enter inserts a new line)",preferences.Flag("enterSend"));
            panel.Children.Add(Ui.Button("Apply appearance",()=>{
                preferences["theme"]=theme.SelectedItem!.ToString();preferences["comfortable"]=comfortable.IsChecked==true;
                preferences["reduceMotion"]=reduced.IsChecked==true;preferences["enterSend"]=enter.IsChecked==true;SavePreferences();
                ((App)Application.Current).Appearance=theme.SelectedItem.ToString()!;((App)Application.Current).ApplyContrast();
                Navigate("Settings/Appearance");status.Text="Appearance saved.";
            },true));page.Children.Add(Ui.Card(panel));return;
        }
        if(Section=="General")
        {
            var panel=new StackPanel();var tray=Ui.Toggle(panel,"Hide the window in the notification area when I close it",preferences.Flag("tray"));
            tray.Click+=(_,_)=>{preferences["tray"]=tray.IsChecked==true;SavePreferences();};
            panel.Children.Add(Ui.Text("Accepted jobs continue on the Gateway. Open Gateway from the notification area to return.",13,"MutedBrush"));
            page.Children.Add(Card("Window behavior",panel));
        }
        BuildSettings();
        if(Section=="Advanced")page.Children.Add(Ui.Actions(Ui.Button("Full debug export",()=>OpenReports()),
            Ui.Button("Device access",()=>Navigate("Settings/Devices"))));
    }
    private void BuildConnection()
    {
        var body=new StackPanel();
        body.Children.Add(Ui.Actions(Ui.Button("Connect phone",()=>Navigate("Connect phone"),true),Ui.Button("Test reachability",()=>Run(async()=>{
            await EnsureConnected(true);status.Text="Confirmed Gateway "+identity.Text("gateway_id")+" · "+identity.Text("principal_id");
        }))));
        page.Children.Add(Card("Listener and network",body));Run(async()=>{
            var result=await Call("workspace-connection-read");var original=result.Object("settings");
            var host=Ui.Field(body,"Listen interface IP",original.Text("bind_host"));var port=Ui.Field(body,"Gateway port",original.Text("port"));
            var names=Ui.Field(body,"Allowed hostnames",original.Text("network_hostnames",""));var grace=Ui.Field(body,"Shutdown grace (seconds)",original.Text("shutdown_seconds"));
            body.Children.Add(Ui.Button("Apply connection settings",()=>Run(async()=>{
                if(!int.TryParse(port.Text,out int p)||!int.TryParse(grace.Text,out int g))throw new InvalidOperationException("Enter whole numbers for the port and shutdown grace.");
                var saved=await Call("workspace-connection-save",new JsonObject{["original"]=original.DeepClone(),["changes"]=new JsonObject{
                    ["bind_host"]=host.Text.Trim(),["port"]=p,["network_hostnames"]=names.Text.Trim(),["shutdown_seconds"]=g}});
                original=saved.Object("settings");status.Text="Saved. Restart when current work finishes to activate the listener.";
            }),true));
            body.Children.Add(Ui.Actions(Ui.Button("Restart when idle",()=>Run(async()=>{await Call("runtime-control",new JsonObject{["action"]="restart"});await RefreshSnapshot();})),
                Ui.Button("Allow private firewall rule",()=>Run(()=>Firewall(false))),Ui.Button("Remove firewall rule",()=>Run(()=>Firewall(true)))));
            async Task Firewall(bool remove){if(Confirm(remove?"Remove this Gateway’s private firewall rule?":"Allow the Gateway on this private subnet?")){
                await Call("workspace-connection-firewall",new JsonObject{["remove"]=remove});status.Text=remove?"Firewall rule removed.":"Private firewall rule applied.";} }
        });
    }
}
