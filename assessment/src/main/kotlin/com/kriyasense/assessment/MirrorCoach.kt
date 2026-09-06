package com.kriyasense.assessment

/**
 * Mirror Coach V1 is a Squat-only visual guide. It uses phase-based reference geometry,
 * is not biomechanical ground truth, requires reliable visible anchors, and never affects scoring.
 */
data class GuidePoint(val x: Double, val y: Double)
data class ReferencePose(val phase: SquatState, val joints: Map<Int, GuidePoint>)

object SquatMirrorCoach {
    private fun pose(phase: SquatState, hip: Double, kneeX: Double, kneeY: Double, shoulderY: Double) = ReferencePose(phase,mapOf(
        11 to GuidePoint(-0.34,shoulderY),12 to GuidePoint(0.34,shoulderY),
        13 to GuidePoint(-0.48,shoulderY+0.34),14 to GuidePoint(0.48,shoulderY+0.34),
        15 to GuidePoint(-0.56,shoulderY+0.62),16 to GuidePoint(0.56,shoulderY+0.62),
        23 to GuidePoint(-0.18,hip),24 to GuidePoint(0.18,hip),
        25 to GuidePoint(-kneeX,kneeY),26 to GuidePoint(kneeX,kneeY),
        27 to GuidePoint(-0.20,1.00),28 to GuidePoint(0.20,1.00)
    ))
    private val references=mapOf(
        SquatState.STANDING to pose(SquatState.STANDING,0.0,0.20,0.52,-0.54),
        SquatState.DESCENDING to pose(SquatState.DESCENDING,0.10,0.30,0.45,-0.43),
        SquatState.BOTTOM to pose(SquatState.BOTTOM,0.22,0.38,0.34,-0.30),
        SquatState.ASCENDING to pose(SquatState.ASCENDING,0.12,0.31,0.43,-0.40)
    )
    fun referenceFor(phase: SquatState)=references.getValue(phase)
    fun aligned(frame: PoseFrame, phase: SquatState): Map<Int, Point>? {
        fun midpoint(a: Int,b: Int): Point? { val x=frame.landmarks[a]?.position ?: return null; val y=frame.landmarks[b]?.position ?: return null; return Point((x.x+y.x)/2,(x.y+y.y)/2) }
        val hips=midpoint(23,24) ?: return null; val shoulders=midpoint(11,12) ?: return null
        val scale=Geometry.distance(hips,shoulders)
        if(!scale.isFinite() || scale < 0.02) return null
        return referenceFor(phase).joints.mapValues { (_,relative) -> Point(hips.x+relative.x*scale,hips.y+relative.y*scale) }
            .takeIf { it.values.all { point -> point.x.isFinite() && point.y.isFinite() && point.x in -0.5..1.5 && point.y in -0.5..1.5 } }
    }
}
