# Oreshik Android build

## Requirements
- JDK 21
- Android SDK (platform 37 and the NDK version declared in `app/build.gradle.kts`)
- Python 3.12 for Chaquopy (`PYTHON` may point to the interpreter)

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The release variant enables R8 and resource shrinking. Core-library desugaring is enabled for Java 17 APIs on the app's supported Android versions. If no release keystore is configured, the release APK is signed with the debug certificate for testing only. Supply `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` Gradle properties for a production signing key.

## Optional runtime packs

Optional packs are not embedded in the APK. Publish a ZIP release asset containing `runtime.json` at its root and all manifest entrypoint files. Add an item to `runtime-catalog.json` with `id`, `name`, `version`, `description`, HTTPS `url`, and the archive's 64-character SHA-256 checksum. The app downloads the archive on demand, verifies the checksum, validates the manifest ID and archive paths, and then installs it in app-private runtime storage.

Runtime archives must be built for Android/Bionic and the device ABI. Linux/glibc binaries are not interchangeable with Android binaries.
