"""Same-origin fetch bridge for the proxied llama.cpp browser UI."""
import re

from fastapi.responses import HTMLResponse, Response

BOOTSTRAP_PATH = "/gateway/browser-bootstrap.js"
SCRIPT_TAG = '<script src="' + BOOTSTRAP_PATH + '"></script>'
LANDING_PATHS = ("/", "/v1", "/v1/")


def gateway_landing():
    """Serve a status entry point without exposing the backend browser proxy."""
    return HTMLResponse(
        """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GPT Mobile Gateway</title>
</head>
<body>
  <main>
    <h1>GPT Mobile Gateway</h1>
    <p>Your browser reached the Gateway. Model readiness is checked separately.</p>
    <p>In GPT Mobile, use this address with <code>/v1</code> as the Gateway API base.</p>
    <p>To manage the Gateway or view its desktop dashboard, open GPT Mobile Gateway on the host PC.</p>
    <p><a href="/gateway/live">Check Gateway status</a></p>
  </main>
</body>
</html>""",
        headers={
            "Content-Security-Policy": "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            "X-Content-Type-Options": "nosniff",
        },
    )


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
