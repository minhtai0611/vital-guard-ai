# Alert Preferences & Parked-State Suppression — Design Spec

**Goal:** (1) Cho tài xế tùy chỉnh cường độ cảnh báo (voice volume/on-off,
climate intensity) mà không bao giờ cho phép tắt hoàn toàn cả hai kênh cùng
lúc khi CRITICAL — một yêu cầu an toàn (EU GSR/DDAW), không phải sở thích UX.
(2) Suppress phản hồi (không suppress detection) khi xe đỗ hẳn, dựa trên tốc
độ xe đọc trực tiếp qua VHAL, không dựa nút tắt tay.

**Kiến trúc:** Cả hai vấn đề đều là thay đổi **thuần Kotlin, phía App** —
không đổi `contracts/trigger.schema.json`, không đổi bất kỳ file Python nào
trong `dms-ai-engine/`. Lý do cho Vấn đề 2 (đã xác nhận qua brainstorm):
Container Node (Python) không có VHAL/CarPropertyManager access — chỉ App
mới đọc được tốc độ xe trực tiếp, nên không có lý do để tốc độ xe phải đi
qua Container Node.

**Tech Stack:** Kotlin (`aaos-cockpit-app`), SharedPreferences (không dùng
DataStore — nhất quán với `PrefsGatewayModeStore` đã có), `CarPropertyManager`
(đọc `PERF_VEHICLE_SPEED`), JUnit4 + Mockito cho test mới.

## Global Constraints

- Không đổi `contracts/trigger.schema.json` hay bất kỳ file trong
  `dms-ai-engine/` cho cả Vấn đề 1 và Vấn đề 2.
- Không đổi phạm vi hysteresis/debounce/cooldown của `DrowsinessController`
  (vẫn tin tưởng Python's `TriggerEmitter` như thiết kế cũ — Decision 6).
- Mọi giá trị số cụ thể (fan/temp mapping, ngưỡng tốc độ đỗ xe) là baseline
  **chưa validate**, đánh dấu rõ trong code — nhất quán với cách
  `trigger_emitter.py`'s ngưỡng 0.85 đã được document.
- Verify, don't assume: mọi chỗ đọc VHAL property (`getCarPropertyConfig()`,
  `PERF_VEHICLE_SPEED`) phải tự query/clamp lúc runtime, không hardcode giả
  định range.

---

## Bước 0 (bắt buộc, chặn trước phần Real của Vấn đề 2)

Tự query `PERF_VEHICLE_SPEED` trên Skycraft VM thật bằng
`CarPropertyManager.getCarPropertyConfig()` **trước khi** wiring
`RealVehicleContextGateway` vào production path. Chưa test thì chưa biết đi
Nhánh A hay B. Việc này cần quyền truy cập VM thật, không thể tự động hoá
trong phiên làm việc này — ghi vào implementation plan như một task riêng,
ops-only, giống các task VM khác đã làm ở plan trước
(`docs/superpowers/plans/2026-07-29-tai-week1-remaining-tasks.md`).

**Nếu đọc được (Nhánh A):** toàn bộ thiết kế Vấn đề 2 dưới đây áp dụng.

**Nếu không đọc được (Nhánh B):** escalate mentor kèm bằng chứng cụ thể (kết
quả `getCarPropertyConfig()`, không phải mô tả chung), hỏi có nguồn tín hiệu
tốc độ nào khác được wire trong blueprint không (GPS giả lập, CAN bus signal
khác). Không tự chế phương án thay thế trước khi có câu trả lời — giống case
Container Node Provisioning đã gặp. Nhánh B không có thiết kế thêm trong spec
này — chờ câu trả lời rồi mới brainstorm tiếp nếu cần.

---

## Vấn đề 1 — Alert Preferences

### Data model

```kotlin
enum class IntensityLevel { LOW, MEDIUM, HIGH }  // không có OFF

data class AlertPreferences(
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 1.0f,
    val climateEnabled: Boolean = true,
    val climateIntensity: IntensityLevel = IntensityLevel.HIGH,  // = hành vi hiện tại
) {
    init {
        require(voiceVolume in 0f..1f) { "voiceVolume must be in [0,1], got $voiceVolume" }
    }
    fun isSafe(): Boolean = voiceEnabled || climateEnabled
}
```

