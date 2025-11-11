# Quick Start Guide

## Prerequisites Setup

### For Android Development:
1. Install Android Studio from https://developer.android.com/studio
2. Open Android Studio and install:
   - Android SDK Platform 34
   - Android SDK Build-Tools
   - Android Emulator (if testing without device)

### For PC Client Development:
1. Install Rust from https://rustup.rs/
2. Install system dependencies:
   
   **Windows:**
   - Install Visual Studio 2019 or later with "Desktop development with C++"
   - Install WebView2 (usually pre-installed on Windows 10/11)
   
   **macOS:**
   ```bash
   xcode-select --install
   ```
   
   **Linux (Ubuntu/Debian):**
   ```bash
   sudo apt update
   sudo apt install libwebkit2gtk-4.0-dev \
       build-essential \
       curl \
       wget \
       libssl-dev \
       libgtk-3-dev \
       libayatana-appindicator3-dev \
       librsvg2-dev
   ```

## Building the Android App

### Option 1: Using Android Studio (Recommended)

1. Launch Android Studio
2. Click "Open an Existing Project"
3. Navigate to and select the `android-app` folder
4. Wait for Gradle sync (first time may take several minutes)
5. Connect your Android device via USB or start an emulator
6. Click the green "Run" button (▶) or press Shift+F10

### Option 2: Command Line Build

```bash
cd android-app

# On macOS/Linux
./gradlew assembleDebug

# On Windows
gradlew.bat assembleDebug
```

The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Building the PC Client

1. Navigate to the pc-client directory:
```bash
cd pc-client
```

2. First time setup - install Tauri CLI:
```bash
cargo install tauri-cli
```

3. Run in development mode:
```bash
cargo tauri dev
```

4. Or build for production:
```bash
cargo tauri build
```

Production builds will be in:
- **Windows**: `src-tauri/target/release/camera-client.exe`
- **macOS**: `src-tauri/target/release/bundle/dmg/`
- **Linux**: `src-tauri/target/release/bundle/appimage/`

## Testing the Application

### Step 1: Set Up Network
- Ensure your Android device and PC are on the same WiFi network
- If using an Android emulator, note that network discovery may not work properly

### Step 2: Run the Android App
1. Open the Camera Server app on your Android device
2. Grant all permissions when prompted:
   - Camera
   - Internet
   - WiFi State
   - Notifications
3. Tap "Start Server"
4. You should see "Server is running"

### Step 3: Run the PC Client
1. Launch the Camera Client on your PC
2. The app should automatically discover your Android device
3. A card will appear showing your camera

### Step 4: Test Features
1. Click "Start Preview" - you should see a live camera feed
2. Click "Capture Photo" - a high-res photo will be saved to your Pictures folder
3. Click "Stop Preview" to stop streaming

## Troubleshooting Quick Fixes

### Android App Won't Start
- Go to Settings → Apps → Camera Server → Permissions
- Enable all permissions manually
- Restart the app

### PC Can't Find Camera
1. Check both devices are on same WiFi:
   - On Android: Settings → WiFi
   - On PC: Check WiFi settings
2. Try disabling VPN if running
3. Check firewall settings (allow port 8080)
4. Restart both apps

### Preview Not Showing
1. Stop and restart the preview
2. Check network connection is stable
3. Move closer to WiFi router if signal is weak

### Build Errors

**Android:**
- Delete `android-app/.gradle` and `android-app/build` folders
- In Android Studio: File → Invalidate Caches / Restart
- Try again

**PC Client:**
```bash
cd pc-client/src-tauri
cargo clean
cd ../..
cargo tauri build
```

## Next Steps

After successfully building and testing:
1. Read the full README.md for detailed information
2. Explore the code to understand the architecture
3. Modify and extend features as needed
4. Create production builds for distribution

## Support

If you encounter issues:
1. Check the Troubleshooting section in README.md
2. Verify all prerequisites are installed correctly
3. Ensure network connectivity between devices
4. Check Android and PC logs for error messages

## Development Notes

- The Android app uses port 8080 by default
- Camera preview is 640x480 at ~20% quality
- Photos are saved with timestamp: `photo_YYYYMMDD_HHMMSS.jpg`
- Service broadcasts as `_mycamapp._tcp` for discovery
