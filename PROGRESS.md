# Milestone 3E progress

## Complete (implemented)

* Completed manual variant selection for Squat (Standard, Chair, Limited-ROM), Push-Up (Standard, Wall), and Bicep Curl (Standard, Seated); Standard-only exercises bypass this screen.
* Standard Squat remains 100° bottom target; Chair Squat uses 120° and Limited-ROM Squat 130°. Wall Push-Up and Seated Curl reuse existing safe upper-body thresholds and change only explicit setup/tutorial context.
* Session and local history now carry variant IDs; legacy missing IDs resolve to the corresponding Standard variant. Movement Signatures group by exercise plus variant, preventing adapted and Standard baseline mixing.
* Curated Challenges and Mirror Coach remain Standard-variant only. Form Drift continues to analyze only the active session timeline and does not use a Standard baseline for adapted variants.
* Variants are manually selected; BMI/profile data never chooses one. Chair/wall contact and seated state are not detected.

# Milestone 3D progress

## Complete (implemented)

* Added curated, on-device Form Challenges: Squat Precision (5 complete reps, ROM consistency ≥90%), Curl Control (5 complete reps, tempo consistency ≥90%), Push-Up Quality (5 complete reps), and Plank Control (30-second qualifying hold).
* Challenge progress is derived only from the existing session timeline/hold metrics. It never creates a second rep detector, changes normal scoring, or fails solely because Form Drift is detected.
* Challenge Mode is optional from the tutorial; live progress and a supportive result summary are shown when active. Challenge outcomes are currently not persisted separately to keep history schema compact.

# Milestone 3C progress

## Complete (implemented)

* Added on-device Form Drift Prediction using only the current session’s last four completed reps. It detects three consecutive ROM decreases and/or three consecutive duration decreases; no single bad/noisy rep triggers it.
* Reason codes: `ROM_DECLINING`, `TEMPO_SPEEDING_UP`, and `MULTIPLE_SIGNALS`. Drift is a movement-quality trend only, not fatigue, injury, or medical prediction, and never changes configured thresholds.
* Drift coaching priority is below visibility/form/range feedback and above success/neutral; existing speech throttling prevents repeated warnings. Plank receives no rep-based drift analysis.
* Added a compact live “MOVEMENT TREND Drifting” indicator and results session-trend line. SessionResult stores only a compact detected flag/reason list, not per-frame history.

# Milestone 3B progress

## Complete (implemented)

* Added local Movement Signature aggregation over the five most recent sessions for the same exercise. It compares users only with their own recorded metrics, stores no video/frames/landmarks, is not medical or biomechanical diagnosis, and never modifies assessment thresholds.
* Added backward-compatible compact history fields for average rep duration and hold duration. Existing history records continue to decode with those fields absent.
* Consistency formula: `100 × (1 − population standard deviation / absolute mean)`, clamped to 0–100. It is unavailable for fewer than two usable values or a zero/non-finite mean.
* Results show a concise personal baseline section. The current session comparison excludes that current record; first/insufficient history shows a baseline-building message. Plank shows hold data and no rep-tempo fields.

# Milestone 3A progress

## Complete (implemented)

* Added Squat-only Mirror Coach V1 as a visual, phase-based reference skeleton. It consumes the existing `SquatState` and never alters scoring, ROM, confidence, visibility, form rules, coaching, or session results.
* The guide is anchored to the detected shoulder/hip midpoints and scaled by torso length; missing/invalid anchors hide it safely. It renders only when Squat visibility is sufficient and the optional live toggle is enabled (default off).
* Guide geometry supports STANDING, DESCENDING, BOTTOM, and ASCENDING. It is a movement guide, not biomechanical ground truth; no mismatch warnings or injury/risk indicators are included in V1.

## Complete (implemented)

