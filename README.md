<a href="https://gauthamvijay.com">
  <picture>
    <img alt="react-native-nitro-cloud-uploader" src="./docs/img/banner.png" />
  </picture>
</a>

# react-native-nitro-cloud-uploader

**React Native Nitro Module** for **reliable, resumable, background-friendly uploads** of large files (audio, video, images, PDFs) to **S3-compatible storage** — built for real production workloads.

---

> [!NOTE]
>
> - This library was originally created for my production app, where we needed to upload **long audio recordings** and large media files directly from the device — reliably, even when the app was in the background.
> - It works great with **multipart presigned URLs** for any S3-compatible object storage:
>   - **AWS S3** — the reference implementation
>   - **Cloudflare R2** — zero egress fees, drop-in S3 API
>   - **Backblaze B2** — cheapest cold storage with S3 compatibility
>   - **DigitalOcean Spaces** — S3-compatible, bundled with DO infra
>   - **Wasabi** — hot storage, no egress fees, S3 API
>   - **MinIO** — self-hosted S3 for on-prem or private cloud
>   - **Linode Object Storage / Akamai Cloud** — S3-compatible
>   - **Vultr Object Storage** — S3-compatible
>   - **Scaleway Object Storage** — S3-compatible, EU-based
>   - **Tigris** — globally distributed S3-compatible storage
>   - **Any custom S3-compatible endpoint** — works as long as the server issues presigned multipart URLs
>
> **What you get out of the box:**
>
> - True multipart uploads with configurable chunk size and parallelism
> - Byte-level progress events (not just per-chunk)
> - Pause, resume, and cancel — mid-upload, on any chunk
> - Background upload survival on both iOS (URLSession background sessions) and Android (foreground service with notification)
> - Automatic retry on chunk failure with network loss detection
> - ETag collection for the final `CompleteMultipartUpload` call
>
> **What this library does NOT do** (by design):
>
> - Generate presigned URLs — your server does that. This keeps AWS credentials off the device.
> - Handle non-S3 protocols — no Firebase Storage, no GCS native, no Azure Blob native. Those have their own SDKs and don't speak S3 multipart.
> - Single-request uploads over ~100MB — use multipart, that's the point.
>
> If you need mobile uploads of **huge files** to S3-compatible storage, this library gives you everything you need out of the box.

---

## 📦 Installation

```bash
npm install react-native-nitro-cloud-uploader react-native-nitro-modules
```

> [!IMPORTANT]
>
> - **iOS**: Fully tested and production-ready ✅
> - **Android**: Implementation complete with full feature parity ✅
>   - Background uploads via ForegroundService
>   - Progress notifications
>   - Network drop/restore handling
>   - Pause/Resume/Cancel controls
>   - Requires Android 7.0+ (API 24+)
> - Tested only for React Native 0.85.3 and above. PRs welcome for lower RN versions to make it work and stable for lower versions.

---

## 🎥 Demo

<table>
  <tr>
    <th align="center">🍏 iOS Demo</th>
    <th align="center">🤖 Android Demo</th>
  </tr>
  <tr>
    <td align="center">
    <img src="./docs/videos/iOS.gif" width="300" alt="Demo GIF" />
    </td>
     <td align="center">
    <img src="./docs/videos/android.gif" width="300" alt="Demo GIF" />
    </td>
  </tr>
</table>

---

> [!NOTE]
>
> S3 multipart **PUT** uploads require a **minimum chunk size of 5 MB**, so this library defaults to splitting files into 5 MB parts to prevent upload issues.
>
> You must implement your own backend endpoint to generate the multipart presigned URLs. Once provided, the library automatically handles uploading each part and storing the returned **ETag** values for you.
> Demo showcases uploading to my Cloudflare R2 Bucket called test-bucket - which you can use to test your integration.
> The files will be automatically deleted after 3 days.

```tsx
const BASE_URL = 'https://api.gauthamvijay.com';
const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
const SINGLE_UPLOAD_URL = `${BASE_URL}/single-upload`;
```

---

## 🧠 Overview

| Feature                           | Implementation                            |
| --------------------------------- | ----------------------------------------- |
| Large file uploads (audio/video)  | Native                                    |
| Multipart / presigned URL uploads | S3-compatible                             |
| Cloudflare R2                     | Tested                                    |
| Backblaze B2                      | Tested                                    |
| S3-compatible storage             | Standard API                              |
| Background uploads                | URLSession.background / ForegroundService |
| Pause/Resume                      | Task suspension                           |
| Cancel                            | Job cancellation                          |
| Network monitoring                | Auto-pause/resume on connection loss      |
| Progress tracking                 | Real-time events (byte-level)             |
| Progress notifications            | Android foreground-service notification   |
| Parallel chunk uploads            | Configurable (default: 3)                 |
| ETag collection                   | Automatic                                 |

