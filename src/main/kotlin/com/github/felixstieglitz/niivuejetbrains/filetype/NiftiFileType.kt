package com.github.felixstieglitz.niivuejetbrains.filetype

import com.github.felixstieglitz.niivuejetbrains.icons.NiivueIcons
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

/**
 * The plugin's single [FileType] entry, registered in `plugin.xml` for all 14
 * supported volume-file extensions (`*.nii`, `*.nii.gz`, `*.nrrd`, `*.mgh`, …).
 *
 * Although the file type carries the "NIfTI" name (the primary and most-common
 * format), it represents the union of all formats the embedded Niivue viewer
 * can render. Marked binary and read-only so IntelliJ's default text/hex editors
 * don't compete with our [com.github.felixstieglitz.niivuejetbrains.editor.NiivueFileEditor].
 */
object NiftiFileType : FileType {
    override fun getName() = "NIfTI"
    override fun getDescription() = "NIfTI medical imaging file"
    override fun getDefaultExtension() = "nii"
    override fun getIcon() = NiivueIcons.FILE
    override fun isBinary() = true
    override fun isReadOnly() = true
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}
