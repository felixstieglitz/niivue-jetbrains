package com.github.felixstieglitz.niivuejetbrains.filetype

import com.github.felixstieglitz.niivuejetbrains.icons.NiivueIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

object NiftiFileType : FileType {
    override fun getName() = "NIfTI"
    override fun getDescription() = "NIfTI medical imaging file"
    override fun getDefaultExtension() = "nii"
    override fun getIcon() = NiivueIcons.FILE
    override fun isBinary() = true
    override fun isReadOnly() = true
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