Phân tầng validate rõ: `voiceVolume` là invariant luôn đúng → enforce trong
`init` (không thể tạo instance sai). `isSafe()` là business rule chỉ bắt
buộc **lúc lưu** (cho phép trạng thái tạm "unsafe" trong lúc UI đang chỉnh,
trước khi user bấm lưu) → enforce ở `AlertPreferencesStore.save()`, không
phải trong `init`.

### Persistence — Fake/Real pair, đúng khuôn `GatewayModeStore`

```kotlin
interface AlertPreferencesStore {
    fun get(): AlertPreferences
    fun save(prefs: AlertPreferences)   // mọi implementation đều require(prefs.isSafe())
}

class InMemoryAlertPreferencesStore(
    initial: AlertPreferences = AlertPreferences()
) : AlertPreferencesStore {
    @Volatile private var current: AlertPreferences = initial
    override fun get(): AlertPreferences = current
    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        current = prefs
    }
}

class PrefsAlertPreferencesStore(private val context: Context) : AlertPreferencesStore {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): AlertPreferences = runCatching {
        AlertPreferences(
            voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true),
            voiceVolume = prefs.getFloat(KEY_VOICE_VOLUME, 1.0f),
            climateEnabled = prefs.getBoolean(KEY_CLIMATE_ENABLED, true),
            climateIntensity = IntensityLevel.valueOf(
                prefs.getString(KEY_CLIMATE_INTENSITY, IntensityLevel.HIGH.name)!!
            ),
        )
    }.getOrDefault(AlertPreferences())

    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        this.prefs.edit()
            .putBoolean(KEY_VOICE_ENABLED, prefs.voiceEnabled)
            .putFloat(KEY_VOICE_VOLUME, prefs.voiceVolume)
            .putBoolean(KEY_CLIMATE_ENABLED, prefs.climateEnabled)
            .putString(KEY_CLIMATE_INTENSITY, prefs.climateIntensity.name)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "vital_guard_alert_preferences"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_CLIMATE_ENABLED = "climate_enabled"
        private const val KEY_CLIMATE_INTENSITY = "climate_intensity"
    }
}
```

`get()` bọc toàn bộ construction trong `runCatching` — nếu dữ liệu lưu bị hỏng
(enum parse lỗi, hoặc `voiceVolume` ngoài range do schema drift), fallback về
`AlertPreferences()` mặc định — đúng pattern `PrefsGatewayModeStore.get()` đã
có (`runCatching { GatewayMode.valueOf(raw) }.getOrDefault(GatewayMode.FAKE)`).

### Intensity → giá trị VHAL cụ thể

Bảng mapping tách riêng thành object thuần, unit test được không cần Context:

```kotlin
object IntensityMapping {
    fun fanSpeedFor(intensity: IntensityLevel): Int = when (intensity) {
        IntensityLevel.HIGH -> 8
        IntensityLevel.MEDIUM -> 5
        IntensityLevel.LOW -> 3
    }
    fun temperatureCFor(intensity: IntensityLevel): Float = when (intensity) {
        IntensityLevel.HIGH -> 20f
        IntensityLevel.MEDIUM -> 22f
        IntensityLevel.LOW -> 23f
    }
}
```

| Intensity | Fan | Temp | Ghi chú |
|---|---|---|---|
| HIGH (mặc định) | 8 | 20°C | = hành vi CRITICAL hiện tại, không đổi trải nghiệm demo |
| MEDIUM | 5 | 22°C | |
| LOW | 3 | 23°C | Vẫn rõ ràng khác baseline (25°C/fan2), không bao giờ map về 0 |

`RealClimateActuatorGateway.applyDrowsinessOverride()` đọc
`alertPreferencesStore.get().climateIntensity`, lấy giá trị từ
`IntensityMapping`, rồi **query `getCarPropertyConfig()` mỗi lần gọi** (không
cache ở constructor — nhất quán với `forEachSupportedArea()` đã query mỗi lần
từ trước) để clamp vào range thật:
- Config đọc được: `target.coerceIn(min, max)`, log khi thực sự bị clamp.
- Config `null`: dùng thẳng giá trị bảng cứng, log cảnh báo riêng
  ("clamp skipped — config unavailable") — không reject, không escalate ngay.
