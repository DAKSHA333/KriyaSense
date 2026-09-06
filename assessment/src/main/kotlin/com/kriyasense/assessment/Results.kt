package com.kriyasense.assessment

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class Status { VALID, NOT_DETECTED, INSUFFICIENT_VISIBILITY, PAUSED }
@Serializable enum class Visibility { SUFFICIENT, INSUFFICIENT }
@Serializable enum class SquatState { STANDING, DESCENDING, BOTTOM, ASCENDING }
enum class CoachingPriority { VISIBILITY, FORM, RANGE_OF_MOTION, DRIFT, SUCCESS, NEUTRAL }
@Serializable data class FormError(val code: String, val message: String, val measuredValue: Double, val expected: String, val confidence: Double)
@Serializable data class Rep(val index: Int, val complete: Boolean, val startSeconds: Double, val endSeconds: Double, val durationSeconds: Double, val romPercentage: Double, val confidence: Double, val formErrors: List<FormError>)
@Serializable data class SessionResult(
    val exerciseId: String = "EX_SQUAT_001", val exerciseName: String = "Squat",
    val status: Status = Status.NOT_DETECTED, val confidence: Double = 0.0,
    val completeReps: Int = 0, val incompleteReps: Int = 0,
    val holdDurationSeconds: Double? = null, val averageRepDurationSeconds: Double? = null,
    val romPercentage: Double? = null, val timeUnderTensionSeconds: Double = 0.0,
    val tutFactor: Double? = null, val formFactor: Double? = null,
    val formErrors: List<FormError> = emptyList(), val visibility: Visibility = Visibility.INSUFFICIENT,
    val timeline: List<Rep> = emptyList(), val driftDetected: Boolean = false, val driftReasons: List<String> = emptyList(), val variantId: String? = null
) { fun toJson(): String = json.encodeToString(this)
    companion object { val json = Json { prettyPrint = true; encodeDefaults = true } }
}
data class CoachingFeedback(val code: String, val message: String, val priority: CoachingPriority, val speakable: Boolean = true)
data class LiveAssessment(val result: SessionResult, val kneeAngle: Double? = null, val currentRomPercentage: Double? = null,
    val state: SquatState = SquatState.STANDING, val instruction: String = "Stand upright with your whole body in view", val corrections: List<FormError> = emptyList(),
    val coaching: CoachingFeedback = CoachingFeedback("GET_IN_FRAME", "Keep your body visible", CoachingPriority.VISIBILITY), val movementState: String = state.name,
    val primaryMetricLabel: String? = null, val primaryMetricValue: Double? = null, val primaryMetricUnit: String = "")

/** Common contract for repetition and hold assessment engines. */
interface AssessmentEngine {
    fun current(): LiveAssessment
    fun process(frame: PoseFrame): LiveAssessment
    fun pause(): LiveAssessment
    fun resume()
    fun finish(): SessionResult
}

/** A presentation-ready summary derived only from recorded attempts. */
data class SessionSummary(val completeReps: Int, val incompleteReps: Int, val totalAttempts: Int,
    val completionPercentage: Double?, val mostCommonObservation: FormError?)
object SessionSummaries {
    fun from(result: SessionResult): SessionSummary {
        val total = result.completeReps + result.incompleteReps
        val common = result.timeline.flatMap { it.formErrors }
            .groupBy { it.code }.maxByOrNull { it.value.size }?.value?.firstOrNull()
        return SessionSummary(result.completeReps, result.incompleteReps, total,
            if (total == 0) null else result.completeReps * 100.0 / total, common)
    }
}

/** Keeps one live message visible long enough to be read, while urgent feedback can replace it. */
class CoachingFeedbackController(private val persistenceMs: Long = 700L) {
    private var current: CoachingFeedback? = null
    private var shownAt = Long.MIN_VALUE

    fun select(candidate: CoachingFeedback, timestampMs: Long): CoachingFeedback {
        val previous = current
        if (previous == null || candidate.code == previous.code || candidate.priority.ordinal < previous.priority.ordinal ||
            timestampMs - shownAt >= persistenceMs) {
            if (candidate.code != previous?.code) shownAt = timestampMs
            current = candidate
        }
        return current!!
    }
    fun reset() { current = null; shownAt = Long.MIN_VALUE }
}

/** Pure timing policy so Android TTS is testable without camera or TTS hardware. */
class SpeechThrottle(private val minimumGapMs: Long = 5_000L, private val repeatGapMs: Long = 20_000L,
    private val urgentOverrideGapMs: Long = 1_000L) {
    private var lastSpokenAt = Long.MIN_VALUE / 2
    private var lastPriority: CoachingPriority? = null
    private val spoken = mutableMapOf<String, Long>()

    fun shouldSpeak(feedback: CoachingFeedback, timestampMs: Long): Boolean {
        val sameAt = spoken[feedback.code] ?: Long.MIN_VALUE / 2
        if (timestampMs - sameAt < repeatGapMs) return false
        val urgent = lastPriority != null && feedback.priority.ordinal < lastPriority!!.ordinal
        if (timestampMs - lastSpokenAt < if (urgent) urgentOverrideGapMs else minimumGapMs) return false
        lastSpokenAt = timestampMs; lastPriority = feedback.priority; spoken[feedback.code] = timestampMs
        return true
    }
}
data class SquatConfig(
    val standingAngle: Double = 165.0, val descentAngle: Double = 150.0, val bottomAngle: Double = 100.0,
    val riseHysteresis: Double = 12.0, val debounceMs: Long = 120, val recoveryFrames: Int = 5,
    val visibilityThreshold: Double = 0.65, val maxFrameGapMs: Long = 250,
    val minimumHipDrop: Double = 0.08, val maximumAnkleDrift: Double = 0.25,
    val minimumRepSeconds: Double = 1.2, val maximumTorsoLean: Double = 45.0,
    val frontalWidthRatio: Double = 0.65, val sideWidthRatio: Double = 0.35,
    val minimumKneeAnkleRatio: Double = 0.75
)
