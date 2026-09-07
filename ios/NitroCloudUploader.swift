import Foundation
import NitroModules
import Network

/// Resumable, background-friendly uploads to presigned (or unsigned) URLs.
///
/// One part per URL: a single URL is a plain PUT of the whole file, several URLs are an
/// S3-style multipart upload. Progress is reported as the body streams out (throttled to
/// ~20 Hz), not per finished part. One active upload at a time.
final class NitroCloudUploader: HybridNitroCloudUploaderSpec {

  private static let defaultContentType = "application/octet-stream"
  private static let defaultParallel = 3

  private let networkMonitor = NWPathMonitor()
  private let listenerLock = NSLock()
  private var listeners: [String: [(UploadProgressEvent) -> Void]] = [:]
  private var currentUpload: UploadSession?
  private var isNetworkAvailable = true
  private var pausedForNetwork = false

  override init() {
    super.init()
    startNetworkMonitoring()
  }

  deinit {
    networkMonitor.cancel()
    currentUpload?.cancel()
  }

  // MARK: - Public API

  func startUpload(
    uploadId: String,
    filePath: String,
    uploadUrls: [String],
    maxParallel: Double?,
    showNotification: Bool?
  ) throws -> Promise<UploadResult> {
    // showNotification is Android-only (foreground service). iOS uses URLSession.background.
    return Promise.async {
      guard !uploadId.isEmpty else { throw UploadError.emptyUploadId }
      if let existing = self.currentUpload {
        throw UploadError.uploadAlreadyInProgress(existing.uploadId)
      }

      let file = try UploadFileResolver.resolve(filePath)
      let parts = try UploadPartPlan.make(fileSize: file.size, uploadUrls: uploadUrls)
      let requestedParallel = maxParallel.flatMap { $0.isFinite ? Int($0) : nil } ?? Self.defaultParallel
      let parallel = max(1, min(10, requestedParallel))
      let resolvedContentType = Self.defaultContentType

      let session = UploadSession(
        uploadId: uploadId,
        file: file,
        parts: parts,
        maxParallel: parallel,
        contentType: resolvedContentType,
        delegate: self
      )
      self.currentUpload = session
      self.pausedForNetwork = false
      defer {
        if self.currentUpload === session {
          self.currentUpload = nil
        }
      }

      print("🚀 Starting upload \(uploadId): \(file.size) bytes, \(parts.count) part(s), parallel=\(parallel), contentType=\(resolvedContentType)")
      self.emit(UploadProgressEvent(
        type: "upload-started",
        uploadId: uploadId,
        progress: 0,
        bytesUploaded: 0,
        totalBytes: Double(file.size),
        chunkIndex: nil,
        errorMessage: nil
      ))

      do {
        let result = try await session.start()
        if result.success {
          print("✅ Upload \(uploadId) complete (\(parts.count) part(s))")
          self.emit(UploadProgressEvent(
            type: "upload-completed",
            uploadId: uploadId,
            progress: 1,
            bytesUploaded: Double(file.size),
            totalBytes: Double(file.size),
            chunkIndex: nil,
            errorMessage: nil
          ))
        }
        // success == false only after cancelUpload(), which already emitted "upload-cancelled".
        return result
      } catch {
        print("❌ Upload \(uploadId) failed: \(error.localizedDescription)")
        self.emit(UploadProgressEvent(
          type: "upload-failed",
          uploadId: uploadId,
          progress: nil,
          bytesUploaded: nil,
          totalBytes: Double(file.size),
          chunkIndex: nil,
          errorMessage: error.localizedDescription
        ))
        throw error
      }
    }
  }

  func pauseUpload(uploadId: String) throws -> Promise<Void> {
    return Promise.async {
      let session = try self.activeSession(for: uploadId)
      session.pause()
      // A manual pause must not be undone by a later network-restored event.
      self.pausedForNetwork = false
      self.emit(self.event("upload-paused", uploadId: uploadId))
    }
  }

  func resumeUpload(uploadId: String) throws -> Promise<Void> {
    return Promise.async {
      let session = try self.activeSession(for: uploadId)
      session.resume()
      self.pausedForNetwork = false
      self.emit(self.event("upload-resumed", uploadId: uploadId))
    }
  }

  func cancelUpload(uploadId: String) throws -> Promise<Void> {
    return Promise.async {
      guard let session = self.currentUpload, session.uploadId == uploadId else { return }
      print("🛑 Cancelling upload \(uploadId)")
      session.cancel()
      self.currentUpload = nil
      self.emit(self.event("upload-cancelled", uploadId: uploadId))
    }
  }

  func getUploadState(uploadId: String) throws -> Promise<UploadState> {
    return Promise.async {
      let session = try self.activeSession(for: uploadId)
      return UploadState(
        uploadId: uploadId,
        state: session.state.rawValue,
        progress: session.progress,
        bytesUploaded: Double(session.bytesUploaded),
        totalBytes: Double(session.totalBytes),
        isPaused: session.isPaused,
        isNetworkAvailable: self.isNetworkAvailable
      )
    }
  }

  func addListener(eventType: String, callback: @escaping (UploadProgressEvent) -> Void) throws {
    listenerLock.lock()
    defer { listenerLock.unlock() }
    listeners[eventType, default: []].append(callback)
  }

  func removeListener(eventType: String) throws {
    listenerLock.lock()
    defer { listenerLock.unlock() }
    listeners[eventType] = nil
  }

  // MARK: - Events

  /// Snapshots the listeners under the lock, then calls them on the main queue.
  func emit(_ event: UploadProgressEvent) {
    listenerLock.lock()
    let targets = (listeners[event.type] ?? []) + (listeners["all"] ?? [])
    listenerLock.unlock()
    guard !targets.isEmpty else { return }
    DispatchQueue.main.async {
      targets.forEach { $0(event) }
    }
  }

  func event(_ type: String, uploadId: String) -> UploadProgressEvent {
    return UploadProgressEvent(
      type: type,
      uploadId: uploadId,
      progress: nil,
      bytesUploaded: nil,
      totalBytes: nil,
      chunkIndex: nil,
      errorMessage: nil
    )
  }

  // MARK: - Helpers

  private func activeSession(for uploadId: String) throws -> UploadSession {
    guard let session = currentUpload, session.uploadId == uploadId else {
      throw UploadError.noActiveUpload(uploadId)
    }
    return session
  }

  // MARK: - Network monitoring

  private func startNetworkMonitoring() {
    networkMonitor.pathUpdateHandler = { [weak self] path in
      guard let self = self else { return }
      let wasAvailable = self.isNetworkAvailable
      let isAvailable = path.status == .satisfied
      self.isNetworkAvailable = isAvailable

      if wasAvailable && !isAvailable {
        print("📡 Network lost")
        if let session = self.currentUpload, !session.isPaused {
          session.pause()
          self.pausedForNetwork = true
        }
        if let uploadId = self.currentUpload?.uploadId {
          self.emit(self.event("network-lost", uploadId: uploadId))
        }
      } else if !wasAvailable && isAvailable {
        print("📡 Network restored")
        // Only undo a pause we caused ourselves; a user pause stays paused.
        if self.pausedForNetwork {
          self.currentUpload?.resume()
          self.pausedForNetwork = false
        }
        if let uploadId = self.currentUpload?.uploadId {
          self.emit(self.event("network-restored", uploadId: uploadId))
        }
      }
    }
    networkMonitor.start(queue: DispatchQueue.global(qos: .utility))
  }
}
