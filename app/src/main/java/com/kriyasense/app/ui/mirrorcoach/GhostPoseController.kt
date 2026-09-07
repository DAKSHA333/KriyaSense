package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.*
import kotlin.math.*

enum class GhostReadiness { WAITING, CALIBRATING, TRACKING_LOST, READY, REPOSITION }
data class GhostOutput(
    val readiness: GhostReadiness,
    val joints: Map<Int,Point>?=null,
    val progress: Double=0.0,
    val calibratedBody: ProjectedBody?=null
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

    fun update(frame: PoseFrame?,live: LiveAssessment,running: Boolean): GhostOutput {
        if(!running) return hide(GhostReadiness.WAITING,false)
        if(frame==null) return hide(GhostReadiness.TRACKING_LOST,false)
        val selectedIndex=optionIndex ?: profile.calibrationOptions.indices.firstOrNull {
            frame.assessRequiredLandmarks(profile.calibrationOptions[it]).sufficient
        }
        if(selectedIndex==null || !frame.assessRequiredLandmarks(profile.criticalOptions[selectedIndex]).sufficient)
            return hide(GhostReadiness.TRACKING_LOST,false)
        val previous=lastTime
        if(previous!=null && (frame.timestampMs<=previous || frame.timestampMs-previous>250))
            return hide(GhostReadiness.TRACKING_LOST,false)
        lastTime=frame.timestampMs
        if(!viewMatches(frame,profile)) return hide(GhostReadiness.WAITING,false)
        val calibrated=body
        if(calibrated!=null && placementChanged(frame,calibrated,profile))
            return hide(GhostReadiness.REPOSITION,true)
        if(body==null) {
            optionIndex=selectedIndex
            val calibrationIds=profile.calibrationOptions[selectedIndex]
            if(!frame.assessRequiredLandmarks(calibrationIds).sufficient) return hide(GhostReadiness.CALIBRATING,false)
            if(samples.isNotEmpty() && !stableAgainst(samples.first(),frame,calibrationIds)) samples.clear()
            samples.add(frame)
            if(samples.size<12 || frame.timestampMs-samples.first().timestampMs<800) return GhostOutput(GhostReadiness.CALIBRATING)
            body=calibrate(profile,samples,calibrationIds) ?: run { samples.clear(); return GhostOutput(GhostReadiness.CALIBRATING) }
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
            return hide(GhostReadiness.REPOSITION,true)
        return GhostOutput(GhostReadiness.READY,joints,progress,body)
    }

    private fun hide(state: GhostReadiness,recalibrate: Boolean): GhostOutput {
        samples.clear(); lastTime=null
        if(recalibrate) { body=null; progress=0.0; optionIndex=null; resetSquatGuide() }
        return GhostOutput(state)
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
            val points=landmarks.associateWith(::point)
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
