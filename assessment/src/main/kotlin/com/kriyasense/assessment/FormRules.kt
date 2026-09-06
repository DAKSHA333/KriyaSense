package com.kriyasense.assessment

import kotlin.math.*

data class Measurement(val knee: Double, val hipY: Double, val ankle: Point, val legLength: Double,
    val confidence: Double, val torsoLean: Double?, val kneeAnkleRatio: Double?)
object Measurements {
    fun from(frame: PoseFrame, confidence: Double, c: SquatConfig): Measurement? {
        fun p(i: Int): Point { val p = frame.landmarks.getValue(i).position; return Point(p.x*frame.width,p.y*frame.height) }
        fun middle(a: Int,b: Int) = Point((p(a).x+p(b).x)/2,(p(a).y+p(b).y)/2)
        val left = Geometry.angle(p(23),p(25),p(27)) ?: return null
        val right = Geometry.angle(p(24),p(26),p(28)) ?: return null
        val hip = middle(23,24); val shoulder = middle(11,12); val ankle = middle(27,28)
        val torso = Geometry.distance(shoulder,hip)
        val leg = (
            Geometry.distance(p(23), p(25)) + Geometry.distance(p(25), p(27)) +
                Geometry.distance(p(24), p(26)) + Geometry.distance(p(26), p(28))
            ) / 2
        if (leg < 20 || torso < 10) return null
        val widthRatio = Geometry.distance(p(11),p(12))/torso
        // View-specific rules: lateral lean in a frontal image is not forward lean.
        val lean = if (widthRatio < c.sideWidthRatio) Math.toDegrees(atan2(abs(shoulder.x-hip.x),hip.y-shoulder.y)) else null
        val ankleWidth = abs(p(27).x-p(28).x)
        val alignment = if (widthRatio > c.frontalWidthRatio && ankleWidth > leg*0.15 && abs(p(27).y-p(28).y)<leg*0.1) abs(p(25).x-p(26).x)/ankleWidth else null
        return Measurement((left+right)/2,hip.y,ankle,leg,confidence,lean,alignment)
    }
}
class FormRules(private val c: SquatConfig) {
    fun live(m: Measurement): List<FormError> = buildList {
        m.torsoLean?.let { if (it > c.maximumTorsoLean) add(FormError("EXCESSIVE_FORWARD_LEAN","Keep your chest lifted",it,"Torso lean <= ${c.maximumTorsoLean} degrees",m.confidence)) }
        m.kneeAnkleRatio?.let { if (it < c.minimumKneeAnkleRatio) add(FormError("KNEE_ALIGNMENT","Keep your knees aligned with your feet",it,"Knee/ankle width >= ${c.minimumKneeAnkleRatio}",m.confidence)) }
    }
    fun end(minAngle: Double, duration: Double, complete: Boolean, confidence: Double): List<FormError> = buildList {
        if (!complete) add(FormError("INSUFFICIENT_DEPTH","Lower your hips a little farther",minAngle,"Knee angle <= ${c.bottomAngle} degrees",confidence))
        if (duration < c.minimumRepSeconds) add(FormError("MOVEMENT_TOO_FAST","Slow down and use a controlled movement",duration,"Rep duration >= ${c.minimumRepSeconds} seconds",confidence))
    }
}
