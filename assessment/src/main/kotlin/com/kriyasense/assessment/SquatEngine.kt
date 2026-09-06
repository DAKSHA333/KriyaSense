package com.kriyasense.assessment

import kotlin.math.*

/** Single-owner engine. Call process/pause/resume from one thread (the demo uses main). */
class SquatEngine(val config: SquatConfig = SquatConfig(), private val variantId: String = "SQUAT_STANDARD") : AssessmentEngine {
    private val gate = VisibilityAssessment(config)
    private val rules = FormRules(config)
    private val coaching = CoachingFeedbackController()
    private var state = SquatState.STANDING
    private var lastTimestamp: Long? = null
    private var clock = 0L
    private var recovery = 0
    private var paused = false
    private var armed = false
    private var baseline: Measurement? = null
    private var pending: String? = null
    private var pendingSince = 0L
    private var attemptStart = 0L
    private var minAngle = 180.0
    private var maxDrop = 0.0
    private var maxDrift = 0.0
    private var attemptConfidence = 1.0
    private var recognized = false
    private var tutMs = 0L
    private var confidenceSum = 0.0
    private var measuredFrames = 0
    private val reps = mutableListOf<Rep>()
    private val attemptErrors = linkedMapOf<String,FormError>()
    private var snapshot = LiveAssessment(SessionResult(variantId=variantId))

