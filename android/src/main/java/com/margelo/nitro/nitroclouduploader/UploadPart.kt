package com.margelo.nitro.nitroclouduploader

/** One byte range of the source file, uploaded with a single PUT to [url]. */
internal data class UploadPart(
    /** 1-based, matches the S3 `PartNumber`. */
    val partNumber: Int,
    val url: String,
    val offset: Long,
    val size: Long,
) {
    /** 0-based index reported to JS as `chunkIndex`. */
    val index: Int get() = partNumber - 1
}
