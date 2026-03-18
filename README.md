# 📷 SecureCam — Android IP Camera App

> Real-time P2P camera streaming with AI motion detection, face detection, object detection, night mode, and dual WebRTC/WebSocket transport.

---

## ✨ Features

| Feature | Details |
|---|---|
| 📡 **WebRTC P2P** | Ultra-low latency peer-to-peer streaming via ICE/STUN |
| 🔁 **WebSocket Relay** | Server-relayed JPEG frame streaming (works through strict NATs) |
| ⚠️ **Motion Detection** | Pixel-diff frame analysis with adjustable sensitivity + vibration alerts |
| 🔍 **Object Detection** | Real-time ML Kit object classification with push notifications |
| 👤 **Face Detection** | ML Kit face tracking with landmarks + alert on new face |
| 🌙 **Night Mode** | Manual + auto-brightness detection with exposure compensation |
| ⚡ **Flash on Motion** | Optional torch flash triggered by motion events |
| 🎤 **Audio Streaming** | Bi-directional audio via WebRTC with echo cancellation |
| 🔔 **Push Notifications** | Dedicated channels for motion, AI, and stream events |
| 🔒 **Foreground Service** | Keeps camera streaming alive in background |

---

## 🚀 Build Instructions

### Prerequisites
- **Android Studio Hedgehog or newer** (2023.1.1+)
- **JDK 17+**
- **Android SDK** with API 34
- A physical Android device (API 24+) — emulator won't have camera

### Steps

1. **Open the project:**
   ```
   File → Open → select SecureCam/ folder
   ```

2. **Sync Gradle:**
   Android Studio will prompt to sync — click **Sync Now**

3. **Build APK:**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

4. **Install on device:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   Or use **Run ▶** directly from Android Studio.

---

## 🌐 Server Setup

### Option A — Local LAN (simplest)
Run the server on any machine on the same Wi-Fi as your phones:
```bash
cd signaling-server
npm install
node server.js
# → Listening on port 8080
```
Use `ws://192.168.x.x:8080` as the server URL in WebSocket mode.

### Option B — Deploy to Cloud (Render / Railway / Heroku)
```bash
cd signaling-server
# Push to your git repo
# Set start command: node server.js
# Set PORT env var if needed
```
Use `wss://your-app.onrender.com` as the server URL.

### Option C — Raspberry Pi
```bash
sudo apt install nodejs npm
cd signaling-server && npm install
node server.js
```

---

## 📱 How to Use

### Camera Phone
1. Open SecureCam → tap **📷 Camera Mode**
2. Enter a room code (or tap **Generate**)
3. Choose **WebRTC** (same network) or **WebSocket** (across internet)
4. Tap **START** — the phone will stream and wait for a viewer

### Viewer Phone
1. Open SecureCam → tap **👁 Viewer Mode**
2. Enter the **same room code**
3. Choose the **same connection type**
4. Tap **START** — the live stream will appear automatically

---

## 🤖 AI Features Details

### Motion Detection
- Compares consecutive frames at 160×90 resolution
- Adjustable sensitivity (1–100%) in Settings
- Fires notification + vibration with 2s cooldown
- Displays motion intensity bar on camera screen

### Object Detection (ML Kit)
- Detects people, vehicles, animals, everyday objects
- Shows overlay label on camera screen
- Push notification when high-confidence object detected (5s cooldown per label)
- Sends AI events to viewer over WebSocket relay

### Face Detection
- Tracks face landmarks (eyes, nose, mouth, ears)
- Shows face count overlay
- Fires alert notification when a new face appears
- 10s cooldown between face alerts

### Night Mode
- **Manual:** Toggle from camera controls
- **Auto:** Analyzes frame brightness every 3 seconds
  - Below 60 avg → activates night mode (+3 EV exposure)
  - Above 90 avg → deactivates night mode (auto exposure)
  - 3-frame hysteresis prevents flickering

---

## ⚙️ Configuration

All settings saved in `SharedPreferences`:

| Setting | Default |
|---|---|
| Motion Alerts | ✅ On |
| Face Detection | ✅ On |
| Object Detection | ✅ On |
| Auto Night Mode | ✅ On |
| Audio Streaming | ✅ On |
| Flash on Motion | ❌ Off |
| Motion Sensitivity | 40% |
| Video Quality | Medium (480p) |

---

## 🔧 Customization

### Change STUN/TURN servers
Edit `WebRTCManager.kt`:
```kotlin
private val iceServers = listOf(
    PeerConnection.IceServer.builder("stun:your-stun-server.com:3478").createIceServer(),
    PeerConnection.IceServer.builder("turn:your-turn-server.com:3478")
        .setUsername("user").setPassword("pass").createIceServer()
)
```

### Change default signaling URL
Edit `ConnectionActivity.kt`:
```kotlin
const val DEFAULT_SIGNALING_URL = "wss://your-server.com"
```

---

## 📦 Dependencies

| Library | Purpose |
|---|---|
| `webrtc-sdk:android` | P2P video/audio streaming |
| `okhttp3` | WebSocket relay transport |
| `mlkit:object-detection` | Real-time object detection |
| `mlkit:face-detection` | Face tracking + landmarks |
| `tensorflow-lite` | TFLite model inference |
| `camera:camera2` | CameraX for AI frame analysis |
| `material` | Material 3 UI components |

---

## 📄 License
MIT — free to use, modify, and distribute.