- `revertToBaseline()` luôn về baseline cố định (fan=2/temp=25°C), **không**
  phụ thuộc `climateIntensity` — intensity chỉ ảnh hưởng nhánh CRITICAL.

`RealVoiceAlertGateway`/`VoiceEmergencyAssistant.speakAlert()` đọc
`voiceVolume` để truyền vào `Bundle` param thứ 3 của `tts?.speak()` (thay
`null` hiện tại, dùng `TextToSpeech.Engine.KEY_PARAM_VOLUME`).

### Enable/disable — nằm ở Controller, không nằm ở Gateway

Quyết định quan trọng: việc kiểm tra `voiceEnabled`/`climateEnabled` nằm
trong `DrowsinessController`/`DistractionController` (đã unit test sẵn qua
Fake gateway), **không** nằm trong `RealClimateActuatorGateway`/
`RealVoiceAlertGateway`. Xem chi tiết code ở mục "Sửa lỗi cross-cutting" bên
dưới — đây cũng là nơi sửa một bug thật đang tồn tại độc lập với feature này.

### UI Settings screen

- Slider `voiceVolume` (0–1), radio button `climateIntensity`
  (LOW/MEDIUM/HIGH), 2 switch `voiceEnabled`/`climateEnabled`.
- **Guard 2 lớp:** (1) UI — khi một switch đang OFF, switch còn lại bị
  **disable** (không bấm tắt được), không cho tạo trạng thái vi phạm dù chỉ
  tạm thời trên màn hình. (2) `AlertPreferencesStore.save()`'s
  `require(isSafe())` — lớp phòng thủ thứ hai, phòng UI state có bug. Hai lớp
  bổ trợ, không thay thế nhau.
- `save()` throw `IllegalArgumentException` khi vi phạm — UI bắt exception để
  hiện thông báo. Không dùng `Result<T>` — nhất quán với style try/catch+log
  đã dùng khắp `DrowsinessController`/`AlertArbiter`.

### Known Limitation

> Đổi `AlertPreferences` giữa lúc CRITICAL đang active **không** dừng/đổi
> ngay cảnh báo đang chạy. `voiceEnabled`/`climateIntensity` chỉ được đọc lại
> tại lần `handleCritical()`/`revertToBaseline()` kế tiếp (FSM chuyển state
> tiếp theo). Cùng tinh thần với `AlertArbiter`'s "no active preemption".

---

## Vấn đề 2 — Suppress khi đỗ xe (Nhánh A)

### File mới

```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── VehicleContextGateway.kt      # Fake + Real
├── ParkedStateTracker.kt          # pure logic, không coroutine
└── VehicleContextPollClient.kt    # coroutine poll loop, 1Hz
```

### `VehicleContextGateway`

```kotlin
interface VehicleContextGateway {
    fun getCurrentSpeedKmh(): Float?   // null nếu property không đọc được — không fabricate
}

class FakeVehicleContextGateway : VehicleContextGateway {
    var speedKmh: Float? = 0f
    override fun getCurrentSpeedKmh(): Float? = speedKmh
}

class RealVehicleContextGateway(context: Context) : VehicleContextGateway {
    private val car: Car = Car.createCar(context)      // connect 1 lần, giữ reference
    private val cpm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    override fun getCurrentSpeedKmh(): Float? = try {
        cpm.getProperty(Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).value * 3.6f
    } catch (t: Throwable) {
        Log.w("VitalGuardVehicleContext", "PERF_VEHICLE_SPEED unreadable: ${t.message}")
        null
    }

    fun disconnect() = car.disconnect()   // gọi ở onDestroy() — tránh leak binder connection
}
```

### `ParkedStateTracker` — hysteresis bất đối xứng cả threshold và sustain

