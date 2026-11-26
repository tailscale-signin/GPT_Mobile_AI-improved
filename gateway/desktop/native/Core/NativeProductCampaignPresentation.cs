using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Gateway.Desktop.Core;

/// <summary>Display canonical native-product evidence without manufacturing missing measurements.</summary>
public static class NativeProductCampaignPresentation
{
    public static readonly string[] Products = ["GPT Mobile AI", "ChatGPT", "Codex", "Claude Code"];

    public static string Headline(JsonObject report)
    {
        if (report.Text("evidenceLane", "") == "SYNTHETIC_ONLY")
            return "Synthetic fixture · product quality remains unmeasured";
        if (report.Text("evidenceLane", "") != "NATIVE_OBSERVED" ||
            report.Text("comparativeModelQuality", "") != "REGISTERED_SUITE_ONLY")
            return "Comparative product quality · Unknown";
        return report.Object("nativeComparison").Text("verdict", "") switch
        {
            "SUPERIOR_ON_REGISTERED_SUITE" => "GPT Mobile AI superior on this registered suite",
            "INCOMPLETE" => "Comparison incomplete · missing evidence remains Unknown",
            _ => "Inconclusive · no qualified winner"
        };
    }

    public static string Metric(JsonNode? value, string key, string suffix = "", string format = "0.##")
    {
        double? measured = value.Number(key);
        return measured is { } number && double.IsFinite(number)
            ? number.ToString(format, CultureInfo.InvariantCulture) + suffix : "Unknown";
    }

    public static string Rate(JsonNode? value)
    {
        double? measured = value.Number("verifiedRate");
        return measured is >= 0 and <= 1 && double.IsFinite(measured.Value)
            ? (100 * measured.Value).ToString("0.##", CultureInfo.InvariantCulture) + "%" : "Unknown";
    }

    public static string CampaignDefinitionSummary(JsonObject definition)
    {
        int? tasks = definition["tasks"] is JsonArray taskRows ? taskRows.Count : null;
        int? products = definition["products"] is JsonArray productRows ? productRows.Count : null;
        double? repeats = definition.Number("repeats");
        string slots = tasks is > 0 && products is > 0 && repeats is >= 1 and <= 20 && repeats == Math.Truncate(repeats.Value)
            ? (tasks.Value * products.Value * repeats.Value).ToString("0", CultureInfo.InvariantCulture) : "Unknown";
        return $"{tasks?.ToString(CultureInfo.InvariantCulture) ?? "Unknown"} tasks × {Metric(definition, "repeats")} repeats × " +
            $"{products?.ToString(CultureInfo.InvariantCulture) ?? "Unknown"} products = {slots} scheduled trials\n" +
            $"Per-trial time ceiling: {Metric(definition, "maxSeconds", " s")} · cost ceiling: USD {Metric(definition, "maxCostUsd")}\n" +
            $"Cache: {definition.Text("cache", "Unknown")} · preregistered margin: {Metric(definition, "marginPoints")} points";
    }

    public static IEnumerable<JsonObject> ProductRows(JsonObject report)
    {
        var products = report.Object("products");
        foreach (string product in Products)
        {
            var row = products.Object(product);
            var coverage = report.Object("coverage").Object(product);
            string coverageText = string.Join(" · ", coverage.Select(pair =>
                pair.Key.Replace('_', ' ') + ": " + Metric(coverage, pair.Key)));
            yield return new JsonObject
            {
                ["title"] = product,
                ["observations"] = $"{Metric(row, "observed")} / {Metric(row, "planned")} observed",
                ["verified"] = $"{Metric(row, "verifiedWithinBudget")} verified within budget · rate {Rate(row)}",
                ["timing"] = $"Median {Metric(row, "elapsedSecondsP50", " s")} · 95th {Metric(row, "elapsedSecondsP95", " s")}",
                ["cost"] = $"Observed USD {Metric(row, "observedCostUsd")} ({Metric(row, "costObserved")} trials) · per verified completion USD {Metric(row, "costPerVerifiedCompletionUsd")}",
                ["coverage"] = coverageText.Length == 0 ? "Evidence coverage · Unknown" : coverageText
            };
        }
    }

