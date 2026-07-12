package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
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
 * Fixed resource path for the overlay volume picked via the toolbar. The
 * file behind it changes per pick (see [NiivueFileEditor.overlayFile]); the
 * page cache-busts each fetch with a query string.
 */
private const val OVERLAY_PATH = "overlay-volume"

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
    private val project: Project,
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

    /**
     * JS-to-IDE bridge behind the toolbar's "Overlay > Add" entry. Created
     * before [JBCefBrowser.loadURL] because JCEF only allows new queries
     * while the native browser is not yet spawned.
     */
    private val pickFileQuery: JBCefJSQuery? =
        browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    /**
     * Volume the overlay resource path currently serves. Written on the EDT
     * when the user picks a file, read on a CEF thread by the resource
     * provider — volatile instead of mutating the request handler's
     * (unsynchronized) resource map after browser start. The path is fixed
     * and registered up front; each pick busts Chromium's cache with a
     * `?v=<uuid>` query the handler ignores when matching.
     */
    @Volatile
    private var overlayFile: VirtualFile? = null

    @Volatile
    private var disposed = false

    private val mainComponent: JComponent = browser?.component ?: createUnsupportedComponent()

    init {
        if (browser != null) {
            installRequestHandler(browser)
            installLoadHandler(browser)
            installWheelBridge(browser)
            installPickFileHandler(browser)
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
        handler.addResource("viewer.js") { classpathResourceHandler("/webview/viewer.js", "text/javascript") }
        handler.addResource("niivue.umd.js") { classpathResourceHandler("/webview/niivue.umd.js", "text/javascript") }
        handler.addResource(volumePath) { volumeStreamHandler(file) }
        handler.addResource(OVERLAY_PATH) { overlayFile?.let { volumeStreamHandler(it) } }
        b.jbCefClient.addRequestHandler(handler, b.cefBrowser)
    }

    private fun volumeStreamHandler(volume: VirtualFile): JBCefStreamResourceHandler? {
        if (disposed) return null
        // NIO bypasses the VFS content-load size limit (~20 MB) for local
        // files; the VFS stream remains as fallback for e.g. archive entries.
        val stream = volume.fileSystem.getNioPath(volume)?.let(Files::newInputStream)
            ?: volume.inputStream
        return JBCefStreamResourceHandler(
            stream,
            "application/octet-stream",
            this,
            mapOf("Cache-Control" to "no-store"),
        )
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

    /**
     * Wires the toolbar's "Overlay > Add" entry to the IDE's native file
     * chooser. The page calls `window.niivuePickFile()` (installed in
     * [scheduleVolumeLoad]); the query handler hops to the EDT, shows the
     * chooser, points the fixed overlay resource at the picked file, and
     * reports the result back via `window.niivueViewerOnFilePicked` — the
     * page then fetches the bytes through the request handler like the main
     * volume, so overlays get the same no-size-limit streaming path.
     */
    private fun installPickFileHandler(b: JBCefBrowser) {
        val query = pickFileQuery ?: return
        Disposer.register(this, query)
        query.addHandler {
            ApplicationManager.getApplication().invokeLater { chooseOverlayFile(b) }
            null
        }
    }

    private fun chooseOverlayFile(b: JBCefBrowser) {
        if (disposed) return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Add Overlay Volume")
            .withDescription("Overlay a second volume (e.g. a segmentation mask or statistical map)")
            .withFileFilter { isSupportedVolumeFileName(it.name) }
        val chosen = FileChooser.chooseFile(descriptor, project, file.parent)
        if (chosen == null || disposed) {
            executeJs(b, "window.niivueViewerOnFilePicked(null, null, 0)")
            return
        }
        overlayFile = chosen
        val sizeMB = chosen.length / (1024 * 1024)
        // Fresh query per pick so Chromium cannot serve a previous overlay
        // from cache; the request handler matches on the path alone.
        val url = "$VIEWER_ORIGIN/$OVERLAY_PATH?v=${UUID.randomUUID()}"
        executeJs(b, "window.niivueViewerOnFilePicked(\"$url\", ${jsString(chosen.name)}, $sizeMB)")
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
        // Expose the file-pick bridge before the volume load so the toolbar
        // is fully functional as soon as the image appears.
        pickFileQuery?.let {
            executeJs(b, "window.niivuePickFile = function() { ${it.inject("''")} };")
        }
        val sizeMB = file.length / (1024 * 1024)
        // Only the fetch URL, name, and size cross the JS bridge; the page
        // pulls the actual bytes through the request handler. The volume path
        // is a UUID ([A-Fa-f0-9-]), safe to inline; the filename can contain
        // arbitrary characters and goes through jsString().
        executeJs(b, "window.loadNiivueVolume(\"$VIEWER_ORIGIN/$volumePath\", ${jsString(file.name)}, $sizeMB)")
    }

    private fun executeJs(b: JBCefBrowser, js: String) {
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
