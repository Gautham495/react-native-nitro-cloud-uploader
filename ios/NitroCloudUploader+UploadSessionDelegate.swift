import Foundation

extension NitroCloudUploader: UploadSessionDelegate {

  func uploadSession(_ session: UploadSession, didUpdateProgress progress: Double, bytesUploaded: Int64) {
    emit(UploadProgressEvent(
      type: "upload-progress",
      uploadId: session.uploadId,
      progress: progress,
      bytesUploaded: Double(bytesUploaded),
      totalBytes: Double(session.totalBytes),
      chunkIndex: nil,
      errorMessage: nil
    ))
  }

  func uploadSession(_ session: UploadSession, didCompletePart index: Int) {
    emit(UploadProgressEvent(
      type: "chunk-completed",
      uploadId: session.uploadId,
      progress: session.progress,
      bytesUploaded: Double(session.bytesUploaded),
      totalBytes: Double(session.totalBytes),
      chunkIndex: Double(index),
      errorMessage: nil
    ))
  }

  func uploadSession(_ session: UploadSession, didFailPart index: Int, error: Error) {
    emit(UploadProgressEvent(
      type: "chunk-failed",
      uploadId: session.uploadId,
      progress: nil,
      bytesUploaded: nil,
      totalBytes: Double(session.totalBytes),
      chunkIndex: Double(index),
      errorMessage: error.localizedDescription
    ))
  }
}
