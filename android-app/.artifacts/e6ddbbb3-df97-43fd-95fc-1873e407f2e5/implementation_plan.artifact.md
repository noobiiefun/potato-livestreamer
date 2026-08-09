# Implementation Plan - Fix Warnings and Errors

The project currently suffers from a critical build error due to a retired dependency and several sub-optimal code patterns in `MainActivity.kt`. Additionally, the layout file `activity_main.xml` reports multiple validation errors that need to be addressed.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle](file:///F:/coding/potato-livestreamer/android-app/app/build.gradle)
- Update the `ffmpeg-kit` dependency to a community-maintained fork (`com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1`). The original `com.arthenica` repository was retired and its binaries removed from Maven Central in April 2025.

### UI and Resources

#### [MODIFY] [activity_main.xml](file:///F:/coding/potato-livestreamer/android-app/app/src/main/res/layout/activity_main.xml)
- Standardize the XML structure and ensure all necessary namespaces (`android`, `app`, `tools`) are correctly declared to resolve analyzer errors.

### App Logic

#### [MODIFY] [MainActivity.kt](file:///F:/coding/potato-livestreamer/android-app/app/src/main/java/com/potato/livestreamer/MainActivity.kt)
- **Migrate to ViewBinding**: Since `viewBinding` is already enabled in `build.gradle`, I will replace `findViewById` calls with binding properties. This is a best practice and avoids potential null-safety warnings.
- **Improve Lifecycle Management**: Implement `onDestroy()` to ensure that the `ControlServer` thread is stopped and any active `FFmpegSession` is cancelled when the activity is destroyed. This prevents memory leaks and background processes from persisting unexpectedly.
- **Refine FFmpeg Callbacks**: Ensure lambdas are correctly typed and handle potential nullability in statistics.

## Verification Plan

### Automated Tests
- Run `gradlew app:assembleDebug` to verify the new dependency resolves and the project builds successfully.
- Run `analyze_file` on `MainActivity.kt` and `activity_main.xml` after changes to confirm all errors and warnings are resolved.

### Manual Verification
- Verify that the UI elements are correctly bound and the "Go LIVE" button enables/disables as expected in the IDE's preview or by deploying to a device.
