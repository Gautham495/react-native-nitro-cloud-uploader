import Foundation

/// The source file of an upload, resolved and measured once up front.
struct UploadFile {
  let url: URL
  let size: Int64
}