    override fun current() = snapshot
    override fun pause(): LiveAssessment {
        paused = true; interrupt()
        snapshot = snapshot.copy(result = snapshot.result.copy(status=Status.PAUSED), corrections=emptyList(), instruction="Session paused")
        return snapshot
    }
    override fun resume() { paused=false; recovery=0; lastTimestamp=null }
    override fun finish(): SessionResult = snapshot.result.copy(timeline=reps.toList())
    private fun interrupt() {
        armed=false; baseline=null; state=SquatState.STANDING; pending=null; recognized=false
        attemptErrors.clear(); recovery=0
    }
    private fun feedback(candidate: CoachingFeedback, timestampMs: Long) = coaching.select(candidate, timestampMs)
    private fun rangeFeedback(errors: List<FormError>): CoachingFeedback {
        val depth = errors.firstOrNull { it.code == "INSUFFICIENT_DEPTH" }
        return if (depth != null) CoachingFeedback(depth.code, "Go lower", CoachingPriority.RANGE_OF_MOTION)
        else CoachingFeedback("REP_NOT_COMPLETED", "Rep not completed", CoachingPriority.RANGE_OF_MOTION)
    }
    private fun stable(key: String?, timestamp: Long): Boolean {
        if (key == null) { pending=null; return false }
        if (pending != key) { pending=key; pendingSince=timestamp; return false }
        return timestamp-pendingSince >= config.debounceMs
    }
    override fun process(frame: PoseFrame): LiveAssessment {
        if (paused) return snapshot
        val previous = lastTimestamp
        if (previous != null && frame.timestampMs <= previous) return snapshot
        lastTimestamp=frame.timestampMs
        val gap = previous != null && frame.timestampMs-previous > config.maxFrameGapMs
        if (gap) interrupt()
        val visibility=gate.assess(frame)
        val m = if (visibility.sufficient) Measurements.from(frame,visibility.confidence,config) else null
        if (m == null) {
            interrupt()
            val instruction = if (visibility.sufficient) "Step closer and show a clear shoulder, hip, knee and ankle outline" else visibility.instruction
            val message = feedback(CoachingFeedback("VISIBILITY", instruction, CoachingPriority.VISIBILITY), frame.timestampMs)
            snapshot = snapshot.copy(result=snapshot.result.copy(status=Status.INSUFFICIENT_VISIBILITY,visibility=Visibility.INSUFFICIENT),
                kneeAngle=null,currentRomPercentage=null,state=state,corrections=emptyList(),
                instruction=message.message, coaching=message)
            return snapshot
        }
        recovery++
        if (recovery < config.recoveryFrames) {
            val message = feedback(CoachingFeedback("VISIBILITY_STABILIZING", "Keep your body visible while tracking stabilizes", CoachingPriority.VISIBILITY), frame.timestampMs)
            snapshot=snapshot.copy(result=snapshot.result.copy(status=Status.INSUFFICIENT_VISIBILITY,visibility=Visibility.INSUFFICIENT),
                kneeAngle=null,currentRomPercentage=null,corrections=emptyList(),instruction=message.message,coaching=message)
            return snapshot
        }
        val dt = if (recovery == config.recoveryFrames || previous == null || gap) 0L else frame.timestampMs-previous
        clock+=dt
        confidenceSum+=m.confidence; measuredFrames++
        var liveErrors = emptyList<FormError>()
        var closedRep: Boolean? = null
        if (!armed) {
            if (stable(if (m.knee>=config.standingAngle) "arm" else null,clock)) {
                armed=true; baseline=m; pending=null
            }
        } else {
            if (state != SquatState.STANDING) {
                val b=baseline!!
                minAngle=min(minAngle,m.knee)
                maxDrop=max(maxDrop,(m.hipY-b.hipY)/b.legLength)
                maxDrift=max(maxDrift,Geometry.distance(m.ankle,b.ankle)/b.legLength)
                attemptConfidence=min(attemptConfidence,m.confidence)
                recognized=maxDrop>=config.minimumHipDrop && maxDrift<=config.maximumAnkleDrift
                if (recognized) {
                    tutMs+=dt
                    liveErrors=rules.live(m)
                    liveErrors.forEach { attemptErrors[it.code]=it }
                }
            }
            when(state) {
                SquatState.STANDING -> {
                    if (m.knee>=config.standingAngle) baseline=m
                    if (stable(if(m.knee<config.descentAngle) "descend" else null,clock)) {
                        state=SquatState.DESCENDING; attemptStart=pendingSince; minAngle=m.knee
                        maxDrop=(m.hipY-baseline!!.hipY)/baseline!!.legLength; maxDrift=0.0
                        attemptConfidence=m.confidence; attemptErrors.clear(); pending=null
                    }
                }
                SquatState.DESCENDING -> {
                    val next=when { m.knee>=config.standingAngle -> "incomplete"; m.knee<=config.bottomAngle -> "bottom"; else -> null }
                    if (stable(next,clock)) {
                        if (next=="bottom") { state=SquatState.BOTTOM; pending=null }
                        else { liveErrors=closeRep(false); closedRep=false; state=SquatState.STANDING; pending=null; baseline=m }
                    }
                }
                SquatState.BOTTOM -> {
                    if (stable(if(m.knee>minAngle+config.riseHysteresis) "ascend" else null,clock)) {
                        state=SquatState.ASCENDING; pending=null
                    }
                }
                SquatState.ASCENDING -> {
                    if (stable(if(m.knee>=config.standingAngle) "complete" else null,clock)) {
                        liveErrors=closeRep(true); closedRep=true; state=SquatState.STANDING; pending=null; baseline=m
                    }
                }
            }
        }
        val completed=reps.filter { it.complete }
        val drift=FormDrift.analyze(reps)
        val result=SessionResult(
            status=if(recognized || reps.isNotEmpty()) Status.VALID else Status.NOT_DETECTED,
            confidence=confidenceSum/measuredFrames, completeReps=completed.size,incompleteReps=reps.size-completed.size,
            averageRepDurationSeconds=completed.takeIf { it.isNotEmpty() }?.map { it.durationSeconds }?.average(),
            romPercentage=reps.takeIf { it.isNotEmpty() }?.map { it.romPercentage }?.average(),
            timeUnderTensionSeconds=tutMs/1000.0,tutFactor=if(clock>0) tutMs.toDouble()/clock else null,
            formFactor=reps.takeIf { it.isNotEmpty() }?.count { it.formErrors.isEmpty() }?.toDouble()?.div(reps.size),
            formErrors=reps.flatMap { it.formErrors }.distinctBy { it.code }, visibility=Visibility.SUFFICIENT,timeline=reps.toList(),driftDetected=drift.state==FormDriftState.DRIFTING,driftReasons=drift.reasons.map { it.name },variantId=variantId)
        val candidate = when {
            liveErrors.any { it.code == "EXCESSIVE_FORWARD_LEAN" || it.code == "KNEE_ALIGNMENT" } ->
                CoachingFeedback(liveErrors.first().code, liveErrors.first().message, CoachingPriority.FORM)
            closedRep == false -> rangeFeedback(liveErrors)
            liveErrors.any { it.code == "MOVEMENT_TOO_FAST" } ->
                CoachingFeedback("MOVEMENT_TOO_FAST", "Slow down", CoachingPriority.FORM)
            drift.state==FormDriftState.DRIFTING -> CoachingFeedback("FORM_DRIFT",FormDrift.message(drift),CoachingPriority.DRIFT)
            closedRep == true -> CoachingFeedback("GOOD_REP", "Good rep", CoachingPriority.SUCCESS)
            !armed -> CoachingFeedback("ESTABLISH_START", "Return to starting position", CoachingPriority.NEUTRAL, false)
            !recognized && reps.isEmpty() -> CoachingFeedback("READY", "Ready—lower your hips with feet planted", CoachingPriority.NEUTRAL, false)
            state == SquatState.DESCENDING -> CoachingFeedback("LOWER", "Keep lowering", CoachingPriority.NEUTRAL, false)
            state == SquatState.BOTTOM -> CoachingFeedback("HOLD", "Hold the position", CoachingPriority.NEUTRAL, false)
            state == SquatState.ASCENDING -> CoachingFeedback("RETURN", "Return to starting position", CoachingPriority.NEUTRAL, false)
            else -> CoachingFeedback("CONTROLLED_MOVEMENT", "Controlled movement", CoachingPriority.NEUTRAL, false)
        }
        val message = feedback(candidate, frame.timestampMs)
        snapshot=LiveAssessment(result,m.knee,Geometry.rom(m.knee,config),state,message.message,liveErrors,message,primaryMetricLabel="Knee angle",primaryMetricValue=m.knee,primaryMetricUnit="°")
        return snapshot
    }
    private fun closeRep(complete: Boolean): List<FormError> {
        if (!recognized) { attemptErrors.clear(); return emptyList() }
        val duration=(clock-attemptStart)/1000.0
        rules.end(minAngle,duration,complete,attemptConfidence).forEach { attemptErrors[it.code]=it }
        val errors=attemptErrors.values.toList()
        reps+=Rep(reps.size+1,complete,attemptStart/1000.0,clock/1000.0,duration,Geometry.rom(minAngle,config),attemptConfidence,errors)
        recognized=false; attemptErrors.clear()
        return errors
    }
}
