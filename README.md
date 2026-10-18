# Aura Music

Aura Music is an offline, gapless Android music player by anmon.org, developed by weyllk. Built with Jetpack Compose and AndroidX Media3 ExoPlayer, it provides sample-accurate zero-delay track transitions, ID3 tag and artwork extraction, local folder management, interactive swipe-to-queue gestures, multiple home screen widgets, and lock-screen media controls.

---

## What the App Does

- **Sample-Accurate Gapless Playback**: Preloads and pre-buffers upcoming songs in the playback queue so track transitions within an album happen seamlessly with 0ms delay.
- **Universal Offline Audio Support**: Plays local audio files directly from device storage without requiring network access or cloud services. Supported formats include MP3, FLAC (including 24-bit/96kHz), AAC, WAV, OGG, Opus, and M4A.
- **Storage Access Framework (SAF) Folder Management**: Select specific music folders on internal storage or SD cards to scan. Music stays strictly on-device.
- **Embedded ID3 Tag & Artwork Extraction**: Automatically extracts metadata including title, artist, album, genre, track numbers, and embedded APIC artwork. Generates procedural vinyl covers when album artwork is not embedded.
- **Swipe-to-Queue Gestures**: Swipe right on any song in the library to add it to Up Next; swipe left to add to a playlist.
- **Persistent Mini-Player & Full Now Playing Screen**:
  - Mini-player pinned to the bottom of the screen with rotating vinyl animation, track info, and quick playback controls.
  - Expandable full-screen player with high-resolution artwork, audio format badges (e.g. FLAC 24-bit, WAV), scrubbable progress bar, queue reordering, shuffle, and repeat modes.
- **Home Screen Widgets**:
  - **Large (4x2)**: Album art, track title, artist, genre badge, and playback controls.
  - **Medium (4x1 & 3x1)**: Album art, track title, artist name, and controls.
  - **Small (2x1)**: Compact card with album art and play/pause toggle.
  - **Tiny (1x1)**: Dedicated dynamic play/pause button.
- **Lock Screen & Notification Controls**: Foreground `MediaSessionService` integrating with Android's Quick Settings media carousel and system lock-screen player.
- **Multiple Color Themes**: Real-time theme cycle button with persistent preferences across sessions:
  - Obsidian Violet (Dark)
  - Clean Indigo (Light)
  - Emerald & Black
  - Sunset Orange & Black
  - Bubblegum Pink & Light Blue
  - Crimson Red & Clean White
- **Library Organization**: Browsing tabs for Songs, Albums, Artists, Playlists, Favorites, and Folders.
- **M3U Playlist Import**: Import standard M3U and M3U8 playlist files from device storage.

---

## How to Build

### Prerequisites

- **JDK**: Java Development Kit 17 or higher
- **Android SDK**: Android 16 (API Level 36), Build Tools 36.0.0
- **Build System**: Gradle 8.13+ (or project Gradle)

### Build Instructions

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd <project-directory>
   ```

2. **Run Unit & Robolectric Tests**:
   ```bash
   gradle :app:testDebugUnitTest
   ```

3. **Build the Debug APK**:
   ```bash
   gradle assembleDebug
   ```
   The generated APK will be at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. **Build the Release APK**:
   ```bash
   gradle assembleRelease
   ```
   The generated APK will be at:
   `app/build/outputs/apk/release/app-release-unsigned.apk` (or signed if signing credentials are provided).
