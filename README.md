# KriyaSense

Milestone 1: an on-device, explainable Squat assessment SDK and native Android demonstration for Build 4 Karnataka. Exercise `EX_SQUAT_001` / `Squat` only. No backend, API key, LLM, Docker or Python. No proprietary final exercise score.

## Build and run

Requirements: JDK 21, Android SDK platform 36 and build tools, Android Studio or command line Android SDK, and an Android 8.0+ physical phone with a camera. Dependencies require internet during the build; the installed app does not.

1. Set `JAVA_HOME` to JDK 21 (not Java 25).
2. Set `ANDROID_HOME` to your SDK, or create an ignored `local.properties` with `sdk.dir=C:/path/to/Android/Sdk`.
3. Run `gradlew.bat :assessment:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` on Windows, or `./gradlew` with the same tasks on macOS/Linux.
4. Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Pinned toolchain: Gradle 8.13, Android Gradle Plugin 8.9.1, Kotlin and Compose compiler 2.1.20, Compose BOM 2025.04.01, CameraX 1.4.2, MediaPipe Tasks Vision 0.10.21. Compile SDK 36, target SDK 35, min SDK 26. See [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

On this Windows machine, Java's Unix-domain socket temporary path required a short directory. If Gradle reports `Unable to establish loopback connection`, create a short directory and set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\KriyaSense\.tools` for that shell. A local JDK was downloaded into ignored `.tools/`; it is not part of the project distribution.

## Model and licence

The official `pose_landmarker_lite.task` is bundled at `app/src/main/assets/pose_landmarker_lite.task`. It is the float16 version 1 Lite bundle, selected for real-time CPU inference.

Exact download: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task

[Official Android integration guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android), [model overview](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker), and [BlazePose GHUM model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf). The model card identifies Apache License 2.0. A copy is in `licenses/Apache-2.0.txt`; attribution is in `licenses/NOTICE.md`. To replace a missing model, download the exact URL above to the asset path before building. No runtime model download is performed.

## Use

Select Squat → Start Camera → grant permission → Start Session → perform squats → Finish → review results. Pause/Resume discards an interrupted attempt and re-establishes a standing baseline. Backgrounding pauses assessment. Results remain in memory for this activity only; starting a new session resets them.

Place the phone upright at hip height with one person fully visible and enough room to squat. A slight side view gives more useful knee-flexion measurements; both sides' required landmarks must remain reliable. Front camera is preferred and mirrored for display; a rear camera is used when no front camera is available. Inference coordinates are upright and unmirrored. CameraX Preview and ImageAnalysis share a viewport crop; the analyzer crops then rotates the image and the overlay mirrors once for a front lens.

Permission: CAMERA only. No INTERNET, microphone, storage or location permission. Frames exist only as transient in-memory camera/inference buffers, are closed after processing, and are never written, logged or transmitted. TTS selects an installed English voice that does not require a network connection; text feedback remains available if none exists.

## Architecture

* `assessment`: pure Kotlin/JVM reusable SDK. `Pose.kt` defines input, geometry and visibility. `Results.kt` defines configuration and serializable contracts. `FormRules.kt` computes image-plane measurements and explainable errors. `SquatEngine.kt` owns debounced state transitions, metrics and session history.
* `app/CameraPipeline.kt`: CameraX, shared viewport, LIVE_STREAM MediaPipe, bounded one-frame inference and lifecycle cleanup.
* `app/VoiceFeedback.kt`: offline Android TextToSpeech, five-second global and twenty-second per-code cooldowns.
* `app/MainActivity.kt`: Compose selection, permission, camera/overlay, session controls, metrics, results, timeline and JSON view.

## Measurement definitions

Angles use aspect-corrected pixel coordinates, averaging the left/right hip–knee–ankle angle. The geometry utility also supports 3D points; the demo deliberately measures image-plane angles. Thresholds live in `SquatConfig`.

A standing baseline (>=165°) must be stable, descent is <150°, bottom <=100°, and ascent must exceed the observed minimum by 12°. Each transition needs 120 ms of consistent evidence. A rep is complete only after standing → descending → bottom → ascending → standing. A shallow attempt returning from descent to standing is incomplete only if hip drop demonstrates squat-like motion. Hip drop must reach 8% of baseline leg length and ankle displacement remain within 25%. These are heuristics, not a trained exercise classifier.

ROM = clamp((165° − knee angle)/(165° − 100°) × 100, 0, 100). Live ROM uses the current angle; results ROM averages minimum-angle ROM across complete and incomplete reps. Average duration uses completed reps only, measured from the first stable descent candidate to confirmed standing. Timeline times use the visible, active session clock. Time under tension sums observed time during recognized squat movement. `tutFactor` is tension time / observed active time. `formFactor` is the fraction of assessed reps without configured form warnings. These are transparent ratios, not final exercise scores. Confidence is the mean of per-frame minimum required-landmark visibility/presence; rep confidence is the minimum during that rep. It is a tracking-quality proxy, not calibrated accuracy.

All required shoulders, hips, knees and ankles must be in frame and have visibility and presence >=0.65. Missing, non-finite or degenerate geometry blocks measurement. Visibility loss freezes aggregates immediately, clears live angles/ROM and corrections, and abandons partial state. Five consecutive reliable frames plus a stable standing baseline are required to re-arm. A >250 ms input gap has the same effect; the UI also detects stalled callbacks. Hidden time never contributes to duration or tension. Finishing midway does not invent a rep.

Forward torso lean is evaluated only with narrow apparent shoulder width (side view); frontal knee-width/ankle-width alignment is evaluated only with wide shoulders and sufficiently separated, level ankles. An unavailable rule produces no warning. Every emitted warning carries a code, measured value, expected threshold and confidence. Results list distinct error codes and preserve rep-specific measurements in the timeline.

## Verification and limitations

Run the Gradle command above for all unit tests, lint and the APK. Pure JVM tests cover angle/degenerate inputs, transitions, complete/incomplete reps, jitter, visibility and frozen metrics, recovery, interruptions, timestamps, duration, ROM, motion rejection, form rules and JSON round trips. See `PROGRESS.md` for actual verification results and `docs/manual-test-plan.md` for phone testing.

Physical-camera alignment, thermal performance, TTS availability and real-person counting need device validation. Image-plane projection, clothing, occlusion, lighting and camera angle can affect accuracy. The thresholds are initial engineering defaults, not clinically validated. Strict bilateral visibility can pause a true side view. No identity tracking or multi-person handling is implemented; use one person. The session is intentionally transient and may be lost if Android recreates/kills the activity. No export, persistence, calibration, seven additional exercises or release signing is included.

Next milestone: validate Squat on a consented physical-phone test matrix, tune view and movement gates against labelled sequences, add robust session restoration and performance instrumentation, then use the stable SDK contract to expand exercise configurations.
