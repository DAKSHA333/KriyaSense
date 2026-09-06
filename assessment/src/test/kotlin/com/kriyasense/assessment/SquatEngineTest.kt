package com.kriyasense.assessment

import kotlin.math.*
import kotlin.test.*

class SquatEngineTest {
    private val config=SquatConfig(recoveryFrames=3,debounceMs=100)
    private var timestamp=0L
    private fun frame(angle: Double, drop: Double=0.0, visible: Boolean=true, ankleShift: Double=0.0): PoseFrame {
        timestamp+=50
        // Fixed ankle; two equal leg segments, flexing the knee moves the hip downward.
        val a=Math.toRadians((180-angle)/2)
        val ankle=Point(0.5+ankleShift,0.9)
        val knee=Point(ankle.x+0.2*sin(a),ankle.y-0.2*cos(a))
        val hip=Point(ankle.x,ankle.y-0.4*cos(a)+drop)
        val points=mapOf(11 to Point(hip.x,hip.y-0.2),12 to Point(hip.x+0.02,hip.y-0.2),
            23 to hip,24 to Point(hip.x+0.02,hip.y),25 to knee,26 to Point(knee.x+0.02,knee.y),
            27 to ankle,28 to Point(ankle.x+0.02,ankle.y))
        return PoseFrame(timestamp,points.mapValues { Landmark(it.value,if(visible) 0.95 else 0.1,0.95) },1000,1000)
    }
    private fun feed(engine: SquatEngine,angle: Double,n: Int=5,visible: Boolean=true): LiveAssessment {
        var output=engine.current()
        repeat(n) { output=engine.process(frame(angle,visible=visible)) }; return output
    }
    private fun squat(engine: SquatEngine,bottom: Double=90.0) {
        feed(engine,140.0); feed(engine,bottom); feed(engine,130.0); feed(engine,175.0,7)
    }
    private fun stored(id: String, time: Long, complete: Int=1, incomplete: Int=0, percentage: Double?=100.0) = WorkoutSession(
        id,"EX_SQUAT_001","Squat",time,complete,incomplete,complete+incomplete,percentage,90.0,0.9,
        listOf(WorkoutObservation("INSUFFICIENT_DEPTH","Lower your hips")),WorkoutObservation("INSUFFICIENT_DEPTH","Lower your hips")
    )
    private fun pose(vararg values: Pair<Int,Point>, time: Long=100) = PoseFrame(time,values.toMap().mapValues { Landmark(it.value,0.95,0.95) },1000,1000)
    private fun curlFrame(time: Long, curled: Boolean=false): PoseFrame {
        val wristX=if(curled) .25 else .8
        return pose(11 to Point(.2,.5),13 to Point(.5,.5),15 to Point(wristX,.5),
            12 to Point(.2,.6),14 to Point(.5,.6),16 to Point(wristX,.6),time=time)
    }
    @Test fun threePointAngles() {
        assertEquals(90.0,Geometry.angle(Point(1.0,0.0),Point(0.0,0.0),Point(0.0,1.0))!!,1e-8)
        assertEquals(180.0,Geometry.angle(Point(-1.0,0.0),Point(0.0,0.0),Point(1.0,0.0))!!,1e-8)
        assertNull(Geometry.angle(Point(0.0,0.0),Point(0.0,0.0),Point(1.0,0.0)))
        assertNull(Geometry.angle(Point(Double.NaN,0.0),Point(0.0,0.0),Point(1.0,0.0)))
    }
    @Test fun mirrorCoachMapsEverySquatPhaseAndAlignsSafely() {
        SquatState.entries.forEach { phase ->
            val reference=SquatMirrorCoach.referenceFor(phase)
            assertEquals(phase,reference.phase)
            assertTrue(setOf(11,12,23,24,25,26,27,28).all { it in reference.joints })
        }
        val anchored=pose(11 to Point(.4,.3),12 to Point(.6,.3),23 to Point(.45,.6),24 to Point(.55,.6),time=10)
        val guide=SquatMirrorCoach.aligned(anchored,SquatState.BOTTOM)
        assertNotNull(guide); assertTrue(guide.values.all { it.x.isFinite() && it.y.isFinite() })
        assertNull(SquatMirrorCoach.aligned(anchored.copy(landmarks=anchored.landmarks-23),SquatState.BOTTOM))
    }
    @Test fun fullTransitionsAndCounting() {
        val e=SquatEngine(config); feed(e,175.0,8)
        assertEquals(SquatState.DESCENDING,feed(e,140.0).state)
        assertEquals(SquatState.BOTTOM,feed(e,90.0).state)
        assertEquals(0,e.current().result.completeReps)
        assertEquals(SquatState.ASCENDING,feed(e,130.0).state)
        assertEquals(SquatState.STANDING,feed(e,175.0,7).state)
        assertEquals(1,e.current().result.completeReps)
        squat(e); assertEquals(2,e.current().result.completeReps)
    }
    @Test fun insufficientDepthIsIncomplete() {
        val e=SquatEngine(config); feed(e,175.0,8); squat(e,125.0)
        assertEquals(0,e.current().result.completeReps)
        assertEquals(1,e.current().result.incompleteReps)
        assertTrue(e.current().result.formErrors.any { it.code=="INSUFFICIENT_DEPTH" })
        assertEquals("INSUFFICIENT_DEPTH",e.current().coaching.code)
        assertEquals("Go lower",e.current().coaching.message)
    }
    @Test fun validRepProducesPositiveCoaching() {
        val e=SquatEngine(config.copy(minimumRepSeconds=0.1)); feed(e,175.0,8); squat(e)
        assertEquals(1,e.current().result.completeReps)
        assertEquals("GOOD_REP",e.current().coaching.code)
        assertEquals(CoachingPriority.SUCCESS,e.current().coaching.priority)
        assertEquals("Knee angle",e.current().primaryMetricLabel)
    }
    @Test fun coachingPriorityAndPersistenceAreDeterministic() {
        val coach=CoachingFeedbackController(persistenceMs=700)
        val neutral=CoachingFeedback("LOWER","Keep lowering",CoachingPriority.NEUTRAL)
        val success=CoachingFeedback("GOOD","Good rep",CoachingPriority.SUCCESS)
        val visibility=CoachingFeedback("VISIBLE","Keep your body visible",CoachingPriority.VISIBILITY)
        assertEquals("LOWER",coach.select(neutral,0).code)
        assertEquals("GOOD",coach.select(success,100).code) // higher-priority success replaces neutral feedback
        assertEquals("VISIBLE",coach.select(visibility,150).code) // urgent feedback wins immediately
        assertEquals("VISIBLE",coach.select(neutral,400).code)
        assertEquals("LOWER",coach.select(neutral,900).code)
    }
    @Test fun speechThrottleDebouncesRepeatedFeedbackAndAllowsUrgentFeedback() {
        val throttle=SpeechThrottle()
        val success=CoachingFeedback("GOOD","Good rep",CoachingPriority.SUCCESS)
        val visibility=CoachingFeedback("VISIBLE","Keep visible",CoachingPriority.VISIBILITY)
        assertTrue(throttle.shouldSpeak(success,0))
        assertFalse(throttle.shouldSpeak(success,5_000))
        assertTrue(throttle.shouldSpeak(success,20_000)) // exactly at the 20-second repeat boundary
        assertTrue(throttle.shouldSpeak(visibility,21_100)) // urgent message can pre-empt after its short gap
    }
    @Test fun summaryUsesRecordedAttemptsOnly() {
        val e=SquatEngine(config); feed(e,175.0,8); squat(e); squat(e,125.0)
        val summary=SessionSummaries.from(e.finish())
        assertEquals(1,summary.completeReps)
        assertEquals(1,summary.incompleteReps)
        assertEquals(2,summary.totalAttempts)
        assertEquals(50.0,summary.completionPercentage)
        assertEquals("MOVEMENT_TOO_FAST",summary.mostCommonObservation?.code)
    }
    @Test fun historyPersistsFieldsOnceAndReturnsNewestFirst() {
        val history=InMemoryWorkoutHistoryRepository()
        val first=stored("first",100)
        val newest=stored("newest",200,complete=2,incomplete=1,percentage=66.7)
        assertTrue(history.save(first)); assertTrue(history.save(newest)); assertFalse(history.save(newest))
        assertEquals(listOf("newest","first"),history.sessions().map { it.id })
        assertEquals(2,history.sessions().first().completeReps)
        assertEquals(1,history.sessions().first().incompleteReps)
        assertEquals(3,history.sessions().first().totalAttempts)
    }
    @Test fun movementSignatureUsesMatchingRecentFiniteSessionsOnly() {
        val squat=(1..6).map { i -> stored("s$i",i.toLong(),percentage=(80+i).toDouble()).copy(averageRomPercentage=(70+i).toDouble(),averageRepDurationSeconds=2.0,averageConfidence=.9) }
        val lunge=stored("l",99).copy(exerciseId="EX_LUNGE_001",exerciseName="Lunge",averageRomPercentage=1.0)
        val signature=MovementSignatures.build(squat+lunge,"EX_SQUAT_001")!!
        assertEquals(5,signature.sessionsUsed)
        assertEquals(74.0,signature.averageRomPercentage)
        assertEquals(2.0,signature.averageRepDurationSeconds)
        assertEquals(100.0,signature.tempoConsistency)
        assertNull(MovementSignatures.build(emptyList(),"EX_SQUAT_001"))
    }
    @Test fun movementSignatureExcludesCurrentSessionFromComparisonAndSupportsPlankHolds() {
        val prior=stored("old",1).copy(averageRomPercentage=80.0,averageRepDurationSeconds=2.0)
        val current=stored("now",2).copy(averageRomPercentage=90.0,averageRepDurationSeconds=3.0)
        val comparison=MovementSignatures.compare(current,listOf(prior))!!
        assertEquals(10.0,comparison.romChange); assertEquals(1.0,comparison.tempoChangeSeconds)
        val plank=stored("p",3).copy(exerciseId=ExerciseType.PLANK.id,exerciseName="Plank",completeReps=0,incompleteReps=0,totalAttempts=0,completionPercentage=null,averageRomPercentage=null,averageRepDurationSeconds=null,holdDurationSeconds=20.0)
        val signature=MovementSignatures.build(listOf(plank),ExerciseType.PLANK.id)!!
        assertEquals(20.0,signature.averageHoldDurationSeconds); assertNull(signature.averageRepDurationSeconds)
    }
    @Test fun formDriftRequiresFourDirectionalCompletedReps() {
        fun rep(rom: Double,duration: Double)=Rep(1,true,0.0,duration,duration,rom,.9,emptyList())
        assertEquals(FormDriftState.NONE,FormDrift.analyze(listOf(rep(90.0,2.0),rep(89.0,1.9),rep(88.0,1.8))).state)
        val drift=FormDrift.analyze(listOf(rep(95.0,3.0),rep(90.0,2.7),rep(85.0,2.4),rep(80.0,2.1)))
        assertEquals(FormDriftState.DRIFTING,drift.state); assertTrue(FormDriftReason.MULTIPLE_SIGNALS in drift.reasons)
        assertEquals(FormDriftState.NONE,FormDrift.analyze(listOf(rep(90.0,2.0),rep(88.0,2.2),rep(91.0,1.9),rep(89.0,2.1))).state)
    }
    @Test fun driftPriorityStaysBelowRangeAndAboveSuccess() {
        assertTrue(CoachingPriority.RANGE_OF_MOTION.ordinal < CoachingPriority.DRIFT.ordinal)
        assertTrue(CoachingPriority.DRIFT.ordinal < CoachingPriority.SUCCESS.ordinal)
        assertEquals(FormDriftState.NONE,FormDrift.analyze(emptyList()).state) // Plank has no rep-based drift input
        assertTrue(ExerciseType.CALF_RAISE.experimental)
    }
    @Test fun curatedChallengesUseExistingRecordedMetricsOnly() {
        fun rep(rom: Double,duration: Double)=Rep(1,true,0.0,duration,duration,rom,.9,emptyList())
        val consistent=SessionResult(timeline=List(5) { rep(95.0,2.0) })
        assertEquals(ChallengeStatus.COMPLETED,FormChallenges.evaluate(ChallengeDefinitions.squat,consistent).status)
        assertEquals(ChallengeStatus.COMPLETED,FormChallenges.evaluate(ChallengeDefinitions.curl,consistent).status)
        val inconsistent=SessionResult(timeline=listOf(rep(100.0,1.0),rep(80.0,2.0),rep(100.0,1.0),rep(80.0,2.0),rep(100.0,1.0)))
        assertEquals(ChallengeStatus.NOT_COMPLETED,FormChallenges.evaluate(ChallengeDefinitions.squat,inconsistent).status)
        assertEquals(ChallengeStatus.NOT_STARTED,FormChallenges.evaluate(ChallengeDefinitions.pushUp,consistent,false).status)
        val plank=SessionResult(exerciseId=ExerciseType.PLANK.id,holdDurationSeconds=30.0)
        assertEquals(ChallengeStatus.COMPLETED,FormChallenges.evaluate(ChallengeDefinitions.plank,plank).status)
    }
    @Test fun variantsKeepStandardThresholdsAndAdaptedRangesExplicit() {
        assertEquals(100.0,ExerciseVariants.squatConfig("SQUAT_STANDARD").bottomAngle)
        assertEquals(120.0,ExerciseVariants.squatConfig("SQUAT_CHAIR").bottomAngle)
        assertEquals(130.0,ExerciseVariants.squatConfig("SQUAT_LIMITED_ROM").bottomAngle)
        assertEquals(3,ExerciseVariants.forExercise(ExerciseType.SQUAT).size)
        assertEquals(2,ExerciseVariants.forExercise(ExerciseType.PUSH_UP).size)
        assertEquals(2,ExerciseVariants.forExercise(ExerciseType.BICEP_CURL).size)
        assertEquals(1,ExerciseVariants.forExercise(ExerciseType.PLANK).size)
    }
    @Test fun variantEngineMetadataAndIsolationAreDeterministic() {
        assertEquals("SQUAT_CHAIR",ExerciseEngines.create(ExerciseType.SQUAT,"SQUAT_CHAIR").finish().variantId)
        assertEquals("PUSH_UP_WALL",ExerciseEngines.create(ExerciseType.PUSH_UP,"PUSH_UP_WALL").finish().variantId)
        assertEquals("BICEP_CURL_SEATED",ExerciseEngines.create(ExerciseType.BICEP_CURL,"BICEP_CURL_SEATED").finish().variantId)
        val standard=stored("standard",1).copy(variantId="SQUAT_STANDARD",averageRomPercentage=90.0)
        val chair=stored("chair",2).copy(variantId="SQUAT_CHAIR",averageRomPercentage=70.0)
        assertEquals(90.0,MovementSignatures.build(listOf(standard,chair),"EX_SQUAT_001","SQUAT_STANDARD")!!.averageRomPercentage)
        assertEquals(70.0,MovementSignatures.build(listOf(standard,chair),"EX_SQUAT_001","SQUAT_CHAIR")!!.averageRomPercentage)
        assertNull(ChallengeDefinitions.forExercise(ExerciseType.SQUAT)?.takeIf { "SQUAT_CHAIR"==ExerciseVariants.standardId(ExerciseType.SQUAT) })
    }
    @Test fun persistedWorkoutIsDerivedOnlyFromResultMetrics() {
        val error=FormError("INSUFFICIENT_DEPTH","Lower your hips",125.0,"Knee angle <= 100",0.8)
        val result=SessionResult(completeReps=1,incompleteReps=1,romPercentage=75.0,confidence=0.8,formErrors=listOf(error),
            timeline=listOf(Rep(1,true,0.0,2.0,2.0,100.0,0.8,emptyList()),Rep(2,false,2.0,4.0,2.0,50.0,0.8,listOf(error))))
        val session=WorkoutHistory.fromResult(result,"id",123)
        assertEquals(2,session.totalAttempts); assertEquals(50.0,session.completionPercentage)
        assertEquals(75.0,session.averageRomPercentage); assertEquals(0.8,session.averageConfidence)
        assertEquals("INSUFFICIENT_DEPTH",session.mostCommonObservation?.code)
    }
    @Test fun progressAndEmptyHistoryAreDataDriven() {
        assertEquals(ProgressOverview(0,0,null,null),WorkoutHistory.overview(emptyList()))
        val overview=WorkoutHistory.overview(listOf(stored("old",100,complete=1,percentage=50.0),stored("new",200,complete=3,percentage=75.0)))
        assertEquals(2,overview.totalWorkouts); assertEquals(4,overview.totalCompletedReps)
        assertEquals(62.5,overview.averageCompletionPercentage); assertEquals(25.0,overview.latestCompletionChange)
    }
    @Test fun clearingHistoryRemovesAllRecords() {
        val history=InMemoryWorkoutHistoryRepository(); history.save(stored("one",1)); history.clear()
        assertTrue(history.sessions().isEmpty())
    }
    @Test fun exerciseFactoryPreservesEachExerciseIdentity() {
        ExerciseType.entries.forEach { type ->
            assertEquals(type.id,ExerciseEngines.create(type).finish().exerciseId)
            assertEquals(type.displayName,ExerciseEngines.create(type).finish().exerciseName)
        }
    }
    @Test fun conservativeSignalsExposeConfiguredRanges() {
        val lunge=pose(23 to Point(.3,.2),25 to Point(.3,.5),27 to Point(.6,.5),24 to Point(.7,.2),26 to Point(.7,.5),28 to Point(1.0,.5))
        assertTrue(ExerciseSignals.forType(ExerciseType.LUNGE,lunge,null)!!.target)
        val curl=pose(11 to Point(.2,.5),13 to Point(.5,.5),15 to Point(.25,.5),12 to Point(.2,.6),14 to Point(.5,.6),16 to Point(.25,.6))
        assertTrue(ExerciseSignals.forType(ExerciseType.BICEP_CURL,curl,null)!!.target)
        val jack=pose(11 to Point(.4,.5),12 to Point(.6,.5),15 to Point(.4,.2),16 to Point(.6,.2),23 to Point(.4,.7),24 to Point(.6,.7),25 to Point(.35,.8),26 to Point(.65,.8),27 to Point(.2,.9),28 to Point(.8,.9))
        assertTrue(ExerciseSignals.forType(ExerciseType.JUMPING_JACK,jack,null)!!.target)
    }
    @Test fun exerciseSignalsExposeExerciseSpecificMetricLabels() {
        val upper=pose(11 to Point(.2,.5),13 to Point(.5,.5),15 to Point(.8,.5),12 to Point(.2,.6),14 to Point(.5,.6),16 to Point(.8,.6),23 to Point(.3,.7),24 to Point(.7,.7))
        assertEquals("Elbow angle",ExerciseSignals.forType(ExerciseType.PUSH_UP,upper,null)!!.metricLabel)
        assertEquals("Elbow angle",ExerciseSignals.forType(ExerciseType.BICEP_CURL,upper,null)!!.metricLabel)
        assertEquals("Elbow angle",ExerciseSignals.forType(ExerciseType.SHOULDER_PRESS,upper,null)!!.metricLabel)
        val calf=pose(29 to Point(.4,.8),30 to Point(.6,.8))
        assertEquals("Heel lift",ExerciseSignals.forType(ExerciseType.CALF_RAISE,calf,.8)!!.metricLabel)
        assertTrue(ExerciseType.CALF_RAISE.experimental)
    }
    @Test fun plankAccumulatesHoldWithoutCreatingReps() {
        val e=PlankEngine()
        val first=pose(11 to Point(.2,.5),12 to Point(.2,.52),23 to Point(.5,.5),24 to Point(.5,.52),27 to Point(.8,.5),28 to Point(.8,.52),time=100)
        e.process(first); e.process(first.copy(timestampMs=200))
        val result=e.finish()
        assertEquals(0,result.completeReps); assertEquals(0,result.incompleteReps)
        assertEquals(0.1,result.holdDurationSeconds); assertEquals(ExerciseType.PLANK.id,result.exerciseId)
        assertEquals("Body alignment angle",e.current().primaryMetricLabel)
    }
    @Test fun visibleRepetitionPoseIsValidBeforeFirstRep() {
        val engine=RepetitionExerciseEngine(ExerciseType.BICEP_CURL)
        var live=engine.current()
        repeat(3) { live=engine.process(curlFrame((it+1)*100L)) }
        assertEquals(0,live.result.completeReps)
        assertEquals(Status.VALID,live.result.status)
        assertEquals(Visibility.SUFFICIENT,live.result.visibility)
        assertEquals("Elbow angle",live.primaryMetricLabel)
    }
    @Test fun missingRequiredRepetitionLandmarkIsInsufficientVisibility() {
        val engine=RepetitionExerciseEngine(ExerciseType.BICEP_CURL)
        val visible=curlFrame(100)
        val live=engine.process(visible.copy(timestampMs=200,landmarks=visible.landmarks-15))
        assertEquals(Status.INSUFFICIENT_VISIBILITY,live.result.status)
        assertEquals(Visibility.INSUFFICIENT,live.result.visibility)
        assertTrue(live.coaching.message.contains("wrists"))
    }
    @Test fun repetitionCompletionRemainsValidAndPauseRemainsPaused() {
        val engine=RepetitionExerciseEngine(ExerciseType.BICEP_CURL)
        repeat(3) { engine.process(curlFrame((it+1)*100L)) }
        repeat(3) { engine.process(curlFrame((it+4)*100L,curled=true)) }
        var live=engine.current()
        repeat(3) { live=engine.process(curlFrame((it+7)*100L)) }
        assertEquals(1,live.result.completeReps)
        assertEquals(Status.VALID,live.result.status)
        val paused=engine.pause()
        assertEquals(Status.PAUSED,paused.result.status)
        assertEquals(paused,engine.process(curlFrame(2_000)))
    }
    @Test fun jitterNeverCountsAndStandingCannotDuplicate() {
        val e=SquatEngine(config); feed(e,175.0,8)
        repeat(15) { feed(e,145.0,1); feed(e,175.0,1) }
        assertEquals(SquatState.STANDING,e.current().state)
        squat(e); feed(e,175.0,40)
        assertEquals(1,e.current().result.completeReps)
    }
    @Test fun visibilityAndFrozenMetrics() {
        val e=SquatEngine(config); feed(e,175.0,8); squat(e)
        val before=e.current().result
        val hidden=feed(e,90.0,20,false)
        assertEquals(Status.INSUFFICIENT_VISIBILITY,hidden.result.status)
        assertNull(hidden.kneeAngle)
        assertEquals(before.copy(status=Status.INSUFFICIENT_VISIBILITY,visibility=Visibility.INSUFFICIENT),hidden.result)
        feed(e,175.0,2)
        assertEquals(Status.INSUFFICIENT_VISIBILITY,e.current().result.status)
        feed(e,175.0,8); assertEquals(Visibility.SUFFICIENT,e.current().result.visibility)
    }
    @Test fun hiddenTransitionCannotFinishRep() {
        val e=SquatEngine(config); feed(e,175.0,8); feed(e,140.0); feed(e,90.0)
        feed(e,90.0,10,false); feed(e,175.0,10)
        assertEquals(0,e.finish().completeReps)
        squat(e); assertEquals(1,e.finish().completeReps)
    }
    @Test fun missingAndOutOfFrameJointsFail() {
        val f=frame(175.0)
        assertFalse(VisibilityAssessment(config).assess(f.copy(landmarks=f.landmarks-25)).sufficient)
        assertTrue(VisibilityAssessment(config).assess(f.copy(landmarks=f.landmarks-25)).instruction.contains("knees"))
        val p=f.landmarks.getValue(27).copy(position=Point(0.5,1.1))
        assertFalse(VisibilityAssessment(config).assess(f.copy(landmarks=f.landmarks+(27 to p))).sufficient)
    }
    @Test fun repDurationAndRom() {
        val e=SquatEngine(config); feed(e,175.0,8); squat(e)
        val r=e.finish().timeline.single()
        assertEquals(0.85,r.durationSeconds,1e-8)
        assertEquals(r.durationSeconds,e.finish().averageRepDurationSeconds)
        assertEquals(100.0,r.romPercentage,1e-8)
        assertEquals(50.0,Geometry.rom(132.5,config),1e-8)
        assertEquals(0.0,Geometry.rom(180.0,config),1e-8)
        assertTrue(e.finish().timeUnderTensionSeconds>0)
        assertTrue(r.formErrors.any { it.code=="MOVEMENT_TOO_FAST" })
    }
    @Test fun noSquatLikeMotionIsNotDetected() {
        val e=SquatEngine(config); feed(e,175.0,10)
        assertEquals(Status.NOT_DETECTED,e.finish().status)
        feed(e,145.0); feed(e,175.0)
        assertEquals(0,e.finish().incompleteReps) // insufficient hip drop
    }
    @Test fun pauseFreezesAndDoesNotBridge() {
        val e=SquatEngine(config); feed(e,175.0,8); feed(e,140.0); feed(e,90.0)
        val paused=e.pause(); feed(e,175.0,30); assertEquals(paused,e.current())
        e.resume(); feed(e,175.0,10); assertEquals(0,e.finish().completeReps)
    }
    @Test fun timestampGapAndOutOfOrderFrames() {
        val e=SquatEngine(config); feed(e,175.0,8); feed(e,140.0); feed(e,90.0)
        val last=e.current(); e.process(frame(90.0).copy(timestampMs=1)); assertEquals(last,e.current())
        timestamp+=2000; feed(e,175.0,10); assertEquals(0,e.finish().completeReps)
    }
    @Test fun jsonRoundTripIncludesNullsAndStableIds() {
        val empty=SessionResult(); assertTrue(empty.toJson().contains("\"holdDurationSeconds\": null"))
        val e=SquatEngine(config); feed(e,175.0,8); squat(e)
        val r=e.finish()
        assertEquals(r,SessionResult.json.decodeFromString<SessionResult>(r.toJson()))
        assertTrue(r.toJson().contains("EX_SQUAT_001"))
    }
    @Test fun viewGatesRulesAndErrorsAreExplainable() {
        val m=Measurement(90.0,0.5,Point(0.0,0.0),100.0,0.9,null,null)
        val rules=FormRules(config)
        assertTrue(rules.live(m).isEmpty())
        val errors=rules.live(m.copy(torsoLean=55.0,kneeAnkleRatio=0.5))
        assertEquals(setOf("EXCESSIVE_FORWARD_LEAN","KNEE_ALIGNMENT"),errors.map {it.code}.toSet())
        errors.forEach { assertTrue(it.expected.isNotBlank()); assertEquals(0.9,it.confidence) }
    }
}
