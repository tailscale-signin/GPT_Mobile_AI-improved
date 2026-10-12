"""Keyless academic facade shared by the authenticated research bridge and v14.

All outbound operations are fixed-origin, read-only and quota-reserved. Provider
metadata is never promoted to full-text evidence. This module does not expose HTTP.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import http.client
import ipaddress
import json
import os
import re
import socket
import sqlite3
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

TOOLS = tuple("deep_research_" + op for op in (
    "search_papers", "get_paper", "read_paper", "citations", "verify_evidence", "status"))
CORE = ("openalex", "crossref", "arxiv", "europepmc", "datacite")
# Conditional connectors remain unavailable until a reviewed anonymous contract exists.
CONDITIONAL = {"semantic_scholar", "openreview", "core", "citeseerx", "osf", "biorxiv", "medrxiv", "iacr", "acl", "openaire"}
EXCLUDED = {"unpaywall", "ieee", "google_scholar", "base", "sci_hub", "orcid", "scopus", "scite", "dimensions", "web_of_science"}
HOSTS = {"api.openalex.org", "api.crossref.org", "export.arxiv.org", "arxiv.org", "www.ebi.ac.uk", "api.datacite.org", "api.opencitations.net", "dblp.org", "zenodo.org", "doaj.org", "inspirehep.net", "api.archives-ouvertes.fr", "api.ror.org", "pmc.ncbi.nlm.nih.gov", "aclanthology.org"}
SUPPORTED = set(CORE) | {"dblp", "zenodo", "doaj", "inspirehep", "hal", "ror", "opencitations"}
OA_HOSTS = {"arxiv.org", "www.ebi.ac.uk", "pmc.ncbi.nlm.nih.gov", "aclanthology.org", "zenodo.org"}
MAX_BODY = 2 * 1024 * 1024
MAX_PDF = 20 * 1024 * 1024
MAX_OUTPUT = 256 * 1024


def digest(value: str | bytes) -> str:
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()


def doi(value: str) -> str:
    value = re.sub(r"^(?:doi:|https?://(?:dx\.)?doi\.org/)", "", value.strip(), flags=re.I).lower()
    return value if re.fullmatch(r"10\.[0-9]{4,9}/[^\s]{1,480}", value) else ""


def domain_allowed(url: str, included=(), excluded=()) -> bool:
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    matches = lambda domain: host == domain or host.endswith("." + domain)
    return bool(host) and not any(matches(x.lower()) for x in excluded) and (not included or any(matches(x.lower()) for x in included))


class AcademicError(RuntimeError):
    def __init__(self, code, retry_at=None):
        super().__init__(code)
        self.code, self.retry_at = code, retry_at


class QuotaRegistry:
    """Atomic across threads, processes and bridge restarts; no wrapper-specific quotas."""
    def __init__(self, path: Path, now=time.time):
        self.path, self.now = path, now
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS quotas (provider TEXT PRIMARY KEY, last REAL, window REAL, used INTEGER, cooldown REAL)")

    def connect(self):
        return sqlite3.connect(self.path, timeout=5)

    def reserve(self, provider: str):
        spacing, window, maximum = {
            "openalex": (1, 86400, 50), "crossref": (.5, 60, 120), "arxiv": (3, 60, 20),
            "europepmc": (1, 60, 60), "datacite": (1, 300, 60), "opencitations": (3, 60, 20),
            "zenodo": (6, 60, 10), "inspirehep": (1, 5, 5), "openaire": (120, 3600, 30),
            "ror": (3, 60, 10)
        }.get(provider, (1, 60, 30))
        now = self.now()
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT last,window,used,cooldown FROM quotas WHERE provider=?", (provider,)).fetchone()
            last, start, used, cooldown = row or (0, now, 0, 0)
            if now - start >= window:
                start, used = now, 0
            until = max(cooldown, start + window if used >= maximum else 0, last + spacing)
            if until > now:
                raise AcademicError("rate_limited", int(until * 1000))
            db.execute("INSERT OR REPLACE INTO quotas VALUES (?,?,?,?,?)", (provider, now, start, used + 1, cooldown))

    def defer(self, provider, seconds):
        until = self.now() + max(1, min(float(seconds), 86400))
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT OR IGNORE INTO quotas VALUES (?,0,0,0,0)", (provider,))
            db.execute("UPDATE quotas SET cooldown=max(cooldown,?) WHERE provider=?", (until, provider))
        return int(until * 1000)

    def status(self):
        with self.connect() as db:
            return {row[0]: {"used": row[1], "retryAtMs": int(row[2] * 1000)} for row in db.execute("SELECT provider,used,cooldown FROM quotas")}


class _PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host, address):
        super().__init__(host, timeout=12, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        sock = socket.create_connection((self.address, 443), timeout=self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def public_fetch(url: str, *, maximum=MAX_BODY, included=(), excluded=(), cancelled=lambda: False):
    """Pin checked public DNS addresses, preserve certificate/SNI checks, validate every redirect."""
    for _ in range(4):
        if cancelled():
            raise AcademicError("cancelled")
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname not in HOSTS or parsed.port not in (None, 443) or parsed.username or parsed.password or not domain_allowed(url, included, excluded):
            raise AcademicError("host_not_allowed")
        addresses = {info[4][0] for info in socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)}
        if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):
            raise AcademicError("non_public_address")
        connection = _PinnedHTTPS(parsed.hostname, sorted(addresses)[0])
        try:
            path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
            connection.request("GET", path, headers={"User-Agent": "GPTMobileAcademic/1 (keyless public research)", "Accept": "application/json, application/xml, text/html, application/pdf"})
            response = connection.getresponse()
            headers = dict((key.lower(), value) for key, value in response.getheaders())
            if response.status in (301, 302, 303, 307, 308):
                url = urllib.parse.urljoin(url, headers.get("location", ""))
                continue
            body = bytearray()
            while True:
                if cancelled():
                    raise AcademicError("cancelled")
                chunk = response.read(min(65536, maximum + 1 - len(body)))
                if not chunk:
                    break
                body.extend(chunk)
                if len(body) > maximum:
                    raise AcademicError("response_too_large")
            return response.status, headers, bytes(body), url
        finally:
            connection.close()
    raise AcademicError("redirect_limit")


def paper(title, url, *, identifier="", ids=None, year=None, provider="", preprint=None, **extra):
    normalized = doi(identifier)
    ids = ids or {}
    key = "doi:" + normalized if normalized else next((f"{name}:{ids[name]}" for name in ("pmcid", "pmid", "arxiv", "acl", "openreview", "zenodo") if ids.get(name)), "url:" + url)
    return {"paperId": key, "versionId": key, "versionGroup": key, "title": re.sub(r"\s+", " ", title)[:400], "canonicalUrl": url,
            "doi": normalized, "identifiers": ids, "year": year, "authors": [], "publicationType": "preprint" if preprint else "unknown",
            "reviewStatus": "preprint" if preprint else "unknown", "isPreprint": preprint, "isRetracted": None,
            "sourceAccess": "metadata_only", "openAccessUrl": None, "sourceProviders": [provider], "providerReceipts": [], **extra}


def merge_papers(records):
    merged = {}
    for record in records:
        key = (record["paperId"], record["versionId"])
        if key not in merged:
            merged[key] = dict(record)
        else:
            saved = merged[key]
            for field in ("sourceProviders", "providerReceipts"):
                saved[field] = sorted(set(saved.get(field, []) + record.get(field, [])))
            saved["openAccessUrl"] = saved.get("openAccessUrl") or record.get("openAccessUrl")
            if record.get("isRetracted") is True:
                saved["isRetracted"] = True
    return list(merged.values())


def xml_document(body):
    if b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
        raise AcademicError("unsafe_xml")
    return ET.fromstring(body)


class AcademicRouter:
    def __init__(self, state_dir=None, fetch=public_fetch, enabled=CORE + ("opencitations",), now=time.time):
        self.root = Path(state_dir or os.environ.get("GPT_ACADEMIC_STATE_DIR", Path.home() / ".gpt-mobile-academic"))
        self.root.mkdir(parents=True, exist_ok=True)
        self.quotas = QuotaRegistry(self.root / "quotas.sqlite3", now)
        self.fetch, self.now = fetch, now
        self.enabled = set(enabled) & SUPPORTED
        self.lock = threading.RLock()
        self.permits = {key: threading.Semaphore(1) for key in SUPPORTED}
        self.global_permit = threading.Semaphore(8)
        self.statuses = {}
        self.papers = {}
        self.cache = {}
        self.cursors = {}

    def _remember(self, record):
        with self.lock:
            if len(self.papers) >= 1200 and record["paperId"] not in self.papers:
                self.papers.pop(next(iter(self.papers)))
            self.papers[record["paperId"]] = record
        return record

    def _request(self, provider, url, args, cancelled, maximum=MAX_BODY):
        if provider not in self.enabled and provider != "opencitations":
            raise AcademicError("provider_disabled")
        requested = args.get("enabledProviderIds")
        if requested and provider not in requested:
            raise AcademicError("provider_disabled")
        with self.permits[provider], self.global_permit:
            if cancelled():
                raise AcademicError("cancelled")
            deadline = time.monotonic() + 7
            while True:
                if cancelled():
                    raise AcademicError("cancelled")
                try:
                    self.quotas.reserve(provider)
                    break
                except AcademicError as error:
                    wait = (error.retry_at or 0) / 1000 - self.now()
                    if error.code != "rate_limited" or wait <= 0 or time.monotonic() + wait > deadline:
                        raise
                    stop = time.monotonic() + wait + .01
                    while time.monotonic() < stop:
                        if cancelled():
                            raise AcademicError("cancelled")
                        time.sleep(min(.05, max(0, stop - time.monotonic())))
            status, headers, body, final = self.fetch(url, maximum=maximum, included=args.get("includeDomains", ()), excluded=args.get("excludeDomains", ()), cancelled=cancelled)
            if status == 429:
                retry = headers.get("retry-after", "60")
                try:
                    seconds = float(retry)
                except ValueError:
                    from email.utils import parsedate_to_datetime
                    try:
                        seconds = parsedate_to_datetime(retry).timestamp() - self.now()
                    except (ValueError, TypeError):
                        seconds = 60
                raise AcademicError("rate_limited", self.quotas.defer(provider, seconds))
            if not 200 <= status < 300:
                raise AcademicError("http_" + str(status))
            return body, headers, final

    def execute(self, name, args, cancelled=lambda: False):
        if name not in TOOLS or not isinstance(args, dict):
            raise AcademicError("unknown_tool")
        if cancelled():
            raise AcademicError("cancelled")
        if len(json.dumps(args).encode()) > 32 * 1024:
            raise AcademicError("arguments_too_large")
        op = name.removeprefix("deep_research_")
        if op == "status":
            return {"schemaVersion": 1, "owner": "gateway_mcp", "providers": [{"id": p, **self.statuses.get(p, {"state": "unknown" if p in self.enabled else "disabled_until_verified"})} for p in sorted(SUPPORTED | CONDITIONAL)], "quotas": self.quotas.status(), "pdfReaderAvailable": self._pdf_reader() is not None, "excludedProviders": sorted(EXCLUDED)}
        if op == "search_papers":
            result = self.search(args, cancelled)
        elif op == "get_paper":
            result = self.get_paper(str(args.get("identifier", "")), args, cancelled) or {"status": "not_found"}
        elif op == "read_paper":
            result = self.read_paper(str(args.get("identifier", "")), args, cancelled)
        elif op == "citations":
            result = self.citations(args, cancelled)
        else:
            result = self.verify(args)
        if len(json.dumps(result).encode()) > MAX_OUTPUT:
            raise AcademicError("output_budget_exhausted")
        return result

    def search(self, args, cancelled):
        query = str(args.get("query", "")).strip()
        if not 1 <= len(query) <= 500:
            raise AcademicError("invalid_query")
        depth = max(1, min(int(args.get("depth", 50)), 100))
        limit = max(1, min(int(args.get("maxResults", 25)), 50))
        page = int(args.get("cursor") or 1)
        if not 1 <= page <= 10:
            raise AcademicError("invalid_cursor")
        mix = list(CORE[:2]) + ["datacite"]
        if re.search(r"gpu|npu|learning|transformer|physics|algorithm", query, re.I):
            mix = ["arxiv", "openalex", "dblp"]
        elif re.search(r"clinical|cancer|patient|medicine|biolog", query, re.I):
            mix = ["europepmc", "openalex", "crossref"]
        elif re.search(r"dataset|software|research data", query, re.I):
            mix = ["datacite", "zenodo", "openalex"]
        elif re.search(r"particle|high.energy|collider", query, re.I):
            mix = ["inspirehep", "arxiv", "crossref"]
        requested = args.get("enabledProviderIds") or sorted(self.enabled)
        if not isinstance(requested, list) or "all" in requested or set(requested) & EXCLUDED:
            raise AcademicError("invalid_provider_allowlist")
        planned = [p for p in dict.fromkeys(mix + list(CORE) + sorted(self.enabled - {"opencitations", "ror"})) if p in self.enabled and p in requested]
        planned = planned if depth == 100 else planned[:max(1, min(int(args.get("maxProviderCalls", 3)), len(SUPPORTED)))]
        def search_one(provider):
            receipt = uuid.uuid4().hex
            cache_key = digest(json.dumps([provider, args], sort_keys=True))
            try:
                if cancelled():
                    raise AcademicError("cancelled")
                cached = self.cache.get(cache_key)
                hit = bool(cached and self.now() - cached[0] < 1800)
                records = cached[1] if hit else self._search_provider(provider, query, min(limit, 20), page, args, cancelled)
                if not hit:
                    with self.lock:
                        if len(self.cache) >= 64:
                            self.cache.pop(next(iter(self.cache)))
                        self.cache[cache_key] = (self.now(), records)
                records = [dict(p, providerReceipts=[receipt]) for p in records if domain_allowed(p["canonicalUrl"], args.get("includeDomains", ()), args.get("excludeDomains", ())) and
                           (args.get("fromYear") is None or p.get("year") is not None and p["year"] >= int(args["fromYear"])) and
                           (args.get("toYear") is None or p.get("year") is not None and p["year"] <= int(args["toYear"])) and
                           (args.get("reviewStatus", "any") == "any" or p.get("reviewStatus") == args["reviewStatus"])]
                state = {"id": provider, "state": "success" if records else "empty", "fetched": len(records), "cacheHit": hit, "receiptId": receipt, "owner": "gateway_mcp"}
                with self.lock:
                    for p in records:
                        self._remember(p)
                    self.statuses[provider] = state
                return records, state
            except Exception as error:
                state = {"id": provider, "state": error.code if isinstance(error, AcademicError) else "unavailable", "fetched": 0, "receiptId": receipt, "retryAtMs": getattr(error, "retry_at", None), "owner": "gateway_mcp"}
                with self.lock:
                    self.statuses[provider] = state
                return [], state
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = [pool.submit(search_one, provider) for provider in planned]
            results = [future.result() for future in concurrent.futures.as_completed(futures)]
        merged = merge_papers([p for records, _ in results for p in records])
        states = [state for _, state in results]
        more = len(merged) > limit or any(s["fetched"] >= min(limit, 20) for s in states)
        return {"schemaVersion": 1, "searchId": uuid.uuid4().hex, "query": query, "status": "complete" if states and all(s["state"] in ("success", "empty") for s in states) else "partial",
                "papers": merged[:limit], "providers": states, "totalDistinctPapers": len(merged[:limit]), "truncated": more, "nextCursor": str(page + 1) if more and page < 10 else None,
                "coverage": {"searchedProviders": len(states), "unavailableProviders": sum(s["state"] not in ("success", "empty") for s in states)}, "depth": depth}

    def _search_provider(self, provider, query, limit, page, args, cancelled):
        q = urllib.parse.quote(query)
        cursor_key = digest(json.dumps([query, limit, {k: v for k, v in args.items() if k != "cursor"}], sort_keys=True))
        cursor = "*" if page == 1 else self.cursors.get((cursor_key, page))
        if provider == "europepmc" and not cursor:
            raise AcademicError("cursor_unavailable")
        endpoints = {
            "openalex": f"https://api.openalex.org/works?search={q}&per-page={limit}&page={page}",
            "crossref": f"https://api.crossref.org/works?query.bibliographic={q}&rows={limit}&offset={(page-1)*limit}",
            "arxiv": f"https://export.arxiv.org/api/query?search_query=all:{q}&max_results={limit}&start={(page-1)*limit}",
            "europepmc": f"https://www.ebi.ac.uk/europepmc/webservices/rest/search?query={q}&format=json&pageSize={limit}&cursorMark={urllib.parse.quote(cursor or chr(42), safe=chr(32))}",
            "datacite": f"https://api.datacite.org/dois?query={q}&page%5Bsize%5D={limit}&page%5Bnumber%5D={page}",
            "dblp": f"https://dblp.org/search/publ/api?q={q}&format=json&h={limit}&f={(page-1)*limit}",
            "zenodo": f"https://zenodo.org/api/records?q={q}&size={limit}&page={page}",
            "inspirehep": f"https://inspirehep.net/api/literature?q={q}&size={limit}&page={page}",
            "hal": f"https://api.archives-ouvertes.fr/search/?q={q}&wt=json&rows={limit}&start={(page-1)*limit}",
            "doaj": f"https://doaj.org/api/search/articles/{q}?pageSize={limit}&cursorMark={urllib.parse.quote(cursor or "*", safe="")}"
        }
        if provider not in endpoints:
            raise AcademicError("not_a_discovery_provider")
        body, _, _ = self._request(provider, endpoints[provider], args, cancelled)
        if provider == "europepmc":
            next_cursor = json.loads(body).get("nextCursorMark")
            if next_cursor and next_cursor != cursor:
                with self.lock:
                    if len(self.cursors) >= 192:
                        self.cursors.pop(next(iter(self.cursors)))
                    self.cursors[(cursor_key, page + 1)] = next_cursor
        return self.parse(provider, body)

    def parse(self, provider, body):
        if provider == "arxiv":
            root = xml_document(body)
            ns = {"a": "http://www.w3.org/2005/Atom"}
            records = []
            for entry in root.findall("a:entry", ns):
                identifier = entry.findtext("a:id", "", ns).split("/abs/")[-1]
                records.append(paper(entry.findtext("a:title", "", ns), "https://arxiv.org/abs/" + identifier, ids={"arxiv": identifier}, provider=provider, preprint=True,
                                     year=int(entry.findtext("a:published", "0000", ns)[:4]), openAccessUrl="https://arxiv.org/pdf/" + identifier,
                                     versionGroup="arxiv:" + re.sub(r"v[0-9]+$", "", identifier)))
            return records
        data = json.loads(body)
        records = []
        if provider == "crossref":
            for row in data.get("message", {}).get("items", []):
                identifier = doi(row.get("DOI", ""))
                records.append(paper((row.get("title") or [identifier])[0], "https://doi.org/" + identifier, identifier=identifier, provider=provider,
                                     year=(row.get("published", {}).get("date-parts") or [[None]])[0][0], publicationType=row.get("type", "unknown"), preprint=True if row.get("type") == "posted-content" else None,
                                     authors=[" ".join([a.get("given", ""), a.get("family", "")]).strip() for a in row.get("author", [])[:30]]))
        elif provider == "openalex":
            for row in data.get("results", []):
                identifier = doi(row.get("doi") or "")
                records.append(paper(row.get("display_name", ""), "https://doi.org/" + identifier if identifier else row["id"], identifier=identifier, provider=provider,
                                     year=row.get("publication_year"), isRetracted=row.get("is_retracted"), openAccessUrl=(row.get("open_access") or {}).get("oa_url"), publicationType=row.get("type", "unknown")))
        elif provider == "europepmc":
            for row in data.get("resultList", {}).get("result", []):
                ids = {k: row[k] for k in ("pmcid",) if row.get(k)}
                if row.get("source") == "MED":
                    ids["pmid"] = row["id"]
                url = "https://pmc.ncbi.nlm.nih.gov/articles/" + ids["pmcid"] + "/" if "pmcid" in ids else "https://europepmc.org/article/" + row.get("source", "MED") + "/" + row["id"]
                records.append(paper(row.get("title", ""), url, identifier=row.get("doi", ""), ids=ids, year=int(row["pubYear"]) if row.get("pubYear", "").isdigit() else None, provider=provider,
                                     openAccessUrl="https://www.ebi.ac.uk/europepmc/webservices/rest/" + ids["pmcid"] + "/fullTextXML" if ids.get("pmcid") and row.get("isOpenAccess") == "Y" else None))
        elif provider == "datacite":
            for row in data.get("data", []):
                attrs = row["attributes"]; identifier = doi(attrs.get("doi", ""))
                records.append(paper((attrs.get("titles") or [{}])[0].get("title", ""), "https://doi.org/" + identifier, identifier=identifier, provider=provider, year=attrs.get("publicationYear"), publicationType=attrs.get("types", {}).get("resourceTypeGeneral", "unknown")))
        elif provider == "dblp":
            rows = data.get("result", {}).get("hits", {}).get("hit", [])
            for hit in rows:
                row = hit["info"]
                records.append(paper(row.get("title", ""), row.get("url", ""), identifier=row.get("doi", ""), provider=provider, year=int(row["year"]) if row.get("year", "").isdigit() else None))
        elif provider in ("zenodo", "inspirehep"):
            for hit in data.get("hits", {}).get("hits", []):
                row = hit.get("metadata", {})
                identifier = row.get("doi", "") if provider == "zenodo" else (row.get("dois") or [{}])[0].get("value", "")
                title = row.get("title", "") if provider == "zenodo" else (row.get("titles") or [{}])[0].get("title", "")
                url = f"https://zenodo.org/records/{hit['id']}" if provider == "zenodo" else f"https://inspirehep.net/literature/{hit['id']}"
                files = hit.get("files", []) if provider == "zenodo" else []
                oa = next((f.get("links", {}).get("self") for f in files if f.get("key", "").endswith(".pdf")), None) if row.get("access_right") == "open" else None
                records.append(paper(title, url, identifier=identifier, provider=provider, openAccessUrl=oa, contentLicense=row.get("license", {}).get("id")))
        elif provider == "hal":
            for row in data.get("response", {}).get("docs", []):
                records.append(paper((row.get("title_s") or [""])[0], row.get("uri_s", ""), identifier=row.get("doiId_s", ""), provider=provider, year=row.get("producedDateY_i")))
        elif provider == "doaj":
            for hit in data.get("results", []):
                row = hit.get("bibjson", {})
                identifier = next((x.get("id", "") for x in row.get("identifier", []) if x.get("type") == "doi"), "")
                records.append(paper(row.get("title", ""), "https://doaj.org/article/" + hit["id"], identifier=identifier, provider=provider))
        return [row for row in records if row["title"] and row["canonicalUrl"]]

    def get_paper(self, identifier, args, cancelled):
        key = "doi:" + doi(identifier) if doi(identifier) else identifier
        cached = self.papers.get(key)
        if cached:
            if not domain_allowed(cached["canonicalUrl"], args.get("includeDomains", ()), args.get("excludeDomains", ())):
                raise AcademicError("domain_excluded")
            if args.get("enabledProviderIds") and not set(cached["sourceProviders"]) & set(args["enabledProviderIds"]):
                raise AcademicError("provider_disabled")
            return cached
        normalized = doi(identifier)
        if normalized:
            body, _, _ = self._request("crossref", "https://api.crossref.org/works/" + urllib.parse.quote(normalized, safe=""), args, cancelled)
            rows = self.parse("crossref", json.dumps({"message": {"items": [json.loads(body)["message"]]}}).encode())
        elif re.fullmatch(r"arxiv:(?:[0-9]{4}\.[0-9]{4,5}|[a-z-]+/[0-9]{7})(?:v[0-9]+)?", identifier):
            body, _, _ = self._request("arxiv", "https://export.arxiv.org/api/query?id_list=" + urllib.parse.quote(identifier[6:], safe=""), args, cancelled)
            rows = self.parse("arxiv", body)
        elif re.fullmatch(r"(?:pmid:[0-9]+|pmcid:PMC[0-9]+)", identifier):
            if args.get("enabledProviderIds") and "europepmc" not in args["enabledProviderIds"]:
                raise AcademicError("provider_disabled")
            return next(iter(self.search({**args, "query": "EXT_ID:" + identifier.split(":", 1)[1], "enabledProviderIds": ["europepmc"], "maxResults": 1}, cancelled)["papers"]), None)
        else:
            raise AcademicError("unsupported_identifier")
        if rows:
            rows[0]["providerReceipts"] = [uuid.uuid4().hex]
            self._remember(rows[0])
            if not domain_allowed(rows[0]["canonicalUrl"], args.get("includeDomains", ()), args.get("excludeDomains", ())):
                raise AcademicError("domain_excluded")
            return rows[0]
        return None

    @staticmethod
    def _pdf_reader():
        import shutil
        return shutil.which("pdftotext")

    def read_paper(self, identifier, args, cancelled):
        record = self.get_paper(identifier, args, cancelled)
        if not record:
            return {"retrievalStatus": "unavailable", "text": ""}
        url = record.get("openAccessUrl")
        if not url or urllib.parse.urlsplit(url).hostname not in OA_HOSTS:
            return {**record, "retrievalStatus": "metadata_only", "text": ""}
        provider = "arxiv" if "arxiv.org" in url else "europepmc" if "ebi.ac.uk" in url else record["sourceProviders"][0]
        body, headers, final = self._request(provider, url, args, cancelled, MAX_PDF)
        sections = []
        is_pdf = "application/pdf" in headers.get("content-type", "") or urllib.parse.urlsplit(url).path.endswith(".pdf") or (urllib.parse.urlsplit(url).hostname == "arxiv.org" and urllib.parse.urlsplit(url).path.startswith("/pdf/")) or body.startswith(b"%PDF-")
        if is_pdf:
            if not body.startswith(b"%PDF-"):
                raise AcademicError("invalid_pdf_signature")
            reader = self._pdf_reader()
            if not reader:
                return {**record, "retrievalStatus": "pdf_reader_unavailable", "text": ""}
            with tempfile.TemporaryDirectory(prefix="academic-pdf-") as folder:
                source = Path(folder) / "source.pdf"; target = Path(folder) / "text.txt"
                source.write_bytes(body)
                # Start an isolated helper, not preexec_fn (unsafe in the router's threads).
                import sys
                process = subprocess.Popen([sys.executable, "-I", str(Path(__file__).resolve()), "--extract-pdf", reader, str(source), str(target)],
                                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                deadline = time.monotonic() + 20
                try:
                    while process.poll() is None:
                        if cancelled():
                            raise AcademicError("cancelled")
                        if time.monotonic() >= deadline:
                            raise AcademicError("pdf_timeout")
                        time.sleep(.05)
                    if process.returncode != 0:
                        raise AcademicError("pdf_extract_failed")
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.wait()
                if target.stat().st_size > 2 * 1024**2:
                    raise AcademicError("pdf_text_limit")
                pages = target.read_text(errors="replace").split("\f")[:40]
                sections = [{"page": index + 1, "text": text[:8000]} for index, text in enumerate(pages) if text.strip()]
                text = "\n\f\n".join(part["text"] for part in sections)[:24000]
        elif "xml" in headers.get("content-type", "") or body.lstrip().startswith(b"<?xml"):
            root = xml_document(body)
            main = root.find("body")
            text = " ".join(main.itertext())[:24000] if main is not None else ""
        else:
            # Do not promote arbitrary landing/error HTML to a paper read.
            return {**record, "retrievalStatus": "metadata_only", "text": ""}
        evidence = {"paperId": record["paperId"], "versionId": record["versionId"] + ":" + digest(body), "sourceUrl": final,
                    "retrievedAt": int(self.now()*1000), "textHash": digest(text), "text": text,
                    "retrievalStatus": "partially_read" if text else "unavailable", "section": "Bounded OA extract (first 40 PDF pages at most)",
                    "contentLicense": record.get("contentLicense"), "providerReceipts": record["providerReceipts"],
                    "sections": sections[:5], "truncated": True}
        if text:
            target = self.root / (digest(evidence["paperId"] + "|" + evidence["versionId"]) + ".json")
            temporary = target.with_suffix("." + uuid.uuid4().hex + ".tmp")
            temporary.write_text(json.dumps(evidence)); os.replace(temporary, target)
            snapshots = sorted(self.root.glob("*.json"), key=lambda item: item.stat().st_mtime)
            total_bytes = sum(item.stat().st_size for item in snapshots)
            for stale in snapshots:
                if total_bytes <= 128 * 1024**2:
                    break
                if self.now() - stale.stat().st_mtime > 30 * 86400:
                    total_bytes -= stale.stat().st_size
                    stale.unlink(missing_ok=True)
        return evidence

    def citations(self, args, cancelled):
        identifier = doi(str(args.get("identifier", "")))
        direction = args.get("direction", "references")
        if not identifier or direction not in ("references", "citations"):
            raise AcademicError("invalid_citation_request")
        body, _, _ = self._request("opencitations", "https://api.opencitations.net/index/v2/" + direction + "/doi:" + urllib.parse.quote(identifier, safe=""), args, cancelled)
        edges = json.loads(body)
        return {"provider": "opencitations", "direction": direction, "edges": edges[:30], "truncated": len(edges) > 30, "relation": "citation_edge_not_claim_support", "receiptId": uuid.uuid4().hex}

    def verify(self, args):
        paper_id, version, quote = (str(args.get(key, "")) for key in ("paperId", "versionId", "quote"))
        path = self.root / (digest(paper_id + "|" + version) + ".json")
        if not path.is_file() or path.stat().st_size > MAX_OUTPUT:
            return {"verdict": "not_read"}
        evidence = json.loads(path.read_text())
        if not domain_allowed(evidence["sourceUrl"], args.get("includeDomains", ()), args.get("excludeDomains", ())):
            raise AcademicError("domain_excluded")
        verdict = "quote_verified"
        if evidence["versionId"] != version:
            verdict = "version_mismatch"
        elif digest(evidence["text"]) != evidence["textHash"]:
            verdict = "snapshot_integrity_failed"
        elif not quote or len(quote) > 4000 or quote not in evidence["text"]:
            verdict = "quote_not_found"
        page = next((section["page"] for section in evidence.get("sections", []) if quote and quote in section.get("text", "")), None)
        return {"verdict": verdict, "page": page, "paperId": paper_id, "versionId": version, "sourceUrl": evidence["sourceUrl"], "textHash": evidence["textHash"], "offset": evidence["text"].find(quote), "section": evidence["section"], "limitation": "Quote existence only; scientific validity requires review"}


def main():
    import sys
    router = AcademicRouter()
    lock = threading.Lock()
    cancellations = {}
    def run(message, cancel):
        try:
            result = {"id": message["id"], "result": router.execute(message["name"], message.get("arguments", {}), cancel.is_set)}
        except Exception as error:
            result = {"id": message["id"], "error": error.code if isinstance(error, AcademicError) else "academic_unavailable"}
        with lock:
            cancellations.pop(message["id"], None)
            print(json.dumps(result), flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for line in sys.stdin:
            if len(line) > 64 * 1024:
                continue
            try:
                message = json.loads(line)
                if message.get("cancel"):
                    cancellations.get(message["cancel"], threading.Event()).set()
                elif len(cancellations) < 8:
                    cancel = threading.Event(); cancellations[message["id"]] = cancel
                    pool.submit(run, message, cancel)
            except (ValueError, KeyError, TypeError):
                continue
        for cancellation in cancellations.values():
            cancellation.set()


def extract_pdf_worker(reader, source, target):
    """The helper owns parser resource limits before starting the external parser."""
    command = [reader, "-f", "1", "-l", "40", "-layout", source, target]
    if os.name == "posix":
        import resource
        resource.setrlimit(resource.RLIMIT_CPU, (15, 15))
        resource.setrlimit(resource.RLIMIT_AS, (512 * 1024**2, 512 * 1024**2))
        resource.setrlimit(resource.RLIMIT_FSIZE, (2 * 1024**2, 2 * 1024**2))
        os.execv(reader, command)
    elif os.name == "nt":
        # Kill-on-close also ends the parser if the parent cancels this helper.
        import ctypes
        from ctypes import wintypes
        class Basic(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64), ("flags", wintypes.DWORD),
                        ("min_working", ctypes.c_size_t), ("max_working", ctypes.c_size_t), ("active", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD)]
        class IO(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]
        class Limits(ctypes.Structure):
            _fields_ = [("basic", Basic), ("io", IO), ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateJobObjectW.restype = wintypes.HANDLE
        kernel.GetCurrentProcess.restype = wintypes.HANDLE
        kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        job = kernel.CreateJobObjectW(None, None)
        limits = Limits(); limits.basic.flags = 0x2000 | 0x100 | 0x2
        limits.basic.process_time = 15 * 10_000_000; limits.process_memory = 512 * 1024**2
        if not job or not kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)) or not kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()):
            raise AcademicError("pdf_resource_limits_unavailable")
        subprocess.run(command, check=True, timeout=18, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # Keep job handle alive until process exit; closing it here kills this helper.
    else:
        raise AcademicError("pdf_resource_limits_unavailable")


if __name__ == "__main__":
    import sys
    if len(sys.argv) == 5 and sys.argv[1] == "--extract-pdf":
        extract_pdf_worker(*sys.argv[2:])
    else:
        main()
