package com.kriyasense.assessment

enum class ChallengeTargetType { QUALITY_REPS, HOLD_SECONDS }
enum class ChallengeStatus { NOT_STARTED, ACTIVE, COMPLETED, NOT_COMPLETED }
data class FormChallenge(val id: String,val title: String,val description: String,val exerciseType: ExerciseType,val targetType: ChallengeTargetType,val targetValue: Int,val consistencyTarget: Double?=null)
data class ChallengeResult(val challenge: FormChallenge,val status: ChallengeStatus,val progress: Int,val qualityValue: Double?=null,val message: String)
object ChallengeDefinitions {
    val squat=FormChallenge("squat_precision","Squat Precision","Complete 5 squats with consistent range of motion.",ExerciseType.SQUAT,ChallengeTargetType.QUALITY_REPS,5,90.0)
    val curl=FormChallenge("curl_control","Curl Control","Complete 5 controlled curls with consistent tempo.",ExerciseType.BICEP_CURL,ChallengeTargetType.QUALITY_REPS,5,90.0)
    val pushUp=FormChallenge("push_up_quality","Push-Up Quality","Complete 5 configured full-range push-ups.",ExerciseType.PUSH_UP,ChallengeTargetType.QUALITY_REPS,5)
    val plank=FormChallenge("plank_control","Plank Control","Maintain a qualifying plank for 30 seconds.",ExerciseType.PLANK,ChallengeTargetType.HOLD_SECONDS,30)
    fun forExercise(type: ExerciseType)=listOf(squat,curl,pushUp,plank).firstOrNull { it.exerciseType==type }
}
object FormChallenges {
    private fun consistency(values: List<Double>): Double? { if(values.size<2) return null; val mean=values.average(); if(mean==0.0 || !mean.isFinite()) return null; val deviation=kotlin.math.sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size); return (100*(1-deviation/kotlin.math.abs(mean))).coerceIn(0.0,100.0) }
    fun evaluate(challenge: FormChallenge,result: SessionResult,active: Boolean=true): ChallengeResult {
        if(!active) return ChallengeResult(challenge,ChallengeStatus.NOT_STARTED,0,message="Challenge not started")
        if(challenge.targetType==ChallengeTargetType.HOLD_SECONDS) { val seconds=result.holdDurationSeconds?:0.0; val done=seconds>=challenge.targetValue; return ChallengeResult(challenge,if(done) ChallengeStatus.COMPLETED else ChallengeStatus.NOT_COMPLETED,seconds.toInt(),seconds,"Hold ${seconds.toInt()} / ${challenge.targetValue} sec") }
        val reps=result.timeline.filter { it.complete }.take(challenge.targetValue)
        val values=when(challenge.id) { "squat_precision" -> reps.map { it.romPercentage }; "curl_control" -> reps.map { it.durationSeconds }; else -> emptyList() }
        val quality=if(values.isEmpty()) null else consistency(values)
        val qualityPass=challenge.consistencyTarget?.let { quality!=null && quality>=it } ?: true
        val done=reps.size>=challenge.targetValue && qualityPass
        return ChallengeResult(challenge,if(done) ChallengeStatus.COMPLETED else ChallengeStatus.NOT_COMPLETED,reps.size,quality,if(done) "${reps.size} / ${challenge.targetValue} quality reps" else "${reps.size} / ${challenge.targetValue} quality reps")
    }
}
