package com.kriyasense.app.ui.mirrorcoach

import com.kriyasense.assessment.Geometry
import com.kriyasense.assessment.Point
import kotlin.math.*

data class ProjectedBody(
    val points: Map<Int,Point>, val lengths: Map<Pair<Int,Int>,Double>,
    val width: Int, val height: Int, val centerX: Double, val floorY: Double,
    val sideDirection: Int
)

object GhostPoseGeometry {
    fun generate(profile: MirrorCoachProfile,body: ProjectedBody,progress: Double): Map<Int,Point> {
        val p=progress.coerceIn(0.0,1.0)
        val pixels=when(profile.referenceModel) {
            ReferenceModel.SQUAT_FRONT -> {
                // Move into the useful part of the front-view reference earlier while preserving
                // the same conservative standing and bottom constraints.
                val depth=1.0-(1.0-p).pow(1.45)
                lowerBody(body,165.0+(100.0-165.0)*depth,true)
            }
            ReferenceModel.LUNGE_SIDE -> lowerBody(body,160.0+(105.0-160.0)*p,false)
            ReferenceModel.BICEP_CURL_FRONT -> arms(body,ArmModel.CURL,p)
            ReferenceModel.SHOULDER_PRESS_FRONT -> arms(body,ArmModel.PRESS,p)
            ReferenceModel.PUSH_UP_SIDE -> arms(body,ArmModel.PUSH_UP,p)
            ReferenceModel.CALF_RAISE_FRONT -> calfRaise(body,p)
            ReferenceModel.PLANK_SIDE -> plank(body)
            ReferenceModel.JUMPING_JACK_FRONT -> jumpingJack(body,p)
        }
        return pixels.mapValues { (_,v)->Point(v.x/body.width,v.y/body.height) }
    }

    private fun lowerBody(body: ProjectedBody,kneeAngle: Double,front: Boolean): Map<Int,Point> {
        val base=body.points; val hipMid=mid(base,23,24)
        val desired=listOf(27,28).associateWith { ankle ->
            val thigh=len(body,ankle-4,ankle-2); val shin=len(body,ankle-2,ankle)
            sqrt((thigh*thigh+shin*shin-2*thigh*shin*cos(Math.toRadians(kneeAngle))).coerceAtLeast(0.0))
        }
        val centers=listOf(27,28).map { ankle ->
            val hip=base.getValue(ankle-4); val foot=base.getValue(ankle); val dx=hip.x-foot.x
            foot.y-sqrt((desired.getValue(ankle).pow(2)-dx*dx).coerceAtLeast(0.0))-(hip.y-hipMid.y)
        }
        val centerY=centers.average(); val result=base.toMutableMap()
        for(ankle in listOf(27,28)) {
            val hipId=ankle-4; val kneeId=ankle-2
            val hip=Point(base.getValue(hipId).x,centerY+(base.getValue(hipId).y-hipMid.y))
            val preferred=if(front) if(ankle==27) -1 else 1 else body.sideDirection
            result[hipId]=hip
            result[kneeId]=intersection(hip,len(body,hipId,kneeId),base.getValue(ankle),len(body,kneeId,ankle),base.getValue(kneeId),preferred)
        }
        val shift=mid(result,23,24).y-hipMid.y
        for(id in listOf(11,12)) result[id]=base.getValue(id).copy(y=base.getValue(id).y+shift)
        if(front) addNeutralSquatArms(body,result)
        return result.filterKeys { it in setOf(11,12,13,14,15,16,23,24,25,26,27,28) }
    }

    /** Neutral presentation arms complete the trainer silhouette; they are not a form target. */
    private fun addNeutralSquatArms(body: ProjectedBody,result: MutableMap<Int,Point>) {
        for(elbow in listOf(13,14).filter { it in body.points && it-2 in result && it+2 in body.points }) {
            val shoulder=elbow-2; val wrist=elbow+2; val side=if(elbow==13) -1 else 1
            val upper=len(body,shoulder,elbow); val lower=len(body,elbow,wrist)
            val s=result.getValue(shoulder)
            val e=Point(s.x+side*upper*.30,s.y+upper*sqrt(1.0-.30*.30))
            val inward=-side
            val w=Point(e.x+inward*lower*.62,e.y-lower*sqrt(1.0-.62*.62))
            result[elbow]=e; result[wrist]=w
        }
    }