* Added a dark-theme landing screen, optional local profile/goals setup, exercise tutorial step, and explicit camera start from tutorial.
* Profile fields are stored only in `SharedPreferences` (`kriyasense_profile_v1`); BMI is calculated from optional height/weight and treated as informational only.
* Tutorials map each `ExerciseType` to truthful setup/cues/checks and expected local media filenames (`res/raw/<exercise>_demo.mp4`). No demo video assets exist yet, so the UI uses an honest “Exercise demo coming soon” placeholder.
* No KriyaSense logo asset was present under `app/src/main/res`; expected future location is `app/src/main/res/drawable/kriyasense_logo.*`. Text branding is used until the supplied asset is copied there.

# Milestone 2C progress

## Selection-screen mobile fix

* Replaced the non-scrollable vertical exercise-button stack with a compact, responsive two-column `LazyVerticalGrid` containing all eight exercises.
* The selected exercise name, ID, instruction and high-contrast selected state are shown above the grid. The Start Camera action is fixed below the scrollable card so it remains reachable on short mobile screens.
* Added Compose instrumentation tests for all eight exercise tags, scrolling to/selecting an exercise, visible Start Camera action, and navigation to the matching live-analysis title.
* Gradle build/lint could not execute in this environment because the Gradle daemon fails before task execution with `java.io.IOException: Unable to establish loopback connection`. Physical Infinix X6816C layout validation remains required.

## Complete (implemented)

* Added an `AssessmentEngine` contract and exercise factory without changing Squat thresholds, state semantics, coaching rules, or form rules.
* Added on-device conservative landmark assessment selection for Lunge (hip/knee/ankle depth), Push-Up (elbow range), Bicep Curl (elbow flexion), Shoulder Press (elbow extension plus wrist-over-shoulder), Jumping Jack (stance width plus raised arms), and Plank (hold-only shoulder/hip/ankle alignment).
* Added Calf Raise as explicitly experimental: it uses heel-landmark image-space lift relative to a standing baseline. It is not presented as medically validated and requires device validation before reliable support can be claimed.
* Repetition engines use start → target → return cycles, visibility gates, ROM, incomplete attempts, success/range coaching, and the existing shared TTS/coaching output. Plank accumulates hold duration and never creates fake repetitions.
* Selection now routes every listed exercise to its own assessment engine; results and existing local history retain the engine-provided exercise identity.

## Verification status

* Assessment sources compiled with the installed Kotlin compiler. Direct JUnit run: **24/24 passed** on 2026-09-05, including pre-existing Squat/coaching/history tests plus engine-selection, conservative-signal, and plank-hold tests.
* Gradle lint/APK verification remains dependent on a usable Gradle local loopback environment; rerun the required commands before release.

# Milestone 2B progress

## Complete (implemented)

* Added local, offline-first workout history using `SharedPreferences` with a compact JSON payload. The storage adapter implements a testable assessment-domain repository contract; no database, account, network dependency, or cloud service was introduced.
* A stored session contains a UUID, exercise identity/name, completion timestamp, complete/incomplete reps, total attempts, completion percentage, average ROM, average confidence, and recorded form observations. It deliberately excludes camera frames, photos, video, landmarks, pose data, and raw biometric recordings.
* Finish persists exactly once for each generated session UUID, protecting the flow from duplicate saves caused by repeated UI events or recomposition.
* Added device-local Workout History, newest-first cards, historical detail, empty state, progress totals, average completion percentage, and latest-versus-previous same-exercise completion change.
* Added a confirmation dialog before clearing all local history.

## Files changed

* `assessment/src/main/kotlin/com/kriyasense/assessment/WorkoutHistory.kt`
* `assessment/src/test/kotlin/com/kriyasense/assessment/SquatEngineTest.kt`
* `app/src/main/java/com/kriyasense/app/SharedPreferencesWorkoutHistory.kt`
* `app/src/main/java/com/kriyasense/app/MainActivity.kt`
* `docs/manual-test-plan.md`

## Verification status

