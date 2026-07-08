package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.utils.JBCefLocalRequestHandler
import com.intellij.ui.jcef.utils.JBCefStreamResourceHandler
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Protocol and authority of the virtual origin the viewer page lives on.
 * Requests to it never leave Chromium: a per-browser [JBCefLocalRequestHandler]
 * intercepts them before any network layer and serves the registered resources.
 * Same setup as the platform's own JCEF image viewer.
 */
private const val VIEWER_PROTOCOL = "http"
private const val VIEWER_AUTHORITY = "localhost"
private const val VIEWER_ORIGIN = "$VIEWER_PROTOCOL://$VIEWER_AUTHORITY"

/**
 * Editor tab that renders NIfTI and related medical-imaging volume files via
 * the embedded [Niivue](https://github.com/niivue/niivue) WebGL2 viewer
 * running in a [JBCefBrowser].
 *
 * The viewer page, the Niivue bundle, and the volume bytes are all served to
 * the browser through a per-browser CEF request handler (see
 * [installRequestHandler]): the page fetches the volume from a per-editor URL
 * and Chromium streams the bytes straight from a file stream. No Base64, no
 * `executeJavaScript` payloads, and no size limit beyond renderer memory.
 * Niivue handles format detection (including gzip decompression) and
 * rendering.
 *
 * Browser disposal cascades from this editor via [Disposer.register], and
 * in-flight volume streams are closed the same way. The empty `selectNotify`,
 * `deselectNotify`, `addPropertyChangeListener` and friends are required by
 * the [FileEditor] interface but unused — this is a stateless read-only
 * viewer.
 */
class NiivueFileEditor(
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    /**
     * Per-editor volume path. All JCEF browsers share one Chromium request
     * context, so the URL must be unique per tab to rule out any cross-tab
     * response aliasing.
     */
    private val volumePath = "volume-${UUID.randomUUID()}"

    private val browser: JBCefBrowser? = createBrowser()
    private val firstLoadHandled = AtomicBoolean(false)

    @Volatile
    private var disposed = false

    private val mainComponent: JComponent = browser?.component ?: createUnsupportedComponent()

    init {
        if (browser != null) {
            installRequestHandler(browser)
            installLoadHandler(browser)
            installWheelBridge(browser)
            browser.loadURL("$VIEWER_ORIGIN/index.html")
        }
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) return null
        return try {
            JBCefBrowser.createBuilder()
                // Off-screen rendering keeps the browser a lightweight Swing
                // component that receives ordinary Swing input events, which
                // lets installWheelBridge() forward scroll input to the page
                // itself. Windowed mode routes macOS trackpad scrolling
                // through a native JCEF path that reaches the page as an
                // unusable wheel-event stream.
                .setOffScreenRendering(true)
                .build()
                .also { Disposer.register(this, it) }
        } catch (t: Throwable) {
            thisLogger().warn("Could not create JBCefBrowser for Niivue viewer", t)
            null
        }
    }

    /**
     * Forwards Swing mouse-wheel input to the webview as
     * `window.niivueWheel(delta, x, y)` calls.
     *
     * JCEF's own wheel synthesis is unreliable for macOS trackpads, so the
     * page swallows every native wheel event (see the proxy in index.html)
     * and scrolls exclusively through this bridge. Swing's
     * [java.awt.event.MouseWheelEvent.getPreciseWheelRotation] reports
     * trackpad gestures faithfully: small fractions per event for trackpads,
     * ±1 per notch for mouse wheels. Coalescing and slice stepping happen on
     * the JS side, so the raw values are forwarded as-is.
     */
    private fun installWheelBridge(b: JBCefBrowser) {
        val listener = java.awt.event.MouseWheelListener { e ->
            val rotation = e.preciseWheelRotation
            if (rotation != 0.0) {
                val js = "window.niivueWheel && window.niivueWheel($rotation, ${e.x}, ${e.y})"
                b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
            }
            e.consume()
        }
        fun attach(c: java.awt.Component) {
            c.addMouseWheelListener(listener)
            if (c is java.awt.Container) c.components.forEach(::attach)
        }
        attach(b.component)
    }

    /**
     * Registers the resources the viewer page consists of under
     * [VIEWER_ORIGIN]. The handler rejects every request outside that mapping,
     * so the page and the bundle must be served through it too (which also
     * makes the volume fetch same-origin — no CORS involved).
     *
     * The provider lambdas run on a CEF thread once per request, so a reload
     * gets fresh streams. Each [JBCefStreamResourceHandler] registers itself
     * against this editor as its [Disposer] parent: streams are closed both
     * at end-of-transfer and when the tab closes mid-transfer. After this
     * editor is disposed, that registration throws and the handler falls back
     * to rejecting the request, which is the behavior we want anyway.
     */
    private fun installRequestHandler(b: JBCefBrowser) {
        val handler = JBCefLocalRequestHandler(VIEWER_PROTOCOL, VIEWER_AUTHORITY)
        handler.addResource("index.html") { classpathResourceHandler("/webview/index.html", "text/html") }
        handler.addResource("niivue.umd.js") { classpathResourceHandler("/webview/niivue.umd.js", "text/javascript") }
        handler.addResource(volumePath) {
            if (disposed) return@addResource null
            // NIO bypasses the VFS content-load size limit (~20 MB) for local
            // files; the VFS stream remains as fallback for e.g. archive entries.
            val stream = file.fileSystem.getNioPath(file)?.let(Files::newInputStream)
                ?: file.inputStream
            JBCefStreamResourceHandler(
                stream,
                "application/octet-stream",
                this,
                mapOf("Cache-Control" to "no-store"),
            )
        }
        b.jbCefClient.addRequestHandler(handler, b.cefBrowser)
    }

    private fun classpathResourceHandler(path: String, mimeType: String) =
        if (disposed) null
        else JBCefStreamResourceHandler(javaClass.getResourceAsStream(path)!!, mimeType, this)

    private fun createUnsupportedComponent(): JComponent =
        JPanel(BorderLayout()).apply {
            add(
                JBLabel(
                    "<html><center>JCEF (embedded Chromium) is not available in this IDE.<br>" +
                        "The Niivue viewer cannot render NIfTI files here.</center></html>",
                    SwingConstants.CENTER
                ),
                BorderLayout.CENTER
            )
        }

    private fun installLoadHandler(b: JBCefBrowser) {
        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && firstLoadHandled.compareAndSet(false, true)) {
                    scheduleVolumeLoad(b)
                }
            }
        }, b.cefBrowser)
    }

    private fun scheduleVolumeLoad(b: JBCefBrowser) {
        val sizeMB = file.length / (1024 * 1024)
        // Only the fetch URL, name, and size cross the JS bridge; the page
        // pulls the actual bytes through the request handler. The volume path
        // is a UUID ([A-Fa-f0-9-]), safe to inline; the filename can contain
        // arbitrary characters and goes through jsString().
        val js = "window.loadNiivueVolume(\"$VIEWER_ORIGIN/$volumePath\", ${jsString(file.name)}, $sizeMB)"
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun jsString(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '<' -> append("\\u003c")
                c.code == 0x2028 -> append("\\u2028")
                c.code == 0x2029 -> append("\\u2029")
                c.code < 0x20 -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    override fun getComponent(): JComponent = mainComponent
    override fun getPreferredFocusedComponent(): JComponent = mainComponent
    override fun getName(): String = "Niivue"
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun selectNotify() {}
    override fun deselectNotify() {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? = null
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getStructureViewBuilder(): StructureViewBuilder? = null
    override fun getFile(): VirtualFile = file
    override fun dispose() {
        disposed = true
    }
}
