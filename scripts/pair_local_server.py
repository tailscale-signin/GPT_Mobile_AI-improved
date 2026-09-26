#!/usr/bin/env python3
"""Offer a model configuration once using a short-lived pairing code. No provider keys."""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
import secrets
import time
from urllib.parse import urlencode, urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, help='Private LAN or Tailscale IP reachable from the phone')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--url', required=True, help='Model API base URL reachable from the phone')
    parser.add_argument('--model', required=True)
    parser.add_argument('--name', default='My local server')
    parser.add_argument('--provider', choices=['LLAMA', 'OLLAMA', 'CUSTOM'], default='LLAMA')
    parser.add_argument('--seconds', type=int, default=180)
    parser.add_argument('--qr', help='Optional SVG filename; requires pip install qrcode')
    args = parser.parse_args()
    address = ipaddress.ip_address(args.host)
    if not (address.is_private or address in ipaddress.ip_network('100.64.0.0/10')) or address.is_unspecified or address.is_multicast:
        parser.error('--host must be a private interface address, never a public or wildcard bind')
    parsed = urlparse(args.url)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error('--url must be a clean HTTP(S) model endpoint without credentials')
    if not 1 <= args.seconds <= 600 or not 1 <= args.port <= 65535:
        parser.error('Pairing lifetime must be 1–600 seconds and port must be valid')
    expires = int(time.time()) + args.seconds
    code = secrets.token_urlsafe(32)
    host = f'[{args.host}]' if address.version == 6 else args.host
    endpoint = f'http://{host}:{args.port}/pair'
    link = 'gptmobile://pair?' + urlencode({'endpoint': endpoint, 'code': code, 'expires': expires})
    configuration = dict(version=1, name=args.name[:100], provider=args.provider, apiUrl=args.url, model=args.model[:200], expiresAt=expires)
    consumed = False

    class PairingHandler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Never log the pairing code or Authorization header.

        def do_GET(self):
            nonlocal consumed
            authorized = hmac.compare_digest(self.headers.get('Authorization', ''), f'Bearer {code}')
            if self.path != '/pair' or self.headers.get('Origin') or not authorized or consumed or time.time() >= expires:
                self.send_error(403)
                return
            consumed = True  # Claim before sending, even if the phone disconnects.
            body = json.dumps(configuration).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    class Server(HTTPServer):
        if address.version == 6:
            import socket
            address_family = socket.AF_INET6

    if args.qr:
        try:
            import qrcode
            import qrcode.image.svg
        except ImportError:
            parser.error('Install qrcode to create an SVG, or omit --qr and open the printed link')
        qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage).save(args.qr)
    print(f'Open on your phone (expires in {args.seconds}s, single use):\n{link}', flush=True)
    print('Use only on a trusted LAN or encrypted Tailscale network. Provider credentials are not included.', flush=True)
    with Server((args.host, args.port), PairingHandler) as server:
        server.timeout = 1
        while not consumed and time.time() < expires:
            server.handle_request()


if __name__ == '__main__':
    main()
