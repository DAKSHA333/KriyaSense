package com.kriyasense.assessment

/** On-device personal baseline; it is analytics only and never changes assessment thresholds. */
data class MovementSignature(val exerciseId: String,val exerciseName: String,val sessionsUsed: Int,val averageRomPercentage: Double?,val averageRepDurationSeconds: Double?,val romConsistency: Double?,val tempoConsistency: Double?,val averageConfidence: Double?,val completionPercentage: Double?,val averageHoldDurationSeconds: Double?,val updatedAtEpochMs: Long)
data class MovementSignatureComparison(val romChange: Double?=null,val tempoChangeSeconds: Double?=null,val completionChange: Double?=null,val holdChangeSeconds: Double?=null)
object MovementSignatures {
    const val RECENT_SESSION_LIMIT=5
    fun build(sessions: List<WorkoutSession>,exerciseId: String, variantId: String?=null): MovementSignature? {
        val key=variantId ?: variantKey(sessions.firstOrNull { it.exerciseId==exerciseId })
        val selected=sessions.filter { it.exerciseId==exerciseId && variantKey(it)==key }.sortedByDescending { it.completedAtEpochMs }.take(RECENT_SESSION_LIMIT)
        if(selected.isEmpty()) return null
        fun values(selector: (WorkoutSession)->Double?)=selected.mapNotNull(selector).filter { it.isFinite() }
        fun average(values: List<Double>)=values.takeIf { it.isNotEmpty() }?.average()
        fun consistency(values: List<Double>): Double? { if(values.size<2) return null; val mean=values.average(); if(mean==0.0 || !mean.isFinite()) return null; val deviation=kotlin.math.sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size); return (100.0*(1.0-deviation/ kotlin.math.abs(mean))).coerceIn(0.0,100.0).takeIf { it.isFinite() } }
        val rom=values { it.averageRomPercentage }; val tempo=values { it.averageRepDurationSeconds }; val holds=values { it.holdDurationSeconds }
        return MovementSignature(exerciseId,selected.first().exerciseName,selected.size,average(rom),average(tempo),consistency(rom),consistency(tempo),average(values { it.averageConfidence }),average(values { it.completionPercentage }),average(holds),selected.maxOf { it.completedAtEpochMs })
    }
    private fun variantKey(session: WorkoutSession?)=session?.variantId ?: session?.let { ExerciseVariants.standardId(ExerciseType.entries.firstOrNull { type -> type.id==it.exerciseId } ?: ExerciseType.SQUAT) }
    fun compare(current: WorkoutSession,prior: List<WorkoutSession>): MovementSignatureComparison? {
        val baseline=build(prior,current.exerciseId,current.variantId) ?: return null
        fun delta(value: Double?,base: Double?)=if(value!=null && base!=null && value.isFinite() && base.isFinite()) value-base else null
        return MovementSignatureComparison(delta(current.averageRomPercentage,baseline.averageRomPercentage),delta(current.averageRepDurationSeconds,baseline.averageRepDurationSeconds),delta(current.completionPercentage,baseline.completionPercentage),delta(current.holdDurationSeconds,baseline.averageHoldDurationSeconds))
    }
}
