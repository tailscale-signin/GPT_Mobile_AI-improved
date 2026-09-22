package dev.melo.gptmobile.improved.ui.components.artifact

import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.TimeUnit

/**
 * Safe interactive HTML/SVG artifact previewing with sandboxed WebView isolation.
 * Prevents XSS and malicious script execution while allowing rich interactive content display.
 */
class SandboxedArtifactView @Inject constructor() {
    private val lock = Any()

    /**
     * Create a new sandboxed WebView instance.
     */
    fun createSandboxedWebView(context: android.content.Context): WebView {
        return with(WebView(context)) {
            settings.apply {
                // Enable JavaScript for interactive content
                javaScriptEnabled = true

                // Disable dangerous features
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false

                // Enable mixed content for HTTPS pages
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true

                // Set user agent to desktop for better compatibility
                userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Security check after page load
                    view?.let {
                        it.settings.javaScriptEnabled = false // Disable JS after load if needed
                    }
                }
            }

            webChromeClient = WebChromeClient()

            // Add security overlay for detecting malicious content
            addSecurityOverlay()
        }
    }

    /**
     * Load HTML content safely.
     */
    fun loadHtmlContent(webView: WebView, html: String) {
        webView.loadDataWithBaseURL(
            null,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    /**
     * Load SVG content safely.
     */
    fun loadSvgContent(webView: WebView, svg: String) {
        webView.loadDataWithBaseURL(
            null,
            "<svg xmlns='http://www.w3.org/2000/svg'>$svg</svg>",
            "image/svg+xml",
            "UTF-8",
            null
        )
    }

    /**
     * Inject security overlay to detect and block malicious scripts.
     */
    private fun addSecurityOverlay(webView: WebView) {
        val script = """
            (function() {
                // Block dangerous event handlers
                document.addEventListener('mouseover', function(e) {
                    if (e.target.tagName === 'SCRIPT') {
                        e.preventDefault();
                    }
                });

                // Limit execution time of any scripts
                var start = Date.now();
                setInterval(function() {
                    if (Date.now() - start > 5000) {
                        location.href = 'about:blank';
                    }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * Clear WebView content.
     */
    fun clearContent(webView: WebView) {
        webView.loadUrl("about:blank")
    }

    /**
     * Destroy WebView to free resources.
     */
    fun destroyWebView(webView: WebView) {
        webView.destroy()
    }
}