# Aura Music 🎵

[![Android CI & Release Build](https://github.com/avatavlan/AuraMusic/actions/workflows/android-build-release.yml/badge.svg)](https://github.com/avatavlan/AuraMusic/actions/workflows/android-build-release.yml)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-M3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3%20ExoPlayer-1.5.1-orange.svg)](https://developer.android.com/media/media3)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Aura Music** is an offline, gapless Android music player built with Jetpack Compose and Media3 ExoPlayer. It plays all common audio formats with sample-accurate zero-delay track transitions, ID3 tag extraction, device folder management, interactive swipe-to-queue gestures, Android home screen widgets, and lock-screen media controls.

---

## ✨ Features

- **True 0-Delay Gapless Playback**: Preloads and pre-buffers upcoming songs in the playback queue. Transitions between tracks in an album occur at sample boundaries with 0ms delay.
- **Universal Offline Audio Support**: Plays MP3, FLAC, AAC, WAV, OGG, Opus, and M4A directly from your device storage.
- **Storage Access Framework (SAF) Folder Selection**: Select and manage specific local folders to scan. Music stays strictly on-device without cloud uploads.
- **Embedded ID3 Tag & Artwork Extraction**: Automatically parses title, artist, album, genre, track numbers, and embedded APIC artwork. Generates dynamic procedural vinyl covers for tracks without embedded art.
- **Swipe-to-Queue Gestures**: Swipe right on any song in your library to enqueue it into Up Next; swipe left to add to a playlist.
- **Persistent Mini-Player & Full Now Playing Screen**:
  - Mini-player pinned to the bottom with rotating vinyl animation, title, artist, gapless indicator badge, and playback controls.
  - Full-screen player with high-res artwork, format badges (e.g. FLAC 24-bit, WAV), seekbar, shuffle, and repeat modes.
- **Home Screen Widgets**:
  - **Large (4x2)**: Album art, song title, artist, genre badge, and full controls.
  - **Medium (4x1 & 3x1)**: Album art, song title, artist name, and playback controls.
  - **Small (2x1)**: Compact card with album art and play/pause toggle.
  - **Tiny (1x1)**: Dedicated dynamic play/pause button.
- **Notification Center & Lock Screen Media Controls**: Foreground `MediaSessionService` that connects to Android's Quick Settings media carousel and lock-screen player.
- **Light & Dark Mode**: Obsidian violet dark mode and crisp indigo light mode with real-time toggle.
- **Dedicated Favorites Tab**: One-tap hearting on any track, with instant "Play All Favorites" and "Shuffle".

---

## 🏗️ Architecture & Tech Stack

- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material Design 3 (M3).
- **Audio Engine**: [AndroidX Media3 ExoPlayer](https://developer.android.com/media/media3) with custom `DefaultLoadControl` for pre-buffering and seamless gapless playback.
- **System Media Controls**: `MediaSessionService` and `NotificationCompat.MediaStyle` integration.
- **Image Loading & Cache**: [Coil](https://coil-kt.github.io/coil/) with two-tier LRU memory cache for embedded ID3 artwork.
- **Architecture**: MVVM with Kotlin Coroutines and `StateFlow`.
- **Target SDK**: Android 16 (API 36), Minimum SDK: Android 7.0 (API 24).

---

## 🚀 Building & Running

### Prerequisites
- JDK 17 or higher
- Android SDK (API 36)

### Local Build Commands
```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/AuraMusic.git
cd AuraMusic

# Make gradlew executable (macOS / Linux)
chmod +x gradlew

# Run unit tests
./gradlew testDebugUnitTest

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 License

```text
Copyright 2026 Aura Music Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
