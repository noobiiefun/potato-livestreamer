# Implementation Plan - Merge IRL Livestreamer and Potato Monitor Desk

This plan outlines the steps to merge the "Potato Monitor Desk" client functionality into the "IRL Livestreamer" project, creating a unified app with two operational modes: PC Mode (Monitor Desk) and IRL Mode (Livestreamer).

## User Review Required

> [!IMPORTANT]
> - **Package Naming:** I will consolidate classes under `com.potato.livestreamer` for consistency, using sub-packages `.irl` and `.relay` to separate functionalities.
> - **Resource Conflict Resolution:** Since both projects have `activity_main.xml`, I will rename them to `activity_irl_main.xml` and `activity_relay_main.xml`.
> - **Android Go Compatibility:** The project will target API 26+ and include ABI filters for `armeabi-v7a` and `arm64-v8a` to ensure compatibility with devices like the Xiaomi Redmi A3.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/build.gradle.kts)
- Merge dependencies from Potato Monitor Desk (Media3 ExoPlayer, TsExtractor).
- Update `RootEncoder` to the latest version used in Monitor Desk (2.5.4).
- Ensure `minSdk` is 26 and `abiFilters` are configured.

### Source Code Integration

#### [NEW] [ModeSelectionActivity.kt](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/java/com/potato/livestreamer/ModeSelectionActivity.kt)
- Create a new launcher activity to choose between "PC Mode" and "IRL Mode".

#### [MODIFY] [MainActivity.kt](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/java/com/potato/livestreamer/MainActivity.kt)
- Move to `com.potato.livestreamer.irl.IrlMainActivity`.
- Update layout reference to `activity_irl_main`.

#### [NEW] [RelayMainActivity.kt](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/java/com/potato/livestreamer/relay/RelayMainActivity.kt)
- Port `MainActivity.kt` from Monitor Desk client.
- Update layout reference to `activity_relay_main`.
- Update package name and imports.

#### [NEW] Other ported classes from Monitor Desk
- Port `RelayStreamService`, `LiveStreamService`, `ControlClient`, `TcpDataSource`, `LivePrefs`, and related utility classes to appropriate packages.

### Resources

#### [NEW] [activity_selection.xml](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/res/layout/activity_selection.xml)
- Layout for the mode selection screen.

#### [MODIFY] [activity_irl_main.xml](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/res/layout/activity_irl_main.xml)
- Rename from `activity_main.xml`.

#### [NEW] [activity_relay_main.xml](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/res/layout/activity_relay_main.xml)
- Port and rename from Monitor Desk's `activity_main.xml`.

#### [NEW] Ported resources
- `drawable/potato_logo.png`
- `xml/network_security_config.xml`

### Manifest

#### [MODIFY] [AndroidManifest.xml](file:///F:/coding/potato-livestreamer/IRL%20livestreamer/app/src/main/AndroidManifest.xml)
- Set `ModeSelectionActivity` as the launcher.
- Register all activities and services.
- Combine all required permissions and features.

## Verification Plan

### Automated Tests
- Run `gradle build` to ensure all dependencies are correctly merged and code compiles.

### Manual Verification
- Deploy to the Xiaomi Redmi A3.
- Verify that the selection menu appears correctly.
- Test "IRL Mode": Check camera preview, streaming capability (at 480p), and GPS tracking.
- Test "PC Mode": Verify it can connect to the PC relay (if available) and the UI switches to landscape correctly.
