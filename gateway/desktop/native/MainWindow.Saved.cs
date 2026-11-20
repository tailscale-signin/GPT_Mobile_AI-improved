using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

public partial class MainWindow
{
    private void BuildSaved()
    {
        var layout=new Grid();layout.ColumnDefinitions.Add(new(){Width=new GridLength(1,GridUnitType.Star)});layout.ColumnDefinitions.Add(new(){Width=new GridLength(1.4,GridUnitType.Star)});
        var left=new DockPanel{Margin=new Thickness(0,0,12,0)};layout.Children.Add(left);
        var options=new StackPanel();DockPanel.SetDock(options,Dock.Top);left.Children.Add(options);
        var query=Ui.Field(options,"Search saved work");
        var filter=Ui.Select(options,"Collection",new[]{"Conversations","Favorites","Archived","Bookmarked messages"});
        var list=new ListBox{DisplayMemberPath="Label"};AutomationProperties.SetName(list,"Saved results");
        var detail=new StackPanel();var reader=new ScrollViewer{Content=detail,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};
        Grid.SetColumn(reader,1);layout.Children.Add(reader);
        var records=new System.Collections.ObjectModel.ObservableCollection<Choice>();list.ItemsSource=records;
        string? cursor=null;int version=0,reading=0;bool loadingMore=false;
        var more=new Button{Content="Load more"};more.Click+=(_,_)=>Run(()=>Load(true));more.IsEnabled=false;
        var foot=Ui.Column(more);DockPanel.SetDock(foot,Dock.Bottom);left.Children.Add(foot);left.Children.Add(list);
        Choice? Selected()=>list.SelectedItem as Choice;
        async Task Load(bool append=false){
            if(append&&(cursor==null||loadingMore))return;
            int request=append?version:++version;bool bookmarks=filter.SelectedIndex==3;string? previous=Selected()?.Value.Text("conversation_id");
            string? next=append?cursor:null;if(append)loadingMore=true;
            try{await EnsureConnected();var args=WithIdentity(new JsonObject{["query"]=query.Text,["favorite"]=filter.SelectedIndex==1,["archived"]=filter.SelectedIndex==2,["cursor"]=next});
                var result=await Call(bookmarks?"saved":"conversations",args);if(request!=version)return;
                if(!append){records.Clear();detail.Children.Clear();}
                foreach(var row in (result["items"] as JsonArray??result.Array("conversations")).OfType<JsonObject>())
                    records.Add(new Choice(bookmarks?row.Text("excerpt",row.Text("content","Saved message")):row.Text("title","Conversation"),row));
                cursor=result.Flag("has_more")||result["has_more"]==null?result["next_cursor"]?.ToString():null;more.IsEnabled=cursor!=null;
                if(!append && records.Count>0)list.SelectedItem=records.FirstOrDefault(r=>CID(r.Value)==previous)??records[0];
                if(records.Count==0)detail.Children.Add(Ui.Text("No saved items match this collection.",15,"MutedBrush"));
            }finally{if(request==version)loadingMore=false;}
        }
        async Task Open(){if(Selected() is {} r)await OpenConversation(CID(r.Value),filter.SelectedIndex==3?r.Value.Text("message_id",""):null);}
        async Task Preview(){
            int n=++reading;if(Selected() is not {} r)return;
            string chosen=CID(r.Value);var receipt=await Call("conversation-read",WithIdentity(new JsonObject{["conversation_id"]=chosen}));if(reading!=n)return;
            var conv=receipt.Object("conversation");detail.Children.Clear();detail.Children.Add(Ui.Text(conv.Text("title"),22,bold:true));
            detail.Children.Add(Ui.Text(conv.Text("model")+" · "+conv.Text("active_job_kind","Conversation")+" · "+conv.Text("active_job_id",""),13,"MutedBrush"));
            var actions=Ui.Actions(Ui.Button("Open in Chat",()=>Run(Open),true));detail.Children.Add(actions);
            async Task Change(string field){
                var changes=new JsonObject();
                if(field=="title"){var title=PromptText("Rename conversation","Title",conv.Text("title"));if(title==null)return;changes[field]=title;}
                else changes[field]=!conv.Flag(field);
                await Call("conversation-metadata",WithIdentity(new JsonObject{["conversation_id"]=chosen,["revision"]=conv["metadata_revision"]?.DeepClone(),["changes"]=changes}));
                await Load();
            }
            actions.Children.Add(Ui.Button("Rename",()=>Run(()=>Change("title"))));
            actions.Children.Add(Ui.Button(conv.Flag("favorite")?"Unfavorite":"Favorite",()=>Run(()=>Change("favorite"))));
            actions.Children.Add(Ui.Button(conv.Flag("archived")?"Restore":"Archive",()=>Run(()=>Change("archived"))));
            var messages=receipt.Array("messages").OfType<JsonObject>();
            if(filter.SelectedIndex==3)messages=messages.Where(m=>m.Text("message_id")==r.Value.Text("message_id"));
            foreach(var msg in messages.TakeLast(12)){detail.Children.Add(Ui.Text(msg.Text("role"),12,"PrimaryBrush",true));detail.Children.Add(ConversationReading.Message(msg.Text("content",""),OpenWeb));}
            if(!conv.Flag("history_complete"))detail.Children.Add(Ui.Text(conv.Text("history_limitation","Retained history is partial."),14,"MutedBrush"));
        }
        list.SelectionChanged+=(_,_)=>Run(Preview);list.MouseDoubleClick+=(_,_)=>Run(Open);
        list.PreviewKeyDown+=(_,e)=>{if(e.Key==Key.Enter){e.Handled=true;Run(Open);}};
        options.Children.Add(Ui.Button("Search",()=>Run(()=>Load())));query.KeyDown+=(_,e)=>{if(e.Key==Key.Enter)Run(()=>Load());};filter.SelectionChanged+=(_,_)=>Run(()=>Load());
        FillWindow(layout);Run(()=>Load());
    }
}