    private enum class ArmModel { CURL, PRESS, PUSH_UP, JACK }
    private fun arms(body: ProjectedBody,model: ArmModel,p: Double): Map<Int,Point> {
        val result=mutableMapOf<Int,Point>()
        for(elbow in listOf(13,14).filter { it in body.points && it-2 in body.points && it+2 in body.points }) {
            val shoulder=elbow-2; val wrist=elbow+2; val side=if(elbow==13) -1 else 1
            val upper=len(body,shoulder,elbow); val lower=len(body,elbow,wrist)
            when(model) {
                ArmModel.CURL -> {
                    val s=body.points.getValue(shoulder); val e=Point(s.x+side*upper*.08,s.y+upper*sqrt(1-.08*.08))
                    val direction=Math.toRadians(-90.0+side*(155.0+(55.0-155.0)*p))
                    result[shoulder]=s; result[elbow]=e; result[wrist]=Point(e.x+cos(direction)*lower,e.y+sin(direction)*lower)
                }
                ArmModel.PRESS -> {
                    val s=body.points.getValue(shoulder)
                    val upperDirection=Math.toRadians((if(side<0) 180.0+90.0*p else -90.0*p))
                    val e=Point(s.x+cos(upperDirection)*upper,s.y+sin(upperDirection)*upper)
                    val lowerDirection=Math.toRadians(if(side<0) 65.0-155.0*p else 115.0+155.0*p)
                    result[shoulder]=s; result[elbow]=e; result[wrist]=Point(e.x+cos(lowerDirection)*lower,e.y+sin(lowerDirection)*lower)
                }
                ArmModel.PUSH_UP -> {
                    val hand=body.points.getValue(wrist); val facing=body.sideDirection
                    val forearmDirection=Math.toRadians(90.0+facing*10.0)
                    val e=Point(hand.x-cos(forearmDirection)*lower,hand.y-sin(forearmDirection)*lower)
                    val angle=Math.toRadians(155.0+(95.0-155.0)*p); val toHand=atan2(hand.y-e.y,hand.x-e.x)
                    val upperDirection=toHand-facing*angle
                    result[wrist]=hand; result[elbow]=e; result[shoulder]=Point(e.x+cos(upperDirection)*upper,e.y+sin(upperDirection)*upper)
                }
                ArmModel.JACK -> {
                    val s=body.points.getValue(shoulder)
                    val direction=Math.toRadians(if(side<0) 100.0+150.0*p else 80.0-150.0*p)
                    val e=Point(s.x+cos(direction)*upper,s.y+sin(direction)*upper)
                    result[shoulder]=s; result[elbow]=e; result[wrist]=Point(e.x+cos(direction)*lower,e.y+sin(direction)*lower)
                }
            }
        }
        return result
    }

    private fun calfRaise(body: ProjectedBody,p: Double): Map<Int,Point> {
        val lift=.039*body.height*p
        return body.points.filterKeys { it in setOf(23,24,25,26,27,28,29,30) }.mapValues { (_,v)->v.copy(y=v.y-lift) }
    }

    private fun plank(body: ProjectedBody): Map<Int,Point> = buildMap {
        val base=body.points
        for(ankle in listOf(27,28).filter { it in base && it-4 in base && it-16 in base }) {
            val hip=ankle-4; val shoulder=ankle-16; val foot=base.getValue(ankle)
            val raw=Point(base.getValue(shoulder).x-foot.x,base.getValue(shoulder).y-foot.y)
            val magnitude=hypot(raw.x,raw.y).coerceAtLeast(1.0); val axis=Point(raw.x/magnitude,raw.y/magnitude)
            val h=Point(foot.x+axis.x*Geometry.distance(base.getValue(hip),foot),foot.y+axis.y*Geometry.distance(base.getValue(hip),foot))
            put(ankle,foot); put(hip,h); put(shoulder,Point(h.x+axis.x*len(body,shoulder,hip),h.y+axis.y*len(body,shoulder,hip)))
        }
    }

