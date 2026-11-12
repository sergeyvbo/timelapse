# Timelapse — Android App Plan

Sideloaded APK (no Play Store). Takes a photo every **N minutes** during the day, pauses for **M hours** at night,
uploads each original photo to **Google Drive**, and keeps a **GIF** of all photos on Drive, appending each new frame.

---

## 1. Key technical decisions

| Concern | Decision | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, single-activity | Current standard, little boilerplate |
| Min / target SDK | minSdk 26 (Android 8), targetSdk 35 | Covers old phones reused as a timelapse cam |
| Camera | CameraX `ImageCapture` (+ optional `Preview` in the UI only) | Works headless from a service, simple API |
| Periodic capture | **Foreground service** (`foregroundServiceType="camera"`) + coroutine scheduler + `AlarmManager.setExactAndAllowWhileIdle` as a wake-up backstop | WorkManager can't go below 15 min and can't use the camera from the background. Android 11+ blocks background camera access unless a camera foreground service is running, and Android 14+ only lets that service start while the app is visible, so the user starts it from the UI and it keeps running |
| Day/night schedule | Pure function `nextCaptureTime(now, settings)` | Easy to unit test; the service just sleeps until the next time |
| Settings | DataStore (Preferences) | Small key/value config |
| Local state | Room: `photos` table (path, takenAt, uploadState, driveFileId, gifAppended) | Queue survives restarts and offline periods |
| Drive auth | Google Identity `AuthorizationClient` with scope **`drive.file`** | Lets the app see only files it created. No Google verification needed for personal use (OAuth consent screen in *Testing* mode with your own account as a test user). Tokens can be fetched silently in the background once you've granted access |
| Drive API | Drive REST v3 over OkHttp (multipart upload for photos, `PATCH uploadType=media` to replace the GIF) | Much lighter than `google-api-services-drive` |
| Uploads | WorkManager `OneTimeWorkRequest` chain with a `NetworkType.CONNECTED` (or UNMETERED, configurable) constraint and exponential backoff | Retries across reboots and network loss automatically |
| GIF | Own streaming GIF89a encoder (NeuQuant quantizer + LZW), built to **append frames to an existing file** | No Android library supports append. See §4 |

---

## 2. Settings (configurable in-app)

- **Day interval N** in minutes (e.g. 1–120, default 5)
- **Night start** as a time of day (default 21:00)
- **Night duration M** in hours (default 9, so capture resumes at 06:00)
- Camera: back/front, JPEG quality, optional AE/AWB lock to reduce flicker (Camera2 interop)
- GIF: frame width (default 480 px), frame delay (default 100 ms)
- Drive folder name (default `Timelapse`)

Fixed behaviors (decided, not settings): uploads run **on Wi-Fi only** (`NetworkType.UNMETERED`), and each local original is
**deleted once Drive confirms its upload**. There is a **single ever-growing GIF**.

Schedule rule: if `now` falls inside [nightStart, nightStart + M), the next shot is at `nightStart + M`. Otherwise it's `lastShot + N`,
clamped so no shot lands inside the night window.

---

## 3. Architecture

```
ui/            Compose screens: Home (status, start/stop, last photo, counters), Settings, Drive account
service/       CaptureService (foreground, camera type), Scheduler (pure logic), WakeAlarmReceiver, BootReceiver
camera/        CameraController (CameraX bound to the service's LifecycleOwner)
data/          SettingsRepository (DataStore), PhotoDao/AppDatabase (Room)
drive/         DriveAuth (AuthorizationClient), DriveClient (REST: ensureFolder, uploadFile, updateFile)
gif/           GifEncoder (append-capable), NeuQuant, LzwEncoder, FrameScaler
work/          UploadPhotoWorker, AppendGifWorker, UploadGifWorker
```

**Flow for each shot:**
1. The scheduler wakes up and takes a partial wakelock. `ImageCapture.takePicture()` writes the JPEG to app storage (`files/photos/yyyy-MM-dd/IMG_HHmmss.jpg`), keeping the original file with EXIF untouched.
2. A Room row is inserted.
3. A unique work chain is enqueued (`APPEND_KEEP`, so workers run one at a time):
   `AppendGifWorker` (downscale, quantize, append the frame to the local GIF) → `UploadPhotoWorker` → `UploadGifWorker` (replace the Drive GIF content).
4. The scheduler computes the next time and sleeps.

The GIF is built locally and incrementally. Drive only ever receives the full file, because Drive has no append API.

---

## 4. GIF details and constraints

