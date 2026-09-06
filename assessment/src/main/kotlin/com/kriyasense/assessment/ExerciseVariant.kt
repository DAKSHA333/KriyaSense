package com.kriyasense.assessment

/** Manually selected training variants; not medical recommendations or mobility classifications. */
data class ExerciseVariant(val id: String,val exerciseType: ExerciseType,val displayName: String,val description: String,val setup: String,val cues: List<String>,val checks: List<String>,val experimental: Boolean=false)
object ExerciseVariants {
    val squatStandard=ExerciseVariant("SQUAT_STANDARD",ExerciseType.SQUAT,"Standard Squat","Standard configured squat range.","Use a clear side view.",listOf("Lower with control","Return to standing"),listOf("Knee angle","ROM","Configured form rules"))
    val squatChair=ExerciseVariant("SQUAT_CHAIR",ExerciseType.SQUAT,"Chair Squat","Use a chair as a setup aid; chair contact is not detected.","Keep your full body and chair setup in view.",listOf("Lower under control","Use the chair as your chosen aid"),listOf("Knee angle","Configured movement range"))
    val squatLimited=ExerciseVariant("SQUAT_LIMITED_ROM",ExerciseType.SQUAT,"Limited-ROM Squat","Limited-ROM variant with an explicit shallower configured target.","Use a clear side view.",listOf("Move within your selected range","Return to standing"),listOf("Knee angle","Configured movement range"))
    val pushStandard=ExerciseVariant("PUSH_UP_STANDARD",ExerciseType.PUSH_UP,"Standard Push-Up","Standard configured push-up.","Keep shoulders, elbows, wrists and hips visible.",listOf("Lower with control"),listOf("Elbow angle","Movement range"))
    val pushWall=ExerciseVariant("PUSH_UP_WALL",ExerciseType.PUSH_UP,"Wall Push-Up","Wall setup selected manually; wall distance is not detected.","Keep shoulders, elbows, wrists and hips visible.",listOf("Use controlled arm range"),listOf("Elbow angle","Configured movement range"))
    val curlStandard=ExerciseVariant("BICEP_CURL_STANDARD",ExerciseType.BICEP_CURL,"Standard Bicep Curl","Standard configured curl.","Keep both arms visible.",listOf("Curl and return"),listOf("Elbow angle","Curl range"))
    val curlSeated=ExerciseVariant("BICEP_CURL_SEATED",ExerciseType.BICEP_CURL,"Seated Bicep Curl","Seated setup selected manually; sitting is not detected.","Keep shoulders, elbows and wrists visible.",listOf("Curl and return"),listOf("Elbow angle","Curl range"))
    fun forExercise(type: ExerciseType)=when(type) { ExerciseType.SQUAT->listOf(squatStandard,squatChair,squatLimited); ExerciseType.PUSH_UP->listOf(pushStandard,pushWall); ExerciseType.BICEP_CURL->listOf(curlStandard,curlSeated); else->listOf(ExerciseVariant("${type.name}_STANDARD",type,type.displayName,"Standard configured movement.","Keep required joints visible.",emptyList(),emptyList(),type.experimental)) }
    fun standardId(type: ExerciseType)=forExercise(type).first().id
    fun squatConfig(variantId: String)=when(variantId) { "SQUAT_CHAIR"->SquatConfig(bottomAngle=120.0); "SQUAT_LIMITED_ROM"->SquatConfig(bottomAngle=130.0); else->SquatConfig() }
}
