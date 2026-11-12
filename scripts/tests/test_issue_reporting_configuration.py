import json
import unittest

from scripts.check_issue_reporting_configuration import check_readiness, validate


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

    def test_readiness_supports_current_acceptance_and_retained_legacy_context_versions(self):
        observed = []
        def fetch(url):
            observed.append(url)
            return b'{"service":"gpt-mobile-issue-reporter","reportContextVersions":[1,2]}'
        check_readiness("https://reports.project.org/", fetch)
        self.assertEqual(["https://reports.project.org/health/ready"], observed)
        for value in (
            {"service": "other", "reportContextVersions": [1, 2]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [True, 2]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": ["1", "2"]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [2]},
            {"service": "gpt-mobile-issue-reporter", "reportContextVersions": [1, "2"]},
            {"service": "gpt-mobile-issue-reporter"},
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                check_readiness("https://reports.project.org", lambda _: json.dumps(value).encode())

    def test_readiness_does_not_accept_unbounded_or_non_json_responses(self):
        for body in (b"x" * 1025, b"not-json", b"[]"):
            with self.subTest(size=len(body)), self.assertRaises(ValueError):
                check_readiness("https://reports.project.org", lambda _: body)
