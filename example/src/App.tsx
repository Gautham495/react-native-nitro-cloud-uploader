import { useEffect, useState } from 'react';
import { Text, View, Button, StyleSheet, Alert, Platform } from 'react-native';
import { pick } from '@react-native-documents/picker';
import { CloudUploader } from 'react-native-nitro-cloud-uploader';
import type { UploadProgressEvent } from 'react-native-nitro-cloud-uploader';
import RNFS from 'react-native-fs';

export default function App() {
  const [uploadId, setUploadId] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState('Idle');
  const [isUploading, setIsUploading] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [bytesUploaded, setBytesUploaded] = useState(0);
  const [totalBytes, setTotalBytes] = useState(0);

  const BASE_URL = 'https://api.uploader.com/file-uploader';
  const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
  const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
  const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;

  // Setup event listeners
  useEffect(() => {
    const handleEvent = (event: UploadProgressEvent) => {
      console.log('📡 Event:', event.type, event);

      switch (event.type) {
        case 'upload-started':
          setStatus('Started');
          setIsUploading(true);
          setIsPaused(false);
          break;

        case 'upload-progress':
          if (event.progress !== undefined) {
            setProgress(event.progress);
            setStatus(`Uploading: ${(event.progress * 100).toFixed(1)}%`);
          }
          if (event.bytesUploaded !== undefined) {
            setBytesUploaded(event.bytesUploaded);
          }
          if (event.totalBytes !== undefined) {
            setTotalBytes(event.totalBytes);
          }
          break;

        case 'upload-paused':
          setStatus('Paused');
          setIsPaused(true);
          break;

        case 'upload-resumed':
          setStatus('Resumed');
          setIsPaused(false);
          break;

        case 'upload-completed':
          setStatus('✅ Completed');
          setProgress(1);
          setIsUploading(false);
          setIsPaused(false);
          break;

        case 'upload-failed':
          setStatus(`❌ Failed: ${event.errorMessage || 'Unknown error'}`);
          setIsUploading(false);
          setIsPaused(false);
          break;

        case 'upload-cancelled':
          setStatus('Cancelled');
          setIsUploading(false);
          setIsPaused(false);
          break;

        case 'chunk-completed':
          console.log('✅ Chunk completed:', event.chunkIndex);
          break;

        case 'chunk-failed':
          console.log('❌ Chunk failed:', event.chunkIndex, event.errorMessage);
          break;

        case 'network-lost':
          setStatus('⚠️ Network lost - upload paused');
          break;

        case 'network-restored':
          setStatus('📡 Network restored - resuming');
          break;
      }
    };

    try {
      // Listen to all events
      CloudUploader.addListener('all', handleEvent);
      console.log('✅ Event listener registered');
    } catch (error) {
      console.error('Failed to add listener:', error);
    }

    return () => {
      try {
        CloudUploader.removeListener('all');
      } catch (error) {
        console.error('Failed to remove listener:', error);
      }
    };
  }, []);

  async function normalizeAndroidFilePath(uri: string) {
    if (uri.startsWith('content://')) {
      try {
        const dest = `${RNFS.CachesDirectoryPath}/${Date.now()}.tmp`;
        await RNFS.copyFile(uri, dest);
        return dest;
      } catch (e) {
        console.log('❌ Failed to copy content://', e);
        throw e;
      }
    }
    return uri.replace('file://', '');
  }

  const startUpload = async () => {
    let createData: any = null;

    const uploadTo = 'r2'; // 'r2' || 'b2'

    try {
      setStatus('Picking file...');

      const files = await pick();
      const file = files[0];

      if (!file) {
        setStatus('No file selected');
        return;
      }

      let filePath: string;

      if (Platform.OS === 'android') {
        filePath = await normalizeAndroidFilePath(file.uri);
      } else {
        filePath = decodeURIComponent(file.uri.replace('file://', ''));
      }

      const fileSize = file.size ?? 0;
      const newUploadId = Math.random().toString();

      console.log('📁 File selected:', {
        name: file.name,
        size: fileSize,
        path: filePath,
      });

      setUploadId(newUploadId);
      setStatus('Creating multipart upload...');

      // 3. Create multipart upload
      const createResponse = await fetch(CREATE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uploadId: newUploadId,
          fileSize,
          chunkSize: 6 * 1024 * 1024, // 6MB chunks
          uploadTo: uploadTo,
        }),
      });

      if (!createResponse.ok) {
        const errorText = await createResponse.text();
        throw new Error(`Failed to create upload: ${errorText}`);
      }

      createData = await createResponse.json();

      const { s3UploadId, parts } = createData;

      console.log('✅ Multipart upload created:', {
        s3UploadId,
        partCount: parts.length,
      });

      // 4. Extract URLs
      const uploadUrls = parts.map((p: any) => p.url);

      setStatus('Starting upload...');

      // 5. Start upload with native module
      const result = await CloudUploader.startUpload(
        newUploadId,
        filePath,
        uploadUrls,
        3, // Upload 3 chunks in parallel
        true // Show notification (Android only)
      );

      if (!result.success) {
        throw new Error('Upload failed');
      }

      console.log('✅ All chunks uploaded:', result.etags);

      setStatus('Completing upload...');

      // 6. Complete multipart upload
      const completeResponse = await fetch(COMPLETE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uploadId: newUploadId,
          s3UploadId,
          parts: result.etags.map((etag, index) => ({
            partNumber: index + 1,
            etag: etag,
          })),
          uploadTo: uploadTo,
        }),
      });

      if (!completeResponse.ok) {
        const errorText = await completeResponse.text();
        throw new Error(`Failed to complete upload: ${errorText}`);
      }

      const completeData = await completeResponse.json();
      console.log('✅ Upload completed successfully:', completeData);

      Alert.alert('Success', 'File uploaded successfully!');
    } catch (error) {
      console.error('❌ Upload error:', error);

      // Abort on backend
      if (createData?.s3UploadId) {
        try {
          await fetch(ABORT_UPLOAD_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              uploadId: uploadId,
              s3UploadId: createData.s3UploadId,
              uploadTo: uploadTo,
            }),
          });
        } catch (abortError) {
          console.error('Failed to abort:', abortError);
        }
      }

      Alert.alert(
        'Error',
        error instanceof Error ? error.message : 'Upload failed'
      );
    }
  };

  const pause = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.pauseUpload(uploadId);
    } catch (error) {
      console.error('Failed to pause:', error);
    }
  };

  const resume = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.resumeUpload(uploadId);
    } catch (error) {
      console.error('Failed to resume:', error);
    }
  };

  const cancel = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.cancelUpload(uploadId);
      setUploadId(null);
      setProgress(0);
    } catch (error) {
      console.error('Failed to cancel:', error);
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${(bytes / Math.pow(k, i)).toFixed(2)} ${sizes[i]}`;
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Cloud Uploader</Text>

      <View style={styles.statusContainer}>
        <Text style={styles.status}>Status: {status}</Text>
        <Text style={styles.progress}>
          Progress: {(progress * 100).toFixed(1)}%
        </Text>
        {totalBytes > 0 && (
          <Text style={styles.bytes}>
            {formatBytes(bytesUploaded)} / {formatBytes(totalBytes)}
          </Text>
        )}
      </View>

      {/* Progress bar */}
      <View style={styles.progressBarContainer}>
        <View style={[styles.progressBar, { width: `${progress * 100}%` }]} />
      </View>

      <View style={{ height: 20 }} />

      <Button
        title="📁 Pick File & Upload"
        onPress={startUpload}
        disabled={isUploading}
      />

      <View style={{ height: 12 }} />

      {isUploading && !isPaused && (
        <Button title="⏸️ Pause" onPress={pause} color="#FF9800" />
      )}

      {isUploading && isPaused && (
        <Button title="▶️ Resume" onPress={resume} color="#4CAF50" />
      )}

      <View style={{ height: 12 }} />

      {isUploading && (
        <Button title="⏹️ Cancel" onPress={cancel} color="#f44336" />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    marginBottom: 30,
  },
  statusContainer: {
    alignItems: 'center',
    marginBottom: 12,
  },
  status: {
    fontSize: 16,
    marginBottom: 8,
    color: '#666',
  },
  progress: {
    fontSize: 20,
    fontWeight: '600',
    marginBottom: 4,
    color: '#000',
  },
  bytes: {
    fontSize: 14,
    color: '#999',
  },
  progressBarContainer: {
    width: '100%',
    height: 12,
    backgroundColor: '#e0e0e0',
    borderRadius: 6,
    overflow: 'hidden',
    marginBottom: 20,
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#4CAF50',
  },
});
