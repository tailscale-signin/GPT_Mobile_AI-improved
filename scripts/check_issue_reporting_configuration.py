#!/usr/bin/env python3
"""Validate shipped reporting routes; optionally verify the deployed reporter's read-only readiness."""

import argparse
import json
import os
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

REQUIRED_DIAGNOSTIC_FEATURES = frozenset({
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
