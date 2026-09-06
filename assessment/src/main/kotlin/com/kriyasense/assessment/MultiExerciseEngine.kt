package com.kriyasense.assessment

import kotlin.math.abs

enum class ExerciseType(val id: String, val displayName: String, val experimental: Boolean = false) {
    SQUAT("EX_SQUAT_001", "Squat"),
    LUNGE("EX_LUNGE_001", "Lunge"),
    PUSH_UP("EX_PUSH_UP_001", "Push-Up"),
    BICEP_CURL("EX_BICEP_CURL_001", "Bicep Curl"),
    SHOULDER_PRESS("EX_SHOULDER_PRESS_001", "Shoulder Press"),
    CALF_RAISE("EX_CALF_RAISE_001", "Calf Raise", experimental = true),
    PLANK("EX_PLANK_001", "Plank"),
    JUMPING_JACK("EX_JUMPING_JACK_001", "Jumping Jack")
}

/** Conservative, image-space thresholds. They are not medical or biomechanical validation claims. */
data class ExerciseSignal(val start: Boolean, val target: Boolean, val rom: Double, val targetCue: String, val required: Set<Int>,
    val metricLabel: String, val metricValue: Double, val metricUnit: String)

private fun PoseFrame.point(id: Int) = landmarks[id]?.position
private fun PoseFrame.angle(a: Int, b: Int, c: Int) = point(a)?.let { x -> point(b)?.let { y -> point(c)?.let { z -> Geometry.angle(x,y,z) } } }
private fun PoseFrame.averageAngle(a: Triple<Int,Int,Int>, b: Triple<Int,Int,Int>): Double? {
    val x=angle(a.first,a.second,a.third) ?: return null; val y=angle(b.first,b.second,b.third) ?: return null
    return (x+y)/2
}
object ExerciseSignals {
    private val lower=setOf(23,24,25,26,27,28)
    private val upper=setOf(11,12,13,14,15,16)
    fun requiredFor(type: ExerciseType): Set<Int> = when(type) {
        ExerciseType.LUNGE -> lower
        ExerciseType.PUSH_UP -> upper + setOf(23,24)
        ExerciseType.BICEP_CURL, ExerciseType.SHOULDER_PRESS -> upper
        ExerciseType.CALF_RAISE -> lower + setOf(29,30)
        ExerciseType.JUMPING_JACK -> lower + upper
        else -> emptySet()
    }
    fun forType(type: ExerciseType, frame: PoseFrame, heelBaseline: Double?): ExerciseSignal? = when(type) {
        ExerciseType.LUNGE -> frame.averageAngle(Triple(23,25,27),Triple(24,26,28))?.let { knee ->
            ExerciseSignal(knee>=160,knee<=105,((160-knee)/55*100).coerceIn(0.0,100.0),"Go lower",lower,"Knee angle",knee,"°")
        }
        ExerciseType.PUSH_UP -> frame.averageAngle(Triple(11,13,15),Triple(12,14,16))?.let { elbow ->
            ExerciseSignal(elbow>=155,elbow<=95,((155-elbow)/60*100).coerceIn(0.0,100.0),"Go lower",upper+setOf(23,24),"Elbow angle",elbow,"°")
        }
        ExerciseType.BICEP_CURL -> frame.averageAngle(Triple(11,13,15),Triple(12,14,16))?.let { elbow ->
            ExerciseSignal(elbow>=155,elbow<=55,((155-elbow)/100*100).coerceIn(0.0,100.0),"Curl higher",upper,"Elbow angle",elbow,"°")
        }
        ExerciseType.SHOULDER_PRESS -> frame.averageAngle(Triple(11,13,15),Triple(12,14,16))?.let { elbow ->
            val overhead=(frame.point(15)?.y ?: 1.0) < (frame.point(11)?.y ?: 0.0) && (frame.point(16)?.y ?: 1.0) < (frame.point(12)?.y ?: 0.0)
            ExerciseSignal(elbow<=115,elbow>=160 && overhead,((elbow-115)/45*100).coerceIn(0.0,100.0),"Press higher",upper,"Elbow angle",elbow,"°")
        }
        ExerciseType.CALF_RAISE -> {
            val heel=((frame.point(29)?.y ?: return null)+(frame.point(30)?.y ?: return null))/2
            val baseline=heelBaseline ?: heel
            val lift=((baseline-heel)/0.06*100).coerceIn(0.0,100.0)
            ExerciseSignal(lift<=10,lift>=65,lift,"Raise your heels higher",lower+setOf(29,30),"Heel lift",lift,"%")
        }
        ExerciseType.JUMPING_JACK -> {
            val leftAnkle=frame.point(27) ?: return null; val rightAnkle=frame.point(28) ?: return null
            val leftWrist=frame.point(15) ?: return null; val rightWrist=frame.point(16) ?: return null
            val shoulderWidth=Geometry.distance(frame.point(11) ?: return null,frame.point(12) ?: return null).coerceAtLeast(0.01)
            val stance=abs(leftAnkle.x-rightAnkle.x)/shoulderWidth
            val armsUp=leftWrist.y<frame.point(11)!!.y && rightWrist.y<frame.point(12)!!.y
            val armsDown=leftWrist.y>frame.point(11)!!.y && rightWrist.y>frame.point(12)!!.y
            ExerciseSignal(stance<=0.9 && armsDown,stance>=1.5 && armsUp,((stance-0.9)/0.6*100).coerceIn(0.0,100.0),if(!armsUp) "Raise your arms higher" else "Open your stance wider",lower+upper,"Stance ratio",stance,"×")
        }
        else -> null
    }
}

