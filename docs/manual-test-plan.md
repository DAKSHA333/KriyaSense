# Physical-phone acceptance test plan

Status: not executed yet. Record device, Android version, camera lens, lighting, app version, observed count and expected count for each case. Use one consenting tester; do not record camera frames. Install the debug APK using adb. Test at least one API 26+ phone and one current phone, including front and rear-camera fallback where available.

| Case | Procedure | Expected |
|---|---|---|
| Fresh install | Select Squat; deny then grant permission | Explanation and retry/settings controls; preview starts after grant |
| Persistent denial | Deny repeatedly, then open settings and grant | No crash; returning checks permission and starts camera |
| Selection | Launch app | Only Squat, EX_SQUAT_001 |
| Preview mapping | Raise left/right arm, move to all four edges, bend knees | Overlay tracks joints, front mirror matches preview, no double mirroring or rotation offset |
| Device posture | Hold portrait; change physical orientation and return | Portrait UI, correct pose orientation; no invalid count from camera movement |
| Baseline | Start seated/crouched, then stand upright | No rep before stable standing baseline |
| Full squats | Perform 5 controlled deep squats, standing fully between reps | Exactly 5 complete; timeline contains 5 entries |
| Shallow squats | Perform 3 distinct half squats | Incomplete count increments once per sufficient attempt; depth error includes measurement |
| Jitter | Slight knee movement near standing and threshold, then remain standing | No duplicate reps |
| Stationary/non-squat | Stand, wave arms, bend torso with straight knees, step around | NOT_DETECTED before qualifying motion; no rep for unrelated motion |
| Occlusion | Hide knees, hips, shoulders, ankles separately | Specific visibility instruction; aggregate counts, ROM, duration, tension, confidence freeze; live angles unavailable |
| Recovery | Hide knees at bottom; reappear standing, then perform a new rep | Hidden rep not completed; automatic visibility recovery and fresh baseline; next full rep counts |
| Slow delivery | Under heavy device load/watch camera stall | No extrapolated motion or hidden-time accumulation; visibility reacquisition after gap |
| Pause | Pause at bottom for 10 seconds; resume standing | Frozen metrics; no bridged rep or paused duration |
| Background | Home/lock screen during descent, return | Paused; explicit Resume; no background counting or speech |
| Finish | Finish standing, mid-rep, paused, and before first rep | No invented rep; null averages when none measured; stable results and JSON |
| Depth / speed | Alternate shallow, full slow, and fast attempts | Appropriate depth/speed errors with stable codes, thresholds and confidence |
| Side torso lean | Side/slight-side view, lean forward | Lean warning only when view allows measurement |
| Frontal alignment | Frontal view, knees inward with level separated feet | Alignment warning when measurable; no frontal alignment warning from side view |
| Voice throttle | Hold same error for 30 s; introduce another | >=5 s between speech, >=20 s repeated code, pause/finish stops speech |
| Coaching success | Perform one controlled, full squat | Complete count increments; `Good rep` is shown briefly and spoken once when an installed offline voice is available |
| Coaching incomplete | Perform one shallow, distinct squat | Incomplete count increments; complete count does not; `Go lower` is shown and may be spoken |
| Coaching priority | While a neutral or success cue is visible, move required joints out of frame; then deliberately lean or misalign knees in a measurable view | Visibility replaces every other cue immediately; measurable form warning outranks depth, success and neutral cues |
| Coaching stability | Hold a stable pose and slowly move through each state | Primary coaching card does not alternate each video frame; normal changes persist about 700 ms; visibility responds immediately |
| Summary | Finish after one full and one shallow squat | Complete 1, incomplete 1, total attempts 2, completion 50%; timeline and SDK JSON agree; most-common observation is based only on recorded errors |
| Offline | Airplane mode after installing; repeat session | Camera/pose/results work; installed offline voice works; absent voice leaves text usable |
| Result consistency | Compare live count with timeline and SDK JSON | Complete/incomplete totals and average durations agree |
| Save once | Finish a workout, rotate/recompose if possible, return to History | Exactly one history item is created for that completed workout |
| Restart persistence | Finish a workout, fully close and reopen the app, open History | Same compact workout record remains on device; no camera image, video, frame, or pose replay is available |
| Ordering and detail | Finish two sessions in sequence; open each History card | Newest is first; each detail view matches its exercise, time, counts, completion, ROM/confidence when available, and observations |
| Progress | Finish multiple sessions for Squat with differing completion percentages | Workout/repetition totals, average completion, and latest-versus-previous same-exercise change match stored sessions |
| Clear history | Choose Clear history, cancel once, then confirm | Cancel preserves records; confirm removes all records and returns the empty state |
| Lunge | Perform 5 deep controlled lunges, then shallow/partial, fast/slow, and briefly leave frame | Full start→depth→return cycles count; shallow attempts are incomplete; visibility interrupts safely |
| Push-Up | Perform 5 full push-ups, then shallow/partial, fast/slow, and briefly leave frame | Full elbow-range cycles count; insufficient depth is not counted as complete |
| Bicep Curl / Shoulder Press | Perform 5 full cycles for each; test partial curls/presses and temporary occlusion | Selected engine uses the correct exercise identity; only full configured ranges count |
| Calf Raise (experimental) | Test 5 controlled raises from a stable side/rear-safe view, partial raises, and different footwear/lighting | Document whether heel landmarks are stable enough; do not claim reliability if counts drift |
| Jumping Jack | Perform 5 full closed→open→closed cycles, partial opens, arm-only and leg-only movement | One count only after full return; partial cycles do not count |
| Plank | Hold a visible side-view plank, break early, leave frame, and compare timer with a stopwatch | No repetitions; hold duration accumulates only while a visible qualifying position is detected |
| Repeated sessions | Run/finish 10 sessions | No camera-in-use error, stale rep or sustained buffer growth |
| Thermal/runtime | 10-minute session on low-end phone | Measure frame latency, heat and responsiveness; document limitations |

Inspect APK merged manifest for absence of INTERNET and storage permissions. Confirm app does not create image/video files. Phone performance and counting accuracy remain unverified until these cases are executed; unit tests alone do not establish camera correctness.
