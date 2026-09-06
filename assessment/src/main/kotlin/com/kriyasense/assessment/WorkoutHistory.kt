package com.kriyasense.assessment

/** Compact, privacy-preserving workout data suitable for local persistence. */
data class WorkoutObservation(val code: String, val message: String)
data class WorkoutSession(
    val id: String,
    val exerciseId: String,
    val exerciseName: String,
    val completedAtEpochMs: Long,
    val completeReps: Int,
    val incompleteReps: Int,
    val totalAttempts: Int,
    val completionPercentage: Double?,
    val averageRomPercentage: Double?,
    val averageConfidence: Double?,
    val observations: List<WorkoutObservation>,
    val mostCommonObservation: WorkoutObservation?,
    val averageRepDurationSeconds: Double? = null,
    val holdDurationSeconds: Double? = null,
    val variantId: String? = null
)

interface WorkoutHistoryRepository {
    /** Returns false when this exact completed-session id is already stored. */
    fun save(session: WorkoutSession): Boolean
    fun sessions(): List<WorkoutSession>
    fun clear()
}

/** Small deterministic repository used by tests and as a contract for device storage. */
class InMemoryWorkoutHistoryRepository : WorkoutHistoryRepository {
    private val records = linkedMapOf<String, WorkoutSession>()
    override fun save(session: WorkoutSession): Boolean {
        if (records.containsKey(session.id)) return false
        records[session.id] = session
        return true
    }
    override fun sessions(): List<WorkoutSession> = records.values.sortedWith(
        compareByDescending<WorkoutSession> { it.completedAtEpochMs }.thenByDescending { it.id }
    )
    override fun clear() { records.clear() }
}

data class ProgressOverview(
    val totalWorkouts: Int,
    val totalCompletedReps: Int,
    val averageCompletionPercentage: Double?,
    val latestCompletionChange: Double?
)

object WorkoutHistory {
    fun fromResult(result: SessionResult, id: String, completedAtEpochMs: Long): WorkoutSession {
        val summary = SessionSummaries.from(result)
        val observations = result.formErrors.map { WorkoutObservation(it.code, it.message) }
        val common = summary.mostCommonObservation?.let { WorkoutObservation(it.code, it.message) }
        return WorkoutSession(
            id, result.exerciseId, result.exerciseName, completedAtEpochMs,
            summary.completeReps, summary.incompleteReps, summary.totalAttempts,
            summary.completionPercentage, result.romPercentage, result.confidence.takeIf { it > 0.0 },observations, common,result.averageRepDurationSeconds,result.holdDurationSeconds,result.variantId
        )
    }

    fun overview(sessions: List<WorkoutSession>): ProgressOverview {
        val percentages = sessions.mapNotNull { it.completionPercentage }
        val newest = sessions.sortedWith(compareByDescending<WorkoutSession> { it.completedAtEpochMs }.thenByDescending { it.id })
        val current = newest.firstOrNull()?.completionPercentage
        val previous = newest.drop(1).firstOrNull { it.exerciseId == newest.firstOrNull()?.exerciseId }?.completionPercentage
        return ProgressOverview(
            totalWorkouts = sessions.size,
            totalCompletedReps = sessions.sumOf { it.completeReps },
            averageCompletionPercentage = percentages.takeIf { it.isNotEmpty() }?.average(),
            latestCompletionChange = if (current != null && previous != null) current - previous else null
        )
    }
}
