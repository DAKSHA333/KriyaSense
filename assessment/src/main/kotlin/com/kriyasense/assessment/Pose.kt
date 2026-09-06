package com.kriyasense.assessment

import kotlin.math.*

data class Point(val x: Double, val y: Double, val z: Double = 0.0)
data class Landmark(val position: Point, val visibility: Double, val presence: Double)
/** Upright, unmirrored normalized image landmarks; dimensions correct the image aspect ratio. */
data class PoseFrame(val timestampMs: Long, val landmarks: Map<Int, Landmark>, val width: Int, val height: Int)
object Joints {
    val required = listOf(11, 12, 23, 24, 25, 26, 27, 28)
    fun name(id: Int) = when(id) { 11,12 -> "shoulders"; 13,14 -> "elbows"; 15,16 -> "wrists"; 23,24 -> "hips"; 25,26 -> "knees"; 27,28 -> "ankles"; 29,30 -> "heels"; else -> "required joints" }
}
object Geometry {
    fun distance(a: Point, b: Point) = hypot(a.x-b.x, a.y-b.y)
    fun angle(a: Point, b: Point, c: Point): Double? {
        val u = Point(a.x-b.x, a.y-b.y, a.z-b.z)
        val v = Point(c.x-b.x, c.y-b.y, c.z-b.z)
        val lengths = sqrt(u.x*u.x+u.y*u.y+u.z*u.z)*sqrt(v.x*v.x+v.y*v.y+v.z*v.z)
        if (!lengths.isFinite() || lengths < 1e-8) return null
        return Math.toDegrees(acos(((u.x*v.x+u.y*v.y+u.z*v.z)/lengths).coerceIn(-1.0,1.0)))
    }
    fun rom(angle: Double, config: SquatConfig) = ((config.standingAngle-angle)/(config.standingAngle-config.bottomAngle)*100).coerceIn(0.0,100.0)
}
data class VisibilityCheck(val sufficient: Boolean, val confidence: Double, val instruction: String)
/** Exercise-specific visibility gate; keeps the established 0.65 landmark reliability threshold. */
fun PoseFrame.assessRequiredLandmarks(required: Set<Int>, threshold: Double = 0.65): VisibilityCheck {
    if (width <= 0 || height <= 0) return VisibilityCheck(false, 0.0, "Camera dimensions are unavailable")
    for (id in required.sorted()) {
        val landmark = landmarks[id]
        if (landmark == null || !landmark.visibility.isFinite() || !landmark.presence.isFinite() ||
            min(landmark.visibility, landmark.presence) < threshold || landmark.position.x !in 0.0..1.0 || landmark.position.y !in 0.0..1.0) {
            return VisibilityCheck(false, 0.0, "Move into frame—${Joints.name(id)} are not visible or reliable")
        }
    }
    return VisibilityCheck(true, required.minOf { min(landmarks.getValue(it).visibility, landmarks.getValue(it).presence) }, "")
}
class VisibilityAssessment(private val config: SquatConfig) {
    fun assess(frame: PoseFrame): VisibilityCheck {
        if (frame.width <= 0 || frame.height <= 0) return VisibilityCheck(false,0.0,"Camera dimensions are unavailable")
        for (id in Joints.required) {
            val p = frame.landmarks[id]
            if (p == null || !p.visibility.isFinite() || !p.presence.isFinite() || min(p.visibility,p.presence) < config.visibilityThreshold ||
                p.position.x !in 0.0..1.0 || p.position.y !in 0.0..1.0) {
                return VisibilityCheck(false,0.0,"Move farther from the camera—${Joints.name(id)} are not visible or reliable")
            }
        }
        return VisibilityCheck(true,Joints.required.minOf { min(frame.landmarks.getValue(it).visibility,frame.landmarks.getValue(it).presence) }, "")
    }
}