```kotlin
class ParkedStateTracker(
    private val enterThresholdKmh: Float = 10f,
    private val enterSustainMs: Long = 30_000L,   // dài, né dừng đèn đỏ
    private val exitThresholdKmh: Float = 15f,
    private val exitSustainMs: Long = 2_000L,     // ngắn, ưu tiên an toàn: resume nhanh
) {
    private var isParked = false
    private var belowSince: Long? = null
    private var aboveSince: Long? = null

    /** true = vừa vào parked, false = vừa resume, null = không đổi. */
    fun update(speedKmh: Float?, nowMs: Long): Boolean? {
        if (speedKmh == null) {
            belowSince = null
            if (isParked) { isParked = false; return false }  // fail-safe: mất tín hiệu -> resume
            return null
        }
        if (!isParked) {
            if (speedKmh < enterThresholdKmh) {
                if (belowSince == null) belowSince = nowMs
                if (nowMs - belowSince!! >= enterSustainMs) { isParked = true; belowSince = null; return true }
            } else belowSince = null
        } else {
            if (speedKmh > exitThresholdKmh) {
                if (aboveSince == null) aboveSince = nowMs
                if (nowMs - aboveSince!! >= exitSustainMs) { isParked = false; aboveSince = null; return false }
            } else aboveSince = null
        }
        return null
    }
}
```

`nowMs` truyền từ caller — không tự đọc `System.currentTimeMillis()` trong
class, giữ test được không cần mock giờ hệ thống (đúng convention
`TriggerEmitter.update(score, now)`). 4 số (10km/h/30s, 15km/h/2s) là
baseline chưa validate.

### Hook trong `DrowsinessController` + `DistractionController` — fix bug latch-freeze

Áp dụng đối xứng cho cả hai controller:

```kotlin
private var isParked = false

fun onParkedStateChanged(parked: Boolean) {
    isParked = parked
    if (parked && latched) revertToBaseline()   // đang active + vừa đỗ -> tắt ngay
}

private fun handleCritical() {
    if (latched) return
    if (isParked) { Log.i(TAG, "Suppressed: vehicle parked"); return }  // KHÔNG set latched=true
    latched = true
    // ... (xem mục "Sửa lỗi cross-cutting" cho phần gọi gateway)
}
```

Điểm mấu chốt tránh bug: **không bao giờ set `latched=true` tại thời điểm bị
suppress** — chỉ set khi gateway thực sự được gọi.

### `VehicleContextPollClient`

```kotlin
class VehicleContextPollClient(
    private val gateway: VehicleContextGateway,
    private val tracker: ParkedStateTracker,
    private val scope: CoroutineScope,
    private val onParkedStateChanged: (Boolean) -> Unit,
    private val pollIntervalMs: Long = 1000L,
) {
    private var job: Job? = null
    fun start() {
        job = scope.launch {
            while (true) {
                try {
                    tracker.update(gateway.getCurrentSpeedKmh(), System.currentTimeMillis())
                        ?.let { onParkedStateChanged(it) }
                } catch (t: Throwable) {
                    Log.e("VitalGuardVehicleContext", "Poll tick failed: ${t.message}")
                }
                delay(pollIntervalMs)
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
```

Quyết định polling (không dùng `CarPropertyManager.registerCallback()`):
đơn giản hơn, nhất quán với `TriggerPollClient` đã có, sai số không đáng kể
vì sustain window (30s/2s) lớn hơn nhiều chu kỳ poll 1s.

### Wiring trong `VitalGuardMonitorService`

```kotlin
val alertPreferencesStore: AlertPreferencesStore = PrefsAlertPreferencesStore(this)
// luôn dùng bản Prefs thật trong production — không qua GATEWAY_MODE switch,
// đây là setting người dùng, không phải actuator cần fallback Fake/Real cho demo
val drowsinessController = DrowsinessController(climateGateway, alertArbiter, alertPreferencesStore)
val distractionController = DistractionController(alertArbiter, alertPreferencesStore)

val vehicleContextGateway: VehicleContextGateway = RealVehicleContextGateway(this)
// không qua GATEWAY_MODE switch — đây là input, không phải actuator cần fallback demo
val vehicleContextPollClient = VehicleContextPollClient(
    gateway = vehicleContextGateway,
    tracker = ParkedStateTracker(),
    scope = serviceScope,
    onParkedStateChanged = { parked ->
        drowsinessController.onParkedStateChanged(parked)
        distractionController.onParkedStateChanged(parked)
    },
)
vehicleContextPollClient.start()

// onDestroy():
vehicleContextPollClient.stop()
(vehicleContextGateway as? RealVehicleContextGateway)?.disconnect()
```

