package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.*
import kotlin.math.*

enum class GhostReadiness { WAITING, CALIBRATING, TRACKING_LOST, READY, REPOSITION }
data class GhostOutput(
    val readiness: GhostReadiness,
    val joints: Map<Int,Point>?=null,
    val progress: Double=0.0,
    val calibratedBody: ProjectedBody?=null,
    val calibrationDebug: GhostCalibrationDebug?=null,
    val personalized: Boolean=false
)
data class GhostCalibrationDebug(
    val accepted: Int,
    val required: Int=12,
    val rejected: String,
    val stableMs: Long,
    val visibility: String,
    val movement: String,
    val missing: List<String>
)

/** Session-owned, read-only presentation state. It never writes to an assessment engine or result. */
class GhostPoseController(val profile: MirrorCoachProfile) {
    private enum class SquatGuidePhase { IDLE, DESCENDING, BOTTOM, ASCENDING }

    private val samples=mutableListOf<PoseFrame>()
    private var body: ProjectedBody?=null
    private var lastTime: Long?=null
    private var progress=0.0
    private var optionIndex: Int?=null
    private var squatGuidePhase=SquatGuidePhase.IDLE
    private var squatReturnRequested=false
    private var squatBottomReachedAt: Long?=null
    private var calibrationDurationMs=0L
    private var fallbackBody: ProjectedBody?=null
    private var lastJoints: Map<Int,Point>?=null

