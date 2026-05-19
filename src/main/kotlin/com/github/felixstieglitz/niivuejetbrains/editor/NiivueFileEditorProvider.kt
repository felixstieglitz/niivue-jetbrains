package com.github.felixstieglitz.niivuejetbrains.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class NiivueFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.name.endsWith(".nii", ignoreCase = true) ||
            file.name.endsWith(".nii.gz", ignoreCase = true)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        NiivueFileEditor(project, file)

    override fun getEditorTypeId(): String = "niivue-viewer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