### Known Limitation

> Nếu xe đã đứng yên từ trước khi app khởi động, `ParkedStateTracker` vẫn cần
> đủ 30s sustain kể từ lúc app start mới nhận ra "đã đỗ" — trong 30s đầu đó
> hệ thống coi như đang chạy bình thường. Nhất quán với triết lý sustain-based
> toàn dự án, không sửa, chỉ ghi nhận.

---

## Sửa lỗi cross-cutting (phát hiện trong quá trình brainstorm, không phải feature mới)

### Bug: climate/voice coupled trong 1 try/catch — vi phạm nguyên tắc "ít nhất 1 kênh active"

Code hiện tại của `handleCritical()` gói climate + voice trong 1 try/catch —
nếu climate throw, voice không bao giờ được thử. Trước Vấn đề 1, đây chỉ là
chi tiết code; giờ nó vi phạm trực tiếp safety invariant đã tuyên bố. Sửa —
tách độc lập, cộng thêm check `climateEnabled`/`voiceEnabled` (thuộc Vấn đề 1):

**Điểm mấu chốt, dễ sai nhất trong toàn bộ spec:** `alertArbiter.setDrowsinessCriticalActive()`
là bookkeeping cho **ưu tiên giữa 2 nguồn cảnh báo** (drowsiness luôn thắng
distraction) — hoàn toàn độc lập với việc tài xế có tắt riêng kênh voice của
drowsiness hay không. **Không được** gate lời gọi này theo `voiceEnabled`.
Nếu gate nhầm: tài xế tắt voice (giữ climate — hợp lệ theo `isSafe()`) rồi
CRITICAL thật xảy ra → cờ ưu tiên không được set → distraction CRITICAL xảy
ra đồng thời sẽ không bị suppress, vi phạm chính nguyên tắc `AlertArbiter`
được tạo ra để đảm bảo. Ngược lại ở `revertToBaseline()`: nếu tài xế tắt
voice **giữa chừng** lúc đang CRITICAL rồi score mới giảm, gate theo
`voiceEnabled` sẽ khiến cờ không được clear — kẹt vĩnh viễn ở `true`, đúng
kiểu lỗi "flag freeze" đã gặp ở `onConnectionLost()` trước đây. Viết tường
minh cả hai hàm, không để "cùng pattern áp dụng" ngầm hiểu:

```kotlin
class DrowsinessController(
    private val climateGateway: ClimateActuatorGateway,
    private val alertArbiter: AlertArbiter,
    private val alertPreferencesStore: AlertPreferencesStore,
) {
    // ...
    private fun handleCritical() {
        if (latched) return
        if (isParked) { Log.i(TAG, "Suppressed: vehicle parked"); return }
        latched = true
        alertArbiter.setDrowsinessCriticalActive(true)   // luôn gọi -- KHÔNG gate theo voiceEnabled

        val prefs = alertPreferencesStore.get()
        var anySucceeded = false

        if (prefs.climateEnabled) {
            try { climateGateway.applyDrowsinessOverride(); anySucceeded = true }
            catch (t: Throwable) { Log.e(TAG, "Climate gateway failure: ${t.message}") }
        }
        if (prefs.voiceEnabled) {
            try { alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS); anySucceeded = true }
            catch (t: Throwable) { Log.e(TAG, "Voice gateway failure: ${t.message}") }
        }

        lastGatewayAction = if (anySucceeded) GatewayActionStatus.OVERRIDE_APPLIED else GatewayActionStatus.OVERRIDE_FAILED
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }

    private fun revertToBaseline() {
        latched = false
        alertArbiter.setDrowsinessCriticalActive(false)   // luôn gọi -- đối xứng với trên
        alertArbiter.stopAlert(AlertSource.DROWSINESS)     // an toàn gọi vô điều kiện -- đã có ownership check
        try {
            climateGateway.revertToBaseline()
            lastGatewayAction = GatewayActionStatus.REVERTED
        } catch (t: Throwable) {
            Log.e(TAG, "Climate gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
        }
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }
}
```
(`DebugOverlayState.instance` đã xác nhận tồn tại thật trong codebase —
`DrowsinessController.kt` hiện tại đã gọi đúng method này, không phải tham
chiếu suy đoán.)

