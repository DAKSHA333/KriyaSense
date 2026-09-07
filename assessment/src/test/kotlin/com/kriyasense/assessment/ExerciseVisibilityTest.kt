package com.kriyasense.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExerciseVisibilityTest {
    private fun landmark(point: Point, reliable: Boolean = true) =
        Landmark(point,if(reliable) .95 else .1,if(reliable) .95 else .1)

    private fun frame(time: Long, bent: Boolean = false, unreliable: Set<Int> = emptySet()): PoseFrame {
        val points=mapOf(
            11 to Point(.20,.40), 13 to Point(.40,.40), 15 to if(bent) Point(.40,.20) else Point(.60,.40),
            12 to Point(.20,.60), 14 to Point(.40,.60), 16 to if(bent) Point(.40,.80) else Point(.60,.60),
            23 to Point(.25,.30), 25 to Point(.25,.60), 27 to if(bent) Point(.55,.60) else Point(.25,.90),
            24 to Point(.75,.30), 26 to Point(.75,.60), 28 to if(bent) Point(.45,.60) else Point(.75,.90)
        )
        return PoseFrame(time,points.mapValues { (id,point)->landmark(point,id !in unreliable) },1000,1000)
    }

    private fun lungeFrame(time: Long, kneeAngle: Double, unreliable: Set<Int> = emptySet()): PoseFrame {
        val bend=Math.toRadians((180.0-kneeAngle)/2.0)
        val offsetX=.18*kotlin.math.sin(bend); val offsetY=.18*kotlin.math.cos(bend)
        val leftAnkle=Point(.30,.90); val rightAnkle=Point(.70,.90)
        val points=mapOf(
            23 to Point(leftAnkle.x,leftAnkle.y-2*offsetY),25 to Point(leftAnkle.x+offsetX,leftAnkle.y-offsetY),27 to leftAnkle,
            24 to Point(rightAnkle.x,rightAnkle.y-2*offsetY),26 to Point(rightAnkle.x-offsetX,rightAnkle.y-offsetY),28 to rightAnkle
        )
        return PoseFrame(time,points.mapValues { (id,point)->landmark(point,id !in unreliable) },1000,1000)
    }

    private fun armLunge(engine: RepetitionExerciseEngine, startTime: Long) {
        repeat(3) { engine.process(lungeFrame(startTime+it*100,165.0)) }
    }

    private fun completeLunge(engine: RepetitionExerciseEngine, startTime: Long) {
        engine.process(lungeFrame(startTime,145.0))
        engine.process(lungeFrame(startTime+100,122.0))
        engine.process(lungeFrame(startTime+200,140.0))
        repeat(3) { engine.process(lungeFrame(startTime+300+it*100,165.0)) }
    }

    @Test fun lungeWithBothLegChainsVisibleIsAssessable() {
        val live=RepetitionExerciseEngine(ExerciseType.LUNGE).process(frame(100))
        assertEquals(Status.VALID,live.result.status)
        assertEquals(Visibility.SUFFICIENT,live.result.visibility)
        assertEquals("Movement range",live.primaryMetricLabel)
        assertNotNull(live.primaryMetricValue)
    }

    @Test fun lungeOppositeKneeCanDropOutWhileTrackedLegRemainsAssessable() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        engine.process(frame(100)) // Equal confidence deterministically selects the left chain.
        val live=engine.process(frame(200,bent=true,unreliable=setOf(26)))
        assertEquals(Status.VALID,live.result.status)
        assertEquals(Visibility.SUFFICIENT,live.result.visibility)
        assertEquals(100.0,live.primaryMetricValue!!,1e-8)
    }

    @Test fun lungeSustainedLossOfBothCriticalChainsBecomesInsufficient() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        engine.process(frame(100))
        val missingBothKnees=setOf(25,26)
        val firstDropout=engine.process(frame(200,unreliable=missingBothKnees))
        assertEquals(Visibility.SUFFICIENT,firstDropout.result.visibility)
        assertNull(firstDropout.primaryMetricValue)
        assertEquals(Visibility.SUFFICIENT,engine.process(frame(300,unreliable=missingBothKnees)).result.visibility)
        val lost=engine.process(frame(400,unreliable=missingBothKnees))
        assertEquals(Status.INSUFFICIENT_VISIBILITY,lost.result.status)
        assertEquals(Visibility.INSUFFICIENT,lost.result.visibility)
    }

    @Test fun briefLungeDropoutDoesNotDestroyAnOtherwiseValidRep() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        repeat(3) { engine.process(frame((it+1)*100L)) }
        engine.process(frame(400,bent=true))
        engine.process(frame(500,bent=true,unreliable=setOf(25,26)))
        engine.process(frame(600,bent=true,unreliable=setOf(25,26)))
        repeat(3) { engine.process(frame((it+7)*100L)) }
        assertEquals(1,engine.finish().completeReps)
    }

    @Test fun pushUpWithBothArmChainsVisibleIsAssessable() {
        val live=RepetitionExerciseEngine(ExerciseType.PUSH_UP).process(frame(100))
        assertEquals(Status.VALID,live.result.status)
        assertEquals(Visibility.SUFFICIENT,live.result.visibility)
        assertEquals("Elbow angle",live.primaryMetricLabel)
        assertNotNull(live.primaryMetricValue)
    }

    @Test fun pushUpFarSideAndUnusedHipsCanBeOccluded() {
        val engine=RepetitionExerciseEngine(ExerciseType.PUSH_UP)
        engine.process(frame(100)) // Equal confidence deterministically selects the left arm.
        val live=engine.process(frame(200,bent=true,unreliable=setOf(12,14,16,23,24)))
        assertEquals(Status.VALID,live.result.status)
        assertEquals(Visibility.SUFFICIENT,live.result.visibility)
        assertEquals(90.0,live.primaryMetricValue!!,1e-8)
    }

    @Test fun pushUpSustainedLossOfBothCriticalArmChainsBecomesInsufficient() {
        val engine=RepetitionExerciseEngine(ExerciseType.PUSH_UP)
        engine.process(frame(100))
        val missingBothElbows=setOf(13,14)
        engine.process(frame(200,unreliable=missingBothElbows))
        engine.process(frame(300,unreliable=missingBothElbows))
        val lost=engine.process(frame(400,unreliable=missingBothElbows))
        assertEquals(Status.INSUFFICIENT_VISIBILITY,lost.result.status)
        assertEquals(Visibility.INSUFFICIENT,lost.result.visibility)
    }

    @Test fun briefPushUpDropoutDoesNotDestroyAnOtherwiseValidRep() {
        val engine=RepetitionExerciseEngine(ExerciseType.PUSH_UP)
        repeat(3) { engine.process(frame((it+1)*100L)) }
        engine.process(frame(400,bent=true))
        engine.process(frame(500,bent=true,unreliable=setOf(13,14)))
        engine.process(frame(600,bent=true,unreliable=setOf(13,14)))
        repeat(3) { engine.process(frame((it+7)*100L)) }
        assertEquals(1,engine.finish().completeReps)
    }

    @Test fun realisticLungeDownAndUpCountsOnceWithoutExposingAngle() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        armLunge(engine,100)
        completeLunge(engine,400)
        assertEquals(1,engine.finish().completeReps)
        assertEquals("Movement range",engine.current().primaryMetricLabel)
        assertEquals("%",engine.current().primaryMetricUnit)
    }

    @Test fun shallowLungeBendDoesNotCountAsComplete() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        armLunge(engine,100)
        engine.process(lungeFrame(400,145.0))
        engine.process(lungeFrame(500,135.0))
        repeat(3) { engine.process(lungeFrame(600+it*100L,165.0)) }
        assertEquals(0,engine.finish().completeReps)
    }

    @Test fun loweredLungeWithoutReturnDoesNotCount() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        armLunge(engine,100)
        engine.process(lungeFrame(400,145.0))
        engine.process(lungeFrame(500,122.0))
        assertEquals(0,engine.finish().completeReps)
    }

    @Test fun twoCompleteLungesCountExactlyTwice() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        armLunge(engine,100)
        completeLunge(engine,400)
        completeLunge(engine,1_000)
        assertEquals(2,engine.finish().completeReps)
    }

    @Test fun briefVisibilityDropoutDuringLungeStillCounts() {
        val engine=RepetitionExerciseEngine(ExerciseType.LUNGE)
        armLunge(engine,100)
        engine.process(lungeFrame(400,145.0))
        engine.process(lungeFrame(500,122.0))
        engine.process(lungeFrame(600,122.0,unreliable=setOf(25,26)))
        engine.process(lungeFrame(700,122.0,unreliable=setOf(25,26)))
        repeat(3) { engine.process(lungeFrame(800+it*100L,165.0)) }
        assertEquals(1,engine.finish().completeReps)
    }
}
