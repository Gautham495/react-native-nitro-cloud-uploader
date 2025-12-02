import { NitroModules } from 'react-native-nitro-modules';
import type {
  NitroCloudUploader,
  UploadResult,
  UploadProgressEvent,
  UploadState,
} from './NitroCloudUploader.nitro';

/**
 * Cloud uploader module for multipart uploads
 */
const NativeModule =
  NitroModules.createHybridObject<NitroCloudUploader>('NitroCloudUploader');

export const CloudUploader = {
  /**
   * Start uploading a file in chunks
   * @param uploadId - Unique identifier
   * @param filePath - Local file path (decoded, no file://)
   * @param uploadUrls - Array of presigned URLs for each chunk
   * @param maxParallel - Number of chunks to upload simultaneously (default: 3)
   * @param showNotification - Show progress notification (default: true)
   * @returns Promise that resolves when upload completes with ETags
   */
  async startUpload(
    uploadId: string,
    filePath: string,
    uploadUrls: string[],
    maxParallel?: number,
    showNotification?: boolean
  ): Promise<UploadResult> {
    try {
      return await NativeModule.startUpload(
        uploadId,
        filePath,
        uploadUrls,
        maxParallel ?? 3,
        showNotification ?? true
      );
    } catch (error) {
      console.error('❌ Upload error:', error);
      throw error;
    }
  },

  /**
   * Pause an ongoing upload
   */
  async pauseUpload(uploadId: string): Promise<void> {
    return NativeModule.pauseUpload(uploadId);
  },

  /**
   * Resume a paused upload
   */
  async resumeUpload(uploadId: string): Promise<void> {
    return NativeModule.resumeUpload(uploadId);
  },

  /**
   * Cancel an upload
   */
  async cancelUpload(uploadId: string): Promise<void> {
    return NativeModule.cancelUpload(uploadId);
  },

  /**
   * Get current state of an upload
   */
  async getUploadState(uploadId: string): Promise<UploadState> {
    return NativeModule.getUploadState(uploadId);
  },

  /**
   * Add event listener for upload events
   * @param eventType - Event type ('all', 'upload-progress', 'chunk-completed', etc.)
   * @param callback - Event handler function
   */
  addListener(
    eventType: string,
    callback: (event: UploadProgressEvent) => void
  ): void {
    try {
      NativeModule.addListener(eventType, callback);
    } catch (error) {
      console.error('Failed to add listener:', error);
    }
  },

  /**
   * Remove event listener
   * @param eventType - Event type to remove
   */
  removeListener(eventType: string): void {
    try {
      NativeModule.removeListener(eventType);
    } catch (error) {
      console.error('Failed to remove listener:', error);
    }
  },
};

// Export types
export type {
  UploadResult,
  UploadProgressEvent,
  UploadState,
} from './NitroCloudUploader.nitro';
