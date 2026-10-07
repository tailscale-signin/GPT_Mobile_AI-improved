#!/usr/bin/env python3
"""Optional read-only MCP companion. Python 3.11+, standard library only.

Not an Android runtime. See README.md before exposing this service to a phone.
Provider credentials stay in environment variables on this host.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hmac
import json
import math
import os
import re
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

MAX_REQUEST = 16_384
MAX_RESPONSE = 2_000_000
PROTOCOLS = ("2025-03-26", "2025-06-18")
KEYS = {
    "ticketmaster": "TICKETMASTER_API_KEY", "tomtom": "TOMTOM_API_KEY",
    "yelp": "YELP_API_KEY", "eventbrite": "EVENTBRITE_TOKEN",
    "arcgis": "ARCGIS_API_KEY", "openrouteservice": "ORS_API_KEY",
    "google-places": "GOOGLE_PLACES_API_KEY", "foursquare": "FOURSQUARE_API_KEY",
}
# Schemas intentionally expose only fixed read operations, not arbitrary URLs/SQL.
FIELDS = {
    "query": {"type": "string", "minLength": 1, "maxLength": 250},
    "location": {"type": "string", "minLength": 1, "maxLength": 250},
    "latitude": {"type": "number", "minimum": -90, "maximum": 90},
    "longitude": {"type": "number", "minimum": -180, "maximum": 180},
    "end_latitude": {"type": "number", "minimum": -90, "maximum": 90},
    "end_longitude": {"type": "number", "minimum": -180, "maximum": 180},
    "resource_id": {"type": "string", "minLength": 1, "maxLength": 80},
    "organization_id": {"type": "string", "minLength": 1, "maxLength": 30},
}
OPERATIONS = {
    "nominatim": {"geocode": ("query",)},
    "overpass": {"restrooms": ("latitude", "longitude")},
    "toronto": {"datasets": ("query",), "records": ("resource_id",)},
    "refuge": {"restrooms": ("latitude", "longitude")},
    "ticketmaster": {"events": ("query", "location")},
    "tomtom": {"places": ("query",)},
    "yelp": {"businesses": ("query", "location")},
    "eventbrite": {"organization_events": ("organization_id",)},
    "arcgis": {"geocode": ("query",)},
    "openrouteservice": {"walking_route": ("latitude", "longitude", "end_latitude", "end_longitude")},
    "google-places": {"places": ("query",)},
    "foursquare": {"places": ("query", "location")},
}


class SafeError(Exception):
    """Only messages from this class may be returned to clients."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SafeError("Upstream redirect refused; check the provider configuration.")


def validate(provider, operation, args):
    if operation not in OPERATIONS.get(provider, {}) or not isinstance(args, dict):
        raise SafeError("Unknown tool or invalid arguments.")
    fields = OPERATIONS[provider][operation]
    if set(args) != set(fields):
        raise SafeError("Supply exactly the fields in the tool schema.")
    for name in fields:
        value, spec = args[name], FIELDS[name]
        if spec["type"] == "number":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise SafeError("Coordinates must be finite numbers.")
            if not spec["minimum"] <= value <= spec["maximum"]:
                raise SafeError("Coordinate outside its valid range.")
        elif not isinstance(value, str) or not value.strip() or len(value) > spec["maxLength"] or any(ord(c) < 32 for c in value):
            raise SafeError("Invalid text argument.")
    if "organization_id" in args and not re.fullmatch(r"[0-9]{1,30}", args["organization_id"]):
        raise SafeError("Organization ID must be numeric.")
    if "resource_id" in args and not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args["resource_id"]):
        raise SafeError("Invalid resource ID.")


