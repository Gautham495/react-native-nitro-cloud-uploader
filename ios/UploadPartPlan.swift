import Foundation

/// Splits a file into exactly one part per upload URL.
///
/// A single URL always maps to one part covering the whole file (a plain PUT to a presigned
/// or unsigned URL). With several URLs the part size is `max(ceil(fileSize / urls), 5 MiB)`
/// and the last part takes the remainder, because S3-compatible multipart uploads reject any
/// non-final part smaller than 5 MiB. Same math as the Android implementation.
enum UploadPartPlan {
  static let minimumMultipartPartSize: Int64 = 5 * 1024 * 1024

  static func make(fileSize: Int64, uploadUrls: [String]) throws -> [UploadPart] {
    guard !uploadUrls.isEmpty else { throw UploadError.emptyUploadUrls }
    guard fileSize > 0 else { throw UploadError.emptyFile }

    let urls = try uploadUrls.map { try URL(uploadUrlString: $0) }

    if urls.count == 1 {
      return [UploadPart(partNumber: 1, url: urls[0], offset: 0, size: fileSize)]
    }

    let partCount = Int64(urls.count)
    let partSize = max((fileSize + partCount - 1) / partCount, minimumMultipartPartSize)
    let minimumFileSize = (partCount - 1) * minimumMultipartPartSize
    guard fileSize > minimumFileSize else {
      throw UploadError.fileTooSmall(fileSize: fileSize, partCount: urls.count, minimumFileSize: minimumFileSize)
    }

    return urls.enumerated().map { index, url in
      let offset = Int64(index) * partSize
      let size = index == urls.count - 1 ? fileSize - offset : partSize
      return UploadPart(partNumber: index + 1, url: url, offset: offset, size: size)
    }
  }
}
