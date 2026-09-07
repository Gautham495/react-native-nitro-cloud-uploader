import Foundation

/// Resolves the JS-provided path to a readable file.
///
/// Accepts a plain filesystem path or a `file://` URL. A plain path is used verbatim; a
/// percent-decoded variant is only tried when the verbatim path does not exist. (The old
/// unconditional `removingPercentEncoding` returned nil — "Invalid file path" — for any
/// file whose name contains a literal `%`.)
enum UploadFileResolver {
  static func resolve(_ filePath: String) throws -> UploadFile {
    guard !filePath.isEmpty else { throw UploadError.emptyFilePath }

    let fileManager = FileManager.default
    for path in candidatePaths(for: filePath) {
      var isDirectory: ObjCBool = false
      guard fileManager.fileExists(atPath: path, isDirectory: &isDirectory), !isDirectory.boolValue else {
        continue
      }
      let attributes = try fileManager.attributesOfItem(atPath: path)
      guard let size = (attributes[.size] as? NSNumber)?.int64Value else {
        throw UploadError.fileSizeUnavailable(path)
      }
      return UploadFile(url: URL(fileURLWithPath: path), size: size)
    }
    throw UploadError.fileNotFound(filePath)
  }

  private static func candidatePaths(for filePath: String) -> [String] {
    if filePath.hasPrefix("file://") {
      var paths: [String] = []
      if let url = URL(string: filePath) {
        paths.append(url.path)
      }
      paths.append(String(filePath.dropFirst("file://".count)))
      return paths
    }
    var paths = [filePath]
    if filePath.contains("%"), let decoded = filePath.removingPercentEncoding, decoded != filePath {
      paths.append(decoded)
    }
    return paths
  }
}
