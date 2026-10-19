"""Parse captured artifacts in cancellable child processes; parsers never fetch URLs."""
from __future__ import annotations

import importlib.metadata
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin

from .contracts import CrawlerError, canonical_json, digest, require, url_identity

PINS = {"trafilatura": "2.3.1", "crawl4ai": "0.9.5", "scrapling": "0.5", "docling": "2.129.0"}


def installed_version(name):
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


class MetadataParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links, self.title, self.in_title, self.password = [], [], False, False
        self.script_count = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "a" and attributes.get("href"):
            self.links.append(attributes["href"])
        if tag == "title":
            self.in_title = True
        if tag == "script":
            self.script_count += 1
        if tag == "input" and attributes.get("type", "").lower() == "password":
            self.password = True

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title:
            self.title.append(data)


class Extractor:
    def __init__(self, max_output_bytes=8 * 1024 * 1024, timeout=25):
        self.max_output_bytes, self.timeout = max_output_bytes, timeout

    def health(self):
        versions = {name: installed_version(name) for name in PINS}
        return {"versions": versions, "requiredVersions": PINS, "htmlReady": versions["trafilatura"] == PINS["trafilatura"],
                "pdfTextReady": shutil.which("pdftotext") is not None,
                "browserReady": False, "browserReason": "Metered browser egress qualification pending",
                "ocrReady": False, "ocrReason": "Offline document models and their licenses must be configured and qualified"}

    def extract(self, response, scope, cancelled):
        if response.status in (401, 403):
            raise CrawlerError("LOGIN_REQUIRED", "The source requires access that this reader does not have.")
        if response.status == 429:
            raise CrawlerError("RATE_LIMITED", "The source rate limit was reached.")
        require(200 <= response.status < 300, "The source returned an unsuccessful HTTP status.", "HTTP_ERROR")
        mime = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        metadata = MetadataParser()
        if response.body.startswith(b"%PDF-"):
            method, engine, version = "pdf_text", "pdftotext", "system"
            text = self._process(response.body, "pdf", cancelled)
            limitations = ["Layout, tables, figures and supplementary material have not been verified."]
        elif mime in ("text/html", "application/xhtml+xml") or response.body.lstrip().lower().startswith((b"<!doctype html", b"<html")):
            html = response.body.decode("utf-8", errors="replace")
            metadata.feed(html)
            title = " ".join(metadata.title).strip().lower()
            require(not (metadata.password and any(word in title for word in ("log in", "login", "sign in", "access"))), "The returned page is a login wall.", "LOGIN_REQUIRED")
            require(not any(word in title for word in ("verify you are human", "just a moment", "access denied")), "The source returned an access challenge.", "BLOCKED")
            method, engine, version = "html", "trafilatura", PINS["trafilatura"]
            text = self._process(response.body, engine, cancelled)
            if not text.strip() and installed_version("scrapling") == PINS["scrapling"]:
                text = self._process(response.body, "scrapling", cancelled)
                engine, version = "scrapling", PINS["scrapling"]
            if not text.strip() and metadata.script_count:
                raise CrawlerError("ENGINE_UNAVAILABLE", "This page needs rendering; the metered browser component is not ready.")
            limitations = ["Extraction completeness and scientific quality have not been appraised."]
        elif mime == "text/plain":
            method, engine, version = "plain_text", "utf8", "1"
            text = response.body.decode("utf-8", errors="replace")
            limitations = ["Source completeness has not been independently verified."]
        else:
            raise CrawlerError("UNSUPPORTED_FORMAT", "The captured artifact has no supported extraction route.")
        text = text.strip()
        require(bool(text), "No readable text was recovered; OCR or another permitted format may be needed.", "PARSE_FAILED")
        require(not cancelled.is_set(), "Extraction cancelled.", "CANCELLED")
        links = []
        for value in metadata.links:
            try:
                links.append(url_identity(urljoin(response.resolved_url, value)))
            except CrawlerError:
                continue
        source = digest(canonical_json({"url": response.resolved_url, "scope": scope}))
        content_hash = digest(text)
        revision = digest(canonical_json({"source": source, "hash": content_hash, "engine": engine, "version": version, "method": method}))
        return {"schemaVersion": 1, "sourceId": source, "documentRevision": revision, "requestedUrl": response.requested_url,
                "resolvedUrl": response.resolved_url, "canonicalUrl": response.resolved_url,
                "fetchedAt": response.fetched_at, "httpStatus": response.status, "mimeType": mime, "language": None,
                "engine": engine, "engineVersion": version, "extractor": engine, "extractorVersion": version,
                "attemptIds": list(response.attempts), "method": method, "retrievalStatus": "partial" if response.truncated else "readable",
                "coverage": {"fullText": None, "abstractOnly": None, "sections": [], "pages": [], "missingSections": [], "ocrUsed": False, "limitations": limitations},
                "contentRef": f"crawler:revision:{revision}", "contentHash": content_hash, "title": " ".join(metadata.title).strip(),
                "text": text, "storedCharacters": len(text), "artifactHash": digest(response.body), "artifactBytes": len(response.body)}, list(dict.fromkeys(links))

    def _process(self, artifact, engine, cancelled):
        if engine == "pdf":
            require(shutil.which("pdftotext") is not None, "Install the optional PDF text component.", "ENGINE_UNAVAILABLE")
        else:
            require(installed_version(engine) == PINS[engine], f"Install the pinned {engine} component.", "ENGINE_UNAVAILABLE")
        with tempfile.TemporaryDirectory(prefix="gptmobile-crawler-") as directory:
            source, target = Path(directory) / "artifact", Path(directory) / "text"
            source.write_bytes(artifact)
            source.chmod(0o600)
            command = ["pdftotext", "-enc", "UTF-8", str(source), str(target)] if engine == "pdf" else [sys.executable, "-m", "crawler_server.extraction", engine, str(source), str(target)]
            # A dedicated process permits cancellation during CPU-bound extraction.
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            deadline = time.monotonic() + self.timeout
            try:
                while process.poll() is None:
                    require(not cancelled.wait(0.05), "Extraction cancelled.", "CANCELLED")
                    require(time.monotonic() < deadline, "Extraction deadline reached.", "PARSE_TIMEOUT")
                    require(not target.exists() or target.stat().st_size <= self.max_output_bytes, "Extracted text exceeds the configured worker output bound.", "OUTPUT_LIMIT_EXCEEDED")
                require(process.returncode == 0 and target.is_file(), "The local extractor failed.", "PARSE_FAILED")
                require(target.stat().st_size <= self.max_output_bytes, "Extracted text exceeds the configured worker output bound.", "OUTPUT_LIMIT_EXCEEDED")
                return target.read_text(encoding="utf-8")
            finally:
                if process.poll() is None:
                    process.kill()
                process.wait()


def _worker(engine, source, target):
    if os.name == "posix":
        import resource
        resource.setrlimit(resource.RLIMIT_AS, (1024**3, 1024**3))
        resource.setrlimit(resource.RLIMIT_CPU, (25, 25))
        resource.setrlimit(resource.RLIMIT_FSIZE, (8 * 1024 * 1024, 8 * 1024 * 1024))
    artifact = Path(source).read_bytes()
    if engine == "trafilatura":
        from trafilatura import extract
        text = extract(artifact, include_comments=False, include_tables=True, output_format="txt") or ""
    elif engine == "scrapling":
        from scrapling.parser import Selector
        text = Selector(artifact.decode("utf-8", errors="replace")).get_all_text(separator="\n", strip=True)
    else:
        raise ValueError("Unsupported local parser")
    Path(target).write_text(text, encoding="utf-8")


if __name__ == "__main__":
    _worker(*sys.argv[1:])
