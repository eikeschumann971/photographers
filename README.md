# Camera Streaming Application

A mobile camera streaming application that allows Android phones to stream camera previews and capture high-resolution photos, controlled from a PC (Windows/macOS).

## Architecture

This application consists of two main components:

### 1. Android App (Server)
- **Language**: Kotlin
- **Key Features**:
  - Runs as a foreground service to maintain camera and network access
  - Uses CameraX for camera operations (preview and high-res capture)
  - Broadcasts presence using Network Service Discovery (NSD/mDNS)
  - Listens for commands from PC client via socket server
  - Streams low-quality preview frames
  - Captures high-quality photos on demand

### 2. PC Client App (Client)
- **Framework**: Tauri (Rust backend + Web UI)
- **Key Features**:
  - Discovers Android cameras automatically via Zeroconf/mDNS
  - Displays real-time camera previews
  - Controls photo capture remotely
  - Saves high-resolution photos to Pictures folder
  - Supports multiple cameras simultaneously

## Project Structure

```
photographers/
├── android-app/              # Android camera server
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/photographers/cameraserver/
│   │   │   │   ├── MainActivity.kt          # Main UI activity
│   │   │   │   └── CameraServerService.kt   # Foreground service
│   │   │   ├── res/                         # Android resources
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle
│   ├── build.gradle
│   └── settings.gradle
│
└── pc-client/                # PC client application
    ├── src-tauri/            # Rust backend
    │   ├── src/
    │   │   └── main.rs       # Main Tauri application
    │   ├── Cargo.toml
    │   ├── build.rs
    │   └── tauri.conf.json
    └── ui/
        └── index.html        # Web UI
```

## Communication Protocol

The application uses a simple binary protocol over TCP sockets:

### Commands (PC → Android)
- `CMD_START_PREVIEW\n` - Start streaming low-quality preview frames
- `CMD_STOP_PREVIEW\n` - Stop preview stream
- `CMD_TAKE_PHOTO\n` - Capture high-resolution photo

### Frame Format (Android → PC)
```
[Type: 1 byte][Size: 4 bytes][Data: Size bytes]
```

**Frame Types:**
- `P` (0x50) - Preview frame (JPEG, low quality)
- `H` (0x48) - High-resolution photo (JPEG, max quality)

## Building and Running

### Android App

#### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 24 (Android 7.0) or higher
- Kotlin 1.9.0

#### Build Instructions

1. Open Android Studio and select "Open an Existing Project"
2. Navigate to `android-app` directory
3. Wait for Gradle sync to complete
4. Connect your Android device or start an emulator
5. Click "Run" or press Shift+F10

#### Manual Build via Command Line

```bash
cd android-app
./gradlew assembleDebug
# APK will be in app/build/outputs/apk/debug/
```

#### Install APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### PC Client

#### Prerequisites
- Rust 1.70 or later
- Node.js 16+ (for Tauri CLI)
- Platform-specific requirements:
  - **Windows**: Visual Studio 2019+ with C++ tools
  - **macOS**: Xcode Command Line Tools
  - **Linux**: See [Tauri prerequisites](https://tauri.app/v1/guides/getting-started/prerequisites)

#### Build Instructions

1. Install Tauri CLI:
```bash
cargo install tauri-cli
```

2. Navigate to the pc-client directory:
```bash
cd pc-client
```

3. Build and run in development mode:
```bash
cargo tauri dev
```

4. Build for production:
```bash
cargo tauri build
```

The built application will be in `src-tauri/target/release/`.

## Usage

### Step 1: Start the Android App

1. Launch the "Camera Server" app on your Android device
2. Grant all requested permissions (Camera, Internet, WiFi State, Notifications)
3. Tap "Start Server" button
4. The app will display "Server is running" and show the broadcast service name

### Step 2: Start the PC Client

1. Launch the Camera Client application on your PC
2. Ensure both PC and Android device are on the same WiFi network
3. The client will automatically discover and connect to available cameras
4. Each discovered camera appears as a card in the UI

### Step 3: Control the Camera

- Click "Start Preview" to begin streaming the camera feed
- Click "Capture Photo" to take a high-resolution photo
- Photos are automatically saved to your Pictures folder with timestamp
- Click "Stop Preview" to stop the stream and save bandwidth

## Permissions

### Android App Requires:
- **CAMERA** - Access device camera
- **INTERNET** - Network communication
- **ACCESS_WIFI_STATE** - Check WiFi status
- **CHANGE_WIFI_MULTICAST_STATE** - Enable mDNS discovery
- **FOREGROUND_SERVICE** - Run service in foreground
- **FOREGROUND_SERVICE_CAMERA** - Use camera in foreground service
- **POST_NOTIFICATIONS** - Show service notification (Android 13+)

### PC App Requires:
- **File System Access** - Save photos to Pictures folder
- **Network Access** - Connect to cameras

## Troubleshooting

### Android App Issues

**App crashes on startup:**
- Ensure all permissions are granted
- Check Android version is 7.0 or higher
- Verify camera is not in use by another app

**Service stops when screen is off:**
- Check that foreground service is running
- Look for persistent notification showing service is active

**Network discovery not working:**
- Ensure WiFi is enabled and connected
- Check firewall settings allow port 8080
- Verify both devices are on the same network

### PC Client Issues

**No cameras discovered:**
- Ensure Android app is running with server started
- Verify both devices are on same WiFi network
- Check firewall isn't blocking mDNS or port 8080
- Try disabling VPN if enabled

**Preview not showing:**
- Check network connection
- Try stopping and restarting preview
- Verify camera permissions on Android device

**Photos not saving:**
- Check Pictures folder permissions
- Verify sufficient disk space

## Technical Details

### Android Service Architecture

The `CameraServerService` runs as a foreground service to ensure:
- Camera access remains active even when screen is off
- Network server continues accepting connections
- App is not killed by Android's battery optimization

### Network Discovery

The app uses mDNS/Zeroconf (aka Bonjour) for automatic discovery:
- Service type: `_mycamapp._tcp`
- Service name: "My Camera"
- Port: 8080

This allows the PC client to find cameras without manual IP configuration.

### Camera Configuration

**Preview Stream:**
- Resolution: 640x480
- Quality: 20% JPEG compression
- Frame rate: Up to 30 fps (depending on network)

**Photo Capture:**
- Resolution: Maximum supported by device
- Quality: 95% JPEG compression
- Capture mode: CAPTURE_MODE_MAXIMIZE_QUALITY

## Future Enhancements

Potential improvements for future versions:
- Multiple camera selection (front/back)
- Flash control
- Focus and exposure controls
- Video recording
- Encrypted communication
- Authentication mechanism
- Configuration UI for quality/resolution settings
- Photo gallery view in PC client
- Support for iOS devices

## License

This project is provided as-is for educational and personal use.

## Contributing

Contributions are welcome! Please ensure:
- Code follows existing style conventions
- Android code uses Kotlin best practices
- Rust code passes `cargo clippy` and `cargo fmt`
- Test on both Android and PC platforms before submitting