---

## 📈 Progress

`upload-progress` fires while bytes leave the device, not only when a chunk finishes:

- **iOS** — `URLSessionTaskDelegate.didSendBodyData`
- **Android** — a streaming OkHttp request body that counts each 64 KB buffer as it is written

`bytesUploaded` = bytes of acknowledged chunks + bytes sent so far for in-flight chunks. Emits are throttled to ~20 Hz (50 ms) and always fire on chunk completion. A single-URL upload (one presigned/unsigned PUT) reports the same smooth progress as a multipart one. `getUploadState()` reflects the same numbers.

Chunks: exactly one per URL. One URL uploads the whole file in one PUT. Several URLs use `partSize = max(ceil(fileSize / urls), 5 MiB)` — S3-compatible services reject non-final parts below 5 MiB — so the file must be larger than `(urls − 1) × 5 MiB`, otherwise `startUpload` rejects with a clear message.

Retries: 3 attempts per chunk with 1 s / 2 s backoff for network errors, 408/429/5xx. Other 4xx (e.g. `403 SignatureDoesNotMatch`) fail immediately. `chunk-failed` is emitted only when a chunk is given up on. Content-Type is sent as `application/octet-stream`; if your presigned URL is signed with a Content-Type, sign it with that value.

---

## ⚙️ Basic Usage

```tsx
import { CloudUploader } from 'react-native-nitro-cloud-uploader';

const createResponse = await fetch(CREATE_UPLOAD_URL, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    uploadId: newUploadId,
    fileSize,
    chunkSize: 5 * 1024 * 1024, // ≥5 MiB, matches the native minimum
  }),
});

CloudUploader.addListener('upload-progress', (e) => {
  console.log(
    `${(e.progress! * 100).toFixed(1)}% — ${e.bytesUploaded}/${
      e.totalBytes
    } bytes`
  );
});

// startUpload(uploadId, filePath, uploadUrls, maxParallel = 3, showNotification = true)
const result = await CloudUploader.startUpload(
  newUploadId,
  filePath,
  uploadUrls,
  3,
  true
);
// result.etags is in part order — pass it to your complete-upload endpoint.
// Single URL: pass [presignedUrl]; the ETag is optional there.
```

---

## 🧩 Supported Platforms

| Platform             | Status             |
| -------------------- | ------------------ |
| **iOS**              | ✅ Fully Supported |
| **Android**          | ✅ Fully Supported |
| **iOS Simulator**    | ✅ Works           |
| **Android Emulator** | ✅ Works           |

### Android Requirements

**Minimum SDK**: API 24 (Android 7.0)

**Required Permissions** (automatically added):

- `INTERNET` - Network uploads
- `ACCESS_NETWORK_STATE` - Network monitoring
- `POST_NOTIFICATIONS` - Progress notifications (Android 13+)
- `FOREGROUND_SERVICE` - Background uploads
- `FOREGROUND_SERVICE_DATA_SYNC` - Data sync service type
- `WAKE_LOCK` - Keep CPU awake during uploads

**Runtime Permission for Android 13+**:

For devices running Android 13+ (API 33+), you must request the `POST_NOTIFICATIONS` permission at runtime to show upload progress notifications:

```tsx
import { PermissionsAndroid, Platform } from 'react-native';

// Request notification permission before starting uploads
if (Platform.OS === 'android' && Platform.Version >= 33) {
  const granted = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
  );

  if (granted === PermissionsAndroid.RESULTS.GRANTED) {
    console.log('Notification permission granted');
  } else {
    console.log(
      'Notification permission denied - uploads will work without notifications'
    );
  }
}
```

> **Note**: The library will gracefully skip notifications if permission is denied. Uploads will continue to work normally.

---

## 🤝 Contributing

Contributions are welcome!

- [Development Workflow](CONTRIBUTING.md#development-workflow)
- [Sending a Pull Request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of Conduct](CODE_OF_CONDUCT.md)

---

## 🪪 License

MIT © [**Gautham Vijayan**](https://gauthamvijay.com)

---

Made with ❤️ and [**Nitro Modules**](https://nitro.margelo.com)
