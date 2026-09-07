package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.*
import org.junit.Assert.*
import org.junit.Test

class GhostPoseTest {
    private fun frame(time: Long=100,side: Boolean=false,width: Int=800,height: Int=1200,shift: Map<Int,Point> = emptyMap()): PoseFrame {
        val front=mapOf(11 to Point(300.0,250.0),12 to Point(500.0,250.0),13 to Point(285.0,405.0),14 to Point(515.0,405.0),
            15 to Point(275.0,560.0),16 to Point(525.0,560.0),23 to Point(340.0,550.0),24 to Point(460.0,550.0),
            25 to Point(340.0,780.0),26 to Point(460.0,780.0),27 to Point(330.0,1020.0),28 to Point(470.0,1020.0),
            29 to Point(320.0,1030.0),30 to Point(480.0,1030.0))
        val sideways=mapOf(11 to Point(300.0,330.0),12 to Point(318.0,335.0),13 to Point(288.0,490.0),14 to Point(306.0,495.0),
            15 to Point(280.0,650.0),16 to Point(298.0,655.0),23 to Point(470.0,570.0),24 to Point(486.0,575.0),
            25 to Point(390.0,790.0),26 to Point(520.0,800.0),27 to Point(300.0,1010.0),28 to Point(590.0,1015.0),
            29 to Point(290.0,1020.0),30 to Point(600.0,1025.0))
        val points=(if(side) sideways else front).mapValues { (id,p)->shift[id]?:p }
        return PoseFrame(time,points.mapValues { (_,p)->Landmark(Point(p.x/width,p.y/height),.99,.99) },width,height)
    }
    private fun frames(profile: MirrorCoachProfile)=List(12) { frame(100+it*80L,profile.preferredView==PreferredView.SIDE) }
    private fun live(type: ExerciseType,state: String="READY")=LiveAssessment(
        SessionResult(exerciseId=type.id,exerciseName=type.displayName,visibility=Visibility.SUFFICIENT),
        kneeAngle=175.0,state=if(type==ExerciseType.SQUAT && state=="MOVING") SquatState.DESCENDING else SquatState.STANDING,movementState=state)
    private fun ready(controller: GhostPoseController): GhostOutput {
        var output=GhostOutput(GhostReadiness.WAITING)
        frames(controller.profile).forEach { output=controller.update(it,live(controller.profile.exerciseType),true) }
        return output
    }

    @Test fun everySelectableExerciseHasItsOwnProfile() {
        val profiles=MirrorCoachProfiles.all()
        assertEquals(ExerciseType.entries.toSet(),profiles.map { it.exerciseType }.toSet())
        assertEquals(ExerciseType.entries.size,profiles.map { it.referenceModel }.toSet().size)
        assertEquals(PreferredView.FRONT,MirrorCoachProfiles.forExercise(ExerciseType.SQUAT).preferredView)
        assertEquals(MirrorMovementType.STATIC_HOLD,MirrorCoachProfiles.forExercise(ExerciseType.PLANK).movementType)
    }

    @Test fun allProfilesCalibrateAndGenerateFiniteDistinctGeometry() {
        val outputs=MirrorCoachProfiles.all().associate { profile ->
            val body=GhostPoseController.calibrate(profile,frames(profile))
            assertNotNull(profile.exerciseId,body)
            val target=GhostPoseGeometry.generate(profile,body!!,1.0)
            assertTrue(profile.exerciseId,target.isNotEmpty())
            assertTrue(profile.exerciseId,target.values.all { it.x.isFinite() && it.y.isFinite() })
            profile.exerciseType to target
        }
        assertEquals(ExerciseType.entries.size,outputs.values.map { it.toString() }.toSet().size)
    }

    @Test fun everyProfileReachesReadyThroughItsOwnViewAndLandmarks() {
        MirrorCoachProfiles.all().forEach { profile ->
            val output=ready(GhostPoseController(profile))
            assertEquals(profile.exerciseId,GhostReadiness.READY,output.readiness)
            assertNotNull(profile.exerciseId,output.joints)
        }
    }

