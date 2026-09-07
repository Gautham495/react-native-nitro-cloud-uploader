import Foundation

extension UploadSession: URLSessionTaskDelegate {

  /// Fired by the system as the request body streams out. This is the byte-level progress
  /// signal; previously progress only moved when a whole part finished.
  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didSendBodyData bytesSent: Int64,
    totalBytesSent: Int64,
    totalBytesExpectedToSend: Int64
  ) {
    queue.async {
      self.handleBytesSent(task: task, totalBytesSent: totalBytesSent)
    }
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    queue.async {
      self.handleCompletion(task: task, error: error)
    }
  }
}
