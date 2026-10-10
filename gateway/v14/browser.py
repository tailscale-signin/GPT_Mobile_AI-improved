"""Same-origin fetch bridge for the proxied llama.cpp browser UI."""
import re

from fastapi.responses import Response

BOOTSTRAP_PATH = "/gateway/browser-bootstrap.js"
SCRIPT_TAG = '<script src="' + BOOTSTRAP_PATH + '"></script>'


def inject_bootstrap(response):
    if not isinstance(response, Response) or response.status_code != 200 or "text/html" not in response.headers.get("content-type", "").lower():
        return response
    try:
        html = response.body.decode("utf-8")
    except (AttributeError, UnicodeDecodeError):
        return response
    if SCRIPT_TAG in html:
        return response
    # Run before the UI's module scripts. Never insert gateway credentials into
    # the HTML or persistent storage: the session token stays in this page's RAM.
    head = re.search(r"<head\b[^>]*>", html, re.I)
    if head:
        html = html[:head.end()] + SCRIPT_TAG + html[head.end():]
    else:
        html = SCRIPT_TAG + html
    headers = {k: v for k, v in response.headers.items() if k.lower() not in {"content-length", "etag", "last-modified", "content-encoding"}}
    headers["cache-control"] = "no-store"
    return Response(html.encode("utf-8"), status_code=response.status_code, headers=headers)


def install_browser_proxy(runtime, config):
    proxy = runtime.proxy_to_llama
    build_headers = runtime.build_proxy_headers

    def private_headers(request_headers):
        headers = build_headers(request_headers)
        return {k: v for k, v in headers.items() if k.lower() != "x-gateway-csrf" and not k.lower().startswith("tailscale-") and k.lower() not in {"forwarded", "x-forwarded-for", "x-forwarded-host"}}

    async def browser_proxy(request, path):
        response = await proxy(request, path)
        # The trusted-proxy browser surface remains disabled; this is the
        # existing loopback UI, not a new remote administrative surface.
        if config.mode == "loopback" and request.method == "GET":
            return inject_bootstrap(response)
        return response

    runtime.build_proxy_headers = private_headers
    runtime.proxy_to_llama = browser_proxy
