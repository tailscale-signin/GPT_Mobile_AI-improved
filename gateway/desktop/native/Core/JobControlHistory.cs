using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

/// <summary>Never replace unreadable history or forget an unresolved cancellation command.</summary>
public sealed class JobControlHistory
{
    public const int MaximumBytes = 8 * 1024 * 1024;
    private readonly string path;
    public JsonObject Preferences { get; }
    public string Error { get; }
    public bool Writable => Error.Length == 0;

    private JobControlHistory(string path, JsonObject preferences, string error = "")
    { this.path=path;Preferences=preferences;Error=error; }

    public static JobControlHistory Open(string path)
    {
        try
        {
            using var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read);
            if(stream.Length>MaximumBytes)throw new InvalidDataException();
            using var reader=new StreamReader(stream,Encoding.UTF8,detectEncodingFromByteOrderMarks:true);
            string text=reader.ReadToEnd();
            if(Encoding.UTF8.GetByteCount(text)>MaximumBytes)throw new InvalidDataException();
            var preferences=JsonNode.Parse(text) as JsonObject ?? throw new InvalidDataException();
            Validate(preferences);
            return new(path,preferences);
        }
        catch(Exception error) when(error is FileNotFoundException or DirectoryNotFoundException)
        { return new(path,[]); }
        catch(Exception error) when(error is IOException or InvalidDataException or UnauthorizedAccessException or JsonException or ArgumentException or InvalidOperationException)
        {
            return new(path,[],"Saved cancellation history could not be read. Job controls and preference saves are paused. " +
                "Restore access to desktop-appearance.json or recover its original contents, then reload saved history. The original file is kept.");
        }
    }

    public void Save(JsonObject preferences)
    {
        if(!Writable)throw new InvalidOperationException(Error);
        Validate(preferences);
        string serialized=preferences.ToJsonString();
        if(Encoding.UTF8.GetByteCount(serialized)>MaximumBytes)
            throw new InvalidOperationException("The retained desktop job history is full. No new job control was sent.");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temporary=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        try { File.WriteAllText(temporary,serialized);File.Move(temporary,path,true); }
        finally { File.Delete(temporary); }
    }

    private static void Validate(JsonObject preferences)
    {
        if(!preferences.TryGetPropertyValue("jobCancellationRequests",out var value))return;
        if(value is not JsonObject requests)throw new InvalidDataException("Saved cancellation history is malformed.");
        foreach(var entry in requests)
        {
            if(entry.Key.Length!=64 || !entry.Key.All(char.IsAsciiHexDigit) || entry.Value is not JsonObject request)
                throw new InvalidDataException("Saved cancellation history is malformed.");
            ValidateRequest(request);
            if(request.TryGetPropertyValue("previous_requests",out var earlier))
            {
                if(earlier is not JsonArray previous)throw new InvalidDataException("Saved cancellation history is malformed.");
                foreach(var item in previous)
                {
                    if(item is not JsonObject prior)throw new InvalidDataException("Saved cancellation history is malformed.");
                    ValidateRequest(prior);
                    if(prior.ContainsKey("previous_requests") || !JobQueuePresentation.DefinitivelyNotDispatched(prior,prior.Object("rejection")))
                        throw new InvalidDataException("An earlier cancellation remains unresolved.");
                }
            }
        }
    }

    private static void ValidateRequest(JsonObject request)
    {
        var target=request.Object("target");
        if(!Guid.TryParse(request.Text("command_id",""),out var command) || command==Guid.Empty ||
            string.IsNullOrWhiteSpace(request.Text("job_id","")) ||
            new[]{"gateway_id","principal_id"}.Any(field=>string.IsNullOrWhiteSpace(target.Text(field,""))) ||
            target.Text("job_kind","") is not ("engineering" or "generation") ||
            target.Number("revision") is not { } revision || revision<0 || !double.IsFinite(revision) || revision!=Math.Truncate(revision) ||
            target.Text("job_kind","")=="generation" && string.IsNullOrWhiteSpace(target.Text("generation","")) ||
            request.ContainsKey("rejection") && !JobQueuePresentation.DefinitivelyNotDispatched(request,request.Object("rejection")))
            throw new InvalidDataException("Saved cancellation history is malformed.");
    }
}