    private fun jumpingJack(body: ProjectedBody,p: Double): Map<Int,Point> {
        val result=body.points.filterKeys { it in setOf(11,12,23,24) }.toMutableMap()
        val shoulderWidth=body.points.getValue(12).x-body.points.getValue(11).x
        val baseHipMid=mid(body.points,23,24)
        val targetFeet=listOf(27,28).associateWith { ankle ->
            val sign=if(ankle==27) -1 else 1
            Point(body.centerX+sign*shoulderWidth*.75,body.points.getValue(ankle).y)
        }
        val targetHipCenterY=listOf(27,28).map { ankle ->
            val hip=body.points.getValue(ankle-4); val foot=targetFeet.getValue(ankle)
            val reach=len(body,ankle-4,ankle-2)+len(body,ankle-2,ankle); val dx=hip.x-foot.x
            foot.y-sqrt((reach*reach-dx*dx).coerceAtLeast(0.0))-(hip.y-baseHipMid.y)
        }.average()
        val hipShift=(targetHipCenterY-baseHipMid.y)*p
        for(id in listOf(23,24,11,12)) result[id]=body.points.getValue(id).copy(y=body.points.getValue(id).y+hipShift)
        for(ankle in listOf(27,28)) {
            val sign=if(ankle==27) -1 else 1; val start=body.points.getValue(ankle)
            val targetX=targetFeet.getValue(ankle).x
            val foot=Point(start.x+(targetX-start.x)*p,start.y); val hip=body.points.getValue(ankle-4)
                .copy(y=body.points.getValue(ankle-4).y+hipShift)
            result[ankle]=foot
            result[ankle-2]=intersection(hip,len(body,ankle-4,ankle-2),foot,len(body,ankle-2,ankle),body.points.getValue(ankle-2),sign)
        }
        result.putAll(arms(body.copy(points=body.points+result.filterKeys { it in setOf(11,12) }),ArmModel.JACK,p)); return result
    }

    private fun intersection(a: Point,ra: Double,b: Point,rb: Double,@Suppress("UNUSED_PARAMETER") near: Point,preferredSide: Int): Point {
        val dx=b.x-a.x; val dy=b.y-a.y; val d=hypot(dx,dy).coerceAtLeast(1e-6)
        val x=(ra*ra-rb*rb+d*d)/(2*d); val h=sqrt((ra*ra-x*x).coerceAtLeast(0.0))
        val center=Point(a.x+x*dx/d,a.y+x*dy/d)
        val one=Point(center.x-h*dy/d,center.y+h*dx/d); val two=Point(center.x+h*dy/d,center.y-h*dx/d)
        return if(preferredSide<0) listOf(one,two).minBy { it.x } else listOf(one,two).maxBy { it.x }
    }
    private fun len(body: ProjectedBody,a: Int,b: Int)=body.lengths.getValue(canonical(a,b))
    fun canonical(a: Int,b: Int)=if(a<b) a to b else b to a
    private fun mid(p: Map<Int,Point>,a: Int,b: Int)=Point((p.getValue(a).x+p.getValue(b).x)/2,(p.getValue(a).y+p.getValue(b).y)/2)
    fun display(p: Point,width: Double,height: Double,mirror: Boolean)=Point((if(mirror) 1-p.x else p.x)*width,p.y*height)
    fun advance(current: Double,target: Double,dtMs: Long): Double {
        if(dtMs<=0) return current
        val desired=current+(target.coerceIn(0.0,1.0)-current)*(1-exp(-dtMs/180.0)); val limit=dtMs/1000.0*1.5
        return desired.coerceIn(current-limit,current+limit).coerceIn(0.0,1.0)
    }
}
