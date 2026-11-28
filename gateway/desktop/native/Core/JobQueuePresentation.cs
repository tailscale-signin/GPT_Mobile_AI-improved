using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Gateway.Desktop.Core;

/// <summary>Keep list visibility separate from host authority and confirmed cancellation.</summary>
public static class JobQueuePresentation
{
    public static bool Terminal(JsonObject row)
    {
        if (row.Text("phase", "").ToUpperInvariant() is "TIMED_OUT" or "RECONCILE_REQUIRED") return false;
        return row.Text("state", row.Text("status", "")).ToLowerInvariant()
            is "completed" or "failed" or "cancelled" or "canceled" or "interrupted" or "rejected";
    }

    public static bool CanRequestCancellation(JsonObject row) =>
        row.Text("job_kind", "") is "engineering" or "generation" && !Terminal(row) &&
        !row.Flag("cancellation_requested") && row.Text("phase", "").ToUpperInvariant() != "CANCELLING";

    public static bool Visible(JsonObject row, string filter, bool pinned, bool removed)
    {
        bool hidden = removed && Terminal(row);
        if (filter == "Removed") return hidden;
        if (hidden) return false;
        return filter switch
        {
            "All" => true,
            "Pinned" => pinned,
            "Host receipts" => row.Flag("host_receipt"),
            _ => row.Text("state", "") == filter
        };
    }

    public static string ScopeKey(JsonObject identity, JsonObject row)
    {
        string gateway = identity.Text("gateway_id", ""), actor = identity.Text("principal_id", "");
        string owner = row.Text("principal_id", ""), kind = row.Text("job_kind", ""), id = row.Text("job_id", "");
        if (new[] { gateway, actor, owner, kind, id }.Any(string.IsNullOrWhiteSpace))
            throw new InvalidOperationException("The original job owner is unknown. Refresh the job before changing this list.");
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(new JsonArray(gateway, actor, owner, kind, id).ToJsonString())));
    }

    public static void ValidateRead(JsonObject observed, JsonObject identity, JsonObject row)
    {
        var job = observed.Object("job");
        if (observed.Text("gateway_id", "") != identity.Text("gateway_id", "") ||
            observed.Text("principal_id", "") != row.Text("principal_id", "") ||
            job.Text("job_id", job.Text("id", "")) != row.Text("job_id", ""))
            throw new InvalidOperationException("The original job identity changed. Refresh before continuing.");
    }

    public static JsonObject? CancellationTarget(JsonObject observed, JsonObject identity, JsonObject row)
    {
        ValidateRead(observed, identity, row);
        if (!observed.Flag("cancel_available")) return null;
        if (observed["control_target"] is not JsonObject target ||
            target.Text("gateway_id", "") != identity.Text("gateway_id", "") ||
            target.Text("principal_id", "") != row.Text("principal_id", "") ||
            target.Text("job_kind", "") != row.Text("job_kind", "") ||
            target.Text("job_kind", "") is not ("engineering" or "generation") ||
            target.Number("revision") is not { } revision || revision < 0 || !double.IsFinite(revision) || revision != Math.Truncate(revision) ||
            target.Text("job_kind", "") == "generation" && string.IsNullOrWhiteSpace(target.Text("generation", "")))
            throw new InvalidOperationException("The host did not confirm the original cancellation target.");
        return (JsonObject)target.DeepClone();
    }

    public static bool CanRemove(JsonObject observed, JsonObject identity, JsonObject row)
    {
        ValidateRead(observed, identity, row);
        return observed.Flag("removal_available") && Terminal(observed.Object("job")) &&
            observed.Text("cancellation_outcome", "") is "confirmed" or "already_terminal";
    }

    public static bool DefinitivelyNotDispatched(JsonObject request, JsonObject observed)
    {
        var receipt=observed.Object("receipt");
        var target=request.Object("target");
        return observed.Text("dispatch_state", "")=="not_dispatched" &&
            receipt.Text("status", "")=="NOT_DISPATCHED" && receipt.Text("dispatch_state", "")=="not_dispatched" &&
            receipt.Text("actor", "")=="local:manager" &&
            !string.IsNullOrWhiteSpace(request.Text("command_id", "")) && !string.IsNullOrWhiteSpace(request.Text("job_id", "")) &&
            observed.Text("command_id", "")==request.Text("command_id", "") && receipt.Text("command_id", "")==request.Text("command_id", "") &&
            observed.Text("job_id", "")==request.Text("job_id", "") && receipt.Text("job_id", "")==request.Text("job_id", "") &&
            target.Count>0 && JsonNode.DeepEquals(receipt["target"],target) &&
            new[]{"gateway_id","principal_id","job_kind"}.All(field=>
                !string.IsNullOrWhiteSpace(target.Text(field,"")) && observed.Text(field,"")==target.Text(field,""));
    }

    public static string CancellationLabel(JsonObject observed) =>
        observed.Text("dispatch_state", "")=="not_dispatched" && observed.Object("receipt").Text("status", "")=="NOT_DISPATCHED"
        ? "Cancellation not sent · review the current job" : observed.Text("cancellation_outcome", "") switch
    {
        "confirmed" => "Cancellation confirmed",
        "pending" => "Cancellation pending · waiting for the job to stop",
        "already_terminal" => "Already finished · no cancellation needed",
        _ => "Cancellation unconfirmed · read the original receipt"
    };

    public static JsonObject HostRow(JsonObject item, string kind, JsonObject identity, double? observedAt)
    {
        if (item["job_id"] is JsonValue)
        {
            var ready = (JsonObject)item.DeepClone();
            ready["host_receipt"] = true;
            ready["allowed_actions"] = new JsonArray("open");
            return ready;
        }
        string phase = item.Text("status", "unknown"), lowered = phase.ToLowerInvariant();
        string state = lowered switch
        {
            "completed" or "failed" or "cancelled" or "interrupted" => lowered,
            "canceled" => "cancelled",
            "needs_input" or "merge_ready" or "cancelling" or "cancel_requested" or "reconcile_required" or "timed_out" or "unknown" => "needs_attention",
            "queued" or "accepted" or "prepared" => "queued",
            _ => "running"
        };
        return new JsonObject
        {
            ["job_id"] = item["id"]?.DeepClone(), ["job_kind"] = kind, ["host_receipt"] = true,
            ["gateway_id"] = identity["gateway_id"]?.DeepClone(), ["principal_id"] = item["principal_id"]?.DeepClone(),
            ["title"] = item["title"]?.DeepClone(), ["state"] = state, ["phase"] = phase,
            ["repository"] = kind is "engineering" or "copilot" ? item["title"]?.DeepClone() : null,
            ["branch"] = item["branch"]?.DeepClone(), ["model"] = kind == "generation" ? item["title"]?.DeepClone() : null,
            ["revision"] = item["revision"]?.DeepClone(), ["generation"] = item["generation"]?.DeepClone(),
            ["cancellation_requested"] = item.Flag("cancellation_requested") || lowered is "cancelling" or "cancel_requested",
            ["accepted_at"] = item["started"]?.DeepClone(), ["finished_at"] = state is "completed" or "failed" or "cancelled" or "interrupted" ? item["updated"]?.DeepClone() : null,
            ["server_now"] = observedAt, ["queue_reason"] = item["detail"]?.DeepClone(),
            ["batch_progress"] = item["batch_progress"]?.DeepClone(), ["progress"] = item["progress"]?.DeepClone(),
            ["allowed_actions"] = new JsonArray("open")
        };
    }
}
