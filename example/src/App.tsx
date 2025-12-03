import { useEffect, useState } from 'react';
import {
  Text,
  View,
  StyleSheet,
  Alert,
  Platform,
  PermissionsAndroid,
  TouchableOpacity,
  StatusBar,
  ScrollView,
} from 'react-native';
import { pick } from '@react-native-documents/picker';
import { CloudUploader } from 'react-native-nitro-cloud-uploader';
import type { UploadProgressEvent } from 'react-native-nitro-cloud-uploader';
import RNFS from 'react-native-fs';

export default function App() {
  const [uploadId, setUploadId] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState('Ready to upload');
  const [isUploading, setIsUploading] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [bytesUploaded, setBytesUploaded] = useState(0);
  const [totalBytes, setTotalBytes] = useState(0);

  const BASE_URL = 'https://your-api.workers.dev';
  const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
  const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
  const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
  const SINGLE_UPLOAD_URL = `${BASE_URL}/single-upload`;

  // Setup event listeners
  useEffect(() => {
    const handleEvent = (event: UploadProgressEvent) => {
      console.log('📡 Event:', event.type, event);

      switch (event.type) {
        case 'upload-started':
          setStatus('Upload started...');
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
          setStatus('Upload paused');
          setIsPaused(true);
          break;

        case 'upload-resumed':
          setStatus('Upload resumed');
          setIsPaused(false);
          break;

        case 'upload-completed':
          setStatus('✅ Upload completed!');
          setProgress(1);
          setIsUploading(false);
          setIsPaused(false);
          break;

        case 'upload-failed':
          setStatus(
            `❌ Upload failed: ${event.errorMessage || 'Unknown error'}`
          );
          setIsUploading(false);
          setIsPaused(false);
          break;

        case 'upload-cancelled':
          setStatus('Upload cancelled');
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

      const uploadProps = {
        uploadId: newUploadId,
        fileSize,
        chunkSize: 6 * 1024 * 1024,
        uploadTo: uploadTo,
      };

      const createResponse = await fetch(CREATE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(uploadProps),
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

      const uploadUrls = parts.map((p: any) => p.url);
      setStatus('Starting upload...');

      const result = await CloudUploader.startUpload(
        newUploadId,
        filePath,
        uploadUrls,
        3,
        true
      );

      if (!result.success) {
        throw new Error('Upload failed');
      }

      console.log('✅ All chunks uploaded:', result.etags);
      setStatus('Completing upload...');

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

  const startSingleUpload = async () => {
    let createData: any = null;
    const uploadTo = 'r2';

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
      setStatus('Creating single upload...');

      const uploadProps = {
        uploadId: newUploadId,
        fileName: file.name,
        uploadTo: uploadTo,
      };

      const createResponse = await fetch(SINGLE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(uploadProps),
      });

      if (!createResponse.ok) {
        const errorText = await createResponse.text();
        throw new Error(`Failed to create upload: ${errorText}`);
      }

      createData = await createResponse.json();

      setStatus('Starting upload...');

      console.log(createData, 'createdData');

      const result = await CloudUploader.startUpload(
        newUploadId,
        filePath,
        [createData.url],
        1,
        true
      );

      if (!result.success) {
        throw new Error('Upload failed');
      }

      Alert.alert('Success', 'File uploaded successfully!');
      console.log('✅ Public URL:', createData);
    } catch (error) {
      console.error('❌ Upload error:', error);

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
      setBytesUploaded(0);
      setTotalBytes(0);
      setStatus('Upload cancelled');
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

  const requestAndroidPermissions = async () => {
    if (Platform.OS !== 'android') {
      return true;
    }

    try {
      if (Platform.Version >= 33) {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
          {
            title: 'Notification Permission',
            message:
              'App needs permission to show upload progress notifications.',
            buttonNeutral: 'Ask Me Later',
            buttonNegative: 'Cancel',
            buttonPositive: 'OK',
          }
        );

        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          console.log('✅ Notification permission granted');
          return true;
        } else {
          console.log('⚠️ Notification permission denied');
          Alert.alert(
            'Notification Permission',
            "Notifications are disabled. You won't see upload progress.",
            [{ text: 'OK' }]
          );
          return false;
        }
      } else {
        console.log('✅ Notification permission not required for Android < 13');
        return true;
      }
    } catch (err) {
      console.error('❌ Notification permission error:', err);
      return false;
    }
  };

  useEffect(() => {
    requestAndroidPermissions();
  }, []);

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#6366f1" />

      <View style={styles.header}>
        <Text style={styles.headerTitle}>☁️ Cloud Uploader</Text>
        <Text style={styles.headerSubtitle}>Secure & Fast File Uploads</Text>
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* Status Card */}
        <View style={styles.statusCard}>
          <View style={styles.statusHeader}>
            <Text style={styles.statusLabel}>STATUS</Text>
            <View
              style={[
                styles.statusIndicator,
                isUploading && !isPaused && styles.statusIndicatorActive,
                isPaused && styles.statusIndicatorPaused,
              ]}
            />
          </View>
          <Text style={styles.statusText}>{status}</Text>

          {totalBytes > 0 && (
            <View style={styles.bytesContainer}>
              <Text style={styles.bytesText}>
                {formatBytes(bytesUploaded)} / {formatBytes(totalBytes)}
              </Text>
              <Text style={styles.percentageText}>
                {(progress * 100).toFixed(1)}%
              </Text>
            </View>
          )}

          {/* Progress Bar */}
          <View style={styles.progressBarContainer}>
            <View
              style={[
                styles.progressBar,
                { width: `${progress * 100}%` },
                isPaused && styles.progressBarPaused,
              ]}
            />
          </View>
        </View>

        {/* Upload Buttons */}
        <View style={styles.buttonsContainer}>
          <TouchableOpacity
            style={[styles.button, styles.primaryButton]}
            onPress={startUpload}
            activeOpacity={0.7}
            disabled={isUploading}
          >
            <Text style={styles.buttonIcon}>📦</Text>
            <Text style={styles.buttonText}>Multipart Upload</Text>
            <Text style={styles.buttonSubtext}>For large files</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.button, styles.secondaryButton]}
            onPress={startSingleUpload}
            activeOpacity={0.7}
            disabled={isUploading}
          >
            <Text style={styles.buttonIcon}>📄</Text>
            <Text style={styles.buttonText}>Single Upload</Text>
            <Text style={styles.buttonSubtext}>For small files</Text>
          </TouchableOpacity>
        </View>

        {/* Control Buttons */}
        {isUploading && (
          <View style={styles.controlsContainer}>
            {!isPaused ? (
              <TouchableOpacity
                style={[styles.controlButton, styles.pauseButton]}
                onPress={pause}
                activeOpacity={0.7}
              >
                <Text style={styles.controlButtonText}>⏸️ Pause</Text>
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                style={[styles.controlButton, styles.resumeButton]}
                onPress={resume}
                activeOpacity={0.7}
              >
                <Text style={styles.controlButtonText}>▶️ Resume</Text>
              </TouchableOpacity>
            )}

            <TouchableOpacity
              style={[styles.controlButton, styles.cancelButton]}
              onPress={cancel}
              activeOpacity={0.7}
            >
              <Text style={styles.controlButtonText}>⏹️ Cancel</Text>
            </TouchableOpacity>
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f8fafc',
  },
  header: {
    backgroundColor: '#6366f1',
    paddingTop: Platform.OS === 'ios' ? 60 : 40,
    paddingBottom: 30,
    paddingHorizontal: 24,
    borderBottomLeftRadius: 24,
    borderBottomRightRadius: 24,
    shadowColor: '#6366f1',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 8,
  },
  headerTitle: {
    fontSize: 32,
    fontWeight: '800',
    color: '#ffffff',
    marginBottom: 4,
  },
  headerSubtitle: {
    fontSize: 16,
    color: '#e0e7ff',
    fontWeight: '500',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 20,
    paddingBottom: 40,
  },
  statusCard: {
    backgroundColor: '#ffffff',
    borderRadius: 20,
    padding: 24,
    marginBottom: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 8,
    elevation: 4,
  },
  statusHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  statusLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#94a3b8',
    letterSpacing: 1,
  },
  statusIndicator: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#cbd5e1',
  },
  statusIndicatorActive: {
    backgroundColor: '#22c55e',
  },
  statusIndicatorPaused: {
    backgroundColor: '#f59e0b',
  },
  statusText: {
    fontSize: 18,
    fontWeight: '600',
    color: '#1e293b',
    marginBottom: 16,
  },
  bytesContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  bytesText: {
    fontSize: 14,
    color: '#64748b',
    fontWeight: '500',
  },
  percentageText: {
    fontSize: 24,
    fontWeight: '700',
    color: '#6366f1',
  },
  progressBarContainer: {
    width: '100%',
    height: 8,
    backgroundColor: '#e2e8f0',
    borderRadius: 4,
    overflow: 'hidden',
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#6366f1',
    borderRadius: 4,
  },
  progressBarPaused: {
    backgroundColor: '#f59e0b',
  },
  buttonsContainer: {
    marginBottom: 20,
  },
  button: {
    borderRadius: 16,
    padding: 20,
    marginBottom: 16,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 8,
    elevation: 3,
  },
  primaryButton: {
    backgroundColor: '#6366f1',
  },
  secondaryButton: {
    backgroundColor: '#8b5cf6',
  },
  buttonIcon: {
    fontSize: 40,
    marginBottom: 8,
  },
  buttonText: {
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
    marginBottom: 4,
  },
  buttonSubtext: {
    fontSize: 13,
    color: '#e0e7ff',
    fontWeight: '500',
  },
  controlsContainer: {
    flexDirection: 'row',
    gap: 12,
  },
  controlButton: {
    flex: 1,
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 2,
  },
  pauseButton: {
    backgroundColor: '#f59e0b',
  },
  resumeButton: {
    backgroundColor: '#22c55e',
  },
  cancelButton: {
    backgroundColor: '#ef4444',
  },
  controlButtonText: {
    fontSize: 16,
    fontWeight: '700',
    color: '#ffffff',
  },
});
