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
> - It works great with **multipart presigned URLs** for:
>   - Cloudflare R2
>   - Backblaze B2
>   - Any S3-compatible bucket
> - I haven't tested AWS S3 yet, but it should work without changes.
>
> If you need mobile uploads of **huge files** to S3-compatible storage, this library gives you everything you need out of the box.

---

## 📦 Installation

```bash
npm install react-native-nitro-cloud-uploader react-native-nitro-modules
```

> [!IMPORTANT]
>
> - Tested only for React Native 0.81.0 and above. PRs welcome for lower RN versions to make it work and stable for lower versions.

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
    <img src="./docs/videos/Android.gif" width="300" alt="Demo GIF" />
    </td>
  </tr>
</table>

---

> [!NOTE]
>
> S3 multipart **PUT** uploads require a **minimum chunk size of 5 MB**, so this library defaults to splitting files into 5 MB parts to prevent upload issues.
>
> You must implement your own backend endpoint to generate the multipart presigned URLs. Once provided, the library automatically handles uploading each part and storing the returned **ETag** values for you.
> Demo showcases uploading to cloudflare R2 Bucket

```tsx
const BASE_URL = 'https://your-api.workers.dev';
const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
const SINGLE_UPLOAD_URL = `${BASE_URL}/single-upload`;
```

---

## 🧠 Overview

| Feature                          | Support        |
| -------------------------------- | -------------- |
| Large file uploads (audio/video) | ✅             |
| S3-compatible storage            | ✅             |
| Background upload (iOS)          | ✅             |
| Progress tracking                | ✅             |
| Kotlin Android support           | 🚧 PRs welcome |

## 🌍 S3-Compatible Provider Support

This library relies on true S3-style multipart uploads:

- 5MB+ chunked uploads
- Presigned `PUT` URLs
- `UploadId` + `partNumber`
- `ETag` collection
- `CompleteMultipartUpload`

Because of this, only providers with **full S3 API compatibility** work reliably.

| Provider               | Works? | Reason                                                   |
| ---------------------- | ------ | -------------------------------------------------------- |
| AWS S3                 | ✅     | Native S3 API with complete multipart support.           |
| Cloudflare R2          | ✅     | Fully S3-compatible including multipart uploads + ETags. |
| Backblaze B2           | ✅     | Implements S3 API including multipart operations.        |
| Wasabi                 | ✅     | 100% S3-compatible; no changes needed.                   |
| DigitalOcean Spaces    | ⚠️     | Mostly compatible; some multipart quirks.                |
| Linode/Akamai          | ✅     | Full S3 API implementation.                              |
| Vultr                  | ⚠️     | Partial multipart edge-case issues.                      |
| MinIO                  | ✅     | Perfect for dev/self-hosted setups.                      |
| Oracle/Alibaba/Tencent | ⚠️     | S3 mode works but not fully tested.                      |
| Google Cloud Storage   | ❌     | Not S3-compatible; different API.                        |
| Azure Blob             | ❌     | Uses BlockBlob API; no multipart S3 support.             |
| Firebase Storage       | ❌     | Built on GCS; no S3 API.                                 |
| Supabase Storage       | ❌     | Not S3; exposes plain HTTP upload endpoints.             |
| Cloudinary             | ❌     | Proprietary API; no multipart presigned URLs.            |

---

## ⚙️ Basic Usage

```tsx
import CloudUploader from 'react-native-nitro-cloud-uploader';

const createResponse = await fetch(CREATE_UPLOAD_URL, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    uploadId: newUploadId,
    fileSize,
    chunkSize: 6 * 1024 * 1024, // 6MB chunks for safe chunk uploads
  }),
});

await CloudUploader.startUpload(newUploadId, filePath, uploadUrls, 3, true);
```

---

## 🧩 Supported Platforms

| Platform          | Status                         |
| ----------------- | ------------------------------ |
| **iOS**           | ✅ Fully Supported             |
| **Android**       | 🚧 Does not work (PRs welcome) |
| **iOS Simulator** | ✅ Works                       |
| **AOSP Emulator** | 🚧 Does not work (PRs welcome) |

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
