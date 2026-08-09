# Fix: Could Not Resolve com.arthenica:ffmpeg-kit-https:6.0-2

The original `com.arthenica:ffmpeg-kit` library has been retired and its artifacts were removed from Maven Central in early 2025. This caused the build failure as the requested version `6.0-2` is no longer available.

The project source code already anticipated this eventuality with a comment suggesting a swap to a community fork.

## Proposed Changes

### [app] (:app)

I will migrate the FFmpeg dependency to a community-maintained fork that is available on Maven Central and supports modern Android requirements (including 16KB page sizes for Android 15+).

#### [MODIFY] [build.gradle](file:///F:/coding/potato-livestreamer/android-app/app/build.gradle)
- Update the FFmpeg dependency from `com.arthenica:ffmpeg-kit-https:6.0-2` to `dev.ffmpegkit-maintained:ffmpeg-kit-https:8.1.7`.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the project builds successfully and the dependency is resolved.

### Manual Verification
- None required beyond ensuring the build passes.