`DistractionController` chỉ có 1 kênh (voice) nên không có vấn đề coupling
climate/voice, nhưng vẫn cần nhận `alertPreferencesStore` để check
`voiceEnabled` — **không** có cờ ưu tiên nào cần tách ra như bên trên (chỉ
`DrowsinessController` gọi `setDrowsinessCriticalActive()`):

```kotlin
class DistractionController(
    private val alertArbiter: AlertArbiter,
    private val alertPreferencesStore: AlertPreferencesStore,
) {
    private fun handleCritical() {
        if (latched) return
        if (isParked) { Log.i(TAG, "Suppressed: vehicle parked"); return }
        latched = true
        if (alertPreferencesStore.get().voiceEnabled) {
            try { alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION) }
            catch (t: Throwable) { Log.e(TAG, "Gateway failure: ${t.message}") }
        }
    }

    private fun revertToBaseline() {
        latched = false
        try { alertArbiter.stopAlert(AlertSource.DISTRACTION) }
        catch (t: Throwable) { Log.e(TAG, "Gateway failure: ${t.message}") }
    }
}
```

### Test hiện có sẽ vỡ compile — phải cập nhật, không phải việc mới

3 file test đã tồn tại và đã xác nhận đọc trực tiếp, gọi constructor cũ:
- `DrowsinessControllerTest.kt`: `DrowsinessController(climate, arbiter)` (2 tham số)
- `DistractionControllerTest.kt`: `DistractionController(arbiter)` (1 tham số)
- `AlertArbiterIntegrationTest.kt`: cả hai, cùng constructor cũ

Cả 3 file: thêm `InMemoryAlertPreferencesStore()` (mặc định, an toàn — không
cần override gì) vào `setUp()`, truyền vào constructor. **Assertions của mọi
test hiện có giữ nguyên, chỉ đổi cách construct** — đúng cách đã ghi rõ khi
đổi constructor lần trước (Task 12, thêm `AlertArbiter` vào
`DrowsinessController`).

### Bug: `RealVehicleContextGateway` leak binder connection nếu tạo mới mỗi lần poll

Đã sửa trực tiếp trong thiết kế ở trên (`Car.createCar()` gọi 1 lần ở
constructor, giữ reference, có `disconnect()` gọi ở `onDestroy()`). Nếu
không sửa: poll 1Hz suốt 20 phút demo = 1200 lần tạo kết nối Car Service
không giải phóng — rủi ro trực tiếp cho KPI "20-minute load test, 0 crashes".

---

## File Structure (tổng hợp)

**Mới:**
```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── AlertPreferences.kt           # data class + IntensityLevel + IntensityMapping
├── AlertPreferencesStore.kt       # interface + InMemory + Prefs
├── VehicleContextGateway.kt       # interface + Fake + Real
├── ParkedStateTracker.kt
└── VehicleContextPollClient.kt
aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/
├── AlertPreferencesTest.kt
├── IntensityMappingTest.kt
├── InMemoryAlertPreferencesStoreTest.kt
├── ParkedStateTrackerTest.kt
└── VehicleContextPollClientTest.kt
```

**Sửa:**
```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── DrowsinessController.kt        # +AlertPreferencesStore, +isParked, tách try/catch
├── DistractionController.kt       # +AlertPreferencesStore, +isParked
├── ClimateActuatorGateway.kt      # RealClimateActuatorGateway đọc IntensityMapping + clamp
├── VoiceAlertGateway.kt           # RealVoiceAlertGateway đọc voiceVolume
├── VoiceEmergencyAssistant.kt     # speakAlert() dùng Bundle KEY_PARAM_VOLUME
└── VitalGuardMonitorService.kt    # wire AlertPreferencesStore + VehicleContextPollClient
aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/
├── DrowsinessControllerTest.kt      # +6 test mới; setUp() thêm InMemoryAlertPreferencesStore()
├── DistractionControllerTest.kt     # +3 test mới; setUp() thêm InMemoryAlertPreferencesStore()
└── AlertArbiterIntegrationTest.kt   # +1 test mới; setUp() thêm InMemoryAlertPreferencesStore()
                                      # (cả 3 file: constructor DrowsinessController/DistractionController
                                      #  đổi từ 2/1 tham số lên 3/2 tham số -- vỡ compile nếu không sửa)
```

