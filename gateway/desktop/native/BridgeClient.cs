using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Gateway.Desktop.Core;

namespace Gateway.Desktop;

internal sealed class BridgeClient(string root, string runtime)
{
    public async Task<JsonObject> Call(string action, JsonObject? args = null, Action<string, JsonNode?>? progress = null)
    {
        if (!File.Exists(runtime)) throw new InvalidOperationException("The installed Gateway runtime is unavailable. Repair this installation.");
        var start = new ProcessStartInfo(runtime) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8 };
        foreach (var arg in new[] { "native-bridge", "--root", root, "--quiet" }) start.ArgumentList.Add(arg);
        using var child = Process.Start(start) ?? throw new InvalidOperationException("Could not start the local Gateway adapter.");
        // Drain stderr, but never surface raw subprocess output or credential data.
        var stderr = BridgeStream.DrainPrivateOutput(child.StandardError);
        try
        {
            await child.StandardInput.WriteLineAsync(new JsonObject { ["protocol"] = "gptmobile.desktop.v1", ["action"] = action,
                ["args"] = args ?? new JsonObject() }.ToJsonString());
            child.StandardInput.Close();
            JsonObject? result = null;
            string? failure = null;
            int frames = 0;
            await foreach (string line in BridgeStream.Lines(child.StandardOutput))
            {
                if (++frames > 100000)
                    throw new InvalidOperationException("The Gateway observation exceeded its display limit. Check the saved receipt.");
                var frame = BridgeFrame.Parse(line);
                if (frame.Event == "error") failure = frame.Value?["message"]?.ToString() ?? "Gateway could not confirm the action.";
                else if (frame.Event == "result") result = frame.Value as JsonObject ?? new JsonObject { ["items"] = frame.Value?.DeepClone() };
                else if (frame.Event is "delta" or "accepted") progress?.Invoke(frame.Event, frame.Value);
            }
            await child.WaitForExitAsync();
            await stderr;
            if (failure is not null) throw new InvalidOperationException(failure);
            if (child.ExitCode != 0 || result is null)
                throw new InvalidOperationException("The observation ended without a receipt. Check the original operation before retrying.");
            return result;
        }
        catch
        {
            // End only this presentation adapter. The supervisor and accepted
            // runtime jobs retain ownership and are reconciled by command ID.
            try { if (!child.HasExited) child.Kill(entireProcessTree: false); }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { }
            try { await stderr.WaitAsync(TimeSpan.FromSeconds(2)); }
            catch (Exception error) when (error is IOException or TimeoutException or ObjectDisposedException) { }
            throw;
        }
    }
}
