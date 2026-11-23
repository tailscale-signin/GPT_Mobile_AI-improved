using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

public static class BenchmarkComparisonPresentation
{
    public static string Headline(JsonObject summary) =>
        summary.Text("status", "") == "SUPERIOR_ON_MATCHED_SUITE" &&
        summary["winner"] is JsonValue value && value.TryGetValue<string>(out var winner) &&
        !string.IsNullOrWhiteSpace(winner)
            ? "Superior on this matched suite: " + winner
            : "Inconclusive · no qualified winner";
}
