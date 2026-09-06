# Assessment SDK API

`assessment` has no Android/Compose dependency. Its public entry point is `com.kriyasense.assessment.SquatEngine`. Use one owner thread; do not call concurrently. Construct one engine per session.

## Input contract

`PoseFrame(timestampMs, landmarks, width, height)`:

* `timestampMs`: monotonic milliseconds, strictly increasing. Duplicate/out-of-order frames are ignored. Gaps >250 ms abandon partial state and require reacquisition.
* `landmarks`: map from MediaPipe pose index to `Landmark(Point(x,y,z), visibility, presence)`. Required indices: shoulders 11/12, hips 23/24, knees 25/26, ankles 27/28.
* x/y: upright, unmirrored normalized coordinates in the analyzed crop; [0,1] is inside the frame. z is optional normalized model depth, unused by this engine. Visibility/presence are probabilities in [0,1]. Missing values must not default to confidence 1.
* width/height: positive pixel dimensions of that upright crop, used to undo normalized-coordinate aspect distortion.

Submit empty landmarks when no pose is detected. Do not omit failure frames: the engine must know tracking was lost. The UI adapter supplies a watchdog failure when callbacks stall. No camera frames are accepted or stored by the SDK.

## Example

```kotlin
val engine = SquatEngine(SquatConfig())
val live: LiveAssessment = engine.process(frame)
println(live.instruction)
// live.kneeAngle/currentRomPercentage are null when measurement is unavailable.
engine.pause()
engine.resume() // re-acquire visibility and standing baseline
val result: SessionResult = engine.finish()
val json: String = result.toJson()
```

`finish()` returns a snapshot; stop feeding the engine after finishing. Unfinished attempts are not counted. `pause()` returns a frozen live result with PAUSED status. Form corrections in `LiveAssessment.corrections` are current observations, separate from historical errors.

## Status codes

| Status | Meaning |
|---|---|
| NOT_DETECTED | No qualifying squat motion or assessed rep in this session yet; standing/baseline instructions remain available. |
| VALID | Squat-like movement is recognized or at least one rep has been assessed. Does not mean error-free form. |
| INSUFFICIENT_VISIBILITY | Missing/unreliable/out-of-frame joints, invalid geometry, or visibility recovery is in progress. Aggregates freeze. |
| PAUSED | Explicit or lifecycle pause; aggregates freeze. |

`visibility` is SUFFICIENT or INSUFFICIENT. PAUSED preserves the last visibility flag and historical confidence; neither is a current measurement while paused. On visibility failure live angle/ROM are null; result aggregate values retain their last measured values. Display the status alongside any retained values.

## Output schema

| Field | Type / definition |
|---|---|
| exerciseId / exerciseName | String, EX_SQUAT_001 / Squat |
| status / visibility | Enums above |
| confidence | Double [0,1], mean minimum joint visibility/presence for accepted frames; 0 before measurements |
| completeReps / incompleteReps | Integer counts |
| holdDurationSeconds | Null for Squat |
| averageRepDurationSeconds | Double or null; completed reps only |
| romPercentage | Double [0,100] or null; mean per-rep ROM across all assessed reps |
| timeUnderTensionSeconds | Double >=0, observed recognized movement time |
| tutFactor | Double [0,1] or null; observed tension/active time |
| formFactor | Double [0,1] or null; warning-free reps / all assessed reps |
| formErrors | Distinct historical error codes, retaining first occurrence measurements |
| timeline | Ordered Rep records including index, complete, startSeconds, endSeconds, durationSeconds, romPercentage, confidence, formErrors |

`FormError`: `code: String`, `message: String`, `measuredValue: Double`, `expected: String` (includes units/range), `confidence: Double`. Stable codes: INSUFFICIENT_DEPTH, EXCESSIVE_FORWARD_LEAN, KNEE_ALIGNMENT, MOVEMENT_TOO_FAST. Errors are emitted only when the corresponding measurement/view is available. Confidence is not a medical or exercise-validity probability. No final score is produced.

Initial serialized output (additional timeline/metric fields are always present):

```json
{
  "exerciseId": "EX_SQUAT_001", "exerciseName": "Squat",
  "status": "NOT_DETECTED", "confidence": 0.0,
  "completeReps": 0, "incompleteReps": 0,
  "holdDurationSeconds": null, "averageRepDurationSeconds": null,
  "romPercentage": null, "timeUnderTensionSeconds": 0.0,
  "tutFactor": null, "formFactor": null, "formErrors": [],
  "visibility": "INSUFFICIENT", "timeline": []
}
```

Serialization uses kotlinx.serialization with explicit defaults/nulls and no non-finite numbers. See README for threshold and metric equations.
