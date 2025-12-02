import Foundation
import NitroModules
import Network

/**
 * Production-ready cloud uploader with:
 * - Pause/Resume/Cancel
 * - Network drop handling with auto-resume
 * - Background uploads
 * - Event emission for UI updates
 * - Parallel chunk uploads
 * - One active upload at a time
 *
 * Note: Chunk size is calculated automatically based on file size and number of upload URLs
 * Formula: chunkSize = max(5MB, ceil(fileSize / numberOfURLs))
 * Minimum chunk size of 5 MB is enforced for S3 multipart upload compatibility
 */
class NitroCloudUploader: HybridNitroCloudUploaderSpec {
    
    // MARK: - Properties
    
    private let queue = DispatchQueue(label: "CloudUploader.main")
    private var currentUpload: UploadSession?
    private let networkMonitor = NWPathMonitor()
    private var isNetworkAvailable = true
    private var eventListeners: [String: [(UploadProgressEvent) -> Void]] = [:]
    
    // MARK: - Initialization
    
    override init() {
        super.init()
        setupNetworkMonitoring()
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
        
        return Promise.async {
            // Note: showNotification parameter is ignored on iOS (not implemented)
            
            // Validate inputs
            guard !uploadId.isEmpty else {
                throw NSError(domain: "CloudUploader", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Upload ID cannot be empty"])
            }
            
            guard !filePath.isEmpty else {
                throw NSError(domain: "CloudUploader", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "File path cannot be empty"])
            }
            
            guard !uploadUrls.isEmpty else {
                throw NSError(domain: "CloudUploader", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Upload URLs cannot be empty"])
            }
            
            // Only one upload at a time
            if let existing = self.currentUpload {
                throw NSError(
                    domain: "CloudUploader",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "Upload already in progress: \(existing.uploadId)"]
                )
            }
            
            let parallel = Int(maxParallel ?? 3)
            
            // Create upload session
            let session: UploadSession
            do {
                session = try UploadSession(
                    uploadId: uploadId,
                    filePath: filePath,
                    uploadUrls: uploadUrls,
                    maxParallel: parallel,
                    delegate: self
                )
            } catch {
                print("❌ Failed to create session:", error)
                throw error
            }
            
            self.currentUpload = session
            
            // Emit start event
            self.emitEvent(UploadProgressEvent(
                type: "upload-started",
                uploadId: uploadId,
                progress: 0,
                bytesUploaded: 0,
                totalBytes: Double(session.totalBytes),
                chunkIndex: nil,
                errorMessage: nil
            ))
            
            // Start the upload
            let result: UploadResult
            do {
                result = try await session.start()
            } catch {
                print("❌ Upload failed:", error)
                self.currentUpload = nil
                
                // Emit failure event
                self.emitEvent(UploadProgressEvent(
                    type: "upload-failed",
                    uploadId: uploadId,
                    progress: nil,
                    bytesUploaded: nil,
                    totalBytes: Double(session.totalBytes),
                    chunkIndex: nil,
                    errorMessage: error.localizedDescription
                ))
                
                throw error
            }
            
            // Cleanup
            self.currentUpload = nil
            
            // Emit completion event
            self.emitEvent(UploadProgressEvent(
                type: result.success ? "upload-completed" : "upload-failed",
                uploadId: uploadId,
                progress: result.success ? 1.0 : nil,
                bytesUploaded: result.success ? Double(session.totalBytes) : nil,
                totalBytes: Double(session.totalBytes),
                chunkIndex: nil,
                errorMessage: result.success ? nil : "Upload failed"
            ))
            
            return result
        }
    }
    
     func pauseUpload(uploadId: String) throws -> Promise<Void> {
        return Promise.async {
            guard let session = self.currentUpload, session.uploadId == uploadId else {
                throw NSError(
                    domain: "CloudUploader",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No active upload found"]
                )
            }
            
            session.pause()
            
            self.emitEvent(UploadProgressEvent(
                type: "upload-paused",
                uploadId: uploadId,
                progress: nil,
                bytesUploaded: nil,
                totalBytes: nil,
                chunkIndex: nil,
                errorMessage: nil
            ))
        }
    }
    
     func resumeUpload(uploadId: String) throws -> Promise<Void> {
        return Promise.async {
            guard let session = self.currentUpload, session.uploadId == uploadId else {
                throw NSError(
                    domain: "CloudUploader",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No active upload found"]
                )
            }
            
            session.resume()
            
            self.emitEvent(UploadProgressEvent(
                type: "upload-resumed",
                uploadId: uploadId,
                progress: nil,
                bytesUploaded: nil,
                totalBytes: nil,
                chunkIndex: nil,
                errorMessage: nil
            ))
        }
    }
    
