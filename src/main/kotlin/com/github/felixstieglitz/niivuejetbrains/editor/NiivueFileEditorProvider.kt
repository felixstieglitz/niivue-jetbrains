package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

private val SUPPORTED_EXTENSIONS = setOf(
    ".nii", ".nii.gz",
    ".nrrd", ".nhdr",
    ".mgh", ".mgz",
    ".mha",
    ".mif", ".mif.gz", ".mih",
    ".v", ".v16", ".vmr",
    ".npy", ".npz",
)

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