    fun update(frame: PoseFrame?,live: LiveAssessment,running: Boolean): GhostOutput {
        if(running && frame!=null && fallbackBody==null && profile.referenceModel==ReferenceModel.SQUAT_FRONT)
            fallbackBody=defaultSquatBody(frame)
        if(!running) return hide(GhostReadiness.WAITING,false,debug(frame,live,"SESSION_NOT_RUNNING",0,0),false)
        if(frame==null) return hide(GhostReadiness.TRACKING_LOST,false,debug(null,live,"NO_POSE_FRAME",0,0))
        val selectedIndex=optionIndex ?: profile.calibrationOptions.indices.firstOrNull {
            frame.assessRequiredLandmarks(profile.calibrationOptions[it]).sufficient
        }
        if(selectedIndex==null) {
            val issue=firstLandmarkIssue(frame,profile.calibrationOptions.first())
            return hide(GhostReadiness.TRACKING_LOST,false,debug(frame,live,issue?.reason?:"CALIBRATION_LANDMARKS_UNRELIABLE",0,0))
        }
        val criticalIssue=firstLandmarkIssue(frame,profile.criticalOptions[selectedIndex])
        if(criticalIssue!=null)
            return hide(GhostReadiness.TRACKING_LOST,false,debug(frame,live,criticalIssue.reason,0,0))
        val previous=lastTime
        if(previous!=null && frame.timestampMs<=previous)
            return hide(GhostReadiness.TRACKING_LOST,false,debug(frame,live,"FRAME_TIME_NOT_INCREASING",0,0))
        if(previous!=null && frame.timestampMs-previous>250)
            return hide(GhostReadiness.TRACKING_LOST,false,debug(frame,live,"FRAME_TIMEOUT",0,0))
        lastTime=frame.timestampMs
        if(!viewMatches(frame,profile)) return hide(GhostReadiness.WAITING,false,debug(frame,live,"BODY_NOT_FRONT_FACING",0,0))
        val calibrated=body
        if(calibrated!=null && placementChanged(frame,calibrated,profile))
            return hide(GhostReadiness.REPOSITION,true,debug(frame,live,"ANKLE_POSITION_CHANGED",0,0))
        if(body==null) {
            optionIndex=selectedIndex
            val calibrationIds=profile.calibrationOptions[selectedIndex]
            val calibrationIssue=firstLandmarkIssue(frame,calibrationIds)
            if(calibrationIssue!=null)
                return hide(GhostReadiness.CALIBRATING,false,debug(frame,live,calibrationIssue.reason,0,0))
            val stabilityIssue=samples.firstOrNull()?.let { stabilityIssue(it,frame,calibrationIds) }
            if(stabilityIssue!=null) {
                samples.clear(); samples.add(frame)
                val reason=if(stabilityIssue.landmark=="FRAME_DIMENSIONS") "FRAME_DIMENSIONS_CHANGED" else
                    "MOVEMENT_TOO_HIGH (${stabilityIssue.landmark} ${stabilityIssue.distance.roundToInt()}px > ${stabilityIssue.threshold.roundToInt()}px)"
                return visualOutput(GhostReadiness.CALIBRATING,debug(frame,live,reason,1,0))
            }
            samples.add(frame)
            val stableMs=frame.timestampMs-samples.first().timestampMs
            if(samples.size<12 || stableMs<800) return visualOutput(GhostReadiness.CALIBRATING,
                debug(frame,live,"NONE",samples.size,stableMs))
            body=calibrate(profile,samples,calibrationIds) ?: run {
                samples.clear()
                return visualOutput(GhostReadiness.CALIBRATING,debug(frame,live,"CALIBRATION_GEOMETRY_INVALID",0,0))
            }
            calibrationDurationMs=stableMs
            samples.clear(); progress=if(profile.movementType==MirrorMovementType.STATIC_HOLD) 1.0 else 0.0
        }
        val dt=if(previous==null) 0 else frame.timestampMs-previous
        progress=when {
            profile.movementType==MirrorMovementType.STATIC_HOLD -> 1.0
            profile.exerciseType==ExerciseType.SQUAT -> advanceSquatGuide(live.state,frame.timestampMs,dt)
            else -> {
                val target=when(live.movementState) {
                    "MOVING" -> 1.0
                    "RETURNING","READY" -> 0.0
                    else -> progress
                }
                GhostPoseGeometry.advance(progress,target,dt)
            }
        }
        val joints=runCatching { GhostPoseGeometry.generate(profile,body!!,progress) }.getOrNull()
        if(joints==null || joints.values.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 })
            return hide(GhostReadiness.REPOSITION,true,debug(frame,live,"REFERENCE_COORDINATES_INVALID",0,0))
        lastJoints=joints
        return GhostOutput(GhostReadiness.READY,joints,progress,body,
            debug(frame,live,"NONE",12,calibrationDurationMs),true)
    }

    private fun hide(state: GhostReadiness,recalibrate: Boolean,calibrationDebug: GhostCalibrationDebug?=null,keepTrainer: Boolean=true): GhostOutput {
        samples.clear(); lastTime=null
        if(recalibrate) { body=null; progress=0.0; optionIndex=null; calibrationDurationMs=0; lastJoints=null; resetSquatGuide() }
        return if(keepTrainer) visualOutput(state,calibrationDebug) else GhostOutput(state,calibrationDebug=calibrationDebug)
    }

    private fun visualOutput(state: GhostReadiness,calibrationDebug: GhostCalibrationDebug?): GhostOutput {
        val personalizedBody=body
        val visualBody=personalizedBody ?: fallbackBody
        val visualJoints=lastJoints ?: visualBody?.let {
            runCatching { GhostPoseGeometry.generate(profile,it,if(personalizedBody==null) 0.0 else progress) }.getOrNull()
        }
        return GhostOutput(state,visualJoints,progress,visualBody,calibrationDebug,personalizedBody!=null)
    }

    private data class LandmarkIssue(val landmark: String,val reason: String)
    private data class StabilityIssue(val landmark: String,val distance: Double,val threshold: Double)

    private fun firstLandmarkIssue(frame: PoseFrame,ids: Set<Int>): LandmarkIssue? {
        for(id in ids.sorted()) {
            val name=landmarkName(id); val landmark=frame.landmarks[id]
            if(landmark==null) return LandmarkIssue(name,"${name}_MISSING")
            if(!landmark.visibility.isFinite() || !landmark.presence.isFinite())
                return LandmarkIssue(name,"${name}_CONFIDENCE_INVALID")
            if(min(landmark.visibility,landmark.presence)<.65)
                return LandmarkIssue(name,"${name}_LOW_CONFIDENCE")
            if(landmark.position.x !in 0.0..1.0 || landmark.position.y !in 0.0..1.0)
                return LandmarkIssue(name,"${name}_OUT_OF_FRAME")
        }
        return null
    }

    private fun stabilityIssue(first: PoseFrame,current: PoseFrame,ids: Set<Int>): StabilityIssue? {
        if(first.width!=current.width || first.height!=current.height)
            return StabilityIssue("FRAME_DIMENSIONS",Double.POSITIVE_INFINITY,0.0)
        fun pixel(frame: PoseFrame,id: Int)=Point(frame.landmarks.getValue(id).position.x*frame.width,frame.landmarks.getValue(id).position.y*frame.height)
        val scale=if(11 in ids && 23 in ids) Geometry.distance(pixel(first,11),pixel(first,23)) else first.height*.2
        val threshold=max(8.0,scale*.05)
        return ids.sorted().map { id -> StabilityIssue(landmarkName(id),Geometry.distance(pixel(first,id),pixel(current,id)),threshold) }
            .firstOrNull { it.distance>it.threshold }
    }

    private fun debug(frame: PoseFrame?,live: LiveAssessment,rejected: String,accepted: Int,stableMs: Long): GhostCalibrationDebug? {
        if(profile.referenceModel!=ReferenceModel.SQUAT_FRONT) return null
        val required=profile.calibrationLandmarks
        val missing=if(frame==null) required.sorted().map(::landmarkName) else required.sorted().filter { firstLandmarkIssue(frame,setOf(it))!=null }.map(::landmarkName)
        val confidence=frame?.let { current -> required.mapNotNull { id -> current.landmarks[id]?.let { min(it.visibility,it.presence) } }.minOrNull() }
        val visibility="assessment=${live.result.visibility.name} • calibrationMin=${confidence?.let { "%.2f".format(it) }?:"n/a"}/0.65"
        return GhostCalibrationDebug(accepted,12,rejected,stableMs,visibility,"${live.movementState}/${live.state.name}",missing)
    }

    private fun landmarkName(id: Int)=when(id) {
        11->"LEFT_SHOULDER"; 12->"RIGHT_SHOULDER"; 13->"LEFT_ELBOW"; 14->"RIGHT_ELBOW"
        15->"LEFT_WRIST"; 16->"RIGHT_WRIST"; 23->"LEFT_HIP"; 24->"RIGHT_HIP"
        25->"LEFT_KNEE"; 26->"RIGHT_KNEE"; 27->"LEFT_ANKLE"; 28->"RIGHT_ANKLE"
        else->"LANDMARK_$id"
    }

    /**
     * A detected descent starts one complete reference cycle. The live state starts and releases
     * the guide, but live joint coordinates never shape it. Finishing the reference descent even
     * when a shallow attempt returns to standing keeps the ghost useful as an upcoming target.
     */
    private fun advanceSquatGuide(state: SquatState,now: Long,dtMs: Long): Double {
        val dt=dtMs.coerceIn(0,250).toDouble()
        if(squatGuidePhase==SquatGuidePhase.IDLE && (state==SquatState.DESCENDING || state==SquatState.BOTTOM))
            squatGuidePhase=SquatGuidePhase.DESCENDING
        if(squatGuidePhase!=SquatGuidePhase.IDLE && (state==SquatState.ASCENDING || state==SquatState.STANDING))
            squatReturnRequested=true
        when(squatGuidePhase) {
            SquatGuidePhase.IDLE -> progress=0.0
            SquatGuidePhase.DESCENDING -> {
                progress=(progress+dt/600.0).coerceAtMost(1.0)
                if(progress>=1.0) {
                    squatGuidePhase=SquatGuidePhase.BOTTOM
                    squatBottomReachedAt=now
                }
            }
            SquatGuidePhase.BOTTOM -> {
                progress=1.0
                if(squatReturnRequested && now-(squatBottomReachedAt?:now)>=120L)
                    squatGuidePhase=SquatGuidePhase.ASCENDING
            }
            SquatGuidePhase.ASCENDING -> {
                progress=(progress-dt/650.0).coerceAtLeast(0.0)
                if(progress<=0.0) resetSquatGuide()
            }
        }
        return progress
    }

    private fun resetSquatGuide() {
        squatGuidePhase=SquatGuidePhase.IDLE
        squatReturnRequested=false
        squatBottomReachedAt=null
        progress=0.0
    }

    companion object {
        private val segments=listOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,28 to 30)

        fun calibrate(profile: MirrorCoachProfile,frames: List<PoseFrame>,landmarks: Set<Int> = profile.calibrationLandmarks): ProjectedBody? {
            if(frames.isEmpty()) return null
            val first=frames.first()
            if(first.width<=0 || first.height<=0 || frames.any { it.width!=first.width || it.height!=first.height ||
                    !it.assessRequiredLandmarks(landmarks).sufficient }) return null
            fun median(values: List<Double>)=values.sorted()[values.size/2]
            fun point(id: Int)=Point(median(frames.map { it.landmarks.getValue(id).position.x*it.width }),median(frames.map { it.landmarks.getValue(id).position.y*it.height }))
            fun stableLength(a: Int,b: Int): Boolean {
                if(frames.any { !it.assessRequiredLandmarks(setOf(a,b)).sufficient }) return false
                val values=frames.map { current ->
                    val one=current.landmarks.getValue(a).position; val two=current.landmarks.getValue(b).position
                    Geometry.distance(Point(one.x*current.width,one.y*current.height),Point(two.x*current.width,two.y*current.height))
                }
                val middle=median(values); val tolerance=max(6.0,middle*.15)
                return middle.isFinite() && middle>=6.0 && values.all { abs(it-middle)<=tolerance }
            }
            val optionalArms=if(profile.referenceModel==ReferenceModel.SQUAT_FRONT) buildSet {
                if(stableLength(11,13) && stableLength(13,15)) addAll(setOf(13,15))
                if(stableLength(12,14) && stableLength(14,16)) addAll(setOf(14,16))
            } else emptySet()
            val points=(landmarks+optionalArms).associateWith(::point)
            val lengths=segments.filter { (a,b)->a in points && b in points }.associate { (a,b)->
                GhostPoseGeometry.canonical(a,b) to Geometry.distance(points.getValue(a),points.getValue(b))
            }
            if(lengths.values.any { !it.isFinite() || it<6.0 }) return null
            val centerIds=listOf(23,24).filter { it in points }.ifEmpty { listOf(11,12).filter { it in points } }
            if(centerIds.isEmpty()) return null
            val centerX=centerIds.map { points.getValue(it).x }.average()
            val floorIds=listOf(27,28,29,30).filter { it in points }
            val floorY=(if(floorIds.isEmpty()) points.values else floorIds.map { points.getValue(it) }).maxOf { it.y }
            val sideDirection=inferSideDirection(profile,points,centerX)
            return ProjectedBody(points,lengths,first.width,first.height,centerX,floorY,sideDirection)
        }

        private fun defaultSquatBody(frame: PoseFrame): ProjectedBody? {
            if(frame.width<=0 || frame.height<=0) return null
            val width=frame.width.toDouble(); val height=frame.height.toDouble()
            val centerCandidates=listOf(11,12,23,24).mapNotNull { id -> frame.landmarks[id]?.position }
                .filter { it.x.isFinite() && it.x in 0.0..1.0 }
            val center=(centerCandidates.map { it.x }.averageOrNull()?.times(width) ?: width*.5).coerceIn(width*.18,width*.82)
            val ankleCandidates=listOf(27,28).mapNotNull { id -> frame.landmarks[id]?.position }
                .filter { it.y.isFinite() && it.y in 0.0..1.0 }
            val floor=(ankleCandidates.maxOfOrNull { it.y*height } ?: height*.88).coerceIn(height*.68,height*.92)
            val shoulderHalf=min(height*.075,width*.17)
            val hipHalf=min(height*.047,width*.11)
            val stanceHalf=min(height*.068,width*.15)
            val shoulderY=floor-height*.60; val hipY=floor-height*.36; val kneeY=floor-height*.18
            val points=mapOf(
                11 to Point(center-shoulderHalf,shoulderY),12 to Point(center+shoulderHalf,shoulderY),
                23 to Point(center-hipHalf,hipY),24 to Point(center+hipHalf,hipY),
                25 to Point(center-stanceHalf,kneeY),26 to Point(center+stanceHalf,kneeY),
                27 to Point(center-stanceHalf,floor),28 to Point(center+stanceHalf,floor)
            )
            val lengths=segments.filter { (a,b)->a in points && b in points }.associate { (a,b)->
                GhostPoseGeometry.canonical(a,b) to Geometry.distance(points.getValue(a),points.getValue(b))
            }
            return ProjectedBody(points,lengths,frame.width,frame.height,center,floor,1)
        }

        private fun inferSideDirection(profile: MirrorCoachProfile,p: Map<Int,Point>,centerX: Double): Int {
            if(profile.preferredView==PreferredView.FRONT) return 1
            val shoulder=listOf(11,12).filter { it in p }.map { p.getValue(it).x }.averageOrNull()
            val hips=listOf(23,24).filter { it in p }.map { p.getValue(it).x }.averageOrNull()
            val directional=(shoulder?:centerX)-(hips?:centerX)
            if(abs(directional)>4) return if(directional<0) -1 else 1
            val ankles=listOf(27,28).filter { it in p }
            val lead=ankles.maxByOrNull { abs(p.getValue(it).x-centerX) }
            return if(lead!=null && p.getValue(lead).x<centerX) -1 else 1
        }

        private fun viewMatches(frame: PoseFrame,profile: MirrorCoachProfile): Boolean {
            val p=frame.landmarks
            if(listOf(11,12,23,24).any { it !in p }) return profile.preferredView==PreferredView.FRONT
            fun pixel(id: Int)=Point(p.getValue(id).position.x*frame.width,p.getValue(id).position.y*frame.height)
            val shoulderMid=Point((pixel(11).x+pixel(12).x)/2,(pixel(11).y+pixel(12).y)/2)
            val hipMid=Point((pixel(23).x+pixel(24).x)/2,(pixel(23).y+pixel(24).y)/2)
            val torso=Geometry.distance(shoulderMid,hipMid).coerceAtLeast(1.0)
            val width=Geometry.distance(pixel(11),pixel(12))/torso
            return if(profile.preferredView==PreferredView.FRONT) width>=.35 else width<.65
        }

        private fun stableAgainst(first: PoseFrame,current: PoseFrame,ids: Set<Int>): Boolean {
            if(first.width!=current.width || first.height!=current.height) return false
            fun pixel(frame: PoseFrame,id: Int)=Point(frame.landmarks.getValue(id).position.x*frame.width,frame.landmarks.getValue(id).position.y*frame.height)
            val scale=if(11 in ids && 23 in ids) Geometry.distance(pixel(first,11),pixel(first,23)) else first.height*.2
            return ids.all { Geometry.distance(pixel(first,it),pixel(current,it))<=max(8.0,scale*.05) }
        }

        private fun placementChanged(frame: PoseFrame,body: ProjectedBody,profile: MirrorCoachProfile): Boolean {
            if(frame.width!=body.width || frame.height!=body.height) return true
            fun current(id: Int)=frame.landmarks[id]?.position?.let { Point(it.x*frame.width,it.y*frame.height) }
            val anchors=when(profile.referenceModel) {
                ReferenceModel.SQUAT_FRONT,ReferenceModel.LUNGE_SIDE,ReferenceModel.PLANK_SIDE -> listOf(27,28)
                ReferenceModel.PUSH_UP_SIDE -> listOf(15,16)
                ReferenceModel.BICEP_CURL_FRONT,ReferenceModel.SHOULDER_PRESS_FRONT -> listOf(11,12)
                ReferenceModel.CALF_RAISE_FRONT -> emptyList()
                ReferenceModel.JUMPING_JACK_FRONT -> listOf(23,24)
            }.filter { it in body.points }
            val scale=body.lengths.values.average().coerceAtLeast(20.0)
            if(profile.referenceModel==ReferenceModel.CALF_RAISE_FRONT) {
                val ankles=listOf(27,28).filter { it in body.points }
                return ankles.any { id->current(id)?.let { abs(it.x-body.points.getValue(id).x)>scale*.22 }!=false }
            }
            if(profile.referenceModel==ReferenceModel.JUMPING_JACK_FRONT) {
                val hips=listOf(23,24).filter { it in body.points }
                return hips.any { id->current(id)?.let { abs(it.x-body.points.getValue(id).x)>scale*.22 }!=false }
            }
            return anchors.any { id->current(id)?.let { Geometry.distance(it,body.points.getValue(id))>scale*.22 }!=false }
        }

        private fun List<Double>.averageOrNull()=if(isEmpty()) null else average()
    }
}