     func cancelUpload(uploadId: String) throws -> Promise<Void> {
        return Promise.async {
            guard let session = self.currentUpload, session.uploadId == uploadId else {
                return
            }
            
            session.cancel()
            self.currentUpload = nil
            
            self.emitEvent(UploadProgressEvent(
                type: "upload-cancelled",
                uploadId: uploadId,
                progress: nil,
                bytesUploaded: nil,
                totalBytes: nil,
                chunkIndex: nil,
                errorMessage: nil
            ))
        }
    }
    
     func getUploadState(uploadId: String) throws -> Promise<UploadState> {
        return Promise.async {
            guard let session = self.currentUpload, session.uploadId == uploadId else {
                throw NSError(
                    domain: "CloudUploader",
                    code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "No active upload found"]
                )
            }
            
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
    
    // MARK: - Event System
    
     func addListener(eventType: String, callback: @escaping (UploadProgressEvent) -> Void) throws {
        queue.async {
            if self.eventListeners[eventType] == nil {
                self.eventListeners[eventType] = []
            }
            self.eventListeners[eventType]?.append(callback)
        }
    }
    
     func removeListener(eventType: String) throws {
        queue.async {
            self.eventListeners[eventType] = nil
        }
    }
    
    private func emitEvent(_ event: UploadProgressEvent) {
        // ⚠️ Emit on main thread to avoid Nitro bridge issues
        DispatchQueue.main.async {
            // Emit to specific event listeners
            self.eventListeners[event.type]?.forEach { callback in
                // ✅ Wrap in do-catch to prevent crashes
                do {
                    callback(event)
                } catch {
                    print("⚠️ Event callback error:", error)
                }
            }
            
            // Emit to 'all' listeners
            self.eventListeners["all"]?.forEach { callback in
                do {
                    callback(event)
                } catch {
                    print("⚠️ Event callback error:", error)
                }
            }
        }
    }
    
    // MARK: - Network Monitoring
    
    private func setupNetworkMonitoring() {
        networkMonitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            
            let wasAvailable = self.isNetworkAvailable
            let isAvailable = (path.status == .satisfied)
            self.isNetworkAvailable = isAvailable
            
            if wasAvailable && !isAvailable {
                // Network lost
                self.currentUpload?.pause()
                
                if let uploadId = self.currentUpload?.uploadId {
                    self.emitEvent(UploadProgressEvent(
                        type: "network-lost",
                        uploadId: uploadId,
                        progress: nil,
                        bytesUploaded: nil,
                        totalBytes: nil,
                        chunkIndex: nil,
                        errorMessage: nil
                    ))
                }
                
            } else if !wasAvailable && isAvailable {
                // Network restored
                self.currentUpload?.resume()
                
                if let uploadId = self.currentUpload?.uploadId {
                    self.emitEvent(UploadProgressEvent(
                        type: "network-restored",
                        uploadId: uploadId,
                        progress: nil,
                        bytesUploaded: nil,
                        totalBytes: nil,
                        chunkIndex: nil,
                        errorMessage: nil
                    ))
                }
            }
        }
        
        networkMonitor.start(queue: DispatchQueue.global(qos: .utility))
    }
}

// MARK: - Upload Session Delegate

extension NitroCloudUploader: UploadSessionDelegate {
    func uploadSession(_ session: UploadSession, didUpdateProgress progress: Double, bytesUploaded: Int64) {
        emitEvent(UploadProgressEvent(
            type: "upload-progress",
            uploadId: session.uploadId,
            progress: progress,
            bytesUploaded: Double(bytesUploaded),
            totalBytes: Double(session.totalBytes),
            chunkIndex: nil,
            errorMessage: nil
        ))
    }
    
    func uploadSession(_ session: UploadSession, didCompleteChunk index: Int) {
        emitEvent(UploadProgressEvent(
            type: "chunk-completed",
            uploadId: session.uploadId,
            progress: nil,
            bytesUploaded: nil,
            totalBytes: nil,
            chunkIndex: Double(index),
            errorMessage: nil
        ))
    }
    
    func uploadSession(_ session: UploadSession, didFailChunk index: Int, error: Error) {
        emitEvent(UploadProgressEvent(
            type: "chunk-failed",
            uploadId: session.uploadId,
            progress: nil,
            bytesUploaded: nil,
            totalBytes: nil,
            chunkIndex: Double(index),
            errorMessage: error.localizedDescription
        ))
    }
}

// MARK: - Upload Session Delegate Protocol

protocol UploadSessionDelegate: AnyObject {
    func uploadSession(_ session: UploadSession, didUpdateProgress progress: Double, bytesUploaded: Int64)
    func uploadSession(_ session: UploadSession, didCompleteChunk index: Int)
    func uploadSession(_ session: UploadSession, didFailChunk index: Int, error: Error)
}

// MARK: - Upload Session

