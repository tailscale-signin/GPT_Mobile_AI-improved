using System.Windows;
using System.Windows.Media;

namespace Gateway.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ApplyContrast();
        SystemParameters.StaticPropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(SystemParameters.HighContrast)) ApplyContrast();
        };
        try
        {
            MainWindow = new MainWindow(e.Args);
            MainWindow.Show();
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Gateway could not open", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    internal void ApplyContrast(bool? highContrast = null)
    {
        var colors = new Dictionary<string, string>
        {
            ["CanvasBrush"] = "#001219", ["PanelBrush"] = "#0B2028", ["RaisedBrush"] = "#103239",
            ["PrimaryBrush"] = "#00FFDE", ["PrimaryTextBrush"] = "#001219", ["SecondaryBrush"] = "#CC00FF", ["TextBrush"] = "#E0F4F7",
            ["MutedBrush"] = "#A3C4CD", ["LineBrush"] = "#24464E"
        };
        foreach (var (key, color) in colors)
            Resources[key] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color));
        if (!(highContrast ?? SystemParameters.HighContrast)) return;
        foreach (var key in new[] { "CanvasBrush", "PanelBrush", "RaisedBrush" }) Resources[key] = SystemColors.WindowBrush;
        foreach (var key in new[] { "TextBrush", "MutedBrush" }) Resources[key] = SystemColors.WindowTextBrush;
        Resources["PrimaryBrush"] = SystemColors.HighlightBrush;
        Resources["PrimaryTextBrush"] = SystemColors.HighlightTextBrush;
        Resources["SecondaryBrush"] = SystemColors.HighlightBrush;
        Resources["LineBrush"] = SystemColors.WindowTextBrush;
    }
}
