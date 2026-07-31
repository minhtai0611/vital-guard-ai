# Alert Escalation Design — Drowsiness & Distraction

**Ngày:** 2026-07-31
**Phạm vi:** thêm cơ chế leo thang cường độ cảnh báo (voice lặp lại + climate leo thang cho drowsiness) khi driver ở trạng thái CRITICAL liên tục mà không hồi phục — giải quyết Gap #1 ("không có cơ chế leo thang/nhắc lại") đã xác định trong báo cáo hành vi hệ thống theo 6 kịch bản (2026-07-31).

**Ngoài phạm vi (explicit non-goal):** cơ chế mute/snooze/disable do driver tự bật tắt (Gap #2) — không thiết kế, không đề cập trong tài liệu này theo yêu cầu của chủ dự án, vì đang có hướng xây riêng. Thiết kế dưới đây không phụ thuộc vào Gap #2 và không cần sửa gì thêm khi Gap #2 được xây sau này (điểm actuation trong Kotlin — `handleCritical()` — là nơi tự nhiên để gắn 1 check "if muted, skip" sau này, nhưng không xây ở đây).

---

## 1. Bối cảnh & vấn đề

Hệ thống hiện tại (`TriggerEmitter`/`DistractionTriggerEmitter` phía Python, `DrowsinessController`/`DistractionController` phía Kotlin) là **latch-once**: một khi CRITICAL bắn ra và gateway đã được gọi, hệ thống khoá lại (`latched=true`) và **không lặp lại** dù trạng thái xấu tiếp diễn, cho tới khi score thật sự hồi phục dưới `exit_threshold`.

Hậu quả: nếu driver ngủ gật liên tục không hồi phục (score không bao giờ tụt dưới exit-threshold), hệ thống nói **đúng 1 câu rồi im hoàn toàn** — không tăng âm lượng, không lặp lại, không đổi giọng gấp hơn. Climate được set 1 lần rồi giữ tĩnh. Tương tự với distraction.

## 2. Cơ chế lõi

**Quyết định kiến trúc:** tạo class mới `EscalationTracker`, độc lập, KHÔNG nhúng vào `TriggerEmitter`/`DistractionTriggerEmitter` hiện có (đã test kỹ, không đụng vào). Chỉ thêm 1 property đọc-only `critical_active` (trả `self._critical_active` sẵn có) vào 2 emitter.

**Lý do chọn `critical_active` làm input, không phải raw score mỗi tick:** `_critical_active` (trong `trigger_emitter.py:36,46-49,53-57`) chỉ chuyển `False` khi `score <= exit_threshold` — KHÔNG bị ảnh hưởng bởi dao động trong vùng WARNING (0.50–0.85 cho drowsiness). Nhờ vậy `EscalationTracker` tự động **không flap** khi score dao động quanh ngưỡng, và **không reset** khi driver cải thiện tạm về WARNING rồi lại xấu — đúng tinh thần "không cho một cải thiện ngắn hạn xoá độ khẩn cấp đã tích lũy". Đã verify bằng chính code hiện tại, không suy luận.

### `EscalationTracker` (file mới: `dms-ai-engine/services/escalation_tracker.py`)

```python
class EscalationTracker:
    """
    Tính escalation_level (1/2/3) dựa trên thời gian LIÊN TỤC ở CRITICAL đã qua
    hysteresis (driven bởi critical_active property của TriggerEmitter/
    DistractionTriggerEmitter -- KHÔNG phải raw score > threshold từng frame).
    """

    def __init__(self, level_up_seconds: list[float], repeat_interval_seconds: list[float]):
        assert len(repeat_interval_seconds) == len(level_up_seconds) + 1
        self._level_up_seconds = level_up_seconds
        self._repeat_interval_seconds = repeat_interval_seconds
        self._critical_since: float | None = None
        self._last_repeat_time: float | None = None
        self._last_level = 1

    def update(self, critical_active: bool, now: float) -> tuple[int, bool, bool]:
        """Trả về (level, repeat_due, level_changed)."""
        if not critical_active:
            self._critical_since = None
            self._last_repeat_time = None
            level_changed = self._last_level != 1
            self._last_level = 1
            return 1, False, level_changed

        if self._critical_since is None:
            self._critical_since = now
            self._last_repeat_time = None  # chưa lặp lần nào trong episode này

        elapsed = now - self._critical_since
        level = 1 + sum(1 for t in self._level_up_seconds if elapsed >= t)

        level_changed = level != self._last_level
        self._last_level = level

        interval = self._repeat_interval_seconds[level - 1]
        # level_changed cũng tự tính là "vừa lặp" -- nếu không, _last_repeat_time
        # cũ (neo theo interval của level TRƯỚC) có thể khiến lần repeat_due kế
        # tiếp rơi chỉ 1-2s sau khi vừa nói câu ở level MỚI, cắt ngang utterance
        # đó giữa chừng. Phát hiện khi trace lại data flow với số interval thật
        # (Section 4) -- không phải giả định lý thuyết.
        repeat_due = level_changed or self._last_repeat_time is None or (now - self._last_repeat_time) >= interval
        if repeat_due:
            self._last_repeat_time = now
        return level, repeat_due, level_changed

    def reset(self) -> None:
        """Gọi khi UNKNOWN (mất mặt) bắn ra -- force về level 1 bất kể
        critical_active hiện tại (TriggerEmitter không cập nhật trong lúc mất
        mặt, nên không thể tự phản ánh qua critical_active)."""
        self._critical_since = None
        self._last_repeat_time = None
        self._last_level = 1
```

**Ghi chú thiết kế quan trọng (đã tranh luận, không phải ngầm định):**
- `_last_repeat_time = None` lúc mới vào CRITICAL → `repeat_due=True` ngay tick đầu. Tick đó **trùng frame** với cạnh CRITICAL gốc do `TriggerEmitter` bắn (publish gate đã publish vì `signal=="CRITICAL"` rồi), nên không tạo publish thừa — nó chỉ neo mốc `_last_repeat_time` để lần lặp kế tiếp cách đúng `interval[0]` giây sau.
- `level_changed` được tính **bên trong** `update()`, không phải so sánh ở `main.py` — gọn hơn cho unit test, tránh `main.py` phải giữ biến `prev_level` riêng dễ lệch.
- 2 instance riêng (drowsiness, distraction), hằng số khác nhau — xem bảng dưới.

### Hằng số (Python-owned, chỉ timing — tunable, chưa validate)

| | Drowsiness | Distraction |
|---|---|---|
| `level_up_seconds` | `[8.0, 16.0]` | `[6.0, 12.0]` |
| `repeat_interval_seconds` | `[10.0, 5.0, 4.0]` | `[7.0, 5.0, 3.0]` |
| Số mức tối đa | 3, giữ nguyên mức 3 mãi cho tới RECOVERED | 3, giữ nguyên mức 3 mãi cho tới RECOVERED |

**`repeat_interval_seconds` đã tune lại (không còn là 8/5/3 và 6/4/2 ban đầu) — bắt buộc phải dài hơn thời lượng TTS thật của câu ở đúng level đó, có margin an toàn.** Xem "Ràng buộc thời lượng TTS" ở Section 3 — đây là chỗ 2 phía Python/Kotlin phải khớp số với nhau, không tách rời được (Python quyết định nhịp publish, Kotlin quyết định câu nói dài bao lâu).

**Nhiệt độ per-level KHÔNG sống ở Python** — xem Section Kotlin bên dưới. Python chỉ biết `escalation_level` (int).

### `main.py` — publish gate

Đã verify chính xác vị trí code hiện tại (`main.py:169-330`) trước khi viết diff dưới đây — không suy luận.

**Nhánh face-loss (`main.py:244-254`, chạy TRƯỚC `if has_face:` ở dòng 261):**
```python
face_signal = face_tracker.update(has_face=has_face, now=t)
if face_signal == "UNKNOWN":
    drowsy_escalation.reset()
    distraction_escalation.reset()
    event_counter += 1
    store.update_latest(build_trigger_payload(
        state="UNKNOWN", score=0.0, confidence=0.0,
        perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
        reason="lost_face", source="container-python", event_counter=event_counter,
        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
        distraction_reason="lost_face",
        escalation_level=1, distraction_escalation_level=1,  # LITERAL -- KHÔNG dùng drowsy_level/distraction_level
    ))
```
**Bắt buộc dùng literal `1`, không tham chiếu biến `drowsy_level`/`distraction_level`** — vì nhánh này chạy ở tick mà `has_face=False`, biến đó (nếu có) sẽ giữ giá trị stale từ tick trước (Python không reset local giữa các vòng `while`). File hiện tại đã có kỷ luật này cho mọi field khác trong đúng branch này (`score=0.0`, `distraction_state="NORMAL"` là literal, không tham chiếu biến tick trước) — tuân theo đúng pattern sẵn có.

**Nhánh chính (`main.py:261+`, trong `if has_face:`, ngay sau `signal`/`distraction_signal` được tính):**
```python
drowsy_level, drowsy_repeat_due, drowsy_level_changed = drowsy_escalation.update(emitter.critical_active, now=t)
distraction_level, distraction_repeat_due, distraction_level_changed = distraction_escalation.update(distraction_emitter.critical_active, now=t)

if (signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED")
        or drowsy_repeat_due or distraction_repeat_due
        or drowsy_level_changed or distraction_level_changed):
    event_counter += 1
    store.update_latest(build_trigger_payload(
        ..., escalation_level=drowsy_level, distraction_escalation_level=distraction_level, ...
    ))
```

**CSV evidence logging — đã verify lại đúng cấu trúc code thật (`main.py:222-226`, `324-329`), sửa mô tả sai ở bản trước:** có **2 điểm `writer.writerow()` riêng biệt**, không phải 1 điểm chung.

```python
if has_face:
    ...
    writer.writerow([..., distraction_signal or "", drowsy_level, distraction_level])  # tính NGAY tick này, an toàn
else:
    writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or "",
                      "", hands_visibility, int(on_wheel), "", "", "",
                      "", ""])   # 2 cột mới: LITERAL "" -- KHÔNG đọc drowsy_level/distraction_level
```

**Bắt buộc literal `""` ở nhánh `else`, không đọc biến `drowsy_level`/`distraction_level`** — cùng lý do đã áp dụng cho payload UNKNOWN ở trên: biến đó chỉ được gán bên trong `if has_face:`, nếu tick này `has_face=False` thì biến vẫn giữ giá trị stale từ tick trước (Python không reset local giữa các vòng `while`). Nhánh `else` hiện tại **đã sẵn** dùng literal `""` cho mọi cột derive-từ-score khác (`distraction_state` cũng là `""` ở CSV, khác với `"NORMAL"` trong payload JSON — 2 mục đích khác nhau) — 2 cột mới phải theo đúng convention đó để không tạo ra 1 nguồn evidence lệch với payload JSON cùng thời điểm. Header thêm `"escalation_level", "distraction_escalation_level"`.

### `contracts/trigger.schema.json`

```diff
   "score": 0.0,
+  "escalationLevel": 1,
   "confidence": 0.0,
   "state": "NORMAL|WARNING|CRITICAL",
   ...
   "distraction": {
     "score": 0.0,
     "state": "NORMAL|WARNING|CRITICAL",
+    "escalationLevel": 1,
     "yawDeg": 0.0,
     ...
   }
```
Cả 2 field thêm vào `"required"`, default `1` khi không ở CRITICAL — không phá vỡ tính "luôn có mặt" của schema hiện tại.

**Ràng buộc kiểu trong `"properties"` (không chỉ thêm tên field vào `"required"`):**
```json
"escalationLevel": { "type": "integer", "minimum": 1, "maximum": 3 }
```
Áp dụng y hệt cho `distraction.escalationLevel`.

**Naming asymmetry đã ghi nhận, chấp nhận, không sửa:** `escalationLevel` top-level (mirror `state`) vs `distraction.escalationLevel` nested (mirror `distraction.state`) — cố ý mirror cách schema hiện tại đã tách `state`/`distraction.state`, không phải thiếu nhất quán. Cần ghi lại lý do này trong runbook khi implement.

---

## 3. Phía Kotlin

**Nguyên tắc phân chia trách nhiệm:** Python chỉ tính `escalationLevel` (int) dựa trên timing — KHÔNG biết giá trị actuation thật (°C, câu nói). Kotlin sở hữu toàn bộ mapping level→hành động thật, giống cách `COLD_TEMPERATURE_C=20.0` hiện đã sống trong `ClimateActuatorGateway.kt`, không phải Python.

### `DrowsinessController.kt`

```kotlin
private var latched = false
private var lastAppliedClimateLevel: Int? = null  // null = chưa áp override

fun onPayload(payload: TriggerPayload) {
    if (payload.correlationId == lastCorrelationId) return
    lastCorrelationId = payload.correlationId
    when (payload.state) {
        STATE_CRITICAL -> handleCritical(payload.escalationLevel)
        else -> handleNonCritical()
    }
}

private fun handleCritical(level: Int) {
    latched = true
    if (lastAppliedClimateLevel != level) {           // climate: CHỈ áp lại khi level đổi
        try {
            climateGateway.applyDrowsinessOverride(level)
            lastAppliedClimateLevel = level
            lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
        } catch (t: Throwable) {
            Log.e(TAG, "Climate override failed at level $level", t)
            lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
            // lastAppliedClimateLevel KHÔNG gán = level (đúng vì nằm sau dòng throw)
            // -> tick kế tiếp cùng level vẫn retry tự nhiên, không bị coi là "đã áp rồi".
        }
    }
    try {                                              // voice: LUÔN gọi lại mỗi tick
        alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS, level)
    } catch (t: Throwable) {
        Log.e(TAG, "Voice alert failed at level $level", t)
    }
}

private fun revertToBaseline() {
    latched = false
    lastAppliedClimateLevel = null
    climateGateway.revertToBaseline()
    alertArbiter.stopAlert(AlertSource.DROWSINESS)
}
```

**Vì sao an toàn khi bỏ `if (latched) return`:** dedup theo `correlationId` ở `onPayload()` đã chặn payload trùng lặp thật; mỗi payload CRITICAL mới đến (khác `correlationId`) từ giờ **luôn có ý nghĩa thật** — cạnh gốc, `repeat_due`, hoặc `level_changed`. Xác nhận bằng test đã pass sẵn: [`test_dms.py:59-65`](../../../dms-ai-engine/tests/test_dms.py) `test_no_duplicate_trigger_while_still_above_threshold` — `signal` là edge-only, không phải state sống suốt, nên Python không publish dồn dập ngoài ý muốn.

### `DistractionController.kt` — cùng pattern, không climate
```kotlin
private fun handleCritical(level: Int) {
    latched = true
    try {
        alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION, level)
    } catch (t: Throwable) {
        Log.e(TAG, "Voice reminder failed at level $level", t)
    }
}
```
Không có `GatewayActionStatus`/`lastGatewayAction` (không có climate gateway để theo dõi) — test tương ứng bỏ hẳn phần assert climate, còn lại pattern y hệt.

### Đường truyền `level` xuống actuation thật
```
DrowsinessController.handleCritical(level)
  → AlertArbiter.requestVoiceAlert(source, level)
    → VoiceAlertGateway.triggerAlert(level)
      → RealVoiceAlertGateway → VoiceEmergencyAssistant.executeVoiceIntervention(level)
```
`AlertArbiter`, `VoiceAlertGateway` (interface + Fake/Real), `ClimateActuatorGateway` (interface + Fake/Real) đổi chữ ký thêm `level: Int`. **Logic arbitration/suppression trong `AlertArbiter` (drowsiness luôn thắng, stop-outgoing-trước-khi-handoff) không đổi** — chỉ truyền thêm tham số qua.

### Nội dung câu nói theo level (concrete, chưa phải copy final — cần team duyệt câu chữ + đo lại bằng TTS engine thật)

| Level | Drowsiness | Distraction |
|---|---|---|
| 1 (giữ nguyên câu hiện tại) | *"Warning! Drowsiness detected!... Shall I guide you to the nearest rest stop?"* (19 từ, ~8.3s) | *"Please keep your eyes on the road and both hands on the wheel."* (13 từ, ~5.7s) |
| 2 (rút ngắn) | *"You're still drowsy. Please pull over now."* (~3.0s) | *"Eyes on the road, please. This is important."* (~3.5s) |
| 3 (rút ngắn) | *"Pull over immediately. Not safe to continue."* (~2.6s) | *"Eyes on the road now!"* (~2.2s) |

### Ràng buộc thời lượng TTS vs `repeat_interval_seconds` — rủi ro cao nhất trong thiết kế này

**Phát hiện khi so khớp số cụ thể:** câu L1 hiện tại (chưa từng bị lặp lại trước đây, chỉ nói 1 lần/episode) mất **~8.3s** (drowsiness) / **~5.7s** (distraction) để nói hết — nếu `repeat_interval_seconds[0]` ngắn hơn con số đó, `AlertArbiter.requestVoiceAlert()` gọi lại sẽ khiến TTS (`QUEUE_FLUSH`) tự cắt ngang chính câu đang nói để nói lại từ đầu — câu không bao giờ nói trọn. Đây chính xác là khoảnh khắc "giọng nói vang lên trước ban giám khảo" trong demo script — rủi ro cao nhất nếu không xử lý.

**Hướng xử lý đã chốt (ưu tiên tốc độ cho deadline hackathon, không xây coalesce/utterance-done state machine):**
1. **Rút ngắn câu L2/L3** (bảng trên) — vừa an toàn hơn về thời lượng, vừa nghe khẩn cấp hơn.
2. **Nới `repeat_interval_seconds`** đủ margin an toàn (~1.3-2.0s) so với thời lượng ước lượng, **vẫn giữ đúng thứ tự giảm dần theo level** (nhanh hơn khi khẩn cấp hơn) — số trong bảng constants ở Section 2 (`[10.0, 5.0, 4.0]` / `[7.0, 5.0, 3.0]`) đã tính theo hướng này.
3. **Bắt buộc trước khi chốt số final:** đo lại thời lượng thật bằng `TextToSpeech.speak()` trên chính thiết bị/voice engine sẽ dùng ở demo (Skycraft AAOS VM) — số trong bảng chỉ là ước lượng minh hoạ (~2.3 từ/giây), không phải giá trị đã đo.
4. **Lớp an toàn rẻ, chỉ để log (không đổi control flow):** thêm `UtteranceProgressListener` vào `VoiceEmergencyAssistant` (API Android TTS có sẵn, không xây mới) — `Log.w(TAG, "Utterance cut off before completion: $utteranceId")` khi 1 utterance bị `QUEUE_FLUSH` cắt ngang giữa câu. Không thay đổi timing/control flow, chi phí gần như 0 — cho phép phát hiện qua `adb logcat` trong lúc rehearsal nếu số đo trên máy demo thật lệch so với ước lượng, trước khi lên sân khấu thật.

**Cách 1 (coalesce theo callback "utterance done", không gọi lại nếu đang nói dở) đúng bản chất hơn nhưng tốn thêm state — để lại làm cải tiến sau nếu còn thời gian, không phải phần bắt buộc của thiết kế này.**

### Nhiệt độ theo level (Kotlin-owned, `ClimateActuatorGateway.kt`)

| Level | Temp target |
|---|---|
| 1 | 20.0°C (đã có, `COLD_TEMPERATURE_C`) |
| 2 | 17.0°C |
| 3 | 16.0°C |

Cả 3 vẫn đi qua **đúng logic clamp-to-real-config-min đã có sẵn** trong `RealClimateActuatorGateway` (`getCarPropertyConfig().getMinValue()`, log khi bị clamp) — không viết clamp mới, chỉ tham số hoá method hiện có theo level.

### Observability (giới hạn đã biết)

Không có UI dashboard nào trong app hiện tại nhận status `OVERRIDE_FAILED` (đây là Gap #3 đã xác định riêng trong báo cáo hành vi hệ thống — debug overlay CLAUDE.md yêu cầu chưa được xây, chỉ có 1 `TextView` tĩnh). Kênh quan sát duy nhất hiện có là `Log.e()` qua `adb logcat` + field `lastGatewayAction` (đã tồn tại sẵn, chỉ cần set đúng khi fail). Không thiết kế dashboard mới ở đây — nằm ngoài phạm vi.

### Rủi ro ripple-effect đã biết trước
Đổi chữ ký `applyDrowsinessOverride()`, `triggerAlert()`, `triggerDistractionReminder()`, `requestVoiceAlert()`, thêm field `escalationLevel`/`DistractionInfo.escalationLevel` vào `TriggerPayload` — sẽ động tới nhiều call site test (giống pattern Task 12 của plan trước, nơi tìm ra 1 call site thứ 3 ngoài dự kiến). Cần grep repo-wide cho các tên hàm/constructor này ở giai đoạn implement, không giả định chỉ có N call site đã biết.

---

## 4. Data flow trace

### Kịch bản 4 — buồn ngủ liên tục, không hồi phục
(dùng `level_up_seconds=[8.0, 16.0]`, `repeat_interval_seconds=[10.0, 5.0, 4.0]` đã tune theo ràng buộc TTS ở Section 3)

| t (từ lúc CRITICAL đầu) | Python | Kotlin |
|---|---|---|
| t=0 | `signal="CRITICAL"`, `drowsy_level=1`, publish (edge gốc); `_last_repeat_time=0` | `handleCritical(1)`: climate→20°C, voice câu L1 (~8.3s, xong trước 10.0s interval, margin 1.7s) |
| t=8 | `level_changed`→2 (`level_up[0]=8.0`); `level_changed` tự set `repeat_due=True` + `_last_repeat_time=8` (fix double-fire) → publish | `handleCritical(2)`: climate→17°C (level đổi), voice câu L2 (~3.0s) |
| t=13 | `repeat_due` (13-8=5≥interval L2=5.0) → publish | `handleCritical(2)`: climate KHÔNG gọi lại (level không đổi), voice lại câu L2 |
| t=16 | `level_changed`→3 (`level_up[1]=16.0`); `_last_repeat_time=16` (không phải 13 — đúng chỗ đã fix) → publish | `handleCritical(3)`: climate→16°C, voice câu L3 (~2.6s) |
| t=20, 24, 28... | `repeat_due` mỗi 4.0s kể từ t=16 (không phải t=17 như bản có bug) → publish | voice lại câu L3 mỗi 4.0s (margin 1.4s so với ~2.6s utterance), climate giữ 16°C — giữ mãi mức 3 tới khi score thật sự ≤0.50 |

**Case biên đã verify (không phải episode mới):** nếu tại t=22 score tụt vào vùng WARNING (0.60) rồi t=24 lại CRITICAL — `_critical_active` KHÔNG reset (chỉ reset khi score ≤0.50), nên `EscalationTracker` **tiếp tục đếm elapsed từ `_critical_since` gốc**, level không lùi về 1.

### Kịch bản 5 — mất tập trung, mặc kệ cảnh báo
Cấu trúc y hệt (cùng fix double-fire), mốc `[6.0, 12.0]`/interval `[7.0, 5.0, 3.0]`, chỉ voice, không climate. Nếu drowsiness đồng thời CRITICAL → `AlertArbiter` chặn hoàn toàn các tick distraction (đã có sẵn, không đổi).

---

## 5. Edge cases

| Case | Xử lý |
|---|---|
| WARNING dip giữa episode | Không reset (Section 4) |
| Face-loss (>2s, UNKNOWN) giữa escalation | Reset về level 1 cả 2 tracker (Section 2/3), literal `1` trong payload UNKNOWN |
| Connection-loss (Android↔Container) | Không reset phía Python (pipeline vẫn chạy real-time); Kotlin `revertToBaseline()` tạm dừng actuation, tự fire lại đúng level khi reconnect |
| `cooldown_seconds` (10s/5s) vs escalation tick | Không liên quan — cooldown chỉ gate cạnh CRITICAL **gốc** của `TriggerEmitter` (chống bug giả định ở sustain logic), tách biệt hoàn toàn với `EscalationTracker` |
| `face_signal` có flood không? | Không — edge-only, xác nhận bằng test có sẵn [`test_dms.py:143-148`](../../../dms-ai-engine/tests/test_dms.py) |
| Face-loss NGẮN (<2s, chưa đủ ngưỡng bắn UNKNOWN) | `_critical_since` KHÔNG reset, `elapsed` vẫn tính xuyên qua khoảng mất mặt ngắn đó — hệ quả tất yếu của việc chọn ngưỡng 2.0s cho `FacePresenceTracker`, không phải bug mới. **Chấp nhận được, không xử lý** — ảnh hưởng nhỏ (tối đa lệch ~2s trên tổng elapsed), và xử lý triệt để sẽ cần biết chính xác khoảng mất mặt <2s đã kéo dài bao lâu (dữ liệu không có sẵn ở granularity này). Ghi rõ ở đây để tránh sau này có người đọc code tưởng là bug sót. |
| TTS utterance dài hơn `repeat_interval` | Xem "Ràng buộc thời lượng TTS" ở Section 3 — đã tune số + thêm `UtteranceProgressListener` log làm safety net phát hiện sớm. |

---

## 6. Testing plan

**Python (mới):**
- `test_escalation_tracker.py`: onset tick (level=1, repeat_due=True, level_changed=False), level-up boundary chính xác (`elapsed==8.0`), single-tick jump 1→3 (mô phỏng gap frame lớn), RECOVERED reset đúng 1 lần (`level_changed` không lặp ở tick False kế tiếp), constructor validation (`assert` length mismatch), continues-through-warning-dip (giữ `critical_active=True` cố định), **`level_changed` tự cập nhật `_last_repeat_time` (không double-fire 1-2s sau khi vừa đổi level — case cụ thể: level đổi tại t=16 sau lần repeat cuối ở t=13, assert lần repeat kế tiếp là t=16+interval mới, không phải t=13+interval mới).**
- `test_dms.py`: thêm test anti-flap `critical_active` (score dao động 0.60↔0.80 nhiều vòng, `critical_active` không đổi cho tới khi score ≤0.50).
- `test_main.py`: integration test escalation payload theo thời gian; sửa test UNKNOWN thành đếm chính xác (`sum(...) == 1`, không dùng `any()`); test CSV row ở nhánh `has_face=False` ghi literal `""` cho 2 cột mới (không phải giá trị stale từ tick trước — dựng lại đúng case: 1 tick has_face=True ở level cao, rồi tick kế has_face=False, assert CSV row đó là `""`).

**Kotlin (mới/sửa) — `DrowsinessControllerTest.kt`:**
1. Duplicate `correlationId` → không gọi lại `climateGateway`/`alertArbiter` lần nào.
2. CRITICAL liên tiếp, level không đổi → `applyDrowsinessOverride` không gọi lại, `requestVoiceAlert` vẫn gọi mỗi payload.
3. CRITICAL level tăng → `applyDrowsinessOverride` gọi lại với level mới.
4. `applyDrowsinessOverride` throw → `lastAppliedClimateLevel` không đổi, `lastGatewayAction=OVERRIDE_FAILED`, tick kế tiếp cùng level vẫn retry.
5. UNKNOWN → `revertToBaseline()` được gọi, `latched=false`, `lastAppliedClimateLevel=null`.
6. Sau UNKNOWN, CRITICAL mới level=1 → override áp lại từ đầu.

**`DistractionControllerTest.kt`:** cùng pattern, bỏ assert climate.

**Khác:** `FakeClimateActuatorGateway`/`FakeVoiceAlertGateway` mở rộng ghi nhận `level` vào `callLog`; `AlertArbiterTest.kt` cập nhật chữ ký, xác nhận suppression/handoff không đổi; `contracts/trigger.schema.json` — kiểm tra + mở rộng test schema-conformance nếu có sẵn (bao gồm `type`/`minimum`/`maximum` của 2 field mới).

**Trước khi chốt số final (không phải unit test, nhưng bắt buộc trước khi coi feature "xong"):** đo thời lượng thật của mọi câu TTS (cả 6 câu, 3 level × 2 nguồn) bằng `TextToSpeech.speak()` trên chính thiết bị/voice engine demo (Skycraft AAOS VM) — số trong Section 3 chỉ là ước lượng minh hoạ (~2.3 từ/giây). Nếu số đo lệch, tune lại `repeat_interval_seconds` (Python) cho khớp, không sửa câu nói tuỳ tiện để né đo lại.

---

## 7a. Cảnh báo xung đột với spec khác (Gap #2 — đang được thiết kế riêng)

`docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md`
(Alert Preferences & Parked-State Suppression, chủ dự án tự thiết kế cho Gap
#2) **đụng chung đúng 2 method này**: `DrowsinessController.handleCritical()`/
`revertToBaseline()` + constructor — spec đó thêm `alertPreferencesStore` +
`isParked`; spec này thêm `level: Int` + logic climate-chỉ-áp-khi-đổi-level.
`DistractionController` cũng đụng chung tương tự (nhẹ hơn, không có climate).

**Tại thời điểm viết (2026-07-31), spec kia đang ở giai đoạn lên plan, thứ tự
implement thực tế chưa chốt — nhiều khả năng plan này (leo thang) chạy
trước.** Bất kể ai implement sau, **không được tái áp diff của mình một cách
máy móc lên bản gốc cũ** — phải đọc lại `handleCritical()`/`revertToBaseline()`
ở trạng thái đã có cả 2 luồng logic, merge tay: `level` (bao nhiêu, đổi
climate khi nào) VÀ `alertPreferencesStore.get().climateEnabled/voiceEnabled`
VÀ `isParked` đều phải cùng đúng trong 1 hàm. Ghi rõ ở đây để bất kỳ ai viết
plan/implement sau đều thấy, không phải giả định ngầm.

## 7. Tóm tắt quyết định đã chốt (traceability)

| Quyết định | Lựa chọn |
|---|---|
| Kênh leo thang | Voice (cả 2) + Climate (chỉ drowsiness) |
| Trigger leo thang | Thời gian liên tục ở CRITICAL (qua `critical_active`, không phải raw score) |
| Nội dung leo thang Voice | Rút ngắn khoảng lặp + đổi câu nói theo level |
| Nội dung leo thang Climate | Hạ nhiệt thêm theo level, vẫn clamp theo config thật |
| Số mức / giới hạn | 3 mức, giữ nguyên mức 3 tới khi RECOVERED |
| Nơi tính level | Python (tính), Kotlin (chỉ thực thi theo level nhận được) |
| Reset khi mất mặt (>2s, UNKNOWN) | Reset về level 1 |
| Face-loss <2s | Không reset, chấp nhận được, không xử lý |
| TTS utterance dài hơn interval (rủi ro cao nhất) | Rút ngắn câu L2/L3 + tune interval có margin + `UtteranceProgressListener` chỉ log (safety net, không coalesce đầy đủ) — đo lại số thật trước khi final |
| `level_changed` double-fire ngay sau khi đổi level | Fix: `level_changed` tự cập nhật `_last_repeat_time` |
| CSV logging ở nhánh `has_face=False` | Literal `""`, không đọc biến `drowsy_level`/`distraction_level` (cùng lớp lỗi đã fix cho payload UNKNOWN) |
| Mute/disable UI (Gap #2) | **Ngoài phạm vi** — chủ dự án tự thiết kế riêng |
