using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Text.Json.Nodes;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

internal static class Ui
{
    public static SolidColorBrush Brush(string key) => (SolidColorBrush)Application.Current.FindResource(key);

    public static TextBlock Text(string text, double size = 15, string color = "TextBrush", bool bold = false)
    {
        var control = new TextBlock { Text = text, FontSize = size, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8) };
        control.SetResourceReference(TextBlock.ForegroundProperty, color);
        return control;
    }

    public static StackPanel Column(params UIElement[] children)
    {
        var result = new StackPanel();
        foreach (var child in children) result.Children.Add(child);
        return result;
    }

    public static Border Card(UIElement child, bool hero = false)
    {
        var result = new Border { Child = child, CornerRadius = new CornerRadius(hero ? 24 : 20),
            Padding = new Thickness(20), BorderThickness = new Thickness(1), Margin = new Thickness(0, 0, 0, 16) };
        result.SetResourceReference(Border.BackgroundProperty, "PanelBrush");
        result.SetResourceReference(Border.BorderBrushProperty, "LineBrush");
        return result;
    }

    public static Button Button(string title, Action action, bool primary = false)
    {
        var button = new Button { Content = title };
        AutomationProperties.SetName(button, title);
        if (primary) button.SetResourceReference(FrameworkElement.StyleProperty, "PrimaryButton");
        button.Click += (_, _) => action();
        return button;
    }

    public static WrapPanel Actions(params UIElement[] items)
    {
        var result = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
        foreach (var item in items) result.Children.Add(item);
        return result;
    }

    public static TextBox Field(StackPanel parent, string label, string value = "")
    {
        parent.Children.Add(Text(label, color: "MutedBrush"));
        var input = new TextBox { Text = value };
        AutomationProperties.SetName(input, label);
        parent.Children.Add(input);
        return input;
    }

    public static ComboBox Select(StackPanel parent, string label, IEnumerable<string> options, string? selected = null)
    {
        parent.Children.Add(Text(label, color: "MutedBrush"));
        var box = new ComboBox { ItemsSource = options.ToArray() };
        AutomationProperties.SetName(box, label);
        box.SelectedItem = selected;
        if (box.SelectedIndex < 0 && box.Items.Count > 0) box.SelectedIndex = 0;
        parent.Children.Add(box);
        return box;
    }

    public static CheckBox Toggle(StackPanel parent, string label, bool value)
    {
        var check = new CheckBox { Content = label, IsChecked = value };
        AutomationProperties.SetName(check, label);
        parent.Children.Add(check);
        return check;
    }

    public static void Rows(ListBox list, IEnumerable<JsonNode?> values, string titleKey = "title", string statusKey = "status")
    {
        list.Items.Clear();
        foreach (var value in values.OfType<JsonObject>())
        {
            var title = value.Text(titleKey, value.Text("name", value.Text("id", "Saved item")));
            var detail = value.Text(statusKey, value.Text("state", ""));
            var body = Column(Text(title, 16, bold: true), Text(detail.Replace('_', ' '), 13, "MutedBrush"));
            var extra = value.Text("detail", value.Text("description", ""));
            if (extra.Length > 0) body.Children.Add(Text(extra, 13, "MutedBrush"));
            list.Items.Add(new ListBoxItem { Content = body, Tag = value.DeepClone() });
        }
    }

    public static JsonObject? Selected(ListBox list) => (list.SelectedItem as ListBoxItem)?.Tag as JsonObject;

    public static IEnumerable<T> Descendants<T>(DependencyObject parent) where T : DependencyObject
    {
        for (int index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is T found) yield return found;
            foreach (var descendant in Descendants<T>(child)) yield return descendant;
        }
    }

    public static void Capture(FrameworkElement view, string file)
    {
        view.UpdateLayout();
        var dpi = VisualTreeHelper.GetDpi(view);
        var image = new RenderTargetBitmap((int)Math.Ceiling(view.ActualWidth * dpi.DpiScaleX),
            (int)Math.Ceiling(view.ActualHeight * dpi.DpiScaleY), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        image.Render(view);
        var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(image));
        using var stream = File.Create(file); png.Save(stream);
    }
}
