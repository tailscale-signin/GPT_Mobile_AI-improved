import json
import unittest

from scripts.check_issue_reporting_configuration import MAX_READINESS_BYTES, REQUIRED_DIAGNOSTIC_FEATURES, check_readiness, validate


class IssueReportingConfigurationTest(unittest.TestCase):
    def test_native_route_supports_builds_without_an_invented_service(self):
        self.assertEqual({"nativeGitHub": True, "reportingService": False}, validate("", "true"))

    def test_disabling_both_routes_fails(self):
        with self.assertRaises(ValueError):
            validate("", "false")

    def test_missing_or_misspelled_native_configuration_is_not_truthy(self):
        for value in ("", "TRUE", "yes", "1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate("", value)

    def test_placeholder_or_credential_redirect_origins_fail(self):
        for origin in ("http://reports.project.org", "https://reports.project.org:8443", "https://user:secret@reports.project.org", "https://reports.project.org/v1", "https://reports.project.org?next=1", "https://issue-report-pr-validation.invalid", "https://reports.example.org"):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                validate(origin, "true")

    def test_service_can_be_the_only_route(self):
        self.assertEqual({"nativeGitHub": False, "reportingService": True}, validate("https://reports.project.org", "false"))

    def test_readiness_supports_recovery_review_acceptance_and_retained_legacy_context_versions(self):
        observed = []
        def fetch(url):
            observed.append(url)
            return json.dumps({"service": "gpt-mobile-issue-reporter", "version": 2, "reportContextVersions": [1, 2, 3, 4, 5, 6, 7],
                               "reportDiagnosticFeatures": sorted(REQUIRED_DIAGNOSTIC_FEATURES)}, separators=(",", ":")).encode()
        check_readiness("https://reports.project.org/", fetch)
        self.assertEqual(["https://reports.project.org/health/ready?capabilities=extended"], observed)
        for value in (
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, 5, 6]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, 5, 6, "7"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, 5]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, 5, "6"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, "5"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, True]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, "4"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, True]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [4]},
            {"service": "other", "reportContextVersions": [1, 2, 3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [True, 2, 3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": ["1", "2", "3"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [2]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [2, 3]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, "3"]},
            {"service": "gpt-mobile-issue-reporter"},
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                check_readiness("https://reports.project.org", lambda _: json.dumps({"version": 2, "reportDiagnosticFeatures": sorted(REQUIRED_DIAGNOSTIC_FEATURES), **value}, separators=(",", ":")).encode())

    def test_current_deployment_requires_strict_protocol_two_and_every_required_feature(self):
        self.assertTrue({"TOOL_REPEAT_ADMISSION_EVIDENCE_V1", "TOOL_EVENT_PERSISTENCE_EVIDENCE_V1",
                         "ACCEPTANCE_OBSERVATION_CAUSES_V1", "SQLITE_CONSTRAINT_EVIDENCE_V1"} <= REQUIRED_DIAGNOSTIC_FEATURES)
        ready = {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, 2, 3, 4, 5, 6, 7],
                 "reportDiagnosticFeatures": sorted(REQUIRED_DIAGNOSTIC_FEATURES)}
        for version in (None, True, False, "2", 2.0, [], {}, -1, 0, 1):
            # Isolate the protocol check from the independent readiness bound.
            value = {**ready, "reportDiagnosticFeatures": []}
            if version is not None:
                value["version"] = version
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "protocol version"):
                check_readiness("https://reports.project.org", lambda _: json.dumps(value, separators=(",", ":")).encode())
        for version in (2, 3):
            check_readiness("https://reports.project.org", lambda _: json.dumps({**ready, "version": version}, separators=(",", ":")).encode())
        for missing in REQUIRED_DIAGNOSTIC_FEATURES:
            value = {**ready, "version": 2, "reportDiagnosticFeatures": sorted(REQUIRED_DIAGNOSTIC_FEATURES - {missing})}
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "reportDiagnosticFeatures"):
                check_readiness("https://reports.project.org", lambda _: json.dumps(value, separators=(",", ":")).encode())

    def test_readiness_does_not_accept_unbounded_or_non_json_responses(self):
        for body in (b"x" * (MAX_READINESS_BYTES + 1), b"not-json", b"[]"):
            with self.subTest(size=len(body)), self.assertRaises(ValueError):
                check_readiness("https://reports.project.org", lambda _: body)

    def test_extended_readiness_enforces_exact_response_boundary(self):
        ready = json.dumps({"service": "gpt-mobile-issue-reporter", "version": 2,
                            "reportContextVersions": [1, 2, 3, 4, 5, 6, 7],
                            "reportDiagnosticFeatures": sorted(REQUIRED_DIAGNOSTIC_FEATURES)},
                           separators=(",", ":")).encode()
        self.assertGreater(len(ready), 1024)
        bounded = ready + b" " * (MAX_READINESS_BYTES - len(ready))
        check_readiness("https://reports.project.org", lambda _: bounded)
        with self.assertRaisesRegex(ValueError, "response bound"):
            check_readiness("https://reports.project.org", lambda _: bounded + b" ")
