import Foundation

/// Every user-reachable failure of the uploader. `localizedDescription` is what JS sees.
enum UploadError: LocalizedError {
  case emptyUploadId
  case emptyFilePath
  case emptyUploadUrls
  case uploadAlreadyInProgress(String)
  case noActiveUpload(String)
  case fileNotFound(String)
  case fileSizeUnavailable(String)
  case emptyFile
  case fileTooSmall(fileSize: Int64, partCount: Int, minimumFileSize: Int64)
  case invalidUrl(String)
  case tempFileCreationFailed(String)
  case unexpectedEndOfFile(offset: Int64, expected: Int64, missing: Int64)
  case badResponse(part: Int)
  case httpStatus(part: Int, statusCode: Int)
  case missingETag(part: Int)
  case partFailed(part: Int, attempts: Int, underlying: Error)

  var errorDescription: String? {
    switch self {
    case .emptyUploadId:
      return "Upload ID cannot be empty"
    case .emptyFilePath:
      return "File path cannot be empty"
    case .emptyUploadUrls:
      return "Upload URLs cannot be empty"
    case .uploadAlreadyInProgress(let uploadId):
      return "Upload already in progress: \(uploadId)"
    case .noActiveUpload(let uploadId):
      return "No active upload found: \(uploadId)"
    case .fileNotFound(let path):
      return "File not found: \(path)"
    case .fileSizeUnavailable(let path):
      return "Could not read file size: \(path)"
    case .emptyFile:
      return "File is empty"
    case .fileTooSmall(let fileSize, let partCount, let minimumFileSize):
      return "File size (\(fileSize) bytes) is too small for \(partCount) parts: every part except "
        + "the last must be at least \(UploadPartPlan.minimumMultipartPartSize) bytes, so the file "
        + "must be larger than \(minimumFileSize) bytes. Request fewer upload URLs."
    case .invalidUrl(let url):
      return "Invalid upload URL: \(url)"
    case .tempFileCreationFailed(let path):
      return "Failed to create temporary part file at \(path)"
    case .unexpectedEndOfFile(let offset, let expected, let missing):
      return "Unexpected EOF reading part at offset \(offset): wanted \(expected) bytes, short by \(missing)"
    case .badResponse(let part):
      return "Part \(part): no HTTP response"
    case .httpStatus(let part, let statusCode):
      return "Part \(part): HTTP \(statusCode)"
    case .missingETag(let part):
      return "No ETag header in the response for part \(part); completing a multipart upload needs one"
    case .partFailed(let part, let attempts, let underlying):
      return "Part \(part) failed after \(attempts) attempt(s): \(underlying.localizedDescription)"
    }
  }
}
