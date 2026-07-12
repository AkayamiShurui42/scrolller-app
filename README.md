# Scrolller Pro 📱✨

A high-performance, ad-free, premium client for **Scrolller.com** designed as a mobile-first Progressive Web App (PWA) and self-contained Android APK. 

Built using modern web standards, it delivers a direct, high-quality, ad-free media experience with swipe gestures, auto-playing video streams, layout density selectors, SFW/NSFW filters, client-side sorting (oldest/newest/best/top), and local offline Favorites.

---

## 🌟 Key Features

1. **🚫 100% Ad-Free & Pop-Up Free**: No banners, no trackers, and no page redirects. All media resources are queried directly from Scrolller's GraphQL endpoint and rendered using native HTML5 image and video players.
2. **🔥 Highest Quality (No Downscaling)**: Automatically sorts the media sources list returned from the server to choose the maximum resolution and prefers unoptimized/raw media assets.
3. **🎭 Immersive Fullscreen Mode**: Double-tap on the media viewer to enter immersive mode, turning the backdrop pitch-black and hiding all browser and app navigation UI.
4. **📊 Density Control**: Instantly toggle grid sizes from **1 to 5 columns** (1 column is list layout, perfect for scrolling through videos; 3-5 columns are optimized for fast gallery browsing).
5. **🎛️ Dynamic Client-Side Sorting**: Supports sorting by:
   - **Best** (aggregates Hot posts)
   - **Newest** (aggregates recent uploads)
   - **Oldest** (accumulates the feed and sorts by creation timestamp ascending)
   - **Top Rated** (highest voted media)
   - **Random**
6. **⭐ Offline Bookmarks & Favorites**: Star your favorite subreddits or save individual posts directly to your sidebar collection without registering or signing in.
7. **🍿 Intersection Video Autoplay**: Autoplays videos (muted, looping) natively as they enter your screen and pauses them immediately as you scroll past them to save resources.

---

## 🚀 Running Locally in Termux

You can run the web client locally on your device using only standard Python:

1. Navigate to the directory:
   ```bash
   cd ~/scrolller-app
   ```
2. Start the local server:
   ```bash
   python3 server.py
   ```
3. Open your mobile browser and go to:
   **`http://localhost:8080`**
   *(To access it from other devices on the same Wi-Fi network, replace `localhost` with your computer/Termux IP address.)*

---

## 📲 Install as a PWA (Recommended)

When running the Python server locally:
1. Open the URL `http://localhost:8080` in **Google Chrome** on your Android device.
2. Tap the three dots (menu) in the browser header.
3. Select **"Add to Home screen"** or **"Install App"**.
4. The client will install as a standalone PWA, enabling fullscreen mode and making it look and function like a native APK.

---

## 🛠️ GitHub Repository & Automated APK Build Setup

To set up the public repository and compile the APK automatically via GitHub Actions:

### 1. Initialize Git and Commit Code
Initialize git and make your first commit locally:
```bash
git init
git checkout -b main
git add .
git commit -m "feat: initial commit for Scrolller Pro ad-free client"
```

### 2. Create the Public GitHub Repository
You can create it via your browser or using `gh` CLI (if authenticated):
```bash
gh repo create scrolller-app --public --source=. --push
```
*If you are using the web interface:*
1. Go to [github.com/new](https://github.com/new).
2. Name the repository `scrolller-app` and check **Public**.
3. Link and push your repository:
   ```bash
   git remote add origin https://github.com/YOUR_GITHUB_USERNAME/scrolller-app.git
   git push -u origin main
   ```

### 3. Verify the Automatic APK Build
Once pushed:
1. Go to the **Actions** tab on your public GitHub repository.
2. You will see the **Build Android APK** workflow running.
3. Once completed (typically ~2-3 minutes), click on the completed run and scroll to the bottom to download the compiled **`scrolller-pro-debug-apk`** artifact!

---

## 🏗️ Folder Structure
- `public/` - Web assets containing HTML5 layout, custom styles, manifest, and service worker.
- `android/` - Native Android project.
- `server.py` - Local Python static file and GraphQL CORS-bypass proxy server.
- `.github/workflows/build-apk.yml` - CI/CD pipeline script to automatically compile the WebView APK.
