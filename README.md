<a href="https://gauthamvijay.com">
  <picture>
    <img alt="react-native-nitro-cloud-uploader" src="./docs/img/banner.png" />
  </picture>
</a>

# react-native-nitro-cloud-uploader (Beta)

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
> - All of my users are on **iOS in the US**, so iOS support is complete.
> - Android support is **not fully implemented yet andd does not work and should not used for now!**.
> - PRs for Kotlin / Android support are absolutely welcome!

---

## 🎥 Demo

<table>
  <tr>
    <th align="center">🍏 iOS Demo</th>
  </tr>
  <tr>
    <td align="center">
  <video height="650" width="300" src="https://github.com/user-attachments/assets/f6c23e68-5e3f-4538-9c78-877208847bfc" controls> </video>
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
const BASE_URL = 'https://api.uploader.com/file-uploader';
const CREATE_UPLOAD_URL = `${BASE_URL}/create-and-start-upload`;
const COMPLETE_UPLOAD_URL = `${BASE_URL}/complete-upload`;
const ABORT_UPLOAD_URL = `${BASE_URL}/abort-upload`;
```

---

## 🧠 Overview

| Feature                           | Support        |
| --------------------------------- | -------------- |
| Large file uploads (audio/video)  | ✅             |
| Multipart / presigned URL uploads | ✅             |
| Cloudflare R2                     | ✅             |
| Backblaze B2                      | ✅             |
| S3-compatible storage             | ✅             |
| Background upload (iOS)           | ✅             |
| Progress tracking                 | ✅             |
| Kotlin Android support            | 🚧 PRs welcome |

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
    chunkSize: 6 * 1024 * 1024, // 6MB chunks
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
