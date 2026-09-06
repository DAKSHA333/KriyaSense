package com.kriyasense.app

import android.content.Context
import com.kriyasense.assessment.WorkoutHistoryRepository
import com.kriyasense.assessment.WorkoutObservation
import com.kriyasense.assessment.WorkoutSession
import org.json.JSONArray
import org.json.JSONObject

/** Device-local storage for compact completed-workout metrics; it never receives camera or pose data. */
class SharedPreferencesWorkoutHistory(context: Context) : WorkoutHistoryRepository {
    private val preferences = context.applicationContext.getSharedPreferences("kriyasense_workout_history_v1", Context.MODE_PRIVATE)
    private val lock = Any()

    override fun save(session: WorkoutSession): Boolean = synchronized(lock) {
        val current = sessionsInternal()
        if (current.any { it.id == session.id }) return@synchronized false
        preferences.edit().putString(KEY_SESSIONS, JSONArray(current.plus(session).map { it.toJson() }).toString()).commit()
    }

    override fun sessions(): List<WorkoutSession> = synchronized(lock) { sessionsInternal().sortedWith(order) }
    override fun clear() = synchronized(lock) { preferences.edit().remove(KEY_SESSIONS).commit(); Unit }

    private fun sessionsInternal(): List<WorkoutSession> = runCatching {
        val raw = JSONArray(preferences.getString(KEY_SESSIONS, "[]"))
        List(raw.length()) { raw.getJSONObject(it).toSession() }
    }.getOrElse { emptyList() }

    private fun WorkoutSession.toJson() = JSONObject().apply {
        put("id", id); put("exerciseId", exerciseId); put("exerciseName", exerciseName); put("completedAt", completedAtEpochMs)
        put("variantId", variantId ?: JSONObject.NULL)
        put("completeReps", completeReps); put("incompleteReps", incompleteReps); put("totalAttempts", totalAttempts)
        put("completionPercentage", completionPercentage ?: JSONObject.NULL); put("averageRomPercentage", averageRomPercentage ?: JSONObject.NULL)
        put("averageConfidence", averageConfidence ?: JSONObject.NULL)
        put("averageRepDurationSeconds", averageRepDurationSeconds ?: JSONObject.NULL); put("holdDurationSeconds", holdDurationSeconds ?: JSONObject.NULL)
        put("observations", JSONArray(observations.map { JSONObject().put("code", it.code).put("message", it.message) }))
        put("mostCommonObservation", mostCommonObservation?.let { JSONObject().put("code", it.code).put("message", it.message) } ?: JSONObject.NULL)
    }
    private fun JSONObject.toSession(): WorkoutSession {
        fun optionalDouble(name: String) = if (isNull(name)) null else getDouble(name)
        fun observation(value: JSONObject?) = value?.let { WorkoutObservation(it.getString("code"), it.getString("message")) }
        val array = getJSONArray("observations")
        return WorkoutSession(
            getString("id"), getString("exerciseId"), getString("exerciseName"), getLong("completedAt"),
            getInt("completeReps"), getInt("incompleteReps"), getInt("totalAttempts"), optionalDouble("completionPercentage"),
            optionalDouble("averageRomPercentage"), optionalDouble("averageConfidence"),
            List(array.length()) { observation(array.getJSONObject(it))!! }, observation(optJSONObject("mostCommonObservation")),
            optionalDouble("averageRepDurationSeconds"),optionalDouble("holdDurationSeconds"),if(isNull("variantId")) null else getString("variantId")
        )
    }
    private companion object {
        const val KEY_SESSIONS = "sessions"
        val order = compareByDescending<WorkoutSession> { it.completedAtEpochMs }.thenByDescending { it.id }
    }
}