    @Test fun sideProfilesCanCalibrateFromEitherReliableBodySide() {
        for(type in listOf(ExerciseType.PUSH_UP,ExerciseType.PLANK)) {
            val profile=MirrorCoachProfiles.forExercise(type); val controller=GhostPoseController(profile)
            var output=GhostOutput(GhostReadiness.WAITING)
            repeat(12) { index ->
                val base=frame(100+index*80L,side=true)
                val obscured=base.copy(landmarks=base.landmarks.mapValues { (id,l)->
                    if(id in setOf(11,13,15,23,25,27)) l.copy(visibility=.1,presence=.1) else l
                })
                output=controller.update(obscured,live(type),true)
            }
            assertEquals(type.name,GhostReadiness.READY,output.readiness)
            assertNotNull(type.name,output.joints)
        }
    }

    @Test fun exerciseTargetsMatchOnlyTheirExistingSignals() {
        fun target(type: ExerciseType): Pair<ProjectedBody,Map<Int,Point>> {
            val profile=MirrorCoachProfiles.forExercise(type); val body=GhostPoseController.calibrate(profile,frames(profile))!!
            val normalized=GhostPoseGeometry.generate(profile,body,1.0)
            return body to normalized.mapValues { (_,p)->Point(p.x*body.width,p.y*body.height) }
        }
        val (squatBody,squat)=target(ExerciseType.SQUAT)
        assertEquals(squatBody.points.getValue(27),squat.getValue(27)); assertEquals(squatBody.points.getValue(28),squat.getValue(28))
        assertTrue((squat.getValue(23).y+squat.getValue(24).y)/2>(squatBody.points.getValue(23).y+squatBody.points.getValue(24).y)/2)
        val (_,lunge)=target(ExerciseType.LUNGE)
        assertEquals(105.0,listOf(Geometry.angle(lunge.getValue(23),lunge.getValue(25),lunge.getValue(27))!!,
            Geometry.angle(lunge.getValue(24),lunge.getValue(26),lunge.getValue(28))!!).average(),3.0)
        val (_,push)=target(ExerciseType.PUSH_UP)
        assertEquals(95.0,Geometry.angle(push.getValue(11),push.getValue(13),push.getValue(15))!!,1e-6)
        val (_,curl)=target(ExerciseType.BICEP_CURL)
        assertEquals(55.0,Geometry.angle(curl.getValue(11),curl.getValue(13),curl.getValue(15))!!,6.0)
        val (_,press)=target(ExerciseType.SHOULDER_PRESS)
        assertTrue(press.getValue(15).y<press.getValue(11).y && press.getValue(16).y<press.getValue(12).y)
        assertTrue(Geometry.angle(press.getValue(11),press.getValue(13),press.getValue(15))!!>=160.0)
        val (calfBody,calf)=target(ExerciseType.CALF_RAISE)
        assertEquals(.039*calfBody.height,calfBody.points.getValue(29).y-calf.getValue(29).y,1e-8)
        val (_,plank)=target(ExerciseType.PLANK)
        assertEquals(180.0,Geometry.angle(plank.getValue(11),plank.getValue(23),plank.getValue(27))!!,1e-6)
        val (jackBody,jack)=target(ExerciseType.JUMPING_JACK)
        assertEquals(1.5,(jack.getValue(28).x-jack.getValue(27).x)/(jackBody.points.getValue(12).x-jackBody.points.getValue(11).x),1e-8)
        assertTrue(jack.getValue(15).y<jack.getValue(11).y && jack.getValue(16).y<jack.getValue(12).y)
    }

    @Test fun repProfilesPreserveSegmentsAndMoveContinuously() {
        val anatomical=setOf(11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,28 to 30)
        MirrorCoachProfiles.all().filter { it.movementType==MirrorMovementType.REP_TRAJECTORY }.forEach { profile ->
            val body=GhostPoseController.calibrate(profile,frames(profile))!!
            var prior=GhostPoseGeometry.generate(profile,body,0.0)
            var moved=false
            for(step in 1..100) {
                val next=GhostPoseGeometry.generate(profile,body,step/100.0)
                profile.renderedEdges.filter { it in anatomical || (it.second to it.first) in anatomical }.forEach { edge ->
                    if(edge.first in next && edge.second in next) {
                        fun pixel(id: Int)=Point(next.getValue(id).x*body.width,next.getValue(id).y*body.height)
                        val length=Geometry.distance(pixel(edge.first),pixel(edge.second))
                        val baseline=body.lengths[GhostPoseGeometry.canonical(edge.first,edge.second)]
                        if(baseline!=null) assertEquals("${profile.exerciseId} $edge",baseline,length,1e-5)
                    }
                }
                val delta=next.keys.intersect(prior.keys).maxOfOrNull { Geometry.distance(next.getValue(it),prior.getValue(it)) }?:0.0
                assertTrue("${profile.exerciseId} jumped",delta<.05)
                moved=moved || delta>1e-6; prior=next
            }
            assertTrue("${profile.exerciseId} has no trajectory",moved)
        }
    }

    @Test fun staticHoldNeverAnimates() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.PLANK)
        val body=GhostPoseController.calibrate(profile,frames(profile))!!
        assertEquals(GhostPoseGeometry.generate(profile,body,0.0),GhostPoseGeometry.generate(profile,body,1.0))
        val controller=GhostPoseController(profile); ready(controller)
        val output=controller.update(frame(1060,true),live(ExerciseType.PLANK,"HOLDING"),true)
        assertEquals(1.0,output.progress,0.0)
    }

    @Test fun targetIsIndependentOfBadLiveJointsAndCalibrationStaysFixed() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)
        val a=GhostPoseController(profile); val b=GhostPoseController(profile); ready(a); ready(b)
        val good=frame(1060); val bad=frame(1060,shift=mapOf(25 to Point(240.0,700.0),26 to Point(570.0,850.0)))
        val one=a.update(good,live(ExerciseType.SQUAT,"MOVING"),true)
        val two=b.update(bad,live(ExerciseType.SQUAT,"MOVING"),true)
        assertEquals(one.joints,two.joints)
        assertEquals(one.progress,two.progress,0.0)
    }

    @Test fun shallowSquatStillCompletesUsefulReferenceDescentBeforeReturning() {
        val controller=GhostPoseController(MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)); ready(controller)
        var time=1060L
        var output=controller.update(frame(time),live(ExerciseType.SQUAT,"MOVING"),true)
        assertTrue(output.progress>0.0)
        repeat(3) {
            time+=80
            output=controller.update(frame(time),live(ExerciseType.SQUAT,"READY"),true)
        }
        assertTrue("reference reversed with shallow live movement",output.progress>.35)
        var maximum=output.progress
        while(time<1900) {
            time+=80
            output=controller.update(frame(time),live(ExerciseType.SQUAT,"READY"),true)
            maximum=maxOf(maximum,output.progress)
        }
        assertEquals(1.0,maximum,1e-8)
        assertTrue(output.progress<maximum)
    }

    @Test fun trainerUsesOppositeFreeSideAndAccountsForDisplayMirror() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)
        val base=GhostPoseController.calibrate(profile,frames(profile))!!
        fun shifted(dx: Double)=base.copy(points=base.points.mapValues { (_,p)->p.copy(x=p.x+dx) },centerX=base.centerX+dx)
        val userLeft=TrainerPoseTransform.create(profile,shifted(-190.0),800.0,1200.0,false)!!
        val userRight=TrainerPoseTransform.create(profile,shifted(190.0),800.0,1200.0,false)!!
        assertEquals(TrainerSide.RIGHT,userLeft.side)
        assertEquals(TrainerSide.LEFT,userRight.side)
        assertEquals(TrainerSide.LEFT,TrainerPoseTransform.create(profile,shifted(-190.0),800.0,1200.0,true)!!.side)
    }

    @Test fun trainerScaleAndPlacementStayFixedAcrossSquatTrajectory() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)
        val body=GhostPoseController.calibrate(profile,frames(profile))!!
        val layout=TrainerPoseTransform.create(profile,body,800.0,1200.0,false)!!
        val standing=layout.transform(GhostPoseGeometry.generate(profile,body,0.0),body)
        val bottom=layout.transform(GhostPoseGeometry.generate(profile,body,1.0),body)
        assertTrue(layout.relativeScale in .50..0.74)
        assertEquals(standing.getValue(27),bottom.getValue(27))
        assertEquals(standing.getValue(28),bottom.getValue(28))
        assertTrue(bottom.getValue(23).y>standing.getValue(23).y)
        assertTrue(layout.targetCenterX !in 300.0..500.0)
    }

    @Test fun trainerReferenceRemainsIndependentOfErroneousLivePoseAndAssessmentResult() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)
        val a=GhostPoseController(profile); val b=GhostPoseController(profile); ready(a); ready(b)
        val assessment=live(ExerciseType.SQUAT,"MOVING")
        val resultBefore=assessment.result
        val one=a.update(frame(1060),assessment,true)
        val badFrame=frame(1060,shift=mapOf(23 to Point(260.0,620.0),25 to Point(210.0,720.0),26 to Point(610.0,850.0)))
        val two=b.update(badFrame,assessment,true)
        val firstLayout=TrainerPoseTransform.create(profile,one.calibratedBody!!,800.0,1200.0,false)!!
        val secondLayout=TrainerPoseTransform.create(profile,two.calibratedBody!!,800.0,1200.0,false)!!
        assertEquals(firstLayout.transform(one.joints!!,one.calibratedBody),secondLayout.transform(two.joints!!,two.calibratedBody))
        assertSame(resultBefore,assessment.result)
    }

    @Test fun squatFrontCalibrationRejectsMissingAndUnstableLandmarksWithoutFacingChoice() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT)
        assertEquals(PreferredView.FRONT,profile.preferredView)
        assertNull(GhostPoseController.calibrate(profile,listOf(frame().copy(landmarks=frame().landmarks-25))))
        val controller=GhostPoseController(profile)
        repeat(12) { index ->
            val x=if(index%2==0) 190.0 else 490.0
            assertNotEquals(GhostReadiness.READY,controller.update(frame(100+index*80L,shift=mapOf(25 to Point(x,780.0))),live(ExerciseType.SQUAT),true).readiness)
        }
    }

    @Test fun aspectRatioAndDisplayMirroringDoNotChangeUnderlyingTarget() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.BICEP_CURL)
        val a=GhostPoseController.calibrate(profile,frames(profile))!!
        val target=GhostPoseGeometry.generate(profile,a,.7)
        assertEquals(target,GhostPoseGeometry.generate(profile,a,.7))
        target.values.forEach { p ->
            val normal=GhostPoseGeometry.display(p,800.0,1200.0,false); val mirrored=GhostPoseGeometry.display(p,800.0,1200.0,true)
            assertEquals(800.0-normal.x,mirrored.x,1e-8); assertEquals(normal.y,mirrored.y,0.0)
        }
        val wideFrames=List(12) { frame(100+it*80L,width=1600,height=1200) }
        val wide=GhostPoseController.calibrate(profile,wideFrames)!!
        assertEquals(a.points.getValue(11).x,wide.points.getValue(11).x,1e-8)
    }

    @Test fun trackingLossAndInvalidPlacementNeverRenderStaleGhost() {
        val profile=MirrorCoachProfiles.forExercise(ExerciseType.SQUAT); val controller=GhostPoseController(profile)
        assertNotNull(ready(controller).joints)
        assertNull(controller.update(null,live(ExerciseType.SQUAT),true).joints)
        assertEquals(GhostReadiness.TRACKING_LOST,controller.update(null,live(ExerciseType.SQUAT),true).readiness)
        val moved=frame(2000,shift=mapOf(27 to Point(520.0,1020.0),28 to Point(660.0,1020.0)))
        assertEquals(GhostReadiness.REPOSITION,controller.update(moved,live(ExerciseType.SQUAT),true).readiness)
        assertNull(controller.update(moved.copy(timestampMs=2080),live(ExerciseType.SQUAT),true).joints)
    }

    @Test fun smoothedProgressIsRateLimitedAndContinuous() {
        var p=0.0
        repeat(60) { val next=GhostPoseGeometry.advance(p,1.0,16); assertTrue(next>=p); assertTrue(next-p<=.024001); p=next }
        assertTrue(p>.95)
        val next=GhostPoseGeometry.advance(p,0.0,16); assertTrue(next<p); assertTrue(p-next<=.024001)
    }
}
