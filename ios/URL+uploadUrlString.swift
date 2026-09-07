import Foundation

extension URL {
  /// Parses an upload URL.
  ///
  /// Presigned URLs are already percent-encoded and parse as-is. An unsigned URL that
  /// contains raw characters such as spaces is rejected by `URL(string:)` before iOS 17
  /// (Android's OkHttp accepts it), so it is percent-encoded once before giving up.
  /// Existing `%XX` escapes and `#` are left untouched.
  init(uploadUrlString string: String) throws {
    if let url = URL(string: string) {
      self = url
      return
    }
    var allowed = CharacterSet.urlQueryAllowed
    allowed.insert(charactersIn: "%#")
    if let encoded = string.addingPercentEncoding(withAllowedCharacters: allowed),
       let url = URL(string: encoded) {
      self = url
      return
    }
    throw UploadError.invalidUrl(string)
  }
}
