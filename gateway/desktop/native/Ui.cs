using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
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

    public static StackPanel NavigationLabel(string glyph, string? label)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 20, VerticalAlignment = VerticalAlignment.Center });
        if (label is not null)
            row.Children.Add(new TextBlock { Text = label, Margin = new Thickness(12, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center, FontSize = 15 });
        foreach (TextBlock text in row.Children)
            text.SetBinding(TextBlock.ForegroundProperty, new System.Windows.Data.Binding("Foreground")
                { RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.FindAncestor, typeof(Button), 1) });
        return row;
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
        result.SetResourceReference(Border.BackgroundProperty, hero ? "RaisedBrush" : "PanelBrush");
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
        SaveCapture(image, file);
    }

    public static bool FullyWithinRenderBounds(FrameworkElement element, FrameworkElement root) => FullyWithinRenderBounds(element, root, out _);

    public static bool ClipContainsRenderBounds(Geometry clip, Rect bounds)
    {
        // WPF viewport rectangles include their edges; the geometry relation can
        // report Intersects for a contained row that shares those exact edges.
        if (clip is RectangleGeometry rectangle && rectangle.RadiusX == 0 && rectangle.RadiusY == 0 &&
            (rectangle.Transform == null || rectangle.Transform.Value.IsIdentity))
            return rectangle.Rect.Contains(bounds);
        return clip.FillContainsWithDetail(new RectangleGeometry(bounds)) == IntersectionDetail.FullyContains;
    }

    public static bool FullyWithinRenderBounds(FrameworkElement element, FrameworkElement root, out string rejection)
    {
        rejection = "";
        if (!element.IsVisible || element.ActualWidth <= 0 || element.ActualHeight <= 0)
        {
            rejection = $"{element.GetType().Name}: IsVisible={element.IsVisible}, RenderSize={element.RenderSize}";
            return false;
        }
        var bounds = new Rect(element.RenderSize);
        Visual current = element;
        while (true)
        {
            var clip = VisualTreeHelper.GetClip(current);
            if (clip != null && !ClipContainsRenderBounds(clip, bounds))
            {
                rejection = $"{current.GetType().Name}: bounds={bounds}, clip={clip.Bounds}, relation={clip.FillContainsWithDetail(new RectangleGeometry(bounds))}";
                return false;
            }
            if (ReferenceEquals(current, root))
            {
                bool contained = new Rect(root.RenderSize).Contains(bounds);
                if (!contained) rejection = $"Root: bounds={bounds}, RenderSize={root.RenderSize}";
                return contained;
            }
            if (VisualTreeHelper.GetParent(current) is not Visual parent)
            {
                rejection = $"{current.GetType().Name}: no visual parent before the capture root";
                return false;
            }
            bounds = current.TransformToAncestor(parent).TransformBounds(bounds);
            current = parent;
        }
    }

    public static async Task CaptureReference(Window owner, FrameworkElement content, Size size, string file, Action<FrameworkElement> verify)
    {
        if (!ReferenceEquals(owner.Content, content) || size.Width <= 0 || size.Height <= 0 ||
            size.Width != Math.Floor(size.Width) || size.Height != Math.Floor(size.Height))
            throw new InvalidOperationException("Reference capture requires the current content and an integral layout size.");

        // A CI desktop can clamp Window.ActualWidth/Height below the reference size.
        // Present the same controls in an offscreen popup, without the Window's client clip.
        var dpi = VisualTreeHelper.GetDpi(owner);
        using var source = new HwndSource(new HwndSourceParameters("Gateway reference capture")
        {
            Width = (int)Math.Ceiling(size.Width * dpi.DpiScaleX), Height = (int)Math.Ceiling(size.Height * dpi.DpiScaleY),
            PositionX = -32000, PositionY = -32000,
            WindowStyle = unchecked((int)0x90000000), // WS_POPUP | WS_VISIBLE
            ExtendedWindowStyle = 0x08000080 // WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW
        });
        var frame = new Border
        {
            Width = size.Width, Height = size.Height, Background = owner.Background, Resources = owner.Resources,
            DataContext = owner.DataContext, FlowDirection = owner.FlowDirection, Language = owner.Language,
            UseLayoutRounding = owner.UseLayoutRounding, SnapsToDevicePixels = owner.SnapsToDevicePixels
        };
        if (frame.Background is not SolidColorBrush backdrop || backdrop.Color.A != byte.MaxValue ||
            backdrop.Opacity != 1 || frame.Opacity != 1)
            throw new InvalidOperationException("Reference capture requires a fully opaque solid background.");
        TextElement.SetFontFamily(frame, owner.FontFamily);
        TextElement.SetFontSize(frame, owner.FontSize);
        TextElement.SetFontStyle(frame, owner.FontStyle);
        TextElement.SetFontWeight(frame, owner.FontWeight);
        TextElement.SetFontStretch(frame, owner.FontStretch);
        TextElement.SetForeground(frame, owner.Foreground);
        owner.Content = null;
        try
        {
            try { frame.Child = content; }
            catch (Exception error)
            {
                throw new InvalidOperationException($"Reference attachment failed: visualParent={VisualTreeHelper.GetParent(content)?.GetType().FullName ?? "none"}, logicalParent={content.Parent?.GetType().FullName ?? "none"}, requested={size}.", error);
            }
            source.RootVisual = frame;
            content.InvalidateMeasure(); content.InvalidateArrange();
            frame.Measure(size); frame.Arrange(new Rect(size)); frame.UpdateLayout();
            await frame.Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ContextIdle);
            frame.UpdateLayout();
            bool complete = FullyWithinRenderBounds(content, frame, out string rejection);
            var sourceDpi = VisualTreeHelper.GetDpi(frame);
            if (frame.RenderSize != size || content.RenderSize != size || !complete)
                throw new InvalidOperationException($"Reference content did not render at its complete requested bounds: requested={size}, frame={frame.RenderSize}, content={content.RenderSize}, owner={owner.RenderSize}, ownerDpi={dpi.DpiScaleX}x{dpi.DpiScaleY}, sourceDpi={sourceDpi.DpiScaleX}x{sourceDpi.DpiScaleY}, rejection={rejection}.");
            verify(frame);

            // Render WPF at the reference DPI; never resize or pad a clipped bitmap.
            var image = new RenderTargetBitmap((int)size.Width, (int)size.Height, 96, 96, PixelFormats.Pbgra32);
            image.Render(frame);
            int stride = image.PixelWidth * 4;
            var pixels = new byte[stride * image.PixelHeight];
            image.CopyPixels(pixels, stride, 0);
            // Keep the exact rendered PNG even when coverage validation fails.
            SaveCapture(image, file);
            int roundedGlyphPixels = ReferenceCaptureCoverage.ValidatePbgra32(pixels, image.PixelWidth, image.PixelHeight);
            Console.WriteLine($"Reference capture coverage: {image.PixelWidth}x{image.PixelHeight}, glyph-alpha rounding pixels={roundedGlyphPixels}.");
        }
        finally
        {
            source.RootVisual = null;
            frame.Child = null;
            owner.Content = content;
            owner.UpdateLayout();
        }
    }

    private static void SaveCapture(BitmapSource image, string file)
    {
        var png = new PngBitmapEncoder(); png.Frames.Add(BitmapFrame.Create(image));
        using var stream = File.Create(file); png.Save(stream);
    }
}
