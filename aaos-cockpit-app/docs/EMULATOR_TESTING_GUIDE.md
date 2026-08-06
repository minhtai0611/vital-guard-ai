# Hướng Dẫn Vận Hành Emulator AAOS End-to-End
### Dành cho người mới bắt đầu với `aaos-cockpit-app`

**Phạm vi tài liệu:** hướng dẫn này đưa một người *chưa từng đụng vào máy này* đi từ con số 0 đến việc tự tay build APK, cài lên Android Automotive OS (AAOS) Emulator, và xác nhận bằng mắt rằng pipeline nhận diện khuôn mặt (MediaPipe FaceLandmarker) đang chạy thật trên thiết bị — không phải chạy trên giấy.

**Không thuộc phạm vi:** kiến trúc tổng thể CarSky/DMS Container/VHAL (xem `VITAL_GUARD_AI_E2E_GUIDE.md` ở gốc repo và root `CLAUDE.md`). Tài liệu này chỉ nói về **một việc**: làm sao chạy và kiểm thử module Android này trên máy phát triển cục bộ.

---

## Mục lục

1. [Bức tranh toàn cảnh trong 60 giây](#1-bức-tranh-toàn-cảnh-trong-60-giây)
2. [Điều kiện tiên quyết](#2-điều-kiện-tiên-quyết)
3. [Giai đoạn A — Định vị công cụ trên máy của bạn](#3-giai-đoạn-a--định-vị-công-cụ-trên-máy-của-bạn)
4. [Giai đoạn B — Build APK](#4-giai-đoạn-b--build-apk)
5. [Giai đoạn C — Khởi động Emulator & cài APK](#5-giai-đoạn-c--khởi-động-emulator--cài-apk)
6. [Giai đoạn D — Chuẩn bị video kiểm thử (Replay Spike)](#6-giai-đoạn-d--chuẩn-bị-video-kiểm-thử-replay-spike)
7. [Giai đoạn E — Chạy & xác nhận bằng mắt](#7-giai-đoạn-e--chạy--xác-nhận-bằng-mắt)
8. [Đọc hiểu màn hình kết quả](#8-đọc-hiểu-màn-hình-kết-quả)
9. [Bảng tra lỗi thường gặp](#9-bảng-tra-lỗi-thường-gặp)
10. [Giới hạn đã biết — đừng báo bug những thứ này](#10-giới-hạn-đã-biết--đừng-báo-bug-những-thứ-này)
11. [Phụ lục: Cheat sheet lệnh nhanh](#11-phụ-lục-cheat-sheet-lệnh-nhanh)

---

## 1. Bức tranh toàn cảnh trong 60 giây

Toàn bộ quy trình kiểm thử module này đi qua 5 trạm, theo đúng thứ tự:

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
│  A. Định vị │ ──▶ │  B. Build   │ ──▶ │ C. Emulator  │ ──▶ │ D. Chuẩn bị │ ──▶ │ E. Chạy &   │
│  JDK / SDK  │     │  APK release│     │  + cài APK   │     │ video test  │     │ xác nhận    │
└─────────────┘     └─────────────┘     └──────────────┘     └─────────────┘     └─────────────┘
```

Điểm mấu chốt cần hiểu trước khi bắt đầu: máy này **không có `adb`/`java` sẵn trên PATH của terminal** — cả hai đều nằm ở những thư mục cài đặt cụ thể (Android Studio, Android SDK) mà bạn phải tự tìm ra trước, chứ không gõ lệnh là chạy ngay được. Giai đoạn A giải quyết đúng việc đó.

---

## 2. Điều kiện tiên quyết

| Thành phần | Bắt buộc? | Vì sao |
|---|---|---|
| Android Studio (bất kỳ bản mới) | ✅ | Đi kèm JDK (`jbr/`) và thường kèm Android SDK |
| Android SDK + Platform Tools (`adb`) | ✅ | Cài kèm Android Studio hoặc cài riêng |
| AAOS Emulator (AVD) đã tạo sẵn, camera trước = `emulated` | ✅ | Nơi APK được cài và chạy |
| Python 3 + package `av` (`pip install av`) | Chỉ khi cần transcode video test | Xem Giai đoạn D |
| Git Bash / PowerShell | ✅ | Chạy các lệnh trong tài liệu này |

> **Không cần** cài Java hay adb riêng theo kiểu "global install" — Giai đoạn A chỉ đi *tìm* những gì Android Studio đã có sẵn.

---

## 3. Giai đoạn A — Định vị công cụ trên máy của bạn

### 3.1. Tìm JDK (`JAVA_HOME`)

Gradle cần một JDK để build. Nếu bạn gõ thử:

```bash
./gradlew.bat assembleRelease
```

và nhận lỗi:

```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

— đừng đi cài JDK riêng. Android Studio luôn có JDK đóng gói sẵn (**JBR — JetBrains Runtime**) ngay trong thư mục cài đặt của nó, tên thư mục con là `jbr`. Đi tìm nó:

```bash
# Ví dụ tìm trên toàn ổ D: (đổi ổ đĩa nếu Android Studio ở nơi khác)
find "/d" -maxdepth 3 -iname "javac.exe" 2>/dev/null
```

Trên máy này, đường dẫn tìm được là:

```
D:\AndroidStudio\jbr\bin\javac.exe
```

Vậy `JAVA_HOME` = `D:/AndroidStudio/jbr` (thư mục **cha** của `bin/`, không phải `bin/` chính nó).

### 3.2. Tìm `adb`

Tương tự, `adb.exe` nằm trong `platform-tools/` của Android SDK:

```bash
find "/c/Users/<username>/AppData/Local/Android" -maxdepth 3 -iname "adb.exe" 2>/dev/null
find "/d" -maxdepth 4 -iname "adb.exe" 2>/dev/null
```

> Nếu tìm ra **nhiều hơn một** `adb.exe` (ví dụ máy này có cả `D:/AndroidSDK/platform-tools` và `D:/dev-tools/android-sdk/platform-tools`) — không sao, chúng đều nói chuyện với cùng một `adb server` chạy nền, dùng cái nào cũng được. Chạy `adb devices` ở cả hai để xác nhận cả hai đều thấy emulator đang chạy.

### 3.3. Ghi nhớ hai biến này

Trong mọi lệnh ở các phần sau, thay `<JAVA_HOME>` và `<ADB>` bằng đường dẫn thật bạn vừa tìm ra. Ví dụ trên máy này:

```bash
export JAVA_HOME="D:/AndroidStudio/jbr"
ADB="/d/AndroidSDK/platform-tools/adb.exe"
```

---

## 4. Giai đoạn B — Build APK

Từ thư mục `aaos-cockpit-app/`:

```bash
export JAVA_HOME="D:/AndroidStudio/jbr"      # theo Giai đoạn A
cd /d/StudioProjects/vital-guard-ai/aaos-cockpit-app
./gradlew.bat assembleRelease -q
```

- `-q` (quiet) chỉ in lỗi ra, không làm ngộp màn hình bằng log thành công dài dòng.
- Build **release** (không phải debug) — vì `signingConfig` của project này trỏ về debug-keystore ngay cả ở `release` build type (để `adb install` chạy được không cần ký thủ công), nên **không cần** `assembleDebug`.
- Nếu build thành công, bạn sẽ không thấy gì in ra (im lặng = thành công, đúng kiểu Unix).

**APK kết quả nằm ở:**

```
aaos-cockpit-app/app/build/outputs/apk/release/app-release.apk
```

Nếu build lỗi vì XML: lỗi phổ biến nhất khi sửa layout tay là gõ `--` (hai dấu gạch ngang liên tiếp) *bên trong* một comment XML (`<!-- ... -->`) — XML parser coi đó là lỗi cú pháp comment, không phải lỗi logic. Sửa bằng cách thay `--` bằng `:` hoặc xuống dòng khác.

---

## 5. Giai đoạn C — Khởi động Emulator & cài APK

### 5.1. Xác nhận Emulator đang chạy và được nhận diện

```bash
export MSYS_NO_PATHCONV=1
"$ADB" devices
```

Kết quả mong đợi:

```
List of devices attached
emulator-5554	device
```

Nếu dòng này không xuất hiện: mở Android Studio → Device Manager → khởi động AVD AAOS trước, đợi màn hình cockpit hiện lên đầy đủ (không phải màn hình boot logo), rồi thử lại.

### 5.2. Cài APK

```bash
"$ADB" install -r "D:/StudioProjects/vital-guard-ai/aaos-cockpit-app/app/build/outputs/apk/release/app-release.apk"
```

`-r` = reinstall, giữ lại dữ liệu app nếu đã cài trước đó (an toàn để chạy lại nhiều lần).

> ⚠️ **Cạm bẫy Windows/Git-Bash quan trọng:** nếu bạn đặt `MSYS_NO_PATHCONV=1` (cần thiết để đường dẫn *trên thiết bị* như `/data/local/tmp` không bị Git Bash tự "dịch" thành đường dẫn Windows), thì bạn phải viết đường dẫn *trên máy Windows* (đường dẫn local, ví dụ file APK) theo dạng gốc Windows (`D:/StudioProjects/...`), **không phải** dạng Unix (`/d/StudioProjects/...`). Biến này tắt việc dịch đường dẫn cho **tất cả** tham số, kể cả những tham số bạn không muốn nó ảnh hưởng — nếu quên, `adb` sẽ báo "No such file or directory" dù file rõ ràng tồn tại.

---

## 6. Giai đoạn D — Chuẩn bị video kiểm thử (Replay Spike)

### 6.1. Vì sao cần bước này?

Trên máy phát triển x86_64 (không phải hardware thật arm64 của Skycraft VM), CameraX **chưa** bind được camera trước của emulator (`IllegalArgumentException: Provided camera selector unable to resolve a camera` — lỗi riêng, xem mục 10). Vì vậy, `MainActivity` có một cơ chế tạm ("Replay Spike"): đọc file video `.mp4` sẵn có trên thiết bị, giải mã từng khung hình, và đẩy trực tiếp vào MediaPipe — hoàn toàn không đi qua camera. Đây là cách duy nhất hiện tại để kiểm thử pipeline nhận diện với video thật trên máy phát triển.

### 6.2. Yêu cầu bắt buộc về codec của video

**Đây là điểm dễ vướng nhất — đọc kỹ trước khi mất thời gian debug.**

Android (`MediaMetadataRetriever`) chỉ giải mã được các file H.264 ở profile tiêu chuẩn:

| Thuộc tính | ✅ Dùng được | ❌ Không dùng được |
|---|---|---|
| Profile | `Baseline` / `Main` / `High` | `High 4:4:4 Predictive` |
| Pixel format | `yuv420p` | `yuv444p` |

Video ở profile `High 4:4:4 Predictive`/`yuv444p` (thường gặp ở file quay bằng thiết bị chuyên dụng/archival) sẽ khiến **mọi** timestamp giải mã ra lỗi:

```
MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer
```

— lỗi này xảy ra ở **100% khung hình**, không phải lỗi lẻ tẻ. Nếu bạn thấy lỗi này tràn log, đừng nghi ngờ code trước — hãy kiểm tra codec của file trước tiên.

### 6.3. Kiểm tra codec của video

```bash
python -c "
import av
inp = av.open(r'D:/path/to/your_video.mp4')
s = inp.streams.video[0]
print('profile:', s.profile)
print('pix_fmt:', s.pix_fmt)
"
```

Nếu chưa có package `av`: `pip install av` (dùng đúng interpreter `python`, không phải `python3`, nếu máy có nhiều bản Python cài song song).

### 6.4. Transcode nếu codec không tương thích

```python
import av

input_path = r'D:/path/to/your_video.mp4'
output_path = r'D:/path/to/your_video_android_compat.mp4'

inp = av.open(input_path)
in_stream = inp.streams.video[0]

out = av.open(output_path, 'w')
out_stream = out.add_stream('libx264', rate=in_stream.average_rate)
out_stream.width = in_stream.width
out_stream.height = in_stream.height
out_stream.pix_fmt = 'yuv420p'
out_stream.options = {'profile': 'high', 'crf': '20'}

for frame in inp.decode(in_stream):
    frame = frame.reformat(format='yuv420p')
    for packet in out_stream.encode(frame):
        out.mux(packet)
for packet in out_stream.encode():
    out.mux(packet)

out.close()
inp.close()
```

Việc này giữ nguyên nội dung, độ dài, số khung hình — chỉ đổi cách mã hóa pixel.

### 6.5. Đẩy video vào thiết bị

Đây là bước có một cạm bẫy kiến trúc riêng của AAOS, không phải lỗi thường gặp trên Android thường:

> **AAOS Emulator chạy app dưới một user profile phụ (uid 10)**, nghĩa là đường dẫn `getExternalFilesDir()` thật của app (`/storage/emulated/10/...`) bị cách ly ở tầng mount-namespace (FUSE) — **`adb root` cũng không đụng được vào**. Đường dẫn duy nhất `adb push` có thể ghi tới *và* app có thể đọc được là `/data/local/tmp/` — nhưng mặc định SELinux (`enforcing`) cũng cấm domain `untrusted_app` đọc file kiểu `shell_data_file` ở đó, nên cần tạm chuyển SELinux sang `permissive`.

Thực hiện theo đúng thứ tự:

```bash
export MSYS_NO_PATHCONV=1
"$ADB" root
"$ADB" shell setenforce 0
"$ADB" push "D:/path/to/your_video_android_compat.mp4" /data/local/tmp/replay_test.mp4
"$ADB" shell chmod 644 /data/local/tmp/replay_test.mp4
```

**Tên file phải đúng chính xác `replay_test.mp4`** — đây là tên `MainActivity.kt` (`REPLAY_FILE_NAME`) đang hard-code tìm kiếm.

Xác nhận file đã nằm đúng vị trí và đúng kích thước:

```bash
"$ADB" shell ls -la /data/local/tmp/
```

---

## 7. Giai đoạn E — Chạy & xác nhận bằng mắt

```bash
export MSYS_NO_PATHCONV=1
"$ADB" logcat -c                                          # xóa log cũ cho sạch
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity   # mở app
```

App sẽ tự động phát hiện file `/data/local/tmp/replay_test.mp4` và bắt đầu giải mã + phân tích ngay khi `onCreate()` chạy — không cần bấm gì thêm trên UI.

### 7.1. Theo dõi qua log (tùy chọn, để debug)

```bash
"$ADB" logcat -s MainActivity
```

Log thành công trông như sau — mỗi khung hình cho ra một cặp giá trị blink + ma trận xoay đầu:

```
D MainActivity: Replay spike: duration=184140ms, sampling every 1s
D MainActivity: eyeBlinkLeft=0.19621399 eyeBlinkRight=0.09297332
D MainActivity: facialTransformationMatrix=[0.9821591, 0.042451385, ...]
...
D MainActivity: Replay spike: finished, fed 185 frames
```

`fed 185 frames` xuất hiện = chạy hết video, không crash giữa đường.

### 7.2. Xác nhận bằng mắt trên màn hình emulator (bước quan trọng nhất)

Đừng chỉ tin vào log — **nhìn trực tiếp màn hình emulator**, hoặc chụp lại để lưu chứng cứ:

```bash
"$ADB" exec-out screencap -p > "D:/StudioProjects/vital-guard-ai/aaos-cockpit-app/screenshot.png"
```

Chụp 2 lần cách nhau khoảng 20-30 giây để thấy sự thay đổi trạng thái rõ ràng (video ~185 khung hình xử lý xong trong dưới một phút, nhanh hơn nhiều so với thời lượng thật của video — xem mục 10).

---

## 8. Đọc hiểu màn hình kết quả

Khi mọi thứ chạy đúng, màn hình emulator hiển thị như sau:

```
┌──────────────────────────────────┐
│                                   │
│     [ Khung hình video thật ]    │  ← replayFramePreview: khung hình
│      (mặt người lái đang          │     MediaPipe đang phân tích tại
│       được camera ghi lại)        │     đúng thời điểm đó
│                                   │
│         SPIKE: EYES OPEN          │  ← trạng thái suy ra từ blendshape
│                                   │     (KHÔNG phải PERCLOS thật)
│   PERCLOS: 0.000                  │  ← luôn 0 trong bản spike này —
│   Eye open prob: 0.678            │     PERCLOS thật chưa được tính,
│   Head pitch: 0.0°                │     đây là điểm cần lưu ý, không
│   Receiving trigger: true         │     phải bug
│   Last gateway action: REVERTED   │
│                                   │
└──────────────────────────────────┘
```

Diễn giải từng dòng:

| Trường | Ý nghĩa | Lưu ý |
|---|---|---|
| Khung hình video | Đúng frame đang được đưa vào FaceLandmarker | Nếu ô này **đen** → xem mục 10.1 |
| `SPIKE: EYES OPEN/CLOSING` | Suy ra trực tiếp từ điểm blendshape `eyeBlinkLeft`/`eyeBlinkRight` trung bình | Không phải state machine FSM thật (`DrowsinessController`), chỉ là ngưỡng đơn giản `> 0.5` cho mục đích spike |
| `PERCLOS: 0.000` | Luôn bằng 0 | `DrowsinessScoreCalculator.kt` (port PERCLOS thật) **chưa được xây** — đây là việc còn tồn đọng, không phải giá trị bị lỗi |
| `Eye open prob` | `1 - avgBlink`, giá trị thật lấy từ model | Đây là số liệu thật duy nhất đáng tin trong bản spike |
| `Receiving trigger: true` | App đang nhận dữ liệu (từ spike, không phải từ Container Node thật) | |
| `Last gateway action` | Hành động Climate/Voice gần nhất | Không thay đổi trong lúc chạy spike vì spike chưa nối vào `DrowsinessController`/gateway thật |

---

## 9. Bảng tra lỗi thường gặp

| Thông báo lỗi | Nguyên nhân thật | Cách xử lý |
|---|---|---|
| `JAVA_HOME is not set and no 'java' command could be found` | Không có JDK trên PATH | Tìm `jbr/bin/javac.exe` trong thư mục cài Android Studio (mục 3.1), set `JAVA_HOME` |
| `adb: command not found` | `adb` không nằm trong PATH của terminal | Gọi bằng đường dẫn đầy đủ tới `platform-tools/adb.exe` (mục 3.2) |
| `The string "--" is not permitted within comments` (build XML) | Gõ `--` bên trong comment `<!-- -->` của layout XML | Đổi `--` thành `:` hoặc dấu khác |
| `adb push`/`adb install`: *No such file or directory* | `MSYS_NO_PATHCONV=1` khiến đường dẫn local Windows bị hiểu sai dạng Unix | Viết đường dẫn local theo dạng `D:/...`, không phải `/d/...`, khi biến này đang set |
| `Permission denied` khi ghi vào `/storage/emulated/10/...` (dù đã `adb root`) | AAOS Emulator chạy app dưới user profile phụ, thư mục này bị cách ly FUSE, root không vượt qua được | Dùng `/data/local/tmp/` thay thế (mục 6.5) |
| `UnsupportedOperationException: bitmap must use ARGB_8888 config` | `MediaMetadataRetriever.getFrameAtTime()` trả `RGB_565` trên thiết bị này, MediaPipe yêu cầu `ARGB_8888` nghiêm ngặt | Đã xử lý trong code (`bitmap.copy(Bitmap.Config.ARGB_8888, false)`) — nếu vẫn thấy lỗi này, kiểm tra bạn đang chạy đúng APK mới build |
| `MediaMetadataRetrieverJNI: getFrameAtTime: videoFrame is a NULL pointer` (mọi timestamp) | Video ở profile `High 4:4:4 Predictive`/`yuv444p`, Android không giải mã được | Transcode sang `High`/`yuv420p` (mục 6.3–6.4) |
| `UnsatisfiedLinkError: dlopen failed: library "libmediapipe_tasks_vision_jni.so" not found` | Version `tasks-vision` cũ (0.10.14/0.10.21) không có bản native cho x86_64 | Đã fix — `build.gradle` hiện dùng `tasks-vision:1.0.0`. Nếu vẫn gặp, kiểm tra `build.gradle` chưa bị revert |
| `run-as: package not debuggable` | Đang thử `run-as` trên **release** build (không debuggable) | Không dùng `run-as`; dùng `/data/local/tmp` + `adb shell` trực tiếp |

---

## 10. Giới hạn đã biết — đừng báo bug những thứ này

### 10.1. Ô `cameraPreview` luôn ẩn/đen

**Không phải bug.** `CameraX.bindToLifecycle()` với `CameraSelector.DEFAULT_FRONT_CAMERA` hiện luôn ném:

```
IllegalArgumentException: Provided camera selector unable to resolve a camera for the given use case
```

trên AVD phát triển này, dù `config.ini` của AVD đã đặt `hw.camera.front=emulated`. Lỗi này **độc lập, chưa được root-cause**, và tách biệt hoàn toàn khỏi pipeline Replay Spike. Layout hiện đã ẩn ô này (`visibility="gone"`) để không hiển thị một ô đen vô nghĩa; view vẫn được giữ trong XML để CameraX có surface sẵn sàng bind ngay khi bug này được sửa trong tương lai.

### 10.2. Video "phát" nhanh hơn thời lượng thật

`detectAsync()` không chờ đúng nhịp thời gian thật của video — nó xử lý các khung hình lấy mẫu nhanh nhất có thể. Một video dài 184 giây có thể xử lý xong toàn bộ trong chưa đầy một phút. Điều này **không** phản ánh hiệu năng thời gian thực của MediaPipe trên hardware thật — chỉ là đặc điểm của cách spike này lấy mẫu, không phải kết quả benchmark.

### 10.3. Replay Spike không phải kiến trúc cuối cùng

`runReplayFileSpikeIfPresent()` trong `MainActivity.kt` là một **test hook tạm thời**, viết ra để lấy giá trị blendshape/ma trận xoay đầu thật từ video thật, khi chưa build xong kiến trúc `DetectionBackendMode`/`ReplayFileFrameSource` chính thức theo kế hoạch migration. Không mở rộng thêm tính năng lên trên spike này — khi `ReplayFileFrameSource` thật được xây, code này sẽ được thay thế, không phải được giữ lại vĩnh viễn.

---

## 11. Phụ lục: Cheat sheet lệnh nhanh

Toàn bộ chu trình từ đầu đến cuối, giả định bạn đã có video test hợp lệ (`replay_test.mp4`, đúng codec):

```bash
# --- Giai đoạn A: set biến (đổi đường dẫn cho đúng máy của bạn) ---
export JAVA_HOME="D:/AndroidStudio/jbr"
export MSYS_NO_PATHCONV=1
ADB="/d/AndroidSDK/platform-tools/adb.exe"

# --- Giai đoạn B: build ---
cd /d/StudioProjects/vital-guard-ai/aaos-cockpit-app
./gradlew.bat assembleRelease -q

# --- Giai đoạn C: cài ---
"$ADB" devices
"$ADB" install -r "D:/StudioProjects/vital-guard-ai/aaos-cockpit-app/app/build/outputs/apk/release/app-release.apk"

# --- Giai đoạn D: đẩy video (chỉ cần làm 1 lần, video ở lại trên thiết bị) ---
"$ADB" root
"$ADB" shell setenforce 0
"$ADB" push "D:/path/to/replay_test.mp4" /data/local/tmp/replay_test.mp4

# --- Giai đoạn E: chạy & xác nhận ---
"$ADB" logcat -c
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
sleep 5
"$ADB" exec-out screencap -p > screenshot.png
"$ADB" logcat -d -s MainActivity | tail -20
```

---

*Tài liệu này mô tả trạng thái tại 2026-08-05. Nếu môi trường thay đổi (đường dẫn SDK, phiên bản MediaPipe, kiến trúc Replay Spike được thay bằng `DetectionBackendMode` thật), hãy cập nhật tài liệu này cùng lúc — đừng để nó trở thành hướng dẫn lỗi thời hướng người mới đi vào ngõ cụt đã biết trước.*
