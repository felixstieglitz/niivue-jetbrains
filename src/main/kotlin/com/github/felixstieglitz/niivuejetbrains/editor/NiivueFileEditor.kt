package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
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
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** File size at which a "loading large file" warning is shown to the user. */
private const val SOFT_LIMIT_BYTES = 200L * 1024 * 1024   // 200 MB

/** Maximum supported file size. Larger files are refused to prevent IDE OOM. */
private const val HARD_LIMIT_BYTES = 1024L * 1024 * 1024  // 1 GB

/**
 * The viewer HTML with the Niivue bundle inlined, built once and shared across
 * all editor instances. The bundle (~2.3 MB) is a static resource, so there's
 * no reason to re-read and re-assemble it on every tab open.
 */
private val CACHED_HTML: String by lazy {
    val cls = NiivueFileEditor::class.java
    val template = cls.getResource("/webview/index.html")!!.readText()
    val bundle = cls.getResource("/webview/niivue.umd.js")!!.readText()
    template.replace("// @@NIIVUE_BUNDLE_INJECTION_POINT@@", bundle)
}

/**
 * Editor tab that renders NIfTI and related medical-imaging volume files via
 * the embedded [Niivue](https://github.com/niivue/niivue) WebGL2 viewer
 * running in a [JBCefBrowser].
 *
 * The editor reads the file as raw bytes on a background thread, Base64-encodes
 * them, and pushes the data into the webview via an `executeJavaScript` call
 * to `window.loadNiivueVolume(...)`. Niivue handles format detection (including
 * gzip decompression) and rendering.
 *
 * Files larger than [HARD_LIMIT_BYTES] are refused to prevent IDE OOM; files
 * between [SOFT_LIMIT_BYTES] and the hard limit are loaded with a warning in
 * the status overlay.
 *
 * Browser disposal cascades from this editor via [Disposer.register], so the
 * editor's own [dispose] is empty. The empty `selectNotify`, `deselectNotify`,
 * `addPropertyChangeListener` and friends are required by the [FileEditor]
 * interface but unused — this is a stateless read-only viewer.
 */
class NiivueFileEditor(
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val browser: JBCefBrowser? = createBrowser()
    private val firstLoadHandled = AtomicBoolean(false)

    @Volatile
    private var disposed = false

    private val mainComponent: JComponent = browser?.component ?: createUnsupportedComponent()

    init {
        if (browser != null) {
            installLoadHandler(browser)
            installWheelBridge(browser)
            browser.loadHTML(CACHED_HTML)
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
        val fileSize = file.length
        val sizeMB = fileSize / (1024 * 1024)
        if (fileSize > HARD_LIMIT_BYTES) {
            val maxMB = HARD_LIMIT_BYTES / (1024 * 1024)
            showStatus(b, "File too large ($sizeMB MB). Maximum supported size is $maxMB MB.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (fileSize > SOFT_LIMIT_BYTES) {
                    showStatus(b, "Loading large file ($sizeMB MB), this may take a moment...")
                }
                val bytes = file.contentsToByteArray()
                val base64 = Base64.getEncoder().encodeToString(bytes)
                if (disposed) return@executeOnPooledThread
                // Base64 output (RFC 4648) contains only [A-Za-z0-9+/=], none of which
                // need JS-string escaping — wrap it directly. Only the filename, which
                // can contain arbitrary characters, goes through jsString().
                val js = "window.loadNiivueVolume(\"$base64\", ${jsString(file.name)})"
                b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
            } catch (t: Throwable) {
                thisLogger().warn("Failed to load NIfTI volume from ${file.path}", t)
                showStatus(b, "Failed to load: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun showStatus(b: JBCefBrowser, msg: String) {
        val js = "document.getElementById('status').textContent = ${jsString(msg)}"
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
