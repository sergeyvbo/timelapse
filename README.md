# Timelapse

Android app (sideloaded APK) that takes a photo every N minutes during the day, pauses for M hours at night,
uploads each photo to Google Drive over Wi-Fi, and once a day re-renders an MP4 of **all** photos
(`<folder>/timelapse.mp4`). Optionally it also keeps a small preview GIF (`<folder>/timelapse.gif`).
Design notes: [PLAN.md](PLAN.md).

## Build

Requires JDK 17 and the Android SDK (platform 35). On this machine they live in `C:\Java` and `C:\Android\sdk`.

```sh
export JAVA_HOME="/c/Java/jdk-17.0.21+9"     # PowerShell: $env:JAVA_HOME = "C:\Java\jdk-17.0.21+9"
./gradlew :app:testDebugUnitTest            # unit tests (scheduler, GIF encoder)
./gradlew :app:assembleDebug                # -> app/build/outputs/apk/debug/app-debug.apk
```

Install: `C:\Android\sdk\platform-tools\adb install -r app/build/outputs/apk/debug/app-debug.apk`,
or copy the APK to the phone and open it (allow "install unknown apps").

## Google Drive setup (one time)

The app uses the `drive.file` scope, so it can only see files it created itself.

1. Go to <https://console.cloud.google.com>, create a project, and enable **Google Drive API**.
2. **OAuth consent screen**: set User type to *External* and Publishing status to *Testing*, and add your Google account under **Test users**.
   Add the scope `.../auth/drive.file`.
3. **Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `dev.timelapse`
   - SHA-1: the fingerprint of the key that signs the APK. For the debug key:
     `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
4. In the app, tap **Connect Google Drive** and allow access.

In *Testing* mode Google may ask you to re-authorize about once a week. When that happens the app shows a notification;
photos stay on the phone (and in the GIF) until you reconnect.

## Using it

1. Mount the phone and keep it plugged in. Use **Framing → Camera preview** to aim it.
2. Set the interval, the night start and the night length, then tap **Save**.
3. In the **Setup** card, disable battery optimization for the app.
4. Tap **Start**. A permanent notification shows the next shot time.

Notes:
- Shots follow a grid that starts when the night ends. For example, with the night starting at 21:00 for 9 h and a 60-minute interval, shots are at 06:00, 07:00, …, 20:00.
- Shots that are missed by more than 5 minutes (e.g. phone off) are skipped, not caught up.
- After a reboot on Android 11+, the app posts a "Timelapse paused" notification. Tap it to resume, because Android doesn't allow
  camera services to start from the background.
- **Resolution** (1440×1080, 2016×1512 or 4032×3024; swapped for portrait) is the size of the stored photos and of the video.
  If the phone's H.264 encoder can't handle it, the video uses the largest 4:3 size it can (Mi MIX 2: max 4096×2160 landscape,
  so 4032×3024 becomes 2880×2160, or 1620×2160 in portrait).
- Photos are **kept on the phone** (`files/photos`), because they are the video's frames. At 1 photo/hour expect roughly
  0.5 MB (1440), 1 MB (2016) or 3–4 MB (4032) per photo.
- The video is rebuilt from all photos after the last shot of each day (or with **Render video now**), then uploaded,
  replacing the previous `timelapse.mp4` on Drive.
- The preview GIF frame size is fixed by its first frame. It is only updated while the checkbox is on.

## Release signing (optional)

Create `keystore.properties` in the project root:

```
storeFile=timelapse.jks
storePassword=...
keyAlias=timelapse
keyPassword=...
```

Then run `./gradlew :app:assembleRelease`. Register that key's SHA-1 as another Android OAuth client.
Without this file, the release build is signed with the debug key.
