#!/usr/bin/env python3
"""Validate shipped reporting routes; optionally verify the deployed reporter's read-only readiness."""

import argparse
import json
import os
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

REQUIRED_DIAGNOSTIC_FEATURES = frozenset({
    "FRAMEWORK_EXCEPTION_FRAMES_V1",
    "GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1", "INCIDENT_FAILURE_DETAILS_V1",
    "DISPATCH_POLICY_DIAGNOSTICS_V1", "CHANGED_POLICY_FIELDS_V1", "EXECUTION_OUTCOME_DIAGNOSTICS_V1",
    "RUN_FAILURE_EVIDENCE_V1", "RECOVERY_OBSERVATIONS_V1", "ISSUE_RECOVERY_DETAILS_V1",
    "ADMISSION_DIAGNOSTICS_V1", "SESSION_RECOVERY_DIAGNOSTICS_V1", "VOICE_PLAYBACK_PHASE_V1",
    "CAPACITY_ADMISSION_EVIDENCE_V1", "CONVERSATION_CONFLICT_EVIDENCE_V1", "ACCEPTANCE_SCOPE_EVIDENCE_V1",
    "VOICE_INSTALL_EVIDENCE_V1", "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1", "VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1",
    "COMPLETION_EVIDENCE_V1", "COMPOSER_IME_EVIDENCE_V1", "GITHUB_PREREQUISITE_EVIDENCE_V1",
    "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1", "ISSUE_RECOVERY_CODES_V1",
    "CAPTURE_BOUNDARY_EVIDENCE_V1", "HANDOFF_RECONCILIATION_EVIDENCE_V1", "CHAT_DELIVERY_EVIDENCE_V1",
    "CLAIM_FRESHNESS_EVIDENCE_V1", "PROFILE_SELECTION_EVIDENCE_V1", "GH_COOLDOWN_V1",
    "ACCEPTANCE_PUBLICATION_EVIDENCE_V1",
    "TOOL_REPEAT_ADMISSION_EVIDENCE_V1", "TOOL_EVENT_PERSISTENCE_EVIDENCE_V1",
    "ACCEPTANCE_OBSERVATION_CAUSES_V1", "ACCEPTANCE_EMPTY_EXPLANATION_V1", "GITHUB_ISSUE_INTAKE_EVIDENCE_V1", "SQLITE_CONSTRAINT_EVIDENCE_V1", "URL_READER_AVAILABILITY_V1", "GITHUB_POLICY_RESPONSE_EVIDENCE_V1",
    "TOOL_REPEAT_HISTORY_EVIDENCE_V1",
    "GITHUB_CI_SEMANTIC_EVIDENCE_V1",
    "TOOL_INVENTORY_PAGINATION_EVIDENCE_V1",
    "GITHUB_SEARCH_VALIDATION_EVIDENCE_V1",
    "GITHUB_SEARCH_ARGUMENT_EVIDENCE_V1",
    "CONVERSATION_RECOVERY_DIAGNOSTICS_V1", "VOICE_EXECUTION_EVIDENCE_V1", "VOICE_STREAMING_BACKEND_EVIDENCE_V1", "VOICE_STREAMING_NPU_EVIDENCE_V1", "VOICE_STREAMING_INSTALL_EVIDENCE_V1",
    "VOICE_POCKET_COMPILED_EVIDENCE_V1",
    "TLS_TRANSPORT_PHASE_EVIDENCE_V1", "GITHUB_READ_REVISION_EVIDENCE_V1",
    "REQUEST_BYTE_BUDGET_V1",
    "REPORT_TEXT_ATTACHMENT_V1",
    "PRIVACY_ELIGIBILITY_EVIDENCE_V1",
    "PRIVACY_FINAL_REQUEST_EVIDENCE_V1",
    "GATEWAY_CAPABILITY_EVIDENCE_V1", "CLAIM_EXTRACTION_SCHEMA_HASH_V1", "STATUS_COVERAGE_EVIDENCE_V1", "AUDIO_FOCUS_MODE_V1", "GITHUB_VALIDATION_CONTEXT_V1",
    "EXECUTION_COMPLETION_EVIDENCE_V1", "VISIBLE_OUTPUT_RECOVERY_EVIDENCE_V1", "COMBINED_SINGLE_PASS_EVIDENCE_V1",
    "REPOSITORY_READ_PROGRESS_V1",
    "SAVED_PAGE_REPLAY_EVIDENCE_V1", "GRAPH_LOCAL_CONTEXT_FAILURE_V1", "PROVIDER_LIMIT_CAUSE_EVIDENCE_V1",
    "RECOVERY_REPORT_EVIDENCE_V1",
    "CATALOG_MISSING_TOOL_IDENTITY_V1",
    "TOOL_PRECHECK_GROUP_V1",
    "PROVIDER_QUOTA_GROUP_V1",
    "GITHUB_POLICY_SELECTION_FIELDS_V1", "DEVICE_PRIVACY_POLICY_CHANGE_V1",
    "VOICE_FOCUS_RETRY_COUNT_V1", "FAILED_EVENT_OUTPUT_FIELDS_V1",
    "APPLICATION_FAILURE_CAUSES_V1",
})

