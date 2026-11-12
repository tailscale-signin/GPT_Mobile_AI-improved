#!/usr/bin/env python3
"""Validate shipped reporting routes; optionally verify the deployed reporter's read-only readiness."""

import argparse
import json
import os
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener


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
            return response.read(1025)
    data = (fetch or network_fetch)(origin.rstrip("/") + "/health/ready")
    if len(data) > 1024:
        raise ValueError("Reporter readiness exceeded the app's response bound")
    value = json.loads(data)
    versions = value.get("reportContextVersions") if isinstance(value, dict) else None
    supported = {version for version in versions if type(version) is int} if isinstance(versions, list) else set()
    if not isinstance(value, dict) or value.get("service") != "gpt-mobile-issue-reporter" or not {1, 2, 3, 4} <= supported:
        raise ValueError("Reporter readiness must identify gpt-mobile-issue-reporter and support reportContextVersions [1, 2, 3, 4]")


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