    public static IEnumerable<string> PairLines(JsonObject report)
    {
        foreach (var pair in report.Object("nativeComparison").Object("comparisonsAgainstMobile"))
        {
            if (pair.Value is not JsonObject row) continue;
            yield return $"GPT Mobile AI minus {pair.Key}: {Metric(row, "deltaPoints")} points · " +
                $"simultaneous 95% lower bound {Metric(row, "lowerSimultaneous95Points")} · " +
                $"{Metric(row, "missingPairs")} missing pairs · {Metric(row, "independentFamiliesObserved")} independent families";
        }
    }

    public static string SeparateMeasurements(JsonObject report) =>
        $"GitHub Intelligence / 100: {Metric(report, "githubIntelligencePointsOutOf100")}\n" +
        "First attempt comparison: Unknown\nBest within budget comparison: Unknown";

    public static string OperationHeadline(JsonObject operation)
    {
        var receipts = operation.Array("receipts").OfType<JsonObject>().ToArray();
        if (operation["receipts"] is JsonArray)
            return $"{receipts.Length} original-trial receipts · " +
                $"{receipts.Count(row => row.Text("status", "") == "UNRESOLVED")} unresolved · " +
                $"{receipts.Count(row => row.Text("status", "") == "RUNTIME_CHANGED_READBACK_ONLY")} readback-only after an application update · " +
                $"{receipts.Count(row => row.Text("submissionState", "") == "NOT_OBSERVED")} with admission unobserved";
        if (operation["imported"] is JsonArray imported)
            return $"{imported.Count} consecutive observed trials imported. Existing observations were preserved.";
        if (operation.Number("scheduleIndex") is not null)
            return "Observer receipt retained for slot " + Metric(operation, "scheduleIndex") + ". Independent grading remains separate.";
        return "Local operation completed. Inspect the retained evidence before drawing conclusions.";
    }

    public static IEnumerable<JsonObject> OperationRows(JsonObject operation)
    {
        foreach (var receipt in operation.Array("receipts").OfType<JsonObject>())
        {
            string kind = receipt.Text("status", "");
            string submission = receipt.Text("submissionState", "");
            string state = receipt.Text("state", "");
            string reason = kind switch
            {
                "UNRESOLVED" => "Preparation or readback remains unresolved. Preserve the original trial; recover performs status only.",
                "RUNTIME_CHANGED_READBACK_ONLY" => "The application revision changed. The original request was read back; this runtime cannot sign a new grade for the frozen campaign.",
                "NOT_PREPARED" => "No original grader trial was prepared. Recovery submitted nothing.",
                "SIGNED_GRADE_PRESENT" => "An independent signed grade was already retained.",
                _ when submission == "NOT_OBSERVED" => "Admission remains unobserved. Preserve the original request identity; do not repeat or replace the submission.",
                _ when submission == "REMOVED" => "The original request was removed. Keep its receipt and original identity.",
                _ => "The original trial was read back. Only the signed independent grade contributes to the comparison."
            };
            if (kind is not ("UNRESOLVED" or "RUNTIME_CHANGED_READBACK_ONLY" or "NOT_PREPARED" or "SIGNED_GRADE_PRESENT"))
                kind = submission is "OBSERVED" or "NOT_OBSERVED" or "REMOVED" ? submission : "Unknown";
            if (state is not ("PREPARED" or "QUEUED" or "RUNNING" or "COMPLETED" or "FAILED" or "BLOCKED" or "INTERRUPTED" or "CANCELLED"))
                state = "Unknown";
            string request = receipt.Text("requestId", "");
            if (!Regex.IsMatch(request, "\\Aextref_[a-f0-9]{32}\\z")) request = "Unknown";
            yield return new JsonObject
            {
                ["title"] = "Slot " + Metric(receipt, "scheduleIndex") + " · " + kind + " · " + state,
                ["state"] = "Original request: " + request,
                ["detail"] = reason
            };
        }
    }
}
