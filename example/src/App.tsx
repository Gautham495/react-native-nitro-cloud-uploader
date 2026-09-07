import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

import { pick } from '@react-native-documents/picker';

import RNFS from 'react-native-fs';

import { CloudUploader } from 'react-native-nitro-cloud-uploader';

import type { UploadProgressEvent } from 'react-native-nitro-cloud-uploader';

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

// 30 MB Audio File - https://cdn.gauthamvijay.com/30.mp3
// 70 MB Audio File - https://cdn.gauthamvijay.com/70.mp3

const BASE_URL = 'https://api.gauthamvijay.com/r2-uploader';

const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
const SINGLE_UPLOAD_URL = `${BASE_URL}/single-upload`;

const MAX_LOG_LINES = 60;

const TEST_FILES = [
  {
    label: '30 MB',
    url: 'https://cdn.gauthamvijay.com/30.mp3',
    name: '30.mp3',
  },
  {
    label: '70 MB',
    url: 'https://cdn.gauthamvijay.com/70.mp3',
    name: '70.mp3',
  },
];

async function downloadTestFile(url: string, name: string) {
  const dest = `${RNFS.DocumentDirectoryPath}/${name}`;
  const exists = await RNFS.exists(dest);
  if (exists) await RNFS.unlink(dest);
  const { promise } = RNFS.downloadFile({ fromUrl: url, toFile: dest });
  const result = await promise;
  if (result.statusCode !== 200) {
    throw new Error(`HTTP ${result.statusCode}`);
  }
  return dest;
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type Phase = 'idle' | 'preparing' | 'uploading' | 'paused' | 'done' | 'error';

interface LogEntry {
  ts: number;
  type: string;
  detail: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function App() {
  // Upload lifecycle
  const [phase, setPhase] = useState<Phase>('idle');
  const [uploadId, setUploadId] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [bytesUploaded, setBytesUploaded] = useState(0);
  const [totalBytes, setTotalBytes] = useState(0);
  const [network, setNetwork] = useState<'online' | 'offline'>('online');
  const [statusLine, setStatusLine] = useState('Ready.');
  const [downloading, setDownloading] = useState<Record<string, boolean>>({});

  // Options (map 1:1 to the new startUpload params)
  const [showNotification, setShowNotification] = useState(true);
  const [maxParallel, setMaxParallel] = useState('3');

  // Event log — the actual proof the byte-level progress is firing
  const [log, setLog] = useState<LogEntry[]>([]);
  const progressTickCount = useRef(0);
  const uploadStartAt = useRef<number>(0);

  const pushLog = (type: string, detail = '') => {
    setLog((prev) => {
      const next = [{ ts: Date.now(), type, detail }, ...prev];
      return next.slice(0, MAX_LOG_LINES);
    });
  };

  const handleDownloadTestFile = async (url: string, name: string) => {
    setDownloading((s) => ({ ...s, [name]: true }));
    try {
      const path = await downloadTestFile(url, name);
      pushLog('test-file-downloaded', `${name} → ${path}`);
      Alert.alert('Downloaded', `${name}\nSaved to app Documents.`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Download failed';
      pushLog('test-file-failed', `${name}: ${message}`);
      Alert.alert('Download failed', message);
    } finally {
      setDownloading((s) => ({ ...s, [name]: false }));
    }
  };

  // -------------------------------------------------------------------------
  // Event wiring
  // -------------------------------------------------------------------------

  useEffect(() => {
    const handleEvent = (event: UploadProgressEvent) => {
      switch (event.type) {
        case 'upload-started':
          uploadStartAt.current = Date.now();
          progressTickCount.current = 0;
          setPhase('uploading');
          setStatusLine('Uploading…');
          pushLog('upload-started');
          break;

        case 'upload-progress':
          progressTickCount.current += 1;
          if (event.progress != null) setProgress(event.progress);
          if (event.bytesUploaded != null)
            setBytesUploaded(event.bytesUploaded);
          if (event.totalBytes != null) setTotalBytes(event.totalBytes);
          // Only log every 20th tick — the whole point is that ticks are frequent.
          if (progressTickCount.current % 20 === 1) {
            pushLog(
              'upload-progress',
              `${((event.progress ?? 0) * 100).toFixed(1)}%`
            );
          }
          break;

        case 'upload-paused':
          setPhase('paused');
          setStatusLine('Paused.');
          pushLog('upload-paused');
          break;

        case 'upload-resumed':
          setPhase('uploading');
          setStatusLine('Uploading…');
          pushLog('upload-resumed');
          break;

        case 'upload-completed': {
          const elapsed = (Date.now() - uploadStartAt.current) / 1000;
          setPhase('done');
          setProgress(1);
          setStatusLine(
            `Done in ${elapsed.toFixed(1)}s · ${
              progressTickCount.current
            } ticks`
          );
          pushLog(
            'upload-completed',
            `${elapsed.toFixed(1)}s, ${
              progressTickCount.current
            } progress ticks`
          );
          break;
        }

        case 'upload-failed':
          setPhase('error');
          setStatusLine(event.errorMessage ?? 'Upload failed');
          pushLog('upload-failed', event.errorMessage ?? '');
          break;

        case 'upload-cancelled':
          setPhase('idle');
          setStatusLine('Cancelled.');
          pushLog('upload-cancelled');
          break;

        case 'chunk-completed':
          pushLog('chunk-completed', `part ${(event.chunkIndex ?? 0) + 1}`);
          break;

        case 'chunk-failed':
          pushLog(
            'chunk-failed',
            `part ${(event.chunkIndex ?? 0) + 1}: ${event.errorMessage ?? ''}`
          );
          break;

        case 'network-lost':
          setNetwork('offline');
          setStatusLine('Network lost — waiting.');
          pushLog('network-lost');
          break;

        case 'network-restored':
          setNetwork('online');
          pushLog('network-restored');
          break;
      }
    };

    try {
      CloudUploader.addListener('all', handleEvent);
    } catch (error) {
      console.error('addListener failed:', error);
    }
    return () => {
      try {
        CloudUploader.removeListener('all');
      } catch (error) {
        console.error('removeListener failed:', error);
      }
    };
  }, []);

  // -------------------------------------------------------------------------
  // Android notification permission (13+)
  // -------------------------------------------------------------------------

  useEffect(() => {
    (async () => {
      if (Platform.OS !== 'android') return;
      if ((Platform.Version as number) < 33) return;
      try {
        await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
        );
      } catch (e) {
        console.error('permission error', e);
      }
    })();
  }, []);

  // -------------------------------------------------------------------------
  // File path handling
  // -------------------------------------------------------------------------

  async function resolvePickedPath(uri: string) {
    if (Platform.OS === 'android' && uri.startsWith('content://')) {
      const dest = `${RNFS.CachesDirectoryPath}/${Date.now()}.tmp`;
      await RNFS.copyFile(uri, dest);
      return dest;
    }
    return decodeURIComponent(uri.replace('file://', ''));
  }

  // -------------------------------------------------------------------------
  // Multipart upload
  // -------------------------------------------------------------------------

  const runMultipart = async () => {
    let createData: any = null;
    const uploadTo = 'r2';

    resetState();

    try {
      setPhase('preparing');
      setStatusLine('Picking file…');

      const files = await pick();
      const file = files[0];
      if (!file) {
        setPhase('idle');
        setStatusLine('No file selected.');
        return;
      }

      const filePath = await resolvePickedPath(file.uri);
      const fileSize = file.size ?? 0;
      const newUploadId = Math.random().toString(36).slice(2);

      setUploadId(newUploadId);
      setFileName(file.name ?? 'file');
      setTotalBytes(fileSize);
      setStatusLine('Requesting presigned URLs…');
      pushLog('pick', `${file.name} · ${formatBytes(fileSize)}`);

      const createResponse = await fetch(CREATE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uploadId: newUploadId,
          fileSize,
          chunkSize: 6 * 1024 * 1024,
          uploadTo,
        }),
      });
      if (!createResponse.ok) {
        throw new Error(`create-upload: ${await createResponse.text()}`);
      }
      createData = await createResponse.json();
      const { s3UploadId, parts } = createData;
      const uploadUrls = parts.map((p: any) => p.url);
      pushLog('create-upload', `${parts.length} parts`);

      const parallel = clampInt(maxParallel, 1, 10, 3);

      const result = await CloudUploader.startUpload(
        newUploadId,
        filePath,
        uploadUrls,
        parallel,
        showNotification
      );

      if (!result.success) throw new Error('Upload reported failure');

      setStatusLine('Finalizing…');
      const completeResponse = await fetch(COMPLETE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uploadId: newUploadId,
          s3UploadId,
          parts: result.etags.map((etag, i) => ({
            partNumber: i + 1,
            etag,
          })),
          uploadTo,
        }),
      });
      if (!completeResponse.ok) {
        throw new Error(`complete: ${await completeResponse.text()}`);
      }
      pushLog('complete-upload', 'ok');
      Alert.alert('Uploaded', `${file.name} · ${formatBytes(fileSize)}`);
    } catch (error) {
      console.error(error);
      if (createData?.s3UploadId) {
        try {
          await fetch(ABORT_UPLOAD_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              uploadId: uploadId,
              s3UploadId: createData.s3UploadId,
              uploadTo,
            }),
          });
          pushLog('abort-upload', 'ok');
        } catch (abortError) {
          console.error('abort failed', abortError);
        }
      }
      const message = error instanceof Error ? error.message : 'Upload failed';
      setStatusLine(message);
      setPhase('error');
      Alert.alert('Upload failed', message);
    }
  };

  // -------------------------------------------------------------------------
  // Single-URL upload (small file path)
  // -------------------------------------------------------------------------

  const runSingle = async () => {
    let createData: any = null;
    const uploadTo = 'r2';

    resetState();

    try {
      setPhase('preparing');
      setStatusLine('Picking file…');

      const files = await pick();
      const file = files[0];
      if (!file) {
        setPhase('idle');
        setStatusLine('No file selected.');
        return;
      }

      const filePath = await resolvePickedPath(file.uri);
      const fileSize = file.size ?? 0;
      const newUploadId = Math.random().toString(36).slice(2);

      setUploadId(newUploadId);
      setFileName(file.name ?? 'file');
      setTotalBytes(fileSize);
      pushLog('pick', `${file.name} · ${formatBytes(fileSize)}`);

      const createResponse = await fetch(SINGLE_UPLOAD_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          uploadId: newUploadId,
          fileName: file.name,
          uploadTo,
        }),
      });
      if (!createResponse.ok) {
        throw new Error(`single-upload: ${await createResponse.text()}`);
      }
      createData = await createResponse.json();
      pushLog('single-upload', 'presigned URL received');

      const result = await CloudUploader.startUpload(
        newUploadId,
        filePath,
        [createData.url],
        1,
        showNotification
      );
      if (!result.success) throw new Error('Upload reported failure');

      Alert.alert('Uploaded', `${file.name}`);
    } catch (error) {
      console.error(error);
      const message = error instanceof Error ? error.message : 'Upload failed';
      setStatusLine(message);
      setPhase('error');
      Alert.alert('Upload failed', message);
    }
  };

  // -------------------------------------------------------------------------
  // Controls
  // -------------------------------------------------------------------------

  const pauseCurrent = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.pauseUpload(uploadId);
    } catch (e) {
      console.error('pause failed', e);
    }
  };

  const resumeCurrent = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.resumeUpload(uploadId);
    } catch (e) {
      console.error('resume failed', e);
    }
  };

  const cancelCurrent = async () => {
    if (!uploadId) return;
    try {
      await CloudUploader.cancelUpload(uploadId);
    } catch (e) {
      console.error('cancel failed', e);
    }
  };

  const resetState = () => {
    setProgress(0);
    setBytesUploaded(0);
    setTotalBytes(0);
    setLog([]);
    progressTickCount.current = 0;
  };

  // -------------------------------------------------------------------------
  // Derived
  // -------------------------------------------------------------------------

  const throughput = (() => {
    if (phase !== 'uploading' || bytesUploaded === 0) return null;
    const seconds = (Date.now() - uploadStartAt.current) / 1000;
    if (seconds < 0.5) return null;
    return `${formatBytes(bytesUploaded / seconds)}/s`;
  })();

  const busy =
    phase === 'uploading' || phase === 'paused' || phase === 'preparing';
  const canControl = phase === 'uploading' || phase === 'paused';

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  return (
    <View style={S.root}>
      <StatusBar barStyle="dark-content" backgroundColor="#fafaf9" />
      <ScrollView
        contentContainerStyle={S.scroll}
        showsVerticalScrollIndicator={false}
      >
        {/* --- Header ------------------------------------------------- */}
        <View style={S.header}>
          <Text style={S.headerTitle}>nitro-cloud-uploader</Text>
          <Text style={S.headerMeta}>
            {Platform.OS === 'android' ? 'Android' : 'iOS'}{' '}
            {Platform.Version.toString()}
          </Text>
        </View>

        {/* --- Progress card ------------------------------------------ */}
        <View style={S.card}>
          <View style={S.cardHeader}>
            <View style={[S.dot, dotStyle(phase, network)]} />
            <Text style={S.cardHeaderText}>
              {fileName ?? 'No file selected'}
            </Text>
          </View>

          <View style={S.progressRow}>
            <Text style={S.progressPct}>
              {(progress * 100).toFixed(1)}
              <Text style={S.progressPctSuffix}>%</Text>
            </Text>
            <View style={S.progressMetrics}>
              <Text style={S.metric}>
                {formatBytes(bytesUploaded)}
                <Text style={S.metricMuted}> / {formatBytes(totalBytes)}</Text>
              </Text>
              {throughput && <Text style={S.metricMuted}>{throughput}</Text>}
            </View>
          </View>

          <View style={S.track}>
            <View
              style={[
                S.trackFill,
                { width: `${Math.max(0, Math.min(1, progress)) * 100}%` },
                phase === 'paused' && S.trackFillPaused,
                phase === 'error' && S.trackFillError,
              ]}
            />
          </View>

          <Text style={S.statusLine}>{statusLine}</Text>
        </View>

        {/* --- Actions ------------------------------------------------ */}
        <View style={S.actions}>
          <TouchableOpacity
            style={[S.actionBtn, S.actionPrimary, busy && S.actionDisabled]}
            onPress={runMultipart}
            disabled={busy}
            activeOpacity={0.6}
          >
            <Text style={S.actionPrimaryText}>Start multipart upload</Text>
            <Text style={S.actionPrimaryHint}>
              Presigned URLs · resumable · background
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[S.actionBtn, S.actionGhost, busy && S.actionDisabled]}
            onPress={runSingle}
            disabled={busy}
            activeOpacity={0.6}
          >
            <Text style={S.actionGhostText}>Single-URL upload</Text>
          </TouchableOpacity>

          {canControl && (
            <View style={S.controlRow}>
              {phase === 'uploading' ? (
                <TouchableOpacity
                  style={[S.control, S.controlNeutral]}
                  onPress={pauseCurrent}
                  activeOpacity={0.6}
                >
                  <Text style={S.controlText}>Pause</Text>
                </TouchableOpacity>
              ) : (
                <TouchableOpacity
                  style={[S.control, S.controlAccent]}
                  onPress={resumeCurrent}
                  activeOpacity={0.6}
                >
                  <Text style={[S.controlText, S.controlTextInvert]}>
                    Resume
                  </Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                style={[S.control, S.controlDanger]}
                onPress={cancelCurrent}
                activeOpacity={0.6}
              >
                <Text style={[S.controlText, S.controlTextInvert]}>Cancel</Text>
              </TouchableOpacity>
            </View>
          )}
        </View>

        {/* --- Options ------------------------------------------------ */}
        <View style={S.card}>
          <Text style={S.sectionLabel}>Options</Text>

          <OptionRow
            label="Show progress notification"
            hint={
              Platform.OS === 'android'
                ? 'Foreground service notification'
                : 'no-op on iOS'
            }
            value={showNotification}
            onChange={setShowNotification}
            disabled={busy}
          />

          <View style={S.optionRow}>
            <View style={S.optionText}>
              <Text style={S.optionLabel}>Parallel chunks</Text>
              <Text style={S.optionHint}>1–10; default 3</Text>
            </View>
            <TextInput
              value={maxParallel}
              onChangeText={setMaxParallel}
              keyboardType="number-pad"
              editable={!busy}
              style={S.numberInput}
              maxLength={2}
            />
          </View>
        </View>

        <View style={S.card}>
          <Text style={S.sectionLabel}>Test files</Text>
          <Text style={S.optionHint}>
            Download into app Documents, then pick with the uploader.
          </Text>
          <View style={S.testFileRow}>
            {TEST_FILES.map((f) => {
              const isDownloading = downloading[f.name];
              return (
                <TouchableOpacity
                  key={f.name}
                  style={[
                    S.actionBtn,
                    S.actionGhost,
                    S.testFileBtn,
                    isDownloading && S.actionDisabled,
                  ]}
                  onPress={() => handleDownloadTestFile(f.url, f.name)}
                  disabled={isDownloading}
                  activeOpacity={0.6}
                >
                  <Text style={S.actionGhostText}>
                    {isDownloading ? 'Downloading…' : `Download ${f.label}`}
                  </Text>
                  <Text style={S.optionHint}>{f.name}</Text>
                </TouchableOpacity>
              );
            })}
          </View>
        </View>

        {/* --- Event log ---------------------------------------------- */}
        <View style={[S.card, S.logCard]}>
          <View style={S.logHeader}>
            <Text style={S.sectionLabel}>Event stream</Text>
            <Text style={S.logCount}>{progressTickCount.current} ticks</Text>
          </View>
          {log.length === 0 ? (
            <Text style={S.logEmpty}>
              No events yet. Start an upload to watch progress ticks fire in
              real time.
            </Text>
          ) : (
            <View style={S.logList}>
              {log.map((entry, i) => (
                <View
                  key={`${entry.ts}-${i}`}
                  style={[S.logRow, i === 0 && S.logRowLatest]}
                >
                  <Text style={S.logTs}>{formatTs(entry.ts)}</Text>
                  <Text style={[S.logType, eventColor(entry.type)]}>
                    {entry.type}
                  </Text>
                  {!!entry.detail && (
                    <Text style={S.logDetail} numberOfLines={1}>
                      {entry.detail}
                    </Text>
                  )}
                </View>
              ))}
            </View>
          )}
        </View>

        <Text style={S.footer}>react-native-nitro-cloud-uploader</Text>
      </ScrollView>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Subcomponents
// ---------------------------------------------------------------------------

function OptionRow({
  label,
  hint,
  value,
  onChange,
  disabled,
}: {
  label: string;
  hint: string;
  value: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <View style={[S.optionRow, disabled && S.optionRowDisabled]}>
      <View style={S.optionText}>
        <Text style={S.optionLabel}>{label}</Text>
        <Text style={S.optionHint}>{hint}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        disabled={disabled}
        trackColor={{ false: '#e7e5e4', true: '#111827' }}
        thumbColor="#fafaf9"
      />
    </View>
  );
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatBytes(bytes: number) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(
    sizes.length - 1,
    Math.floor(Math.log(bytes) / Math.log(k))
  );
  const value = bytes / Math.pow(k, i);
  return `${value < 10 ? value.toFixed(2) : value.toFixed(1)} ${sizes[i]}`;
}

function formatTs(ts: number) {
  const d = new Date(ts);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${d
    .getMilliseconds()
    .toString()
    .padStart(3, '0')}`;
}

function clampInt(s: string, min: number, max: number, fallback: number) {
  const n = parseInt(s, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

function dotStyle(phase: Phase, network: 'online' | 'offline') {
  if (network === 'offline') return { backgroundColor: '#f59e0b' };
  switch (phase) {
    case 'uploading':
      return { backgroundColor: '#16a34a' };
    case 'paused':
      return { backgroundColor: '#f59e0b' };
    case 'done':
      return { backgroundColor: '#111827' };
    case 'error':
      return { backgroundColor: '#dc2626' };
    default:
      return { backgroundColor: '#d6d3d1' };
  }
}

function eventColor(type: string) {
  if (type.startsWith('chunk-completed') || type === 'upload-completed')
    return { color: '#16a34a' };
  if (type.includes('failed') || type === 'upload-cancelled')
    return { color: '#dc2626' };
  if (type.includes('network')) return { color: '#f59e0b' };
  if (type === 'upload-progress') return { color: '#57534e' };
  return { color: '#111827' };
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const S = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#fafaf9',
  },
  scroll: {
    padding: 20,
    paddingTop: Platform.OS === 'ios' ? 65 : 60,
    paddingBottom: 40,
  },

  // Header
  header: {
    marginBottom: 24,
  },
  headerTitle: {
    fontSize: 22,
    fontWeight: '600',
    color: '#111827',
    letterSpacing: -0.5,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  headerMeta: {
    marginTop: 4,
    fontSize: 12,
    color: '#78716c',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },

  // Card
  card: {
    backgroundColor: '#ffffff',
    borderRadius: 12,
    padding: 18,
    marginBottom: 14,
    borderWidth: 1,
    borderColor: '#f5f5f4',
  },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
  },
  cardHeaderText: {
    fontSize: 14,
    color: '#111827',
    fontWeight: '500',
    flex: 1,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '600',
    color: '#78716c',
    textTransform: 'uppercase',
    letterSpacing: 0.8,
    marginBottom: 12,
  },

  // Status dot
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 10,
  },

  // Progress
  progressRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    marginBottom: 10,
  },
  progressPct: {
    fontSize: 34,
    fontWeight: '700',
    color: '#111827',
    letterSpacing: -1,
    fontVariant: ['tabular-nums'],
  },
  progressPctSuffix: {
    fontSize: 18,
    fontWeight: '500',
    color: '#78716c',
  },
  progressMetrics: {
    alignItems: 'flex-end',
  },
  metric: {
    fontSize: 13,
    color: '#111827',
    fontVariant: ['tabular-nums'],
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  metricMuted: {
    fontSize: 12,
    color: '#78716c',
    marginTop: 2,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  track: {
    height: 4,
    backgroundColor: '#f5f5f4',
    borderRadius: 2,
    overflow: 'hidden',
  },
  trackFill: {
    height: '100%',
    backgroundColor: '#111827',
  },
  trackFillPaused: { backgroundColor: '#f59e0b' },
  trackFillError: { backgroundColor: '#dc2626' },

  statusLine: {
    marginTop: 12,
    fontSize: 12,
    color: '#57534e',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },

  // Actions
  actions: {
    marginBottom: 14,
  },
  actionBtn: {
    borderRadius: 10,
    paddingVertical: 14,
    paddingHorizontal: 16,
    marginBottom: 10,
    alignItems: 'flex-start',
  },
  actionPrimary: {
    backgroundColor: '#111827',
  },
  actionPrimaryText: {
    color: '#fafaf9',
    fontSize: 15,
    fontWeight: '600',
  },
  actionPrimaryHint: {
    color: '#a8a29e',
    fontSize: 12,
    marginTop: 3,
  },
  actionGhost: {
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#e7e5e4',
    alignItems: 'center',
  },
  actionGhostText: {
    color: '#111827',
    fontSize: 14,
    fontWeight: '500',
  },
  actionDisabled: {
    opacity: 0.4,
  },

  controlRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 4,
  },
  control: {
    flex: 1,
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
  },
  controlNeutral: {
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#e7e5e4',
  },
  controlAccent: {
    backgroundColor: '#16a34a',
  },
  controlDanger: {
    backgroundColor: '#dc2626',
  },
  controlText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#111827',
  },
  controlTextInvert: {
    color: '#fafaf9',
  },

  // Options
  optionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    borderTopWidth: 1,
    borderTopColor: '#f5f5f4',
  },
  optionRowDisabled: {
    opacity: 0.5,
  },
  optionText: {
    flex: 1,
    paddingRight: 12,
  },
  optionLabel: {
    fontSize: 14,
    color: '#111827',
    fontWeight: '500',
  },
  optionHint: {
    fontSize: 12,
    color: '#78716c',
    marginTop: 2,
  },
  numberInput: {
    width: 50,
    borderWidth: 1,
    borderColor: '#e7e5e4',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
    textAlign: 'center',
    color: '#111827',
    fontVariant: ['tabular-nums'],
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },

  // Log
  logCard: {
    paddingBottom: 8,
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'baseline',
    marginBottom: 8,
  },
  logCount: {
    fontSize: 11,
    color: '#78716c',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  logEmpty: {
    fontSize: 12,
    color: '#a8a29e',
    lineHeight: 18,
    paddingVertical: 8,
  },
  logList: {
    // negative margin so rows sit tight
  },
  logRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    paddingVertical: 4,
    borderTopWidth: 1,
    borderTopColor: '#fafaf9',
  },
  logRowLatest: {
    borderTopWidth: 0,
  },
  logTs: {
    fontSize: 10,
    color: '#a8a29e',
    width: 88,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  logType: {
    fontSize: 11,
    fontWeight: '600',
    marginRight: 8,
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },
  logDetail: {
    flex: 1,
    fontSize: 11,
    color: '#57534e',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },

  footer: {
    marginTop: 12,
    textAlign: 'center',
    fontSize: 10,
    color: '#a8a29e',
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace' }),
  },

  testFileRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 12,
  },
  testFileBtn: {
    flex: 1,
    marginBottom: 0,
    alignItems: 'center',
  },
});