class UploadSession: NSObject {
    
    enum State: String {
        case idle, uploading, paused, completed, failed, cancelled
    }
    
    // S3 multipart upload minimum chunk size requirement (5 MB)
    private static let minimumChunkSize: Int64 = 5 * 1024 * 1024 // 5 MB
    
    let uploadId: String
    let uploadUrls: [String]
    let totalBytes: Int64
    let maxParallel: Int
    
    private let filePath: String
    private let fileURL: URL
    private var urlSession: URLSession!
    private var activeTasks: [Int: URLSessionTask] = [:]
    private var completedChunks: Set<Int> = []
    private var etags: [String] = []
    private let queue = DispatchQueue(label: "UploadSession")
    
    private(set) var state: State = .idle
    private(set) var bytesUploaded: Int64 = 0
    private(set) var isPaused = false
    
    weak var delegate: UploadSessionDelegate?
    
    private var continuation: CheckedContinuation<UploadResult, Error>?
    private var hasResumedContinuation = false
    private let continuationLock = NSLock()
    private let chunkSize: Int64
    
    var progress: Double {
        guard totalBytes > 0 else { return 0 }
        return Double(bytesUploaded) / Double(totalBytes)
    }
    
    init(
        uploadId: String,
        filePath: String,
        uploadUrls: [String],
        maxParallel: Int,
        delegate: UploadSessionDelegate
    ) throws {
        self.uploadId = uploadId
        self.uploadUrls = uploadUrls
        self.maxParallel = maxParallel
        self.delegate = delegate
        
        // Decode file path
        guard let decodedPath = filePath.removingPercentEncoding else {
            throw NSError(domain: "CloudUploader", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Invalid file path"])
        }
        
        self.filePath = decodedPath
        self.fileURL = URL(fileURLWithPath: decodedPath)
        
        // Verify file exists
        guard FileManager.default.fileExists(atPath: decodedPath) else {
            throw NSError(domain: "CloudUploader", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "File not found: \(decodedPath)"])
        }
        
        // Get file size
        let attrs = try FileManager.default.attributesOfItem(atPath: decodedPath)
        guard let fileSize = attrs[.size] as? Int64 else {
            throw NSError(domain: "CloudUploader", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Could not get file size"])
        }
        
        self.totalBytes = fileSize
        
        // Calculate chunk size with minimum 5 MB enforcement for S3 compatibility
        let calculatedChunkSize = Int64(ceil(Double(fileSize) / Double(uploadUrls.count)))
        self.chunkSize = max(calculatedChunkSize, UploadSession.minimumChunkSize)
        
        // Validate that file is large enough for the number of chunks requested
        // (except for the last chunk which can be smaller)
        let requiredMinimumSize = Int64(uploadUrls.count - 1) * UploadSession.minimumChunkSize
        if fileSize < requiredMinimumSize {
            throw NSError(
                domain: "CloudUploader",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "File size (\(fileSize) bytes) is too small for \(uploadUrls.count) chunks. Minimum required: \(requiredMinimumSize) bytes (5 MB per chunk except last)"]
            )
        }
        
        self.etags = Array(repeating: "", count: uploadUrls.count)
        
        super.init()
        
        let config = URLSessionConfiguration.background(withIdentifier: "CloudUploader.\(uploadId)")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        
        self.urlSession = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }
    
