import Foundation

/// One upload: a background `URLSession` driving one PUT per part.
///
/// All mutable state is owned by `queue`; the URLSession delegate hops onto it, and the
/// public control methods hop onto it. Progress = bytes of acknowledged parts + bytes the
/// system has sent so far for in-flight parts (`didSendBodyData`), throttled to ~20 Hz.
///
/// Single-URL uploads PUT the source file directly. Multipart uploads copy each part into a
/// temporary file, because background sessions can only upload from files.
final class UploadSession: NSObject {

  enum State: String {
    case idle, uploading, paused, completed, failed, cancelled
  }

  static let maxAttemptsPerPart = 3
  static let progressEmitInterval: TimeInterval = 0.05

  let uploadId: String
  let totalBytes: Int64
  let parts: [UploadPart]
  let maxParallel: Int
  let contentType: String
  let queue = DispatchQueue(label: "NitroCloudUploader.UploadSession")

  weak var delegate: UploadSessionDelegate?

  private(set) var state: State = .idle
  private(set) var bytesUploaded: Int64 = 0

  var isPaused: Bool { state == .paused }
  var progress: Double { totalBytes > 0 ? Double(bytesUploaded) / Double(totalBytes) : 0 }

  private let sourceURL: URL
  private let partsDirectory: URL
  private var urlSession: URLSession!
  private var activeTasks: [Int: URLSessionTask] = [:]       // part index -> task
  private var partIndexByTaskId: [Int: Int] = [:]            // taskIdentifier -> part index
  private var completedParts: Set<Int> = []
  private var attempts: [Int: Int] = [:]                     // part index -> attempts so far
  private var etags: [String]
  private var completedBytes: Int64 = 0
  private var inFlightBytes: [Int: Int64] = [:]              // part index -> bytes sent this attempt
  private var lastProgressEmitAt: TimeInterval = 0
  private var continuation: CheckedContinuation<UploadResult, Error>?

  init(
    uploadId: String,
    file: UploadFile,
    parts: [UploadPart],
    maxParallel: Int,
    contentType: String,
    delegate: UploadSessionDelegate
  ) {
    self.uploadId = uploadId
    self.sourceURL = file.url
    self.totalBytes = file.size
    self.parts = parts
    self.maxParallel = maxParallel
    self.contentType = contentType
    self.delegate = delegate
    self.etags = Array(repeating: "", count: parts.count)
    self.partsDirectory = FileManager.default.temporaryDirectory
      .appendingPathComponent("NitroCloudUploader", isDirectory: true)
      .appendingPathComponent(uploadId, isDirectory: true)
    super.init()

    let config = URLSessionConfiguration.background(withIdentifier: "NitroCloudUploader.\(uploadId)")
    config.isDiscretionary = false
    config.sessionSendsLaunchEvents = true
    self.urlSession = URLSession(configuration: config, delegate: self, delegateQueue: nil)
  }

  // MARK: - Control

  func start() async throws -> UploadResult {
    return try await withCheckedThrowingContinuation { continuation in
      queue.async {
        self.continuation = continuation
        self.state = .uploading
        self.startPendingParts()
      }
    }
  }

  func pause() {
    queue.async {
      guard self.state == .uploading else { return }
      self.state = .paused
      // A suspended task produces no traffic and is not subject to timeouts.
      self.activeTasks.values.forEach { $0.suspend() }
    }
  }

  func resume() {
    queue.async {
      guard self.state == .paused else { return }
      self.state = .uploading
      self.activeTasks.values.forEach { $0.resume() }
      self.startPendingParts()
    }
  }

  func cancel() {
    queue.async {
      guard self.state == .uploading || self.state == .paused else { return }
      self.finish(.cancelled, result: .success(UploadResult(uploadId: self.uploadId, success: false, etags: [])))
    }
  }

  // MARK: - Delegate entry points (called on `queue` by the URLSession delegate extension)

  func handleBytesSent(task: URLSessionTask, totalBytesSent: Int64) {
    guard state == .uploading, let index = partIndexByTaskId[task.taskIdentifier] else { return }
    inFlightBytes[index] = totalBytesSent
    recomputeBytesUploaded()
    reportProgress(force: false)
  }

  func handleCompletion(task: URLSessionTask, error: Error?) {
    guard let index = partIndexByTaskId.removeValue(forKey: task.taskIdentifier) else { return }
    activeTasks.removeValue(forKey: index)
    inFlightBytes[index] = nil
    removeBodyFile(for: parts[index])

    guard state == .uploading || state == .paused else { return }

    if let error = error {
      handlePartFailure(index: index, error: error, retryable: true)
      return
    }
    guard let response = task.response as? HTTPURLResponse else {
      handlePartFailure(index: index, error: UploadError.badResponse(part: parts[index].partNumber), retryable: true)
      return
    }
    guard (200...299).contains(response.statusCode) else {
      let status = response.statusCode
      // 403 SignatureDoesNotMatch & co. are reported on the first attempt; only transient
      // statuses are retried.
      let retryable = status == 408 || status == 429 || status >= 500
      handlePartFailure(index: index, error: UploadError.httpStatus(part: parts[index].partNumber, statusCode: status), retryable: retryable)
      return
    }

    let etag = (response.value(forHTTPHeaderField: "ETag") ?? "")
      .trimmingCharacters(in: CharacterSet(charactersIn: "\" "))
    if etag.isEmpty && parts.count > 1 {
      let error = UploadError.missingETag(part: parts[index].partNumber)
      delegate?.uploadSession(self, didFailPart: index, error: error)
      finish(.failed, result: .failure(error))
      return
    }

    etags[index] = etag
    completedParts.insert(index)
    completedBytes += parts[index].size
    recomputeBytesUploaded()
    reportProgress(force: true)
    delegate?.uploadSession(self, didCompletePart: index)
    startPendingParts()
  }

