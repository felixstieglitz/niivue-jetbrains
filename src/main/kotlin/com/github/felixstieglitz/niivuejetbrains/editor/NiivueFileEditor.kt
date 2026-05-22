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

private const val SOFT_LIMIT_BYTES = 200L * 1024 * 1024   // 200 MB — warn but proceed
private const val HARD_LIMIT_BYTES = 1024L * 1024 * 1024  // 1 GB — refuse to load

class NiivueFileEditor(
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val browser: JBCefBrowser? = createBrowser()
    private val firstLoadHandled = AtomicBoolean(false)

    private val mainComponent: JComponent = browser?.component ?: createUnsupportedComponent()

    init {
        if (browser != null) {
            installLoadHandler(browser)
            browser.loadHTML(buildHtml())
        }
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) return null
        return try {
            JBCefBrowser.createBuilder()
                .setOffScreenRendering(false)
                .build()
                .also { Disposer.register(this, it) }
        } catch (t: Throwable) {
            thisLogger().warn("Could not create JBCefBrowser for Niivue viewer", t)
            null
        }
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
                val js = "window.loadNiivueVolume(${jsString(base64)}, ${jsString(file.name)})"
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

    private fun buildHtml(): String {
        val cls = NiivueFileEditor::class.java
        val template = cls.getResource("/webview/index.html")!!.readText()
        val bundle = cls.getResource("/webview/niivue.umd.js")!!.readText()
        return template.replace("// @@NIIVUE_BUNDLE_INJECTION_POINT@@", bundle)
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
    override fun dispose() {}
}
