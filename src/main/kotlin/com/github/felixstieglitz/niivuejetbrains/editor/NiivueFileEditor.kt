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
 * File-name pre-filter for DICOM slices, mirroring niivue-vscode: matches
 * `.dcm`/`.ima`, extension-less names (`IM_0001`), and UID-style names made
 * of digits and dots. Content is still verified via [hasDicomMagic] before a
 * file is treated as DICOM.
 */
private val DICOM_UID_NAME = Regex("^[\\d.]+$")

private fun isDicomCandidateName(name: String): Boolean {
    val base = name.substringAfterLast('/').lowercase()
    if (base.endsWith(".dcm") || base.endsWith(".ima")) return true
    return !base.contains('.') || DICOM_UID_NAME.matches(base)
}

/** DICOM Part 10 magic: the bytes "DICM" at offset 128. */
private fun hasDicomMagic(file: VirtualFile): Boolean = try {
    file.inputStream.use { stream ->
        val preamble = ByteArray(132)
        var read = 0
        while (read < preamble.size) {
            val n = stream.read(preamble, read, preamble.size - read)
            if (n < 0) break
            read += n
        }
        read == preamble.size &&
            preamble[128] == 'D'.code.toByte() && preamble[129] == 'I'.code.toByte() &&
            preamble[130] == 'C'.code.toByte() && preamble[131] == 'M'.code.toByte()
    }
} catch (e: Exception) {
    false
}

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
     * JS-to-IDE bridge behind the toolbar's file-picking entries (Overlay >
     * Add, Add Image > File(s) / DICOM Folder). Created before
     * [JBCefBrowser.loadURL] because JCEF only allows new queries while the
     * native browser is not yet spawned. The request string selects the
     * action; results return through `window.niivueViewerOn*Picked`.
     */
    private val hostRequestQuery: JBCefJSQuery? =
        browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    /**
     * Serves every resource of the viewer page. Kept as a field because
     * picked files are registered as new resource paths while the browser is
     * running (the map is concurrent, see [NiivueLocalRequestHandler]).
     */
    private var requestHandler: NiivueLocalRequestHandler? = null

    @Volatile
    private var disposed = false

    private val mainComponent: JComponent = browser?.component ?: createUnsupportedComponent()

    init {
        if (browser != null) {
            installRequestHandler(browser)
            installLoadHandler(browser)
            installWheelBridge(browser)
            installHostRequestHandler(browser)
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
        val handler = NiivueLocalRequestHandler(VIEWER_PROTOCOL, VIEWER_AUTHORITY)
        handler.addResource("index.html") { classpathResourceHandler("/webview/index.html", "text/html") }
        handler.addResource("viewer.js") { classpathResourceHandler("/webview/viewer.js", "text/javascript") }
        handler.addResource("dicom-worker.js") {
            classpathResourceHandler("/webview/dicom-worker.js", "text/javascript")
        }
        handler.addResource("niivue.umd.js") { classpathResourceHandler("/webview/niivue.umd.js", "text/javascript") }
        // dcm2niix WASM build, imported on demand by dicom-worker.js to
        // convert DICOM series without blocking the browser UI thread.
        handler.addResource("dcm2niix.js") { classpathResourceHandler("/webview/dcm2niix.js", "text/javascript") }
        handler.addResource("dcm2niix.wasm") { classpathResourceHandler("/webview/dcm2niix.wasm", "application/wasm") }
        handler.addResource(volumePath) { volumeStreamHandler(file) }
        requestHandler = handler
        b.jbCefClient.addRequestHandler(handler, b.cefBrowser)
    }

    /**
     * Registers [volume] under a fresh resource path and returns the URL the
     * page can fetch it from. Each pick gets its own path, so no caching or
     * cross-pick aliasing concerns arise.
     */
    private fun serveFile(volume: VirtualFile): String {
        val path = "added-${UUID.randomUUID()}"
        requestHandler?.addResource(path) { volumeStreamHandler(volume) }
        return "$VIEWER_ORIGIN/$path"
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
     * Wires the toolbar's file-picking entries to the IDE's native choosers.
     * The page calls `window.niivueHostRequest(action)` (installed in
     * [scheduleVolumeLoad]); the query handler hops to the EDT, shows the
     * matching chooser, registers the picked files as fresh resource paths,
     * and reports URL + name back to the page — which then fetches the bytes
     * through the request handler like the main volume, so every pick gets
     * the same no-size-limit streaming path.
     */
    private fun installHostRequestHandler(b: JBCefBrowser) {
        val query = hostRequestQuery ?: return
        Disposer.register(this, query)
        query.addHandler { action ->
            ApplicationManager.getApplication().invokeLater {
                when (action) {
                    "pickOverlay" -> chooseOverlayFile(b)
                    "pickFiles" -> chooseAddFiles(b)
                    "pickDcmFolder" -> chooseDcmFolder(b)
                }
            }
            null
        }
    }

    private fun chooseOverlayFile(b: JBCefBrowser) {
        if (disposed) return
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Add Overlay Volume")
            .withDescription("Overlay a second volume (e.g. a segmentation mask or statistical map)")
            .withFileFilter { isSupportedVolumeFileName(it.name) }
        val chosen = FileChooser.chooseFile(descriptor, project, file.parent)
        if (chosen == null || disposed) {
            executeJs(b, "window.niivueViewerOnFilePicked(null, null, 0)")
            return
        }
        val sizeMB = chosen.length / (1024 * 1024)
        executeJs(b, "window.niivueViewerOnFilePicked(${jsString(serveFile(chosen))}, ${jsString(chosen.name)}, $sizeMB)")
    }

    /** Add Image > File(s): each picked volume opens on its own canvas. */
    private fun chooseAddFiles(b: JBCefBrowser) {
        if (disposed) return
        val descriptor = FileChooserDescriptorFactory.multiFiles()
            .withTitle("Add Images")
            .withDescription("Each image opens on its own canvas next to the current one")
            .withFileFilter { isSupportedVolumeFileName(it.name) }
        val chosen = FileChooser.chooseFiles(descriptor, project, file.parent)
        if (chosen.isEmpty() || disposed) {
            executeJs(b, "window.niivueViewerOnFilesPicked(null)")
            return
        }
        val json = chosen.joinToString(",", "[", "]") {
            "{\"url\":${jsString(serveFile(it))},\"name\":${jsString(it.name)},\"sizeMB\":${it.length / (1024 * 1024)}}"
        }
        executeJs(b, "window.niivueViewerOnFilesPicked($json)")
    }

    /**
     * Add Image > DICOM Folder: scans the picked directory for DICOM slices
     * (name pre-filter, then `DICM` magic check — same strategy as
     * niivue-vscode) and hands the whole series to the page, which converts
     * it with dcm2niix. Falls back to every file in the folder when nothing
     * passes the sniff (files without the Part 10 preamble). Scanning reads
     * the head of every candidate, so it runs off the EDT.
     */
    private fun chooseDcmFolder(b: JBCefBrowser) {
        if (disposed) return
        val descriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle("Open DICOM Folder")
            .withDescription("Load all DICOM slices in the folder as one series")
        val folder = FileChooser.chooseFile(descriptor, project, file.parent)
        if (folder == null || disposed) {
            executeJs(b, "window.niivueViewerOnDcmFolderPicked(null)")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val files = folder.children.filter { !it.isDirectory }
            val dicom = files
                .filter { isDicomCandidateName(it.name) && hasDicomMagic(it) }
                .sortedBy { it.name }
            val series = dicom.ifEmpty { files.sortedBy { it.name } }
            if (series.isEmpty() || disposed) {
                executeJs(b, "window.niivueViewerOnDcmFolderPicked(null)")
                return@executeOnPooledThread
            }
            val json = series.joinToString(",", "[", "]") {
                "{\"url\":${jsString(serveFile(it))},\"name\":${jsString(it.name)}}"
            }
            executeJs(b, "window.niivueViewerOnDcmFolderPicked($json)")
        }
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
        // is fully functional as soon as the image appears. The page passes
        // the action name ("pickOverlay" / "pickFiles" / "pickDcmFolder").
        hostRequestQuery?.let {
            executeJs(b, "window.niivueHostRequest = function(action) { ${it.inject("action")} };")
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
