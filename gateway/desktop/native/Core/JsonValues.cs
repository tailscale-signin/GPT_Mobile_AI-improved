using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public static class JsonValues
{
    public static string Text(this JsonNode? value, string key, string fallback = "—") =>
        value is JsonObject obj && obj[key] is JsonValue found ? found.ToString() : fallback;
    public static bool Flag(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonValue found && found.TryGetValue<bool>(out var result) && result;
    public static double? Number(this JsonNode? value, string key)
    {
        if (value is not JsonObject obj || obj[key] is not JsonValue number) return null;
        if (number.TryGetValue<double>(out var result)) return result;
        if (number.TryGetValue<long>(out var whole)) return whole;
        return number.TryGetValue<int>(out var small) ? small : null;
    }
    public static JsonArray Array(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonArray result ? result : [];
    public static JsonObject Object(this JsonNode? value, string key) =>
        value is JsonObject obj && obj[key] is JsonObject result ? result : [];
}