def request_spec(provider, operation, a, env):
    validate(provider, operation, a)
    key = env.get(KEYS.get(provider, ""), "")
    if provider in KEYS and (not key or "\r" in key or "\n" in key):
        raise SafeError("Set this provider's credential on the companion host.")
    h, p, body = {"Accept": "application/json", "User-Agent": "GPTMobile-OptionalMarketplace/1.0"}, {}, None
    q = a.get("query", "")
    if provider == "ticketmaster":
        url = "https://app.ticketmaster.com/discovery/v2/events.json"
        p = dict(apikey=key, keyword=q, city=a["location"], size=10)
    elif provider == "tomtom":
        url = "https://api.tomtom.com/search/2/search/" + urllib.parse.quote(q, safe="") + ".json"
        p = dict(key=key, limit=10)
    elif provider == "yelp":
        url, h["Authorization"] = "https://api.yelp.com/v3/businesses/search", "Bearer " + key
        p = dict(term=q, location=a["location"], limit=10)
    elif provider == "eventbrite":
        url = "https://www.eventbriteapi.com/v3/organizations/" + a["organization_id"] + "/events/"
        h["Authorization"] = "Bearer " + key
    elif provider == "arcgis":
        url = "https://geocode-api.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates"
        p = dict(SingleLine=q, f="json", maxLocations=5, forStorage="false", token=key)
    elif provider == "openrouteservice":
        url, h["Authorization"] = "https://api.openrouteservice.org/v2/directions/foot-walking/geojson", key
        body = {"coordinates": [[a["longitude"], a["latitude"]], [a["end_longitude"], a["end_latitude"]]]}
    elif provider == "google-places":
        url, h["X-Goog-Api-Key"] = "https://places.googleapis.com/v1/places:searchText", key
        h["X-Goog-FieldMask"] = "places.id,places.displayName,places.formattedAddress,places.location,places.googleMapsUri,places.attributions"
        body = dict(textQuery=q, pageSize=10)
    elif provider == "foursquare":
        url, h["Authorization"] = "https://places-api.foursquare.com/places/search", "Bearer " + key
        h["X-Places-Api-Version"] = "2025-06-17"
        p = dict(query=q, near=a["location"], limit=10)
    elif provider == "refuge":
        url = "https://www.refugerestrooms.org/api/v1/restrooms/by_location.json"
        p = dict(lat=a["latitude"], lng=a["longitude"], per_page=10)
    elif provider == "toronto":
        base = "https://ckan0.cf.opendata.inter.prod-toronto.ca/api/3/action/"
        if operation == "datasets":
            url, p = base + "package_search", dict(q=q, rows=10)
        else:
            url, p = base + "datastore_search", dict(resource_id=a["resource_id"], limit=20)
    elif provider in ("nominatim", "overpass"):
        url = env.get(provider.upper() + "_ENDPOINT", "")
        parsed = urllib.parse.urlsplit(url)
        shared = ("nominatim.openstreetmap.org", "overpass-api.de", "overpass.kumi.systems", "overpass.private.coffee")
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.port not in (None, 443):
            raise SafeError("Configure an HTTPS managed/self-hosted endpoint without URL credentials.")
        if any(parsed.hostname == host or parsed.hostname.endswith("." + host) for host in shared):
            raise SafeError("Shared public OSM endpoints are not enabled as an app backend.")
        if provider == "nominatim":
            p = dict(q=q, format="jsonv2", limit=5)
        else:
            query = f'[out:json][timeout:15];(nwr(around:1500,{a["latitude"]},{a["longitude"]})[amenity=toilets];nwr(around:1500,{a["latitude"]},{a["longitude"]})[toilets=yes];);out center tags 50;'
            body = urllib.parse.urlencode(dict(data=query)).encode()
            h["Content-Type"] = "application/x-www-form-urlencoded"
    else:
        raise SafeError("Provider is not implemented.")
    if p:
        url += "?" + urllib.parse.urlencode(p)
    if isinstance(body, dict):
        body = json.dumps(body, allow_nan=False).encode()
        h["Content-Type"] = "application/json"
    return urllib.request.Request(url, data=body, headers=h)


