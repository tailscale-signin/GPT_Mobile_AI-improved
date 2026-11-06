#!/usr/bin/env python3
"""Qualify packaged GitHub App Links; consent and token exchange stay separate."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

from release_evidence import certificate_sha, file_digest

ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_manifest(xml: str, app_id: str, host: str) -> str:
    if len(xml.encode()) > 4 * 1024 * 1024:
        raise ValueError("Packaged manifest exceeds its inspection bound")
    manifest = ET.fromstring(xml)
    if manifest.get("package") != app_id:
        raise ValueError("Unexpected packaged application ID")
    activities = [item for item in manifest.findall("./application/activity") if item.get(ANDROID + "name", "").endswith(".GitHubAuthCallbackActivity")]
    if len(activities) != 1:
        raise ValueError("Exactly one GitHub callback activity is required")
    activity = activities[0]
    if activity.get(ANDROID + "enabled") != "true" or activity.get(ANDROID + "exported") != "true":
        raise ValueError("Packaged public GitHub sign-in is not enabled")
    filters = activity.findall("intent-filter")
    if len(filters) != 1 or filters[0].get(ANDROID + "autoVerify") != "true":
        raise ValueError("The callback requires one verified App Link filter")
    rules = filters[0].findall("data")
    expected = {ANDROID + "scheme": "https", ANDROID + "host": host, ANDROID + "path": "/oauth/github/complete"}
    if len(rules) != 1 or rules[0].attrib != expected:
        raise ValueError("Android must capture only its return ticket path, never the broker callback")
    actions = {item.get(ANDROID + "name") for item in filters[0].findall("action")}
    categories = {item.get(ANDROID + "name") for item in filters[0].findall("category")}
    if actions != {"android.intent.action.VIEW"} or not {"android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"}.issubset(categories):
        raise ValueError("Packaged App Link is not browsable")
    name = activity.get(ANDROID + "name")
    return app_id + name if name.startswith(".") else name


def check_asset_links(document, app_id, certificate):
    if not isinstance(document, list) or len(document) > 100:
        raise ValueError("Invalid Digital Asset Links document")
    fingerprint = certificate_sha(certificate)
    for row in document:
        if not isinstance(row, dict):
            continue
        target = row.get("target", {})
        if (isinstance(target, dict) and target.get("namespace") == "android_app" and target.get("package_name") == app_id
                and "delegate_permission/common.handle_all_urls" in row.get("relation", [])):
            values = target.get("sha256_cert_fingerprints", [])
            if isinstance(values, list) and any(certificate_sha(value) == fingerprint for value in values if isinstance(value, str)):
                return
    raise ValueError("Digital Asset Links do not bind this package and signing certificate")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        raise ValueError("Digital Asset Links must be served directly over HTTPS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--host", required=True)
    parser.add_argument("--application-id", required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--verify-installed", action="store_true")
    parser.add_argument("--serial")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", args.host) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]+", args.application_id):
        parser.error("Expected a fixed HTTPS hostname and Android application ID")
    fingerprint = certificate_sha(args.certificate_sha256)
    report = {"schemaVersion": 1, "status": "NOT_QUALIFIED", "apkSha256": file_digest(args.apk),
              "applicationId": args.application_id, "returnHost": args.host, "signingCertificateSha256": fingerprint,
              "accountConsent": "NOT_MEASURED", "tokenExchange": "NOT_MEASURED"}
    try:
        manifest = subprocess.check_output([args.apkanalyzer, "manifest", "print", str(args.apk)], text=True, timeout=60)
        activity = check_manifest(manifest, args.application_id, args.host)
        signed = subprocess.check_output([args.apksigner, "verify", "--print-certs", str(args.apk)], text=True, timeout=120)
        certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9:]+)", signed)
        if len(certificates) != 1 or certificate_sha(certificates[0]) != fingerprint:
            raise ValueError("The packaged APK has a different signing identity")
        opener = urllib.request.build_opener(NoRedirect)
        with opener.open("https://" + args.host + "/.well-known/assetlinks.json", timeout=20) as response:
            content = response.read(1024 * 1024 + 1)
        if len(content) > 1024 * 1024:
            raise ValueError("Digital Asset Links exceeds its bound")
        check_asset_links(json.loads(content), args.application_id, fingerprint)
        report["status"] = "PACKAGED_LINK_CONFIGURATION_VERIFIED"
        if args.verify_installed:
            adb = ["adb"] + (["-s", args.serial] if args.serial else [])
            def device(*values):
                return subprocess.check_output([*adb, *values], text=True, timeout=60).strip()
            paths = device("shell", "pm", "path", args.application_id).splitlines()
            bases = [line.removeprefix("package:") for line in paths if line.startswith("package:") and line.endswith("/base.apk")]
            if len(bases) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+-]+/base\.apk", bases[0]):
                raise ValueError("Could not identify the installed package base APK")
            with tempfile.TemporaryDirectory(prefix="github-link-apk-") as folder:
                installed = Path(folder) / "base.apk"
                subprocess.run([*adb, "pull", bases[0], str(installed)], check=True, stdout=subprocess.DEVNULL, timeout=180)
                if file_digest(installed) != report["apkSha256"]:
                    raise ValueError("Installed APK does not match the inspected artifact")
            state = device("shell", "pm", "get-app-links", args.application_id)
            if not re.search(r"^\s*" + re.escape(args.host) + r":\s*verified\s*$", state, re.MULTILINE):
                raise ValueError("The installed App Link has not been verified by Android")
            resolved = device("shell", "cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", "https://" + args.host + "/oauth/github/complete")
            component = args.application_id + "/" + activity
            short = args.application_id + "/" + activity.removeprefix(args.application_id)
            if component not in resolved.splitlines() and short not in resolved.splitlines():
                raise ValueError("The verified return URL does not resolve to this callback activity")
            report["status"] = "INSTALLED_LINK_ROUTING_VERIFIED"
    except Exception as error:
        report["status"] = "FAILED"
        report["errorClass"] = type(error).__name__
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(report["status"])


if __name__ == "__main__":
    main()