* Compiled the assessment module and its test source directly with the installed Kotlin compiler, then ran JUnit: **21/21 passed** (17 existing/coaching tests plus 4 history tests) on 2026-09-05.
* Compiled the Android `SharedPreferencesWorkoutHistory` adapter against Android API 36 and the assessment classes successfully.
* Gradle commands still cannot start in this environment: they fail before task execution with `java.io.IOException: Unable to establish loopback connection`. `:assessment:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` remain unverified through Gradle.
* Manual device validation remains required for persistence after restart, history navigation, confirmation/clear, and privacy behavior.

# Milestone 2A progress

## Complete (implemented)

* Added assessment-owned live coaching messages with a single primary message, priority ordering (visibility, form, range, successful rep, neutral) and a 700 ms persistence window to prevent frame-by-frame flicker.
* Connected coaching to actual squat visibility, state-machine transitions, end-of-rep depth feedback, and existing live form rules. A valid completed rep produces `Good rep`; a shallow attempt remains incomplete and produces `Go lower`.
* Added a deterministic speech throttle used by offline Android TTS: 5-second general gap, 20-second same-message gap, and a 1-second urgent-message override. Neutral setup cues stay visual-only.
* Added a focused coaching card and compact state/pose metrics to the live camera UI without changing navigation or the camera pipeline.
* Improved the completion screen with total attempts, completion percentage (complete / recorded attempts), and the most frequent recorded form observation. No artificial quality score was introduced.
* Added deterministic assessment tests for valid-rep coaching, incomplete-rep coaching, priority/persistence, speech throttling, and summary totals.

## Files changed

* `assessment/src/main/kotlin/com/kriyasense/assessment/Results.kt`
* `assessment/src/main/kotlin/com/kriyasense/assessment/SquatEngine.kt`
* `assessment/src/test/kotlin/com/kriyasense/assessment/SquatEngineTest.kt`
* `app/src/main/java/com/kriyasense/app/VoiceFeedback.kt`
* `app/src/main/java/com/kriyasense/app/MainActivity.kt`
* `docs/manual-test-plan.md`

## Verification status

* Compiled all assessment production sources with the installed Kotlin compiler and serialization plugin, then compiled and ran `SquatEngineTest` directly with JUnit: **17/17 passed** (the original 13 plus 4 Milestone 2A tests) on 2026-09-05.
* Automated Gradle verification (`:assessment:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`) must be rerun in an environment where Gradle can establish its local loopback connection. Attempts on 2026-09-05 failed before any task execution with `java.io.IOException: Unable to establish loopback connection`; this is an environment failure, not a suppressed test result.
* Manual phone acceptance remains required, especially voice availability and the new feedback/summary cases below.

# Milestone 1 progress

## Complete (implemented)

* Empty repository inspected and scaffolded into Android app and independent Kotlin assessment SDK.
* Squat selection, CameraX permission/preview, MediaPipe LIVE_STREAM and bundled official Lite model.
* Shared crop, upright rotation and front-mirrored pose overlay.
* Geometry, configuration, visibility/recovery gates, debounced complete/incomplete squat state machine and motion consistency checks.
* Live and session metrics, explainable view-gated form errors, throttled offline TTS.
* Start/Pause/Resume/Finish, results, rep timeline and structured JSON.
* Unit tests and setup/API/manual-testing documentation.

## Verified

* Fixed a Kotlin syntax error in the reusable leg-length calculation.
* Ran `:assessment:test`, `:app:testDebugUnitTest`, `:app:assembleDebug` and `:app:lintDebug` successfully on 2026-09-05 using JDK 21 and Android SDK 36.
* All 13 assessment unit tests passed. Android lint completed with 0 errors and 13 non-blocking warnings (toolchain/dependency currency, portrait-only UI, app icon and backup metadata).
* Generated debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Partially complete

* Camera overlay, real-person accuracy, view heuristics, device performance and offline voice availability: implemented, awaiting physical-phone acceptance tests.

## Not implemented / next milestone

* Seven additional exercises (intentionally excluded).
* Calibration, validated accuracy dataset, multi-person handling, persistent session restoration, export, release signing.
* No proprietary score, backend, cloud processing or LLM is included.
