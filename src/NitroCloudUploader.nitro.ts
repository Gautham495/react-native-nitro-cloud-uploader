import type { HybridObject } from 'react-native-nitro-modules';

/**
 * Upload progress event for UI updates
 */
export interface UploadProgressEvent {
 type: string;
  uploadId: string;
  progress?: number;           
  bytesUploaded?: number;
  totalBytes?: number;
  chunkIndex?: number;
  errorMessage?: string;
}

/**
 * Upload result after completion
 */
export interface UploadResult {
  uploadId: string;
  success: boolean;
  etags: string[];
}

/**
 * Upload state for querying
 */
export interface UploadState {
  uploadId: string;
  state: string;
  progress: number;
  bytesUploaded: number;
  totalBytes: number;
  isPaused: boolean;
  isNetworkAvailable: boolean;
}

/**
 * Cloud uploader with full features:
 * - Chunked uploads with pause/resume/cancel
 * - Network drop handling with auto-resume
 * - Background uploads with optional notifications
 * - Event system for UI feedback
 * - Parallel chunk uploads
 * 
 * Note: Chunk size is calculated automatically: ceil(fileSize / numberOfURLs)
 */
export interface NitroCloudUploader
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  
  /**
   * Start uploading a file in chunks
   * - Supports pause/resume/cancel
   * - Automatically handles network drops
   * - Works in background with optional notifications
   * - Uploads multiple chunks in parallel (default: 3)
   * 
   * @param uploadId - Unique identifier
   * @param filePath - Local file path (decoded, no file://)
   * @param uploadUrls - Presigned URLs for each chunk
   * @param maxParallel - Number of chunks to upload simultaneously (default: 3)
   * @param showNotification - Show progress notification (default: true)
   * @returns Promise that resolves when upload completes
   * 
   * Note: Chunk size is calculated automatically: ceil(fileSize / uploadUrls.length)
   */
  startUpload(
    uploadId: string,
    filePath: string,
    uploadUrls: string[],
    maxParallel?: number,
    showNotification?: boolean
  ): Promise<UploadResult>;

  /**
   * Pause an ongoing upload
   * - Suspends all active chunk uploads
   * - Can be resumed later
   */
  pauseUpload(uploadId: string): Promise<void>;

  /**
   * Resume a paused upload
   * - Resumes from where it left off
   * - Automatically called when network is restored
   */
  resumeUpload(uploadId: string): Promise<void>;

  /**
   * Cancel an upload
   * - Stops all uploads immediately
   * - Cannot be resumed
   */
  cancelUpload(uploadId: string): Promise<void>;

  /**
   * Get current state of an upload
   */
  getUploadState(uploadId: string): Promise<UploadState>;

  /**
   * Add event listener for upload events
   * @param eventType - Specific event or 'all' for all events
   * @param callback - Event handler
   */
  addListener(
    eventType: string,
    callback: (event: UploadProgressEvent) => void
  ): void;

  /**
   * Remove event listener
   */
  removeListener(eventType: string): void;
}