/** Shared debounced start → target → start state machine for conservative rep exercises. */
class RepetitionExerciseEngine(private val type: ExerciseType, private val variantId: String = ExerciseVariants.standardId(type)) : AssessmentEngine {
    init { require(type !in setOf(ExerciseType.SQUAT,ExerciseType.PLANK)) }
    private val coach=CoachingFeedbackController()
    private var paused=false; private var lastTime: Long?=null; private var armed=false; private var moving=false; private var targetSeen=false
    private var stable=0; private var attemptStart=0L; private var maxRom=0.0; private var heelBaseline: Double?=null
    private val reps=mutableListOf<Rep>(); private var confidenceSum=0.0; private var frames=0
    private var snapshot=LiveAssessment(SessionResult(exerciseId=type.id,exerciseName=type.displayName,variantId=variantId))
    override fun current()=snapshot
    override fun pause(): LiveAssessment { paused=true; snapshot=snapshot.copy(result=snapshot.result.copy(status=Status.PAUSED),instruction="Session paused",movementState="PAUSED"); return snapshot }
    override fun resume() { paused=false; lastTime=null; stable=0 }
    override fun finish()=snapshot.result.copy(timeline=reps.toList())
    override fun process(frame: PoseFrame): LiveAssessment {
        if(paused || (lastTime!=null && frame.timestampMs<=lastTime!!)) return snapshot
        lastTime=frame.timestampMs
        val visibility=frame.assessRequiredLandmarks(ExerciseSignals.requiredFor(type))
        if(!visibility.sufficient) return publishVisibility(frame.timestampMs,visibility.instruction)
        val signal=ExerciseSignals.forType(type,frame,heelBaseline)
        if(signal==null) return publishNotDetected(frame.timestampMs)
        val visible=visibility.confidence
        if(type==ExerciseType.CALF_RAISE && heelBaseline==null) heelBaseline=(frame.point(29)!!.y+frame.point(30)!!.y)/2
        confidenceSum+=visible; frames++; maxRom=maxOf(maxRom,signal.rom)
        if(!armed) {
            stable=if(signal.start) stable+1 else 0
            if(stable>=3) { armed=true; stable=0; maxRom=0.0 }
            return publish(signal,visible,frame.timestampMs,"READY",CoachingFeedback("READY","Return to starting position",CoachingPriority.NEUTRAL,false))
        }
        if(!moving && !signal.start) { moving=true; attemptStart=frame.timestampMs; maxRom=signal.rom }
        if(moving && signal.target) targetSeen=true
        if(moving && signal.start) {
            stable++
            if(stable>=3) { close(frame.timestampMs,targetSeen,visible); moving=false; targetSeen=false; stable=0; maxRom=0.0 }
        } else stable=0
        val feedback=when {
            moving && targetSeen -> CoachingFeedback("RETURN","Return to starting position",CoachingPriority.NEUTRAL,false)
            moving -> CoachingFeedback("MOVE",signal.targetCue,CoachingPriority.NEUTRAL,false)
            else -> CoachingFeedback("READY","Ready",CoachingPriority.NEUTRAL,false)
        }
        return publish(signal,visible,frame.timestampMs,if(moving) if(targetSeen) "RETURNING" else "MOVING" else "READY",feedback)
    }
    private fun close(end: Long, complete: Boolean, confidence: Double) {
        val errors=if(complete) emptyList() else listOf(FormError("INSUFFICIENT_RANGE", "Complete the movement",maxRom,"Reach the configured exercise range",confidence))
        reps+=Rep(reps.size+1,complete,attemptStart/1000.0,end/1000.0,(end-attemptStart)/1000.0,maxRom,confidence,errors)
    }
    private fun publishVisibility(time: Long, instruction: String): LiveAssessment {
        armed=false; moving=false; targetSeen=false; stable=0
        val feedback=coach.select(CoachingFeedback("VISIBILITY",instruction,CoachingPriority.VISIBILITY),time)
        snapshot=snapshot.copy(result=result(Status.INSUFFICIENT_VISIBILITY,Visibility.INSUFFICIENT),kneeAngle=null,currentRomPercentage=null,instruction=feedback.message,coaching=feedback,movementState="TRACKING",primaryMetricValue=null)
        return snapshot
    }
    private fun publishNotDetected(time: Long): LiveAssessment {
        val feedback=coach.select(CoachingFeedback("POSE_UNCLEAR","Adjust your position so the movement can be measured",CoachingPriority.NEUTRAL,false),time)
        snapshot=snapshot.copy(result=result(Status.NOT_DETECTED,Visibility.SUFFICIENT),instruction=feedback.message,coaching=feedback,movementState="READY")
        return snapshot
    }
    private fun publish(signal: ExerciseSignal, confidence: Double, time: Long, state: String, candidate: CoachingFeedback): LiveAssessment {
        val event=reps.lastOrNull()?.takeIf { it.endSeconds == time/1000.0 }
        val feedback=coach.select(when {
            event?.complete==true -> CoachingFeedback("GOOD_REP","Good rep",CoachingPriority.SUCCESS)
            event?.complete==false -> CoachingFeedback("INSUFFICIENT_RANGE",signal.targetCue,CoachingPriority.RANGE_OF_MOTION)
            FormDrift.analyze(reps).state==FormDriftState.DRIFTING -> CoachingFeedback("FORM_DRIFT",FormDrift.message(FormDrift.analyze(reps)),CoachingPriority.DRIFT)
            else -> candidate
        },time)
        snapshot=LiveAssessment(result(Status.VALID,Visibility.SUFFICIENT),null,signal.rom,SquatState.STANDING,feedback.message,emptyList(),feedback,state,signal.metricLabel,signal.metricValue,signal.metricUnit)
        return snapshot
    }
    private fun result(status: Status = Status.VALID, visibility: Visibility = Visibility.SUFFICIENT): SessionResult {
        val complete=reps.count { it.complete }
        val drift=FormDrift.analyze(reps)
        return SessionResult(type.id,type.displayName,status,
            if(frames==0) 0.0 else confidenceSum/frames,complete,reps.size-complete,
            averageRepDurationSeconds=reps.filter { it.complete }.takeIf { it.isNotEmpty() }?.map { it.durationSeconds }?.average(),
            romPercentage=reps.takeIf { it.isNotEmpty() }?.map { it.romPercentage }?.average(),
            formErrors=reps.flatMap { it.formErrors }.distinctBy { it.code },visibility=visibility,timeline=reps.toList(),driftDetected=drift.state==FormDriftState.DRIFTING,driftReasons=drift.reasons.map { it.name },variantId=variantId)
    }
}

/** Side-view, hold-only plank detection; it never produces repetitions. */
class PlankEngine : AssessmentEngine {
    private val required=setOf(11,12,23,24,27,28); private val coach=CoachingFeedbackController(); private var paused=false; private var lastTime: Long?=null
    private var holdMs=0L; private var confidenceSum=0.0; private var frames=0; private var snapshot=LiveAssessment(SessionResult(exerciseId=ExerciseType.PLANK.id,exerciseName=ExerciseType.PLANK.displayName))
    override fun current()=snapshot; override fun finish()=snapshot.result
    override fun pause()=snapshot.copy(result=snapshot.result.copy(status=Status.PAUSED),movementState="PAUSED").also { paused=true; snapshot=it }
    override fun resume() { paused=false; lastTime=null }
    override fun process(frame: PoseFrame): LiveAssessment {
        if(paused || (lastTime!=null && frame.timestampMs<=lastTime!!)) return snapshot
        val previous=lastTime; lastTime=frame.timestampMs; val visibility=frame.assessRequiredLandmarks(required); val confidence=visibility.confidence
        if(!visibility.sufficient) { val f=coach.select(CoachingFeedback("VISIBILITY",visibility.instruction,CoachingPriority.VISIBILITY),frame.timestampMs); snapshot=snapshot.copy(result=result(Status.INSUFFICIENT_VISIBILITY,Visibility.INSUFFICIENT),instruction=f.message,coaching=f,movementState="TRACKING",primaryMetricValue=null); return snapshot }
        val shoulder=frame.point(11)!!; val hip=frame.point(23)!!; val ankle=frame.point(27)!!
        val line=Geometry.angle(shoulder,hip,ankle) ?: 0.0
        val holding=line>=150.0
        if(holding && previous!=null && frame.timestampMs-previous<=250) holdMs+=frame.timestampMs-previous
        confidenceSum+=confidence; frames++
        val f=coach.select(if(holding) CoachingFeedback("HOLD","Hold the position",CoachingPriority.NEUTRAL,false) else CoachingFeedback("PLANK_POSITION","Align into a straight plank position",CoachingPriority.FORM),frame.timestampMs)
        snapshot=LiveAssessment(result(if(holdMs>0) Status.VALID else Status.NOT_DETECTED,Visibility.SUFFICIENT),null,null,SquatState.STANDING,f.message,emptyList(),f,if(holding) "HOLDING" else "READY","Body alignment angle",line,"°")
        return snapshot
    }
    private fun result(status: Status=if(holdMs>0) Status.VALID else Status.NOT_DETECTED, visibility: Visibility=Visibility.SUFFICIENT)=SessionResult(
        exerciseId=ExerciseType.PLANK.id,exerciseName=ExerciseType.PLANK.displayName,status=status,confidence=if(frames==0) 0.0 else confidenceSum/frames,
        holdDurationSeconds=holdMs/1000.0,visibility=visibility)
}

object ExerciseEngines { fun create(type: ExerciseType, variantId: String = ExerciseVariants.standardId(type)): AssessmentEngine = when(type) { ExerciseType.SQUAT -> SquatEngine(ExerciseVariants.squatConfig(variantId),variantId); ExerciseType.PLANK -> PlankEngine(); else -> RepetitionExerciseEngine(type,variantId) } }
