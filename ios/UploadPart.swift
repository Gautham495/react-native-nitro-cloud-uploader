import Foundation

/// One byte range of the source file, uploaded with a single PUT to `url`.
struct UploadPart {
  /// 1-based, matches the S3 `PartNumber`.
  let partNumber: Int
  let url: URL
  let offset: Int64
  let size: Int64

  /// 0-based index reported to JS as `chunkIndex`.
  var index: Int { partNumber - 1 }
}
