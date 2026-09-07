package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.ExerciseType

enum class PreferredView { FRONT, SIDE }
enum class MirrorMovementType { REP_TRAJECTORY, STATIC_HOLD }
enum class ReferenceModel { SQUAT_FRONT, LUNGE_SIDE, PUSH_UP_SIDE, BICEP_CURL_FRONT, SHOULDER_PRESS_FRONT, CALF_RAISE_FRONT, PLANK_SIDE, JUMPING_JACK_FRONT }

data class MirrorCoachProfile(
    val exerciseType: ExerciseType,
    val preferredView: PreferredView,
    val movementType: MirrorMovementType,
    val calibrationLandmarks: Set<Int>,
    val criticalLandmarks: Set<Int>,
    val referenceModel: ReferenceModel,
    val renderedEdges: List<Pair<Int,Int>>,
    val prescribedProperties: List<String>,
    val unprescribedProperties: List<String>,
    val waitingText: String,
    val calibrationOptions: List<Set<Int>> = listOf(calibrationLandmarks),
    val criticalOptions: List<Set<Int>> = listOf(criticalLandmarks)
) { val exerciseId: String get() = exerciseType.id }

object MirrorCoachProfiles {
    private val shoulders=setOf(11,12); private val hips=setOf(23,24)
    private val arms=setOf(11,12,13,14,15,16); private val legs=setOf(23,24,25,26,27,28)
    private val torso=listOf(11 to 12,11 to 23,12 to 24,23 to 24)
    private val lower=listOf(23 to 25,25 to 27,24 to 26,26 to 28)
    private val upper=listOf(11 to 13,13 to 15,12 to 14,14 to 16)

    private val profiles=ExerciseType.entries.associateWith { type -> when(type) {
        ExerciseType.SQUAT -> MirrorCoachProfile(type,PreferredView.FRONT,MirrorMovementType.REP_TRAJECTORY,
            arms+legs,shoulders+legs,ReferenceModel.SQUAT_FRONT,torso+upper+lower,
            listOf("stance","symmetric projected descent","hip lowering","knee lateral path","ankle contact","rhythm"),
            listOf("torso lean","shin angle","hip flexion","3D depth","arm position"),"Stand facing the camera with your full body visible")
        ExerciseType.LUNGE -> MirrorCoachProfile(type,PreferredView.SIDE,MirrorMovementType.REP_TRAJECTORY,
            shoulders+legs,legs,ReferenceModel.LUNGE_SIDE,listOf(23 to 24)+lower,
            listOf("projected bilateral knee range","hip lowering","foot placement","rhythm"),
            listOf("3D stride depth","pelvic rotation","knee alignment outside the camera plane"),"Turn to a three-quarter side view and keep both legs visible")
        ExerciseType.PUSH_UP -> MirrorCoachProfile(type,PreferredView.SIDE,MirrorMovementType.REP_TRAJECTORY,
            arms+hips,arms,ReferenceModel.PUSH_UP_SIDE,upper,
            listOf("projected elbow range","hand contact","rhythm"),
            listOf("spine alignment","scapular motion","wrist loading"),"Turn sideways and keep one arm fully visible",
            listOf(setOf(11,13,15,23),setOf(12,14,16,24)),listOf(setOf(11,13,15),setOf(12,14,16)))
        ExerciseType.BICEP_CURL -> MirrorCoachProfile(type,PreferredView.FRONT,MirrorMovementType.REP_TRAJECTORY,
            arms,arms,ReferenceModel.BICEP_CURL_FRONT,listOf(11 to 12)+upper,
            listOf("symmetric elbow range","upper-arm position","rhythm"),
            listOf("wrist rotation","load path","shoulder biomechanics"),"Face the camera and keep both arms visible")
        ExerciseType.SHOULDER_PRESS -> MirrorCoachProfile(type,PreferredView.FRONT,MirrorMovementType.REP_TRAJECTORY,
            arms,arms,ReferenceModel.SHOULDER_PRESS_FRONT,listOf(11 to 12)+upper,
            listOf("symmetric press path","elbow extension","overhead finish","rhythm"),
            listOf("spinal position","scapular rotation","wrist loading"),"Face the camera and keep both arms visible")
        ExerciseType.CALF_RAISE -> MirrorCoachProfile(type,PreferredView.FRONT,MirrorMovementType.REP_TRAJECTORY,
            legs+setOf(29,30),setOf(27,28,29,30),ReferenceModel.CALF_RAISE_FRONT,lower+listOf(27 to 29,28 to 30),
            listOf("symmetric projected heel lift","stance","rhythm"),
            listOf("ankle loading","balance","true vertical displacement"),"Face the camera and keep ankles and heels visible")
        ExerciseType.PLANK -> MirrorCoachProfile(type,PreferredView.SIDE,MirrorMovementType.STATIC_HOLD,
            shoulders+hips+setOf(27,28),setOf(11,12,23,24,27,28),ReferenceModel.PLANK_SIDE,
            listOf(11 to 23,23 to 27,12 to 24,24 to 28),listOf("projected shoulder–hip–ankle alignment"),
            listOf("spinal curvature","pelvic rotation","shoulder loading"),"Turn sideways and keep one full side visible",
            listOf(setOf(11,23,27),setOf(12,24,28)),listOf(setOf(11,23,27),setOf(12,24,28)))
        ExerciseType.JUMPING_JACK -> MirrorCoachProfile(type,PreferredView.FRONT,MirrorMovementType.REP_TRAJECTORY,
            arms+legs,arms+legs,ReferenceModel.JUMPING_JACK_FRONT,torso+upper+lower,
            listOf("symmetric arm raise","stance opening","centered rhythm"),
            listOf("jump height","landing force","shoulder rotation"),"Face the camera with room around your arms and feet")
    } }

    fun forExercise(type: ExerciseType)=profiles.getValue(type)
    fun all()=ExerciseType.entries.map(::forExercise)
}
