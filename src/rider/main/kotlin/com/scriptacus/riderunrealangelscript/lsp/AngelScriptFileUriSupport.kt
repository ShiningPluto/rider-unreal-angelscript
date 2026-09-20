package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.features.FileUriSupportBase
import java.net.URI

/**
 * Keeps the plugin and the language server talking about files in the same spelling.
 *
 * The server normalises every URI it stores with `NormalizeUri()`, which lowercases the whole
 * string, and derives the URIs of files it finds on disk from `URI.parse(root).fsPath`, which on
 * Windows lowercases the drive letter. Rider spells the same file `file:///D:/...`. URI lookups
 * still find each other - the server lowercases before looking up - but module names do not:
 * `getModuleName()` strips a workspace root prefix with a case-sensitive `startsWith`, so a
 * drive-letter mismatch leaves a module named after its whole path, and the file the editor has
 * open ends up as a second module beside the one scanned from disk.
 *
 * So everything this plugin sends - documents, workspace folders, script roots - goes through
 * [normalizedUri].
 */
class AngelScriptFileUriSupport : FileUriSupportBase() {

    override fun getFileUri(file: VirtualFile): URI? {
        val baseUri = super.getFileUri(file) ?: return null
        if (!SystemInfo.isWindows) return baseUri

        val uriString = baseUri.toString()
        val normalized = lowercaseDriveLetter(uriString)
        return if (normalized != uriString) URI.create(normalized) else baseUri
    }

    override fun toString(file: VirtualFile): String? {
        val baseString = super.toString(file)
        if (!SystemInfo.isWindows || baseString == null) return baseString
        return lowercaseDriveLetter(baseString)
    }

    override fun findFileByUri(fileUri: String): VirtualFile? {
        if (!SystemInfo.isWindows) return super.findFileByUri(fileUri)
        // Rider's own VirtualFile lookup wants the drive letter back in upper case.
        return super.findFileByUri(uppercaseDriveLetter(fileUri))
    }

    companion object {

        /** The URI for [file] as this plugin spells it to the language server. */
        fun normalizedUri(file: VirtualFile): String {
            val uri = LSPIJUtils.toUri(file).toASCIIString()
            return if (SystemInfo.isWindows) lowercaseDriveLetter(uri) else uri
        }

        /** `file:///D:/x` -> `file:///d:/x`, leaving anything else alone. */
        fun lowercaseDriveLetter(uri: String): String = mapDriveLetter(uri) { it.lowercaseChar() }

        /** `file:///d:/x` -> `file:///D:/x`, leaving anything else alone. */
        fun uppercaseDriveLetter(uri: String): String = mapDriveLetter(uri) { it.uppercaseChar() }

        private inline fun mapDriveLetter(uri: String, map: (Char) -> Char): String {
            val drivePos = "file:///".length
            if (!uri.startsWith("file:///") || uri.length < drivePos + 2) return uri
            if (uri[drivePos + 1] != ':') return uri

            val mapped = map(uri[drivePos])
            if (mapped == uri[drivePos]) return uri
            return uri.substring(0, drivePos) + mapped + uri.substring(drivePos + 1)
        }
    }
}