class Companion:
    def __init__(self, env, database):
        self.env = dict(env)
        self.enabled = set(filter(None, env.get("MARKETPLACE_ENABLED", "").split(",")))
        if not self.enabled or not self.enabled <= OPERATIONS.keys():
            raise SafeError("MARKETPLACE_ENABLED must explicitly list supported provider IDs.")
        self.token = env.get("MARKETPLACE_MCP_TOKEN", "")
        if len(self.token) < 32 or not self.token.isascii() or any(c.isspace() for c in self.token):
            raise SafeError("Set a random ASCII MARKETPLACE_MCP_TOKEN of at least 32 characters.")
        self.limit = int(env.get("MARKETPLACE_DAILY_REQUESTS", "50"))
        if not 1 <= self.limit <= 1000:
            raise SafeError("Daily request limit must be between 1 and 1000.")
        self.db, self.last = str(database), {}
        with sqlite3.connect(self.db) as db:
            db.execute("CREATE TABLE IF NOT EXISTS usage (day TEXT, provider TEXT, calls INTEGER, PRIMARY KEY(day, provider))")

    def reserve(self, provider):
        now = time.monotonic()
        if now - self.last.get(provider, -10) < 1:
            raise SafeError("Provider cooldown active. Try again later.")
        day = dt.datetime.now(dt.timezone.utc).date().isoformat()
        with sqlite3.connect(self.db) as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT calls FROM usage WHERE day=? AND provider=?", (day, provider)).fetchone()
            if row and row[0] >= self.limit:
                raise SafeError("Daily companion request allowance exhausted.")
            db.execute("INSERT INTO usage VALUES (?, ?, 1) ON CONFLICT(day,provider) DO UPDATE SET calls=calls+1", (day, provider))
        self.last[provider] = now

    def dispatch(self, provider, message):
        if provider not in self.enabled:
            raise SafeError("Provider is not enabled on this host.")
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0" or not isinstance(message.get("method"), str):
            raise SafeError("Invalid JSON-RPC request; batches are not supported.")
        method, params = message["method"], message.get("params", {})
        if not isinstance(params, dict):
            raise SafeError("Invalid parameters.")
        if "id" not in message:
            if method in ("notifications/initialized", "notifications/cancelled"):
                return None
            raise SafeError("This method requires a request ID.")
        if not isinstance(message["id"], (str, int)) or isinstance(message["id"], bool):
            raise SafeError("Invalid request ID.")
        if method == "initialize":
            requested = params.get("protocolVersion")
            result = dict(protocolVersion=requested if requested in PROTOCOLS else PROTOCOLS[-1], capabilities={"tools": {}}, serverInfo={"name": "optional-location-companion", "version": "1.0.0"})
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": [dict(name=name, description=f"Read {name.replace('_', ' ')} from {provider}. One bounded request; provider charges/terms may apply. Missing access, opening or accessibility data is unknown.", inputSchema=dict(type="object", properties={f: FIELDS[f] for f in fields}, required=list(fields), additionalProperties=False), annotations={"readOnlyHint": True}) for name, fields in OPERATIONS[provider].items()]}
        elif method == "tools/call":
            try:
                req = request_spec(provider, params.get("name"), params.get("arguments", {}), self.env)
                self.reserve(provider)
                with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
                    raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise SafeError("Provider response exceeded the safe size limit. Narrow the request.")
                data = json.loads(raw)
                text = json.dumps(dict(source=urllib.parse.urlsplit(req.full_url).hostname, retrievedAt=dt.datetime.now(dt.timezone.utc).isoformat(), coverage="Bounded first response, not exhaustive; unknown attributes remain unknown.", data=data), allow_nan=False)
                if len(text) > 100_000:
                    raise SafeError("Result exceeds the output allowance. No partial JSON was returned.")
                result = {"content": [{"type": "text", "text": text}], "isError": False}
            except Exception as exc:
                if isinstance(exc, urllib.error.HTTPError) and exc.code == 429:
                    self.last[provider] = time.monotonic() + 59
                text = str(exc) if isinstance(exc, SafeError) else "Provider request failed. Check host credentials, allowance and service availability."
                result = {"content": [{"type": "text", "text": text}], "isError": True}
        else:
            return dict(jsonrpc="2.0", id=message["id"], error={"code": -32601, "message": "Method not supported."})
        return dict(jsonrpc="2.0", id=message["id"], result=result)


def handler_for(companion):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_):
            pass  # Do not log URLs, headers, coordinates, arguments or provider keys.

        def reply(self, status, payload=None):
            raw = b"" if payload is None else json.dumps(payload, allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            self.reply(405)

        do_DELETE = do_GET

        def do_POST(self):
            supplied = self.headers.get("Authorization", "").encode()
            if not hmac.compare_digest(supplied, ("Bearer " + companion.token).encode()):
                self.reply(401)
                return
            if self.headers.get("Origin") is not None:
                self.reply(403)
                return
            if self.headers.get("MCP-Protocol-Version", PROTOCOLS[0]) not in PROTOCOLS:
                self.reply(400)
                return
            if self.headers.get_content_type() != "application/json" or self.headers.get("Transfer-Encoding"):
                self.reply(415)
                return
            message = None
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_REQUEST:
                    raise SafeError("Request body exceeds the limit or is missing.")
                if not re.fullmatch(r"/mcp/[a-z-]+", self.path):
                    self.reply(404)
                    return
                message = json.loads(self.rfile.read(length))
                result = companion.dispatch(self.path.rsplit("/", 1)[1], message)
                self.reply(202 if result is None else 200, result)
            except Exception as exc:
                request_id = message.get("id") if isinstance(message, dict) else None
                if isinstance(request_id, (dict, list, bool)):
                    request_id = None
                text = str(exc) if isinstance(exc, SafeError) else "Invalid request."
                self.reply(400, dict(jsonrpc="2.0", id=request_id, error={"code": -32600, "message": text}))
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8110)
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--usage-db", type=Path, default=Path("marketplace-usage.sqlite3"))
    args = parser.parse_args()
    if args.host not in ("127.0.0.1", "localhost") and not args.allow_network:
        parser.error("Non-loopback binding needs --allow-network and protected TLS/private-network deployment.")
    companion = Companion(os.environ, args.usage_db)
    server = HTTPServer((args.host, args.port), handler_for(companion))
    print("Optional companion started. Provider keys stay on this host. Ctrl+C stops it.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