MAX_READINESS_BYTES = 4096


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate(origin, github_enabled):
    if github_enabled not in ("true", "false"):
        raise ValueError("ISSUE_REPORT_GITHUB_ENABLED must be true or false")
    if origin:
        url = urlsplit(origin)
        if (url.scheme != "https" or not url.hostname or url.username is not None or url.password is not None
                or url.port not in (None, 443) or url.path not in ("", "/") or url.query or url.fragment
                or url.hostname == "localhost" or url.hostname.endswith((".invalid", ".test", ".example"))
                or any(url.hostname == name or url.hostname.endswith("." + name) for name in ("example.com", "example.net", "example.org"))):
            raise ValueError("ISSUE_REPORT_ORIGIN must be the real, fixed HTTPS root of the deployed reporter")
    if not origin and github_enabled != "true":
        raise ValueError("Distributed APKs require native GitHub reporting or a deployed ISSUE_REPORT_ORIGIN")
    return {"nativeGitHub": github_enabled == "true", "reportingService": bool(origin)}


def check_readiness(origin, fetch=None):
    def network_fetch(url):
        with build_opener(NoRedirect()).open(url, timeout=15) as response:
            return response.read(MAX_READINESS_BYTES + 1)
    data = (fetch or network_fetch)(origin.rstrip("/") + "/health/ready?capabilities=extended")
    if len(data) > MAX_READINESS_BYTES:
        raise ValueError("Reporter readiness exceeded the app's response bound")
    value = json.loads(data)
    versions = value.get("reportContextVersions") if isinstance(value, dict) else None
    supported = {version for version in versions if type(version) is int} if isinstance(versions, list) else set()
    if not isinstance(value, dict) or value.get("service") != "gpt-mobile-issue-reporter" or not {1, 2, 3, 4, 5, 6, 7} <= supported:
        raise ValueError("Reporter readiness must identify gpt-mobile-issue-reporter and support reportContextVersions [1, 2, 3, 4, 5, 6, 7]")
    protocol = value.get("version")
    if type(protocol) is not int or not 2 <= protocol <= 2_147_483_647:
        raise ValueError("Reporter readiness requires numeric integer protocol version 2 or later")
    features = value.get("reportDiagnosticFeatures")
    supported_features = {feature for feature in features if type(feature) is str} if isinstance(features, list) else set()
    if not REQUIRED_DIAGNOSTIC_FEATURES <= supported_features:
        raise ValueError("Reporter readiness is missing current reportDiagnosticFeatures")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", default=os.environ.get("ISSUE_REPORT_ORIGIN", ""))
    parser.add_argument("--github-enabled", default=os.environ.get("ISSUE_REPORT_GITHUB_ENABLED", "true"))
    parser.add_argument("--probe-service", action="store_true")
    args = parser.parse_args()
    try:
        result = validate(args.origin, args.github_enabled)
        if args.origin and args.probe_service:
            check_readiness(args.origin)
            result["reportingServiceReady"] = True
    except (ValueError, OSError) as error:
        parser.exit(1, f"Issue-reporting configuration failed: {error}\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