    func start() async throws -> UploadResult {
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            self.state = .uploading
            self.uploadNextChunks()
        }
    }
    
    func pause() {
        queue.async {
            guard self.state == .uploading else { return }
            self.state = .paused
            self.isPaused = true
            
            for task in self.activeTasks.values {
                task.suspend()
            }
        }
    }
    
    func resume() {
        queue.async {
            guard self.state == .paused else { return }
            self.state = .uploading
            self.isPaused = false
            
            for task in self.activeTasks.values {
                task.resume()
            }
            
            self.uploadNextChunks()
        }
    }
    
    func cancel() {
        queue.async {
            self.continuationLock.lock()
            defer { self.continuationLock.unlock() }
            
            guard !self.hasResumedContinuation else {
                print("⚠️ Upload already completed/failed, skipping cancel")
                return
            }
            
            self.hasResumedContinuation = true
            self.state = .cancelled
            
            for task in self.activeTasks.values {
                task.cancel()
            }
            
            let tempDir = FileManager.default.temporaryDirectory
            for i in 0..<self.uploadUrls.count {
                let tempFileURL = tempDir.appendingPathComponent("chunk_\(self.uploadId)_\(i).tmp")
                try? FileManager.default.removeItem(at: tempFileURL)
            }
            
            self.activeTasks.removeAll()
            self.urlSession.invalidateAndCancel()
            
            self.continuation?.resume(returning: UploadResult(
                uploadId: self.uploadId,
                success: false,
                etags: []
            ))
            self.continuation = nil
        }
    }
    
    private func uploadNextChunks() {
        queue.async {
            guard self.state == .uploading else { return }
            
            while self.activeTasks.count < self.maxParallel {
                guard let nextIndex = self.getNextChunkIndex() else {
                    if self.activeTasks.isEmpty {
                        self.complete()
                    }
                    return
                }
                
                self.uploadChunk(at: nextIndex)
            }
        }
    }
    
    private func getNextChunkIndex() -> Int? {
        for i in 0..<uploadUrls.count {
            if !completedChunks.contains(i) && !activeTasks.keys.contains(i) {
                return i
            }
        }
        return nil
    }
    
    private func uploadChunk(at index: Int) {
        let offset = Int64(index) * chunkSize
        let size = min(chunkSize, totalBytes - offset)
        
        do {
            let tempDir = FileManager.default.temporaryDirectory
            let tempFileURL = tempDir.appendingPathComponent("chunk_\(uploadId)_\(index).tmp")
            
            let data = try readChunkData(offset: offset, size: size)
            try data.write(to: tempFileURL)
            
            guard let url = URL(string: uploadUrls[index]) else {
                throw NSError(domain: "CloudUploader", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
            }
            
            var request = URLRequest(url: url)
            request.httpMethod = "PUT"
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            
            let task = urlSession.uploadTask(with: request, fromFile: tempFileURL)
            activeTasks[index] = task
            task.resume()
        } catch {
            delegate?.uploadSession(self, didFailChunk: index, error: error)
            fail(with: error)
        }
    }
    
    private func readChunkData(offset: Int64, size: Int64) throws -> Data {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        
        try handle.seek(toOffset: UInt64(offset))
        return handle.readData(ofLength: Int(size))
    }
    
    private func complete() {
        continuationLock.lock()
        defer { continuationLock.unlock() }
        
        guard !hasResumedContinuation else {
            print("⚠️ Continuation already resumed, skipping")
            return
        }
        
        hasResumedContinuation = true
        state = .completed
        
        continuation?.resume(returning: UploadResult(
            uploadId: uploadId,
            success: true,
            etags: etags
        ))
        continuation = nil
        
        urlSession.finishTasksAndInvalidate()
    }
    
    private func fail(with error: Error) {
        continuationLock.lock()
        defer { continuationLock.unlock() }
        
        guard !hasResumedContinuation else {
            print("⚠️ Continuation already resumed, skipping error: \(error)")
            return
        }
        
        hasResumedContinuation = true
        state = .failed
        
        continuation?.resume(throwing: error)
        continuation = nil
        
        urlSession.invalidateAndCancel()
    }
}

// MARK: - URLSession Delegate

extension UploadSession: URLSessionTaskDelegate, URLSessionDataDelegate {
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async {
            guard let index = self.activeTasks.first(where: { $0.value == task })?.key else {
                return
            }
            
            self.activeTasks.removeValue(forKey: index)
            
            let tempFileURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("chunk_\(self.uploadId)_\(index).tmp")
            try? FileManager.default.removeItem(at: tempFileURL)
            
            guard self.state == .uploading || self.state == .paused else {
                return
            }
            
            if let error = error {
                print("⚠️ Chunk \(index) failed: \(error.localizedDescription)")
                self.delegate?.uploadSession(self, didFailChunk: index, error: error)
                
                if self.activeTasks.isEmpty && self.completedChunks.count < self.uploadUrls.count {
                    self.fail(with: error)
                }
                return
            }
            
            guard let response = task.response as? HTTPURLResponse,
                  (200...299).contains(response.statusCode) else {
                let error = NSError(domain: "CloudUploader", code: -1,
                                  userInfo: [NSLocalizedDescriptionKey: "Chunk \(index) failed"])
                print("⚠️ Chunk \(index) bad response")
                self.delegate?.uploadSession(self, didFailChunk: index, error: error)
                
                if self.activeTasks.isEmpty && self.completedChunks.count < self.uploadUrls.count {
                    self.fail(with: error)
                }
                return
            }
            
            let etag = response.allHeaderFields["Etag"] as? String
                ?? response.allHeaderFields["ETag"] as? String
                ?? ""
            
            self.etags[index] = etag
            self.completedChunks.insert(index)
            
            let chunkSize = min(self.chunkSize, self.totalBytes - Int64(index) * self.chunkSize)
            self.bytesUploaded += chunkSize
            
            self.delegate?.uploadSession(self, didUpdateProgress: self.progress, bytesUploaded: self.bytesUploaded)
            self.delegate?.uploadSession(self, didCompleteChunk: index)
            
            if self.completedChunks.count == self.uploadUrls.count {
                self.complete()
            } else {
                self.uploadNextChunks()
            }
        }
    }
}