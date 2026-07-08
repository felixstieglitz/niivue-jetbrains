package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * File-name suffixes routed to the Niivue viewer. Covers NIfTI, NRRD, FreeSurfer,
 * MetaImage, MRtrix, BrainVoyager, and NumPy formats.
 *
 * Compound extensions (`.nii.gz`, `.mif.gz`) are matched here via [String.endsWith];
 * see `plugin.xml`'s `patterns=` attribute for the corresponding `WildcardFileNameMatcher`
 * registration that handles IntelliJ's FileType lookup.
 */
private val SUPPORTED_EXTENSIONS = setOf(
    ".nii", ".nii.gz",
    ".nrrd", ".nhdr",
    ".mgh", ".mgz",
    ".mha",
    ".mif", ".mif.gz", ".mih",
    // BrainVoyager. Plain ".v" is intentionally NOT claimed: it is also the
    // standard extension for Verilog, Coq, and V-language source files, and
    // claiming it would hijack those as read-only binaries.
    ".v16", ".vmr",
    ".npy", ".npz",
)

/**
 * Routes supported medical-imaging volume files to [NiivueFileEditor].
 *
 * Uses [FileEditorPolicy.HIDE_DEFAULT_EDITOR] to prevent IntelliJ's binary/hex
 * viewer from competing for the same file. Marked [DumbAware] so the editor
 * opens during initial indexing too — viewing a volume doesn't depend on PSI.
 */
class NiivueFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return SUPPORTED_EXTENSIONS.any { name.endsWith(it) }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        NiivueFileEditor(file)

    override fun getEditorTypeId(): String = "niivue-viewer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
