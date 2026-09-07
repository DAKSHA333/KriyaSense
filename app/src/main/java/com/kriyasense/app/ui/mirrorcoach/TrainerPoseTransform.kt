package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.Point
import kotlin.math.max
import kotlin.math.min

enum class TrainerSide { LEFT, RIGHT }

data class TrainerLayout(
    val side: TrainerSide,
    val pixelScale: Double,
    val relativeScale: Double,
    val sourceCenterX: Double,
    val sourceFloorY: Double,
    val targetCenterX: Double,
    val targetFloorY: Double,
    val trainerHeight: Double,
    val userCenterX: Double,
    val userFloorY: Double
) {
    fun transform(reference: Map<Int,Point>,body: ProjectedBody): Map<Int,Point> = reference.mapValues { (_,point) ->
        val sourceX=point.x*body.width
        val sourceY=point.y*body.height
        Point(
            targetCenterX+(sourceX-sourceCenterX)*pixelScale,
            targetFloorY+(sourceY-sourceFloorY)*pixelScale
        )
    }
}

/** Builds a stable display region from calibration, never from the current live pose. */
object TrainerPoseTransform {
    fun create(
        profile: MirrorCoachProfile,
        body: ProjectedBody,
        viewportWidth: Double,
        viewportHeight: Double,
        mirror: Boolean = false
    ): TrainerLayout? {
        if(profile.referenceModel!=ReferenceModel.SQUAT_FRONT || viewportWidth<=0 || viewportHeight<=0) return null
        val placementIds=setOf(11,12,23,24,25,26,27,28)
        val calibrationPoints=body.points.filterKeys { it in placementIds }.values
        if(calibrationPoints.isEmpty()) return null

        val displayXs=calibrationPoints.map { sourceDisplayX(it.x/body.width,viewportWidth,mirror) }
        val displayYs=calibrationPoints.map { it.y/body.height*viewportHeight }
        val userLeft=displayXs.minOrNull() ?: return null
        val userRight=displayXs.maxOrNull() ?: return null
        val userTop=displayYs.minOrNull() ?: return null
        val userFloor=(body.floorY/body.height*viewportHeight).coerceIn(0.0,viewportHeight)
        val userCenter=sourceDisplayX(body.centerX/body.width,viewportWidth,mirror)
        val side=if(userCenter<viewportWidth*.5) TrainerSide.RIGHT else TrainerSide.LEFT

        val samples=(0..20).flatMap { step ->
            GhostPoseGeometry.generate(profile,body,step/20.0).values.map { Point(it.x*body.width,it.y*body.height) }
        }
        if(samples.isEmpty()) return null
        val sourceShoulders=body.points.keys.containsAll(setOf(11,12))
        val shoulderMidY=if(sourceShoulders) (body.points.getValue(11).y+body.points.getValue(12).y)/2 else samples.minOf { it.y }
        val hipMidY=if(body.points.keys.containsAll(setOf(23,24))) (body.points.getValue(23).y+body.points.getValue(24).y)/2 else shoulderMidY+body.height*.2
        val torso=(hipMidY-shoulderMidY).coerceAtLeast(body.height*.08)
        val headRadius=torso*.22
        val sourceTop=min(samples.minOf { it.y },shoulderMidY-headRadius*2.25)
        val sourceBottom=samples.maxOf { it.y }
        val sourceLeft=samples.minOf { it.x }-headRadius*.25
        val sourceRight=samples.maxOf { it.x }+headRadius*.25
        val sourceHeight=(sourceBottom-sourceTop).coerceAtLeast(1.0)
        val sourceWidth=(sourceRight-sourceLeft).coerceAtLeast(1.0)
        val sourceCenter=(sourceLeft+sourceRight)/2

        val margin=viewportWidth*.035
        val separation=viewportWidth*.025
        val regionLeft: Double
        val regionRight: Double
        if(side==TrainerSide.RIGHT) {
            regionLeft=max(userRight+separation,viewportWidth*.52)
            regionRight=viewportWidth-margin
        } else {
            regionLeft=margin
            regionRight=min(userLeft-separation,viewportWidth*.48)
        }
        val availableWidth=regionRight-regionLeft
        if(availableWidth<=viewportWidth*.12) return null

        val apparentUserHeight=(userFloor-userTop).coerceAtLeast(viewportHeight*.25)
        val desiredHeight=apparentUserHeight*.74
        val targetFloor=min(userFloor,viewportHeight*.90)
        val availableHeight=(targetFloor-viewportHeight*.035).coerceAtLeast(viewportHeight*.25)
        val scale=minOf(desiredHeight/sourceHeight,availableWidth*.90/sourceWidth,availableHeight/sourceHeight)
        if(!scale.isFinite() || scale<=0) return null
        val trainerHeight=sourceHeight*scale
        return TrainerLayout(
            side=side,
            pixelScale=scale,
            relativeScale=trainerHeight/apparentUserHeight,
            sourceCenterX=sourceCenter,
            sourceFloorY=sourceBottom,
            targetCenterX=(regionLeft+regionRight)/2,
            targetFloorY=targetFloor,
            trainerHeight=trainerHeight,
            userCenterX=userCenter,
            userFloorY=userFloor
        )
    }

    private fun sourceDisplayX(normalizedX: Double,width: Double,mirror: Boolean)=
        (if(mirror) 1.0-normalizedX else normalizedX)*width
}
