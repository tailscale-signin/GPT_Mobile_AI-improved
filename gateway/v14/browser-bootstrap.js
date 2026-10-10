/* The gateway's loopback UI keeps CSRF tokens in page memory only. */
(() => {
    "use strict";
    if (window.__gatewayBrowserBridge) return;
    window.__gatewayBrowserBridge = true;
    const originalFetch = window.fetch.bind(window);
    const safeMethods = new Set(["GET", "HEAD", "OPTIONS"]);
    let token = null;
    let sessionPending = null;

    function session(force = false) {
        if (force) token = null;
        if (token) return Promise.resolve(token);
        if (!sessionPending) {
            sessionPending = originalFetch("/gateway/session", {
                credentials: "same-origin",
                cache: "no-store",
                headers: { Accept: "application/json" }
            }).then(async response => {
                if (!response.ok) throw new Error("Gateway browser session unavailable");
                const result = await response.json();
                if (typeof result.csrfToken !== "string" || !/^[a-f0-9]{64}$/.test(result.csrfToken)) {
                    throw new Error("Invalid gateway browser session");
                }
                token = result.csrfToken;
                return token;
            }).finally(() => { sessionPending = null; });
        }
        return sessionPending;
    }

    window.fetch = async (input, init) => {
        const url = new URL(input instanceof Request ? input.url : input, window.location.href);
        const method = (init?.method || (input instanceof Request ? input.method : "GET")).toUpperCase();
        if (url.origin !== window.location.origin || safeMethods.has(method)) {
            return originalFetch(input, init);
        }
        const request = new Request(input instanceof Request ? input : url.href, init);
        const retry = request.clone();
        const headers = new Headers(request.headers);
        headers.set("X-Gateway-CSRF", await session());
        const response = await originalFetch(new Request(request, { headers }));
        // Retry only a gateway admission rejection, which occurred before any
        // model/tool/backend dispatch. Never replay network failures or writes
        // rejected by the upstream service.
        if (response.status === 403 && response.headers.get("X-Gateway-Admission-Error") === "browser_csrf_required") {
            const renewed = new Headers(retry.headers);
            renewed.set("X-Gateway-CSRF", await session(true));
            return originalFetch(new Request(retry, { headers: renewed }));
        }
        return response;
    };
})();
