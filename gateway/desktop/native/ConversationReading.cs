using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using System.Text.RegularExpressions;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

internal sealed class ReadingFollow
{
    private readonly ScrollViewer view;
    private readonly Func<bool> reduced;
    private readonly DispatcherTimer motion=new(){Interval=TimeSpan.FromMilliseconds(20)};
    private readonly Stopwatch clock=new();
    private double from,to,ms;
    private bool gesture;
    public bool Following {get;private set;}=true;
    public ReadingFollow(ScrollViewer view,Func<bool> reduced)
    {
        this.view=view;this.reduced=reduced;
        void Intent(){gesture=true;motion.Stop();view.Dispatcher.BeginInvoke(DispatcherPriority.ContextIdle,new Action(()=>{if(gesture){Following=Bottom;gesture=false;}}));}
        view.PreviewMouseWheel+=(_,e)=>{if(e.Delta>0)Following=false;Intent();};
        view.PreviewKeyDown+=(_,e)=>{if(e.Key is Key.Up or Key.Down or Key.End or Key.Home or Key.PageUp or Key.PageDown){Following=false;Intent();}};
        view.PreviewTouchMove+=(_,_)=>{Following=false;Intent();};view.PreviewTouchUp+=(_,_)=>Intent();
        view.ScrollChanged+=(_,e)=>{if(e.VerticalChange!=0 && (gesture||Mouse.LeftButton==MouseButtonState.Pressed)){motion.Stop();Following=Bottom;}};
        view.AddHandler(System.Windows.Controls.Primitives.ScrollBar.ScrollEvent,new System.Windows.Controls.Primitives.ScrollEventHandler((_,_)=>Intent()));
        view.Unloaded+=(_,_)=>motion.Stop();
        motion.Tick+=(_,_)=>{
            if(!Following || reduced() || clock.Elapsed.TotalMilliseconds>=ms){motion.Stop();if(Following)view.ScrollToVerticalOffset(view.ScrollableHeight);return;}
            var f=clock.Elapsed.TotalMilliseconds/ms;view.ScrollToVerticalOffset(from+(to-from)*f);
        };
    }
    private bool Bottom=>view.ScrollableHeight-view.VerticalOffset<=2;
    public void Restore(bool follow,double offset){Following=follow;if(follow)view.ScrollToEnd();else view.ScrollToVerticalOffset(offset);}
    public void Jump(){Following=true;motion.Stop();view.ScrollToEnd();}
    public void Added(int lines)
    {
        if(!Following||gesture||lines<=0)return;
        if(reduced()){Jump();return;}
        double remaining=motion.IsEnabled?Math.Max(0,ms-clock.Elapsed.TotalMilliseconds):0;
        from=view.VerticalOffset;to=view.ScrollableHeight;ms=remaining+PublicPreview.ReadingMs(lines);clock.Restart();motion.Start();
    }
}

internal sealed class ActivityText : TextBlock
{
    private string latest="";
    private bool busy, initial=true;
    private readonly Func<bool> reduced;
    public ActivityText(Func<bool> reduced){this.reduced=reduced;TextWrapping=TextWrapping.Wrap;SetResourceReference(ForegroundProperty,"MutedBrush");FontSize=13;}
    public void Present(string value)
    {
        latest=value;
        if(reduced()){BeginAnimation(OpacityProperty,null);Text=latest;Opacity=1;busy=false;initial=false;return;}
        if(busy || Text==value)return;
        busy=true;
        if(initial){initial=false;Text=latest;Fade(0,1,PublicPreview.FadeInMs,Complete);}
        else Fade(1,0,PublicPreview.RefreshMs/2,()=>{Text=latest;Fade(0,1,PublicPreview.RefreshMs/2,Complete);});
        void Complete(){busy=false;if(Text!=latest)Present(latest);}
    }
    private void Fade(double a,double b,int ms,Action complete){
        var animation=new DoubleAnimation(a,b,TimeSpan.FromMilliseconds(ms)){FillBehavior=FillBehavior.Stop};
        Opacity=b;animation.Completed+=(_,_)=>complete();BeginAnimation(OpacityProperty,animation);
    }
}

internal static class ConversationReading
{
    public static FrameworkElement Message(string text,Action<string> open)
    {
        var doc=new FlowDocument{PagePadding=new Thickness(0),FontFamily=new FontFamily("Segoe UI"),FontSize=15};
        void Para(string value,bool code=false){
            var p=new Paragraph{Margin=new Thickness(0,2,0,5)};
            if(code){p.FontFamily=new FontFamily("Consolas");p.Background=Ui.Brush("RaisedBrush");p.Padding=new Thickness(8);p.Inlines.Add(new Run(value));}
            else{
                int pos=0;foreach(Match m in Regex.Matches(value,@"\[([^\]]+)\]\((https?://[^\s)]+)\)|(https?://[^\s<>]+)")){
                    p.Inlines.Add(new Run(value[pos..m.Index]));string url=m.Groups[2].Success?m.Groups[2].Value:m.Value;
                    var link=new Hyperlink(new Run(m.Groups[1].Success?m.Groups[1].Value:url));link.SetResourceReference(Hyperlink.ForegroundProperty,"PrimaryBrush");
                    link.Click+=(_,_)=>open(url);p.Inlines.Add(link);pos=m.Index+m.Length;
                }
                p.Inlines.Add(new Run(value[pos..]));
            }
            doc.Blocks.Add(p);
        }
        var lines=text.Replace("\r\n","\n").Split('\n');var code=new List<string>();bool fence=false;
        for(int i=0;i<lines.Length;i++){
            string line=lines[i];
            if(line.TrimStart().StartsWith("```")){if(fence){Para(string.Join("\n",code),true);code.Clear();}fence=!fence;continue;}
            if(fence){code.Add(line);continue;}
            if(line.Contains('|')&&i+1<lines.Length&&Regex.IsMatch(lines[i+1],@"^\s*\|?\s*:?-{3,}")){
                var table=new Table{CellSpacing=3};var group=new TableRowGroup();table.RowGroups.Add(group);
                void Row(string data,bool heading){var row=new TableRow();foreach(var cell in data.Trim().Trim('|').Split('|'))row.Cells.Add(new TableCell(new Paragraph(new Run(cell.Trim()))){FontWeight=heading?FontWeights.SemiBold:FontWeights.Normal});group.Rows.Add(row);}
                Row(line,true);i++;while(i+1<lines.Length&&lines[i+1].Contains('|'))Row(lines[++i],false);doc.Blocks.Add(table);
            }else Para(line);
        }
        if(code.Count>0)Para(string.Join("\n",code),true);
        var viewer=new FlowDocumentScrollViewer{Document=doc,IsToolBarVisible=false,VerticalScrollBarVisibility=ScrollBarVisibility.Disabled,
            HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled,IsSelectionEnabled=true,Focusable=true};
        viewer.SetResourceReference(Control.ForegroundProperty,"TextBrush");return viewer;
    }
}
