package com.margelo.nitro.nitroclouduploader

/**
 * Splits a file into exactly one part per upload URL.
 *
 * A single URL always maps to one part covering the whole file (a plain PUT to a
 * presigned or unsigned URL). With several URLs the part size is
 * `max(ceil(fileSize / urls), 5 MiB)` and the last part takes the remainder, because
 * S3-compatible multipart uploads reject any non-final part smaller than 5 MiB.
 */
internal object UploadPartPlan {
    const val MIN_MULTIPART_PART_SIZE: Long = 5L * 1024 * 1024

    fun plan(fileSize: Long, uploadUrls: Array<String>): List<UploadPart> {
        require(uploadUrls.isNotEmpty()) { "uploadUrls must not be empty" }
        require(fileSize > 0) { "File is empty" }

        if (uploadUrls.size == 1) {
            return listOf(UploadPart(partNumber = 1, url = uploadUrls[0], offset = 0, size = fileSize))
        }

        val partCount = uploadUrls.size
        val partSize = maxOf(ceilDiv(fileSize, partCount.toLong()), MIN_MULTIPART_PART_SIZE)
        val minimumFileSize = (partCount - 1) * MIN_MULTIPART_PART_SIZE
        require(fileSize > minimumFileSize) {
            "File size ($fileSize bytes) is too small for $partCount parts: every part except " +
                "the last must be at least $MIN_MULTIPART_PART_SIZE bytes, so the file must be " +
                "larger than $minimumFileSize bytes. Request fewer upload URLs."
        }

        return uploadUrls.mapIndexed { index, url ->
            val offset = index * partSize
            val size = if (index == partCount - 1) fileSize - offset else partSize
            UploadPart(partNumber = index + 1, url = url, offset = offset, size = size)
        }
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}
