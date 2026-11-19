using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public static class JsonValues
{
    public static string Text(this JsonNode? value, string key, string fallback = "—") =>
        value is JsonObject obj && obj[key] is JsonValue found ? found.ToString() : fallback;
    public static bool Flag(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonValue found && found.TryGetValue<bool>(out var result) && result;
    public static double? Number(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonValue found && found.TryGetValue<double>(out var result) ? result : null;
    public static JsonArray Array(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonArray result ? result : [];
    public static JsonObject Object(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonObject result ? result : [];
}
