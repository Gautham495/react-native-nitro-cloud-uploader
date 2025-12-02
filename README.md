<a href="https://gauthamvijay.com">
  <picture>
    <img alt="react-native-nitro-cloud-uploader" src="./docs/img/banner.png" />
  </picture>
</a>

# react-native-nitro-cloud-uploader (Beta)

A **React Native Nitro Module** for **reliable, resumable, background-friendly uploads** of large files (audio, video, images, PDFs) to **S3-compatible storage** — built for real production workloads.

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
> - Android support is **not fully implemented yet andd does not work!**.
> - PRs for Kotlin / Android support are absolutely welcome!

---

## 🎥 Demo

<table>
  <tr>
    <th align="center">🍏 iOS Demo</th>
  </tr>
  <tr>
    <td align="center">
      <video src="https://github.com/user-attachments/assets/5fa5c82d-054c-46a2-bfec-4a0b4398576f" height="650" width="300" controls></video>
    </td>
  </tr>
</table>

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
| **AOSP Emulator** | ❗ Not tested                  |

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
