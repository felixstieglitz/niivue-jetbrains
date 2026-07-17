package com.github.felixstieglitz.niivuejetbrains.editor

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts allowed to bypass the local-resource mapping and hit the real
 * network. "Add Image > Example Image" fetches the Niivue demo volume from
 * here; the host serves `Access-Control-Allow-Origin: *`, so the fetch from
 * the viewer's `http://localhost` origin succeeds.
 */
private val PASSTHROUGH_URL_PREFIXES = listOf("https://niivue.github.io/")

/**
 * Serves the viewer page and its resources from a virtual origin, in the
 * spirit of the platform's `JBCefLocalRequestHandler`, with two differences
 * the toolbar's Add Image feature needs:
 *
 *  - Resources can be registered at any time. The platform handler backs
 *    [addResource] with an unsynchronized `HashMap`, so its mapping must not
 *    change once the browser is running; here the map is concurrent, and each
 *    file the user picks registers a fresh path on the fly.
 *  - Requests to [PASSTHROUGH_URL_PREFIXES] fall through to Chromium's
 *    default network handling instead of being rejected.
 *
 * Everything else is rejected, exactly like the platform handler: requests to
 * the virtual origin never reach any network layer, and arbitrary external
 * URLs stay blocked.
 */
internal class NiivueLocalRequestHandler(
    private val protocol: String,
    private val authority: String,
) : CefRequestHandlerAdapter() {

    private val resources = ConcurrentHashMap<String, () -> CefResourceHandler?>()

    private val rejectingHandler = object : CefResourceHandlerAdapter() {
        override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
            callback.cancel()
            return false
        }
    }

    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest,
        ): CefResourceHandler {
            val url = try {
                URI(request.url)
            } catch (e: Exception) {
                return rejectingHandler
            }
            if (url.scheme != protocol || url.authority != authority) return rejectingHandler
            val path = url.path.orEmpty().trim('/')
            return resources[path]?.invoke() ?: rejectingHandler
        }
    }

    /**
     * Registers [resourceProvider] under [resourcePath]. The provider is
     * invoked once per request (on a CEF thread) and may return null to
     * reject. Safe to call while the browser is running.
     */
    fun addResource(resourcePath: String, resourceProvider: () -> CefResourceHandler?) {
        resources[resourcePath.trim('/')] = resourceProvider
    }

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? {
        val url = request?.url
        if (url != null && PASSTHROUGH_URL_PREFIXES.any(url::startsWith)) {
            return null // default (network) handling
        }
        return resourceRequestHandler
    }
}
