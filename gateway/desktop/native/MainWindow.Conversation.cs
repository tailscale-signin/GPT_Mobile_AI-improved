using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.Win32;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private sealed class TurnPreview
    {
        public PublicPreview State {get;}=new();
        public Dictionary<string,string> Sources {get;}=[];
    }
    private readonly Dictionary<string,TurnPreview> previews=[];
    private readonly Dictionary<string,(bool Follow,double Offset)> readingPositions=[];
    private readonly Dictionary<string,long> eventCursors=[];
    private readonly HashSet<string> activityGaps=[];
    private bool readingActivity, preparingSend;
    private string liveTurn="",liveConversation="",livePrompt="",liveText="",chatMode="Chat";
    private Action? updateLive,updatePreviews;
    private Func<Task>? flushComposer;
    private string? bookmarkTarget;
    private static string CID(JsonNode? conv)=>conv.Text("conversation_id","");
    private async Task OpenConversation(string id,string? bookmark=null)
    {
        if(preparingSend)return;
        if(flushComposer!=null)await flushComposer();
        await EnsureConnected();int before=epoch;
        var opened=await Call("conversation-read",WithIdentity(new JsonObject{["conversation_id"]=id}));
        if(before!=epoch)return;
        openedConversation=(JsonObject)opened.Object("conversation").DeepClone();transcript=(JsonArray)opened.Array("messages").DeepClone();
        if(openedConversation.Text("active_job_kind")=="engineering")chatMode="Engineering";else chatMode="Chat";
        bookmarkTarget=bookmark;Navigate("Chat");
    }

    private async Task AppendDraft(string value)
    {
        await EnsureConnected();if(sending || draft["pending"] is JsonObject)throw new InvalidOperationException("Finish or recover the current send first.");
        string next=(draft.Text("draft","")+"\n"+value).Trim();
        if(next.Length>32000)throw new InvalidOperationException("This selection exceeds the draft limit.");
        await Call("draft-save",new JsonObject{["draft"]=next,["model"]=draft.Text("model","")});draft["draft"]=next;Navigate("Chat");
    }
    private void AcceptActivity(JsonNode? ev)
    {
        string turn=ev.Text("turn_id","");if(turn.Length==0)return;
        if(!previews.TryGetValue(turn,out var model))previews[turn]=model=new();
        if(!model.State.Apply(ev))return;
        foreach(var r in ev.Array("sources"))if(r.Text("url","") is {Length:>0} url)model.Sources[url]=r.Text("title",url);
        if(previews.Count>800)previews.Remove(previews.Keys.First());
    }
    private async Task RefreshChatActivity()
    {
        string cid=CID(openedConversation);if(readingActivity||cid.Length==0||selectedPage!="Chat"||identity==null)return;
        readingActivity=true;
        try{
            bool more;do{
                var result=await Call("public-activity",WithIdentity(new JsonObject{["conversation_id"]=cid,["after"]=eventCursors.GetValueOrDefault(cid)}));
                if(cid!=CID(openedConversation)||selectedPage!="Chat")return;
                if(result.Flag("gap"))activityGaps.Add(cid);
                foreach(var ev in result.Array("items")){AcceptActivity(ev);eventCursors[cid]=Math.Max(eventCursors.GetValueOrDefault(cid),(long)(ev.Number("sequence")??0));}
                more=result.Flag("has_more")&&result.Array("items").Count>0;
            }while(more);
            updatePreviews?.Invoke();
        }finally{readingActivity=false;}
    }
    private void BuildChat()
    {
        int viewEpoch=epoch;string cid=CID(openedConversation);bool engineering=openedConversation?.Text("active_job_kind")=="engineering";
        var layout=new Grid();layout.ColumnDefinitions.Add(new(){Width=new GridLength(205)});layout.ColumnDefinitions.Add(new());
        var rail=new DockPanel{Margin=new Thickness(0,0,12,0)};layout.Children.Add(rail);
        var railTop=new StackPanel();DockPanel.SetDock(railTop,Dock.Top);rail.Children.Add(railTop);
        railTop.Children.Add(Ui.Button("New chat",()=>Run(async()=>{if(flushComposer!=null)await flushComposer();openedConversation=null;transcript=[];chatMode="Chat";Navigate("Chat");}),true));
        var historySearch=Ui.Field(railTop,"Find conversations");var favorites=Ui.Toggle(railTop,"Favorites",false);
        var history=new ListBox{DisplayMemberPath="Label"};AutomationProperties.SetName(history,"Recent conversations");rail.Children.Add(history);
        int historyRequest=0;
        async Task History(){int token=++historyRequest;await EnsureConnected();var r=await Call("conversations",WithIdentity(new JsonObject{["query"]=historySearch.Text,["favorite"]=favorites.IsChecked==true}));
            if(token!=historyRequest)return;history.ItemsSource=(r["items"] as JsonArray??r.Array("conversations")).OfType<JsonObject>().Select(o=>new Choice(o.Text("title"),o)).ToList();}
        var delayHistory=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(300)};
        delayHistory.Tick+=(_,_)=>{delayHistory.Stop();Run(History);};historySearch.TextChanged+=(_,_)=>{delayHistory.Stop();delayHistory.Start();};
        favorites.Click+=(_,_)=>Run(History);
        history.SelectionChanged+=(_,_)=>{if(history.SelectedItem is Choice item)Run(()=>OpenConversation(CID(item.Value)));};
        var center=new Grid{MaxWidth=1040};Grid.SetColumn(center,1);layout.Children.Add(center);
        foreach(var height in new[]{GridLength.Auto,new GridLength(1,GridUnitType.Star),GridLength.Auto})center.RowDefinitions.Add(new(){Height=height});
        var head=Ui.Column(Ui.Text(openedConversation?.Text("title","New conversation")??"New conversation",22,bold:true));var headActions=new WrapPanel();
        var toggleHistory=Ui.Button("History",()=>{bool show=rail.Visibility!=Visibility.Visible;rail.Visibility=show?Visibility.Visible:Visibility.Collapsed;layout.ColumnDefinitions[0].Width=new GridLength(show?190:0);});
        headActions.Children.Add(toggleHistory);var model=new ComboBox{MaxWidth=285,MinWidth=100};AutomationProperties.SetName(model,"Exact conversation model");headActions.Children.Add(model);
        string original=openedConversation?.Text("model","")??"";
        void Models(){model.ItemsSource=models.Concat(original.Length>0?new[]{original}:Array.Empty<string>()).Distinct().ToArray();model.SelectedItem=original.Length>0?original:draft.Text("model","");
            if(model.SelectedIndex<0&&model.Items.Count>0)model.SelectedIndex=0;model.IsEnabled=original.Length==0&&!sending;}
        var modes=new ComboBox{ItemsSource=engineering?new[]{"Engineering"}:new[]{"Chat","Search","Engineering"},SelectedItem=engineering?"Engineering":chatMode};
        AutomationProperties.SetName(modes,"Conversation mode");headActions.Children.Add(modes);modes.SelectionChanged+=(_,_)=>chatMode=modes.SelectedItem?.ToString()??"Chat";
        headActions.Children.Add(Ui.Button("Tools",()=>Navigate("Tools/Add-ons / MCP")));head.Children.Add(headActions);center.Children.Add(head);
        var messages=new StackPanel{Margin=new Thickness(3,8,8,8)};
        var reader=new ScrollViewer{Content=messages,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};Grid.SetRow(reader,1);center.Children.Add(reader);
        var follow=new ReadingFollow(reader,()=>ReducedMotion);
        string readingKey=cid.Length==0?"new":cid;
        reader.Unloaded+=(_,_)=>{readingPositions[readingKey]=(follow.Following,reader.VerticalOffset);delayHistory.Stop();};
        var previewViews=new Dictionary<string,(Expander Exp,StackPanel Body,ActivityText Brief,StackPanel Sources)>();
        var rendered=new Dictionary<string,FrameworkElement>();var previousRefresh=new Dictionary<string,DateTime>();
        void Slot(string turn){
            if(turn.Length==0 || previewViews.ContainsKey(turn))return;
            var brief=new ActivityText(()=>ReducedMotion);var body=new StackPanel();var sources=new StackPanel();
            var exp=new Expander{Header=brief,Content=body,Visibility=Visibility.Collapsed,HorizontalContentAlignment=HorizontalAlignment.Stretch};
            AutomationProperties.SetName(exp,"Public activity · expand details");
            messages.Children.Add(exp);messages.Children.Add(sources);previewViews[turn]=(exp,body,brief,sources);
        }
        string activeEngineeringTurn=openedConversation?.Text("active_job_id","")??"";
        void Redraw(){
            foreach(var (turn,slot) in previewViews){
                if(!previews.TryGetValue(turn,out var state))continue;
                bool active=turn==liveTurn || turn==openedConversation?.Text("command_id") || turn==activeEngineeringTurn;
                if(active)state.State.Summarize(DateTimeOffset.Now.ToUnixTimeMilliseconds()/1000d);
                var lines=state.State.Lines;
                string text=string.Join("\n",lines.TakeLast(2).Select(a=>a.Text));
                if(lines.Count==0)continue;
                slot.Exp.Visibility=Visibility.Visible;
                if(ReducedMotion || !previousRefresh.TryGetValue(turn,out var last) || (DateTime.UtcNow-last).TotalMilliseconds>=PublicPreview.ChangeIntervalMs){
                    if(slot.Exp.Tag?.ToString()!=text || ReducedMotion){slot.Brief.Present(text);slot.Exp.Tag=text;previousRefresh[turn]=DateTime.UtcNow;}}
                if(slot.Body.Tag?.ToString()!=string.Join('|',lines.Select(l=>l.Text))){
                    slot.Body.Children.Clear();foreach(var line in lines)slot.Body.Children.Add(Ui.Text(line.Text,13,line.Kind=="summary"?"SecondaryBrush":"MutedBrush"));
                    slot.Body.Tag=string.Join('|',lines.Select(l=>l.Text));
                }
                if(slot.Sources.Children.Count!=state.Sources.Count){slot.Sources.Children.Clear();int n=0;
                    foreach(var (url,title) in state.Sources)slot.Sources.Children.Add(Ui.Button($"[{++n}] {title}",()=>OpenWeb(url)));}
            }
        }
        foreach(var m in transcript.OfType<JsonObject>()){
            bool user=m.Text("role")=="user";string turn=m.Text("turn_id",m.Text("command_id",""));
            if(!user)Slot(turn);
            string content=m.Text("content","");var block=Ui.Column(Ui.Text(user?"You":"Assistant",12,user?"MutedBrush":"PrimaryBrush",true),ConversationReading.Message(content,OpenWeb));
            var actions=new WrapPanel();actions.Children.Add(Ui.Button("Copy",()=>Clipboard.SetText(content)));
            actions.Children.Add(Ui.Button("Save message",()=>Run(async()=>{await Call("bookmark",WithIdentity(new JsonObject{["conversation_id"]=cid,["message_id"]=m.Text("message_id"),["saved"]=true}));status.Text="Message bookmarked.";})));
            if(!user)actions.Children.Add(Ui.Button("Use in Analysis",()=>{selectedAssistant=content;Navigate("Tools/Analysis");}));block.Children.Add(actions);
            messages.Children.Add(block);rendered[m.Text("message_id")]=block;
        }
        if(engineering && activeEngineeringTurn.Length>0){string turn=openedConversation!.Text("active_command_id",activeEngineeringTurn);
            Slot(turn);activeEngineeringTurn=turn;}
        var composer=Editor("Your message",draft.Text("draft",""),48);composer.MaxHeight=118;composer.MaxLength=32000;
        composer.AcceptsTab=false;
        var sendBar=new WrapPanel();var hint=Ui.Text("",12,"MutedBrush");
        var bottom=Ui.Card(Ui.Column(composer,sendBar,hint));bottom.Padding=new Thickness(12);bottom.Margin=new Thickness(0);Grid.SetRow(bottom,2);center.Children.Add(bottom);
        TextBox? stream=null;string shownTurn="";int lastLines=0;
        void Live(){
            if(liveTurn.Length==0 || liveConversation!=cid || epoch!=viewEpoch)return;
            if(shownTurn!=liveTurn){shownTurn=liveTurn;messages.Children.Add(Ui.Text("You",12,"MutedBrush",true));messages.Children.Add(ConversationReading.Message(livePrompt,OpenWeb));Slot(liveTurn);
                messages.Children.Add(Ui.Text("Assistant",12,"PrimaryBrush",true));
                stream=new TextBox{IsReadOnly=true,AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,BorderThickness=new Thickness(0),Background=Ui.Brush("CanvasBrush"),MinHeight=26};
                AutomationProperties.SetName(stream,"Assistant response");messages.Children.Add(stream);}
            if(stream!=null&&stream.Text.Length<liveText.Length){
                stream.AppendText(liveText[stream.Text.Length..]);
                stream.Dispatcher.BeginInvoke(DispatcherPriority.Loaded,new Action(()=>{int n=Math.Max(1,stream.LineCount);if(n>lastLines){follow.Added(n-lastLines);lastLines=n;}}));}
            Redraw();
        }
        updateLive=Live;updatePreviews=Redraw;Redraw();Live();Models();
        var saveTimer=new DispatcherTimer{Interval=TimeSpan.FromMilliseconds(650)};bool dirty=false;long edit=0;
        Task priorSave=Task.CompletedTask;
        async Task Save(){saveTimer.Stop();await priorSave;if(!dirty || sending || preparingSend || draft["pending"]!=null)return;
            string value=composer.Text,exact=model.SelectedItem?.ToString()??"";long version=edit;dirty=false;
            async Task Write(){try{await Call("draft-save",new JsonObject{["draft"]=value,["model"]=exact});if(version==edit){draft["draft"]=value;draft["model"]=exact;hint.Text="Draft saved";}}
                catch{if(version==edit)dirty=true;throw;}}
            priorSave=Write();try{await priorSave;}finally{priorSave=Task.CompletedTask;}
        }
        flushComposer=Save;saveTimer.Tick+=(_,_)=>Run(Save);
        composer.TextChanged+=(_,_)=>{dirty=true;edit++;saveTimer.Stop();saveTimer.Start();};
        composer.Unloaded+=(_,_)=>{saveTimer.Stop();if(dirty&&!sending)Run(Save);};
        Button? send=null;
        async Task Send(){
            if(sending||preparingSend)return;
            string prompt=composer.Text,selected=model.SelectedItem?.ToString()??"",mode=modes.SelectedItem?.ToString()??"Chat";
            if(string.IsNullOrWhiteSpace(prompt)||string.IsNullOrWhiteSpace(selected))throw new InvalidOperationException("Choose a model and enter a message.");
            await Save();if(sending||preparingSend||epoch!=viewEpoch)return;
            preparingSend=true;if(send!=null)send.IsEnabled=false;
            try{
                await EnsureConnected();if(draft["pending"]!=null)throw new InvalidOperationException("Check the original send first.");
                string turn="desktop-"+Guid.NewGuid().ToString("N");var args=WithIdentity(new JsonObject{["command_id"]=turn,["model"]=selected,["prompt"]=prompt,["mode"]=mode});
                if(cid.Length>0)args["conversation_id"]=cid;
                if(mode=="Engineering"){await StartEngineering(args,engineering?openedConversation:null);return;}
                if(engineering)throw new InvalidOperationException("Continue this conversation in Engineering.");
                sending=true;preparingSend=false;detachSafe=false;composer.IsReadOnly=true;model.IsEnabled=false;modes.IsEnabled=false;
                liveTurn=turn;liveConversation=cid;liveText="";livePrompt=prompt;Live();
                var receipt=await bridge.Call("chat-send",args,(kind,value)=>{
                    if(closed)return;
                    if(kind=="accepted"){detachSafe=value.Flag("detach_safe");status.Text="Gateway accepted the request.";activeJobForStop=value.Text("job_id","");}
                    else if(kind=="activity"){AcceptActivity(value);updatePreviews?.Invoke();}
                    else if(kind=="delta"){liveText+=value?.ToString();updateLive?.Invoke();}
                });
                var opened=await Call("conversation-read",WithIdentity(new JsonObject{["conversation_id"]=receipt.Text("conversation_id")}));
                draft=[];dirty=false;saveTimer.Stop();
                if(CID(openedConversation)==cid){openedConversation=opened.Object("conversation");transcript=opened.Array("messages");if(selectedPage=="Chat")Navigate("Chat");}
                status.Text="Response saved.";
            }catch{identity=null;throw;}
            finally{preparingSend=false;sending=false;activeJobForStop="";liveTurn="";composer.IsReadOnly=false;model.IsEnabled=original.Length==0;modes.IsEnabled=true;if(send!=null)send.IsEnabled=true;}
        }
        send=Ui.Button("Send",()=>Run(Send),true);sendBar.Children.Add(send);
        composer.PreviewKeyDown+=(_,e)=>{if(e.Key==Key.Enter && (Keyboard.Modifiers==ModifierKeys.Control||preferences.Flag("enterSend")&&Keyboard.Modifiers==ModifierKeys.None)){e.Handled=true;Run(Send);}};
        sendBar.Children.Add(Ui.Button("Attach text",()=>Run(async()=>{var f=new OpenFileDialog{Filter="Text or Markdown|*.txt;*.md;*.csv;*.json"};if(f.ShowDialog(this)!=true)return;
            if(new FileInfo(f.FileName).Length>30000)throw new InvalidOperationException("Choose a text file under 30 KB.");
            string add="\n"+System.IO.Path.GetFileName(f.FileName)+"\n"+await File.ReadAllTextAsync(f.FileName);
            if(composer.Text.Length+add.Length>32000)throw new InvalidOperationException("The attachment exceeds the remaining message length.");composer.AppendText(add);})));
        sendBar.Children.Add(Ui.Button("Latest",follow.Jump));
        sendBar.Children.Add(Ui.Button("Check send",()=>Run(async()=>{var r=await Call("chat-reconcile");
            if(r["opened"] is JsonObject o){openedConversation=o.Object("conversation");transcript=o.Array("messages");draft=[];Navigate("Chat");}else ShowDetails("Original send",r);})));
        sendBar.Children.Add(Ui.Button("Reconnect",()=>Run(async()=>{await Save();await EnsureConnected(true);await RefreshChatActivity();Models();hint.Text="Reconnected";})));
        var stop=Ui.Button("Stop",()=>Run(async()=>{if(activeJobForStop.Length>0&&Confirm("Request cancellation of this response?")){
            await Call("job-cancel",WithIdentity(new JsonObject{["job_id"]=activeJobForStop}));status.Text="Cancellation requested.";}}));
        stop.Visibility=Visibility.Collapsed;sendBar.Children.Add(stop);
        updateLive=()=>{Live();stop.Visibility=activeJobForStop.Length==0?Visibility.Collapsed:Visibility.Visible;};
        layout.SizeChanged+=(_,_)=>{bool wide=layout.ActualWidth>=800;rail.Visibility=wide?Visibility.Visible:Visibility.Collapsed;layout.ColumnDefinitions[0].Width=new GridLength(wide?205:0);};
        FillWindow(layout);
        reader.Loaded+=(_,_)=>{if(bookmarkTarget is {} id && rendered.TryGetValue(id,out var node)){follow.Restore(false,0);node.BringIntoView();bookmarkTarget=null;}
            else if(readingPositions.TryGetValue(readingKey,out var saved))follow.Restore(saved.Follow,saved.Offset);else follow.Jump();};
        Run(async()=>{await EnsureConnected();if(epoch!=viewEpoch)return;Models();if(!dirty&&composer.Text.Length==0)composer.Text=draft.Text("draft","");
            await History();await RefreshChatActivity();if(activityGaps.Contains(cid))hint.Text="Earlier public activity is outside retention.";});
    }
    private string activeJobForStop="";
}
