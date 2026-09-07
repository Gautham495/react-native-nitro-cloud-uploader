package com.margelo.nitro.nitroclouduploader

import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

/**
 * Resolves the JS-provided path to a readable [File].
 *
 * Accepts a plain filesystem path or a `file://` URI. A plain path is used verbatim;
 * a percent-decoded variant is only tried when the verbatim path does not exist.
 * (The previous unconditional `URLDecoder.decode` turned `+` into a space and threw on
 * a literal `%`, so files with either character in their name could never be uploaded.)
 */
internal object UploadFileResolver {
    fun resolve(filePath: String): File {
        require(filePath.isNotBlank()) { "File path cannot be empty" }
        require(!filePath.startsWith("content://")) {
            "content:// URIs are not supported. Copy the file into app storage first and pass " +
                "its filesystem path: $filePath"
        }

        val file = candidatePaths(filePath).map(::File).firstOrNull { it.exists() }
            ?: throw FileNotFoundException("File not found: $filePath")
        require(file.isFile) { "Not a file: ${file.path}" }
        require(file.canRead()) { "Cannot read file: ${file.path}" }
        return file
    }

    private fun candidatePaths(filePath: String): List<String> {
        if (filePath.startsWith("file://")) {
            return listOfNotNull(Uri.parse(filePath).path, filePath.removePrefix("file://"))
        }
        val decoded = if (filePath.contains('%')) Uri.decode(filePath) else null
        return listOfNotNull(filePath, decoded?.takeIf { it != filePath })
    }
}
