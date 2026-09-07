import Foundation

/// Events an `UploadSession` reports while it runs. Called on the session's own queue.
protocol UploadSessionDelegate: AnyObject {
  func uploadSession(_ session: UploadSession, didUpdateProgress progress: Double, bytesUploaded: Int64)
  func uploadSession(_ session: UploadSession, didCompletePart index: Int)
  func uploadSession(_ session: UploadSession, didFailPart index: Int, error: Error)
}
