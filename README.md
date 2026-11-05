# Download Master (ڈاؤنلوڈ ماسٹر)

A high-performance, secure, and lightweight Android application engineered for downloading videos and audio from YouTube, TikTok, Facebook, Instagram, Twitter / X, and public web video platforms without requiring any user registration or login.

Part of The Master Sahib Educational & Software Series (www.mastersahib.com).

---

## Key Features

1. **Zero Login Required (100% Privacy Focused)**:
   - Users do not need to sign in, link accounts, or provide credentials.
   - Operates without tracking tokens, cookies, or telemetry.

2. **Multi-Platform Support**:
   - YouTube: Shorts, standard videos (1080p, 720p, 480p, 360p, and MP3 audio).
   - TikTok: High-definition video without watermark, plus audio soundtrack.
   - Facebook: Watch videos, Reels, and public clips in HD and SD resolutions.
   - Instagram: Reels, IGTV, and video posts.
   - Twitter / X: High quality MP4 video variants.
   - Generic Web: Dailymotion, Vimeo, and direct media links.

3. **Instant Resolution & Format Selection**:
   - Pasting a link instantly displays a preview card with video thumbnail, title, author, and available resolutions.
   - Users can choose between multiple video resolutions or extract MP3 audio directly.

4. **Modern, Lightweight & Battery Efficient**:
   - Built natively using Kotlin and Jetpack Compose with Material 3 design.
   - App size under 5 MB with zero unnecessary bloatware or background services.

5. **Android 14 & 15 Scoped Storage Compliant**:
   - Uses Android MediaStore API to save media directly into `/Movies/DownloadMaster` and `/Music/DownloadMaster`.
   - Downloaded media is immediately indexed and available in the phone gallery, Google Photos, and media players.

6. **Built-in Media Player & Gallery**:
   - Integrated ExoPlayer (Media3) allows users to play videos and listen to audio inside the application.
   - Direct sharing to WhatsApp, Facebook, or Bluetooth.

---

## Architecture & Technology Stack

- **Language**: Kotlin 2.0.0
- **UI Toolkit**: Jetpack Compose with Material 3 (BOM 2024.09.00)
- **Target SDK**: Android 15 (API 35), Minimum SDK: Android 8.0 (API 26)
- **Networking**: OkHttp 4.12.0 with streaming chunk downloader
- **Media Engine**: AndroidX Media3 ExoPlayer 1.4.1
- **Image Loading**: Coil 2.7.0
- **Continuous Integration**: GitHub Actions automated APK compilation and release workflow

---

## Building the Application

### Automated CI/CD (GitHub Actions)
Push changes to the repository. The workflow `.github/workflows/build-apk.yml` automatically compiles the project, runs tests, and produces the release APK under GitHub Releases and Artifacts.

### Local Compilation
```bash
./gradlew assembleRelease
```
The compiled APK will be generated at:
`app/build/outputs/apk/release/app-release.apk`
