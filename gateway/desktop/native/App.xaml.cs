using System.Windows;
using System.Windows.Media;

namespace Gateway.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args.Length == 0)
        {
            // Double-clicks also go through the installed version pointer and the one-window lock.
            string launcher = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "Start-Gateway.ps1"));
            try
            {
                if (!File.Exists(launcher)) throw new FileNotFoundException("Run the Gateway installer to set up this PC.");
                var start = new System.Diagnostics.ProcessStartInfo(Path.Combine(Environment.SystemDirectory, @"WindowsPowerShell\v1.0\powershell.exe"))
                    { UseShellExecute = false, CreateNoWindow = true };
                foreach (var arg in new[] { "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", launcher, "-Command", "dashboard" })
                    start.ArgumentList.Add(arg);
                System.Diagnostics.Process.Start(start)?.Dispose();
                Shutdown();
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "Open Gateway", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown(1);
            }
            return;
        }
        ApplyContrast();
        SystemParameters.StaticPropertyChanged += (_, args) =>
        {
            if (args.PropertyName == nameof(SystemParameters.HighContrast) || Appearance=="System") ApplyContrast();
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

    internal string Appearance { get; set; } = "Dark";

    internal void ApplyContrast(bool? highContrast = null)
    {
        var colors = new Dictionary<string, string>
        {
            ["CanvasBrush"] = "#001219", ["PanelBrush"] = "#0B2028", ["RaisedBrush"] = "#103239",
            ["PrimaryBrush"] = "#00FFDE", ["PrimaryTextBrush"] = "#001219", ["SecondaryBrush"] = "#CC00FF", ["TextBrush"] = "#E0F4F7",
            ["MutedBrush"] = "#A3C4CD", ["LineBrush"] = "#24464E"
        };
        bool light = Appearance=="Light" || Appearance=="System" && Microsoft.Win32.Registry.GetValue(
            @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",1) is int v && v!=0;
        if(light) {
            colors["CanvasBrush"]="#EFF6F7";colors["PanelBrush"]="#FFFFFF";colors["RaisedBrush"]="#DCF0F2";
            colors["PrimaryBrush"]="#00695E";colors["PrimaryTextBrush"]="#FFFFFF";colors["SecondaryBrush"]="#762894";
            colors["TextBrush"]="#102D37";colors["MutedBrush"]="#405C65";colors["LineBrush"]="#A4BEC4";
        }
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