---

## Testing Strategy (đầy đủ)

| File | Test |
|---|---|
| `AlertPreferencesTest.kt` | `default_preferences_is_safe` · `isSafe_false_when_both_channels_disabled` · `isSafe_true_when_only_voice_enabled` · `isSafe_true_when_only_climate_enabled` · `voiceVolume_out_of_range_throws_on_construction` · `voiceVolume_at_boundary_0_and_1_is_valid` |
| `IntensityMappingTest.kt` | `high_maps_to_fan8_temp20` · `medium_maps_to_fan5_temp22` · `low_maps_to_fan3_temp23` · `no_intensity_level_maps_to_zero_fan_or_temperature` |
| `InMemoryAlertPreferencesStoreTest.kt` | `defaults_to_safe_default` · `save_then_get_round_trips` · `save_rejects_unsafe_preferences_and_leaves_prior_value_unchanged` |
| `ParkedStateTrackerTest.kt` | `below_threshold_but_not_yet_sustained_does_not_enter_parked` · `brief_dip_above_threshold_mid_sustain_resets_belowSince` · `sustained_below_threshold_enters_parked_exactly_once` · `speed_in_dead_zone_between_exit_and_enter_threshold_while_parked_does_not_exit` · `null_speed_while_not_parked_does_not_enter_parked` · `null_speed_while_parked_resumes_immediately` |
| `VehicleContextPollClientTest.kt` | `a_failing_tick_does_not_stop_subsequent_polling` |
| `DrowsinessControllerTest.kt` (+mới) | `does_not_freeze_latch_across_park_then_unpark_while_still_critical` · `park_while_critical_active_reverts_to_baseline` · `climate_failure_does_not_prevent_voice_alert_from_firing` · `voice_failure_does_not_prevent_climate_override_from_applying` · `climateEnabled_false_skips_climate_but_still_fires_voice` · `voiceEnabled_false_skips_voice_but_still_applies_climate` |
| `DistractionControllerTest.kt` (+mới) | `does_not_freeze_latch_across_park_then_unpark_while_still_critical` · `park_while_critical_active_reverts_to_baseline` · `voiceEnabled_false_suppresses_distraction_reminder` |
| `AlertArbiterIntegrationTest.kt` (+mới) | `drowsiness_critical_with_voice_disabled_still_suppresses_concurrent_distraction` — khóa đúng bug vừa tìm: drowsiness CRITICAL với `voiceEnabled=false` (chỉ climate) → `setDrowsinessCriticalActive(true)` vẫn phải chạy → distraction CRITICAL đồng thời phải bị suppress (không `distractionReminderTriggered`), y hệt shape 2 test hiện có trong file này (`drowsiness connection-lost while critical clears the arbiter flag...`) |

**Không unit test, verify tay trên VM thật** (nhất quán với tiền lệ
`PrefsGatewayModeStore` hiện không có test nào trong repo):
`PrefsAlertPreferencesStore`, VHAL clamp logic trong `RealClimateActuatorGateway`,
`voiceVolume`→TTS Bundle trong `RealVoiceAlertGateway`/`VoiceEmergencyAssistant`.

## Out of scope

- Nhánh B (PERF_VEHICLE_SPEED không đọc được) — chờ mentor trả lời, không tự
  thiết kế phương án thay thế trong spec này.
- Field `isParked`/tốc độ xe trong `DebugOverlayState` hoặc
  `contracts/trigger.schema.json` — không yêu cầu trong checklist gốc, có thể
  làm sau như một cải tiến riêng.
- `CarPropertyManager.registerCallback()` (push-based, thay polling) — cải
  tiến tương lai, không cần cho MVP.