- **Append mechanics:** GIF89a = header + logical screen descriptor + NETSCAPE2.0 loop ext + frames + trailer `0x3B`.
  To append: truncate the trailing `0x3B`, write a Graphic Control Extension + Image Descriptor + **local color table** + LZW data, then rewrite `0x3B`.
  Each new frame costs one encode, never a re-encode of the whole animation.
- **Local palette per frame** (NeuQuant, 256 colors) gives better quality as daylight changes than one global palette.
- **Fixed canvas size:** set by the first frame. Later frames are scaled and center-cropped to match.
- **Size budget:** a 480×360 frame is ~100–200 KB. At the planned **1 photo/hour** (~15/day) that's ~2–3 MB/day, ~1 GB/year.
  **Decision: one ever-growing `timelapse.gif`**, re-uploaded over Wi-Fi after every shot. If it grows too large, setting a lower frame width keeps it manageable.
  Because a frame's width is fixed once written, a frame-width change only applies to a new GIF ("Start new GIF" button, which archives the old one on Drive).
- **The local GIF is the source of truth.** Originals are deleted after upload, so they can't be used to rebuild it. Each frame must be appended
  **before** its original is deleted (the work chain order guarantees this).
- Crash safety: write frames to a temp file with fsync, then make one atomic truncate+append. On startup, check that the GIF ends with `0x3B` and repair it if not.
  Track `gifAppended` per photo so a frame is never added twice.

---

## 5. Android reliability checklist

- Permissions: `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` (API 34), `POST_NOTIFICATIONS` (33), `WAKE_LOCK`,
  `SCHEDULE_EXACT_ALARM` (31+), `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`
- Ask to exempt the app from battery optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). This is fine because the app isn't going to the Play Store
- Persistent notification showing: next shot time, count today, pending uploads, Stop action
- Boot: you can't restart a camera FGS from the background on 14+, so `BootReceiver` posts a "Tap to resume timelapse" notification (on older OS versions it restarts the service directly)
- During the night the service stays alive but idle (no camera bound, wakelock released), using only an exact alarm to wake up
- Release the camera between shots (bind → capture → unbind) to save power and heat; or keep it bound if N < 1 min
- Assumption: the phone stays plugged in and fixed in place

---

## 6. Google Cloud / Drive setup (one-time, manual)

1. Create a GCP project and enable **Google Drive API**
2. OAuth consent screen: External, *Testing*, add your Google account as a test user, scope `drive.file`
3. Create an OAuth client of type **Android** with package name + SHA-1 of your **release keystore** (and debug keystore for dev)
4. In the app: sign in once → the app creates the `Timelapse/` folder and stores its id in DataStore

Note: refresh tokens for apps in *Testing* mode can expire after 7 days. `AuthorizationClient` handles this via Google Play services on Android,
but if it ever needs a UI resolution in the background, the app shows a "Re-authorize Drive" notification and keeps queueing uploads until then.

---

## 7. Milestones

**Status (2026-11-11):** milestones 1–5 are implemented and compile, the unit tests (scheduler + GIF) pass, and the debug APK builds.
They haven't been tested on a device yet. Milestone 6 is partly done (boot handling, battery-optimization prompt, re-auth notification).

1. **Skeleton** — Gradle project, Compose, DataStore settings screen, Room DB. *Done when* settings persist.
2. **Scheduler** — pure `nextCaptureTime` + unit tests (midnight crossing, DST, night windows that span midnight).
3. **Capture service** — FGS + CameraX headless capture + notification + wakelock/alarm. *Done when* it shoots every 1 min with the screen off for 2 h.
4. **GIF encoder** — streaming encoder with append + repair, plus JVM unit tests (output decodes correctly, frame count is right after N appends, recovers from a truncated file).
5. **Drive** — auth, folder creation, photo upload, GIF replace, WorkManager retry/offline queue.
6. **Hardening** — boot handling, battery-optimization prompt, re-auth flow, storage cleanup, error log screen.
7. **Release** — signed APK via `./gradlew assembleRelease`, install steps in README.

---

## 8. Decisions

1. **Night**: fixed night start + M hours (no location/sunrise logic).
2. **GIF**: a single ever-growing GIF; the expected rate is ~1 photo/hour.
3. **Upload**: the photo and the GIF are uploaded after every shot, **Wi-Fi only**. Shots taken offline are queued and uploaded in order once Wi-Fi returns.
4. **Retention**: the local original is deleted once its Drive upload succeeds (Drive returns a file id → delete the file → mark the row `UPLOADED`).

5. **Video (2026-11-11 change)**: the main output is now an MP4 (H.264, MediaCodec), re-rendered daily from *all* stored photos.
   Photos are stored at the selected resolution (1440×1080 / 2016×1512 / 4032×3024) and **kept locally** as frames
   (this replaces decision 4). The GIF is optional, for previews only.