  // MARK: - Scheduling

  private func startPendingParts() {
    guard state == .uploading else { return }
    while activeTasks.count < maxParallel, let index = nextPendingPartIndex() {
      startPart(at: index)
      guard state == .uploading else { return }
    }
    if activeTasks.isEmpty && completedParts.count == parts.count {
      finish(.completed, result: .success(UploadResult(uploadId: uploadId, success: true, etags: etags)))
    }
  }

  private func nextPendingPartIndex() -> Int? {
    return parts.indices.first { !completedParts.contains($0) && activeTasks[$0] == nil }
  }

  private func startPart(at index: Int) {
    let part = parts[index]
    do {
      let bodyURL = try bodyFileURL(for: part)
      var request = URLRequest(url: part.url)
      request.httpMethod = "PUT"
      request.setValue(contentType, forHTTPHeaderField: "Content-Type")

      let task = urlSession.uploadTask(with: request, fromFile: bodyURL)
      activeTasks[index] = task
      partIndexByTaskId[task.taskIdentifier] = index
      inFlightBytes[index] = 0
      task.resume()
    } catch {
      // The source file cannot be read: nothing to retry.
      delegate?.uploadSession(self, didFailPart: index, error: error)
      finish(.failed, result: .failure(error))
    }
  }

  private func handlePartFailure(index: Int, error: Error, retryable: Bool) {
    let attempt = (attempts[index] ?? 0) + 1
    attempts[index] = attempt
    recomputeBytesUploaded()

    if retryable && attempt < UploadSession.maxAttemptsPerPart {
      let delay = pow(2.0, Double(attempt - 1)) // 1s, 2s
      print("⚠️ Part \(parts[index].partNumber) attempt \(attempt) failed (\(error.localizedDescription)); retrying in \(delay)s")
      queue.asyncAfter(deadline: .now() + delay) { [weak self] in
        self?.startPendingParts()
      }
      return
    }

    delegate?.uploadSession(self, didFailPart: index, error: error)
    finish(.failed, result: .failure(UploadError.partFailed(part: parts[index].partNumber, attempts: attempt, underlying: error)))
  }

  /// Single exit point: settles the continuation exactly once, invalidates the session and
  /// removes temporary part files. Runs on `queue`.
  private func finish(_ terminalState: State, result: Result<UploadResult, Error>) {
    guard let continuation = continuation else { return }
    self.continuation = nil
    state = terminalState
    activeTasks.values.forEach { $0.cancel() }
    activeTasks.removeAll()
    partIndexByTaskId.removeAll()
    inFlightBytes.removeAll()
    urlSession.invalidateAndCancel()
    if parts.count > 1 {
      try? FileManager.default.removeItem(at: partsDirectory)
    }
    continuation.resume(with: result)
  }

  // MARK: - Progress

  private func recomputeBytesUploaded() {
    let inFlight = inFlightBytes.values.reduce(0, +)
    bytesUploaded = min(totalBytes, completedBytes + inFlight)
  }

  private func reportProgress(force: Bool) {
    let now = ProcessInfo.processInfo.systemUptime
    if !force && now - lastProgressEmitAt < UploadSession.progressEmitInterval { return }
    lastProgressEmitAt = now
    delegate?.uploadSession(self, didUpdateProgress: progress, bytesUploaded: bytesUploaded)
  }

  // MARK: - Part bodies

  /// Single-URL uploads PUT the source file itself; multipart parts are copied out first.
  private func bodyFileURL(for part: UploadPart) throws -> URL {
    if parts.count == 1 {
      return sourceURL
    }
    let fileManager = FileManager.default
    try fileManager.createDirectory(at: partsDirectory, withIntermediateDirectories: true)
    let partURL = partsDirectory.appendingPathComponent("part-\(part.partNumber)", isDirectory: false)
    try copyRange(of: part, to: partURL)
    return partURL
  }

  private func removeBodyFile(for part: UploadPart) {
    guard parts.count > 1 else { return }
    let partURL = partsDirectory.appendingPathComponent("part-\(part.partNumber)", isDirectory: false)
    try? FileManager.default.removeItem(at: partURL)
  }

  /// Copies `part`'s byte range into `destination` using a fixed-size buffer.
  private func copyRange(of part: UploadPart, to destination: URL) throws {
    let fileManager = FileManager.default
    if fileManager.fileExists(atPath: destination.path) {
      try fileManager.removeItem(at: destination)
    }
    guard fileManager.createFile(atPath: destination.path, contents: nil) else {
      throw UploadError.tempFileCreationFailed(destination.path)
    }

    let reader = try FileHandle(forReadingFrom: sourceURL)
    defer { try? reader.close() }
    let writer = try FileHandle(forWritingTo: destination)
    defer { try? writer.close() }

    try reader.seek(toOffset: UInt64(part.offset))
    var remaining = part.size
    while remaining > 0 {
      let chunk = reader.readData(ofLength: Int(min(Int64(256 * 1024), remaining)))
      if chunk.isEmpty {
        try? fileManager.removeItem(at: destination)
        throw UploadError.unexpectedEndOfFile(offset: part.offset, expected: part.size, missing: remaining)
      }
      try writer.write(contentsOf: chunk)
      remaining -= Int64(chunk.count)
    }
  }
}
