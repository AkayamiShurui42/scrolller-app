# Scrolller 📱✨

A high-performance, native Android application for **Scrolller.com** built with **Kotlin** and **Jetpack Compose**.

Delivers a modern, full-screen media experience with gesture snapping, high-performance ExoPlayer video streams, density controls, multi-column grid browsing, client-side filters, and offline Bookmarks.

---

## 🌟 Features

1. **🚀 100% Native Jetpack Compose UI**: Smooth 60+ FPS navigation, snapping vertical reels pager, and fluid staggered grid view.
2. **🎬 Seamless Video Auto-Playback**: Powered by AndroidX Media3 / ExoPlayer with automatic viewport playback, loop support, and mute/unmute controls.
3. **🔥 Highest Quality Media Selection**: Automatically parses and sorts multi-resolution source variants, choosing maximum resolution and supporting original/uncompressed assets.
4. **📊 Dual View Modes**:
   - **Full-Screen Media Pager**: Immersive vertical snapping feed with gesture controls, title & subreddit info overlay, and quick action rail.
   - **Gallery Staggered Grid**: Multi-column visual overview for fast thumbnail browsing.
5. **🎛️ Sort & Filters**:
   - Sort by **Best (Hot)**, **Newest**, **Top**, and **Random**.
   - Filter by media type: **All**, **Images**, **Videos**, **GIFs**, **Albums**.
   - Filter by content rating: **All**, **SFW**, **NSFW**.
6. **🔍 Global & Gallery Search**:
   - Search across posts or find subreddits/galleries with instant previews and item counts.
7. **⭐ Offline Bookmarks & Saved Subreddits**:
   - Star favorite media posts or pin custom subreddits for instant access anytime.

---

## 🛠️ Architecture & Tech Stack

- **Language**: Kotlin 2.2
- **UI Framework**: Jetpack Compose (Material 3)
- **Video Engine**: AndroidX Media3 (ExoPlayer)
- **Image & GIF Engine**: Coil Compose
- **Networking**: OkHttp3 with Scrolller GraphQL endpoint integration
- **Serialization**: Kotlinx Serialization JSON
- **Build System**: Gradle 9.3 (Kotlin DSL) with Android Gradle Plugin 9.1
