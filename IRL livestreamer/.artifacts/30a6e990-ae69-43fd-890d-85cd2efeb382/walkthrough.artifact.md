# Walkthrough - Integrated IRL & Relay Mode

I have successfully merged the **Potato Monitor Desk Client** into the **IRL Livestreamer** project. The app now serves as a dual-purpose tool for both mobile streaming and PC monitoring.

## Key Changes

### Unified Launcher
- **ModeSelectionActivity:** A new entry point that allows users to choose between **PC Mode** (Relay) and **IRL Mode** (Livestreaming).
- **Consolidated Manifest:** All activities, services, and permissions for both modes are now managed in a single manifest.

### Optimized for Android Go
- **ABI Filters:** Included `armeabi-v7a` and `arm64-v8a` to ensure compatibility with devices like the **Xiaomi Redmi A3**.
- **Memory Management:** IRL Mode is configured to stream at **480p** by default to prevent lag on low-RAM devices.
- **Cache Redirection:** `osmdroid` cache is redirected to the app's internal cache directory to avoid needing broad storage permissions.

### Component Separation
- **IRL Component:** Located in `com.potato.livestreamer.irl`.
- **Relay Component:** Ported and updated in `com.potato.livestreamer.relay`, including the RTMP relay engine and internet speed test functionality.

## Verification Results

### Build Success
- The project compiles successfully with all merged dependencies, including **Media3 ExoPlayer** and the latest **RootEncoder** library.

### Dependency Consolidation
- Updated `RootEncoder` to `2.5.4` across both components for better stability and performance.

> [!TIP]
> To test the **PC Mode**, ensure your PC is running the Potato Monitor Desk server and connected via USB with `adb reverse tcp:9999 tcp:9999` and `adb reverse tcp:8080 tcp:8080` configured.

render_diffs(file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/AndroidManifest.xml)
render_diffs(file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/build.gradle.kts)
