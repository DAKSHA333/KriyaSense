package com.kriyasense.app.ui.mirrorcoach

import android.content.pm.ApplicationInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kriyasense.app.ui.theme.*
import com.kriyasense.assessment.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private const val SHOW_MC_CALIBRATION_DEBUG=false

@Composable
fun GhostPoseOverlay(
    frame: PoseFrame?,live: LiveAssessment,running: Boolean,mirror: Boolean,
    sessionId: String?,profile: MirrorCoachProfile,controller: GhostPoseController
) {
    var output by remember(sessionId,profile.exerciseId) { mutableStateOf(GhostOutput(GhostReadiness.WAITING)) }
    var renderedFrame by remember(sessionId,profile.exerciseId) { mutableStateOf<PoseFrame?>(null) }
    var viewportSize by remember(sessionId,profile.exerciseId) { mutableStateOf(IntSize.Zero) }
    val showDebug=LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0
    LaunchedEffect(frame,live,running,controller) {
        output=controller.update(frame,live,running); renderedFrame=frame
        delay(250)
        output=output.copy(readiness=GhostReadiness.TRACKING_LOST,calibrationDebug=output.calibrationDebug?.copy(
            accepted=0,rejected="FRAME_TIMEOUT",stableMs=0))
    }
    val currentVisible=running && frame!=null && profile.criticalOptions.any { frame.assessRequiredLandmarks(it).sufficient }
    val trainerLayout=remember(output.calibratedBody,viewportSize,mirror,profile.exerciseId) {
        output.calibratedBody?.let { body ->
            TrainerPoseTransform.create(profile,body,viewportSize.width.toDouble(),viewportSize.height.toDouble(),mirror)
        }
    }
    val animatedScale by animateFloatAsState((trainerLayout?.pixelScale?:0.0).toFloat(),tween(400),label="trainerScale")
    val animatedCenterX by animateFloatAsState((trainerLayout?.targetCenterX?:0.0).toFloat(),tween(400),label="trainerCenter")
    val animatedFloorY by animateFloatAsState((trainerLayout?.targetFloorY?:0.0).toFloat(),tween(400),label="trainerFloor")
    val animatedHeight by animateFloatAsState((trainerLayout?.trainerHeight?:0.0).toFloat(),tween(400),label="trainerHeight")
    val displayedLayout=trainerLayout?.copy(pixelScale=animatedScale.toDouble(),targetCenterX=animatedCenterX.toDouble(),
        targetFloorY=animatedFloorY.toDouble(),trainerHeight=animatedHeight.toDouble())
    val trainerJoints=remember(output.joints,displayedLayout,output.calibratedBody) {
        val body=output.calibratedBody
        val sourceJoints=output.joints
        if(body!=null && displayedLayout!=null && sourceJoints!=null) displayedLayout.transform(sourceJoints,body) else null
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewportSize=it }) {
        if(profile.referenceModel==ReferenceModel.SQUAT_FRONT && running && displayedLayout!=null && trainerJoints!=null) {
            VirtualTrainerCanvas(trainerJoints,displayedLayout,profile.renderedEdges)
            TrainerLabels(displayedLayout,viewportSize)
        } else if(currentVisible && renderedFrame==frame) {
            output.joints?.let { joints ->
                LegacyReferenceSkeleton(joints,profile.renderedEdges,mirror)
            }
        }
        val message=when {
            !running -> "Start your session to use Mirror Coach"
            profile.referenceModel==ReferenceModel.SQUAT_FRONT && !output.personalized && output.calibratedBody!=null -> "Calibrating..."
            profile.referenceModel==ReferenceModel.SQUAT_FRONT && output.readiness==GhostReadiness.TRACKING_LOST -> "Tracking paused • Trainer holding"
            !currentVisible || output.readiness==GhostReadiness.TRACKING_LOST -> "Step back into view to continue"
            output.readiness==GhostReadiness.WAITING -> profile.waitingText
            output.readiness==GhostReadiness.CALIBRATING -> "Calibrating Mirror Coach..."
            output.readiness==GhostReadiness.REPOSITION -> "Return to your starting position to recalibrate"
            profile.referenceModel==ReferenceModel.SQUAT_FRONT && trainerLayout==null -> "Move to one side so your trainer has room"
            profile.referenceModel==ReferenceModel.SQUAT_FRONT -> "Mirror Coach Ready • Follow the trainer beside you"
            else -> "Mirror Coach Ready • Follow the lavender ghost"
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(showDebug && SHOW_MC_CALIBRATION_DEBUG && output.readiness==GhostReadiness.READY && trainerLayout!=null) {
                Text(String.format(Locale.US,"MC TRAINER • side=%s • progress=%.2f • scale=%.2f",
                    trainerLayout.side.name,output.progress,trainerLayout.relativeScale),
                    Modifier.background(AppBackground.copy(alpha=.9f),RoundedCornerShape(10.dp)).padding(horizontal=8.dp,vertical=5.dp),
                    color=Lavender,style=MaterialTheme.typography.labelSmall)
            }
            Text(message,Modifier.background(AppBackground.copy(alpha=.9f),RoundedCornerShape(12.dp)).padding(8.dp),
                color=PrimaryText,style=MaterialTheme.typography.bodySmall)
        }
        if(showDebug && SHOW_MC_CALIBRATION_DEBUG && profile.referenceModel==ReferenceModel.SQUAT_FRONT && output.readiness!=GhostReadiness.READY) {
            output.calibrationDebug?.let { debug ->
                val missing=debug.missing.ifEmpty { listOf("none") }.joinToString(", ")
                Text("MC CALIBRATION\naccepted = ${debug.accepted} / ${debug.required}\nrejected = ${debug.rejected}\n"+
                    "stableMs = ${debug.stableMs}\nvisibility = ${debug.visibility}\nmovement = ${debug.movement}\nmissing = $missing",
                    Modifier.align(Alignment.TopEnd).padding(12.dp).widthIn(max=280.dp)
                        .background(AppBackground.copy(alpha=.92f),RoundedCornerShape(12.dp)).padding(9.dp),
                    color=PaleLavender,style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun VirtualTrainerCanvas(joints: Map<Int,Point>,layout: TrainerLayout,edges: List<Pair<Int,Int>>) {
    Canvas(Modifier.fillMaxSize()) {
        fun point(id: Int)=joints[id]?.let { Offset(it.x.toFloat(),it.y.toFloat()) }
        val h=layout.trainerHeight.toFloat()
        val upperArm=(h*.09f).coerceIn(16.dp.toPx(),44.dp.toPx())
        val forearm=(h*.075f).coerceIn(14.dp.toPx(),38.dp.toPx())
        val thigh=(h*.115f).coerceIn(20.dp.toPx(),54.dp.toPx())
        val lowerLeg=(h*.09f).coerceIn(17.dp.toPx(),44.dp.toPx())
        val limbLayers=listOf(
            (11 to 13) to upperArm,(12 to 14) to upperArm,
            (13 to 15) to forearm,(14 to 16) to forearm,
            (23 to 25) to thigh,(24 to 26) to thigh,
            (25 to 27) to lowerLeg,(26 to 28) to lowerLeg
        )

        // 1. Low-opacity human halo.
        limbLayers.forEach { (edge,width) ->
            val a=point(edge.first); val b=point(edge.second)
            if(a!=null && b!=null) drawLine(Lavender.copy(alpha=.13f),a,b,width*1.55f,StrokeCap.Round)
        }

        val ls=point(11); val rs=point(12); val lh=point(23); val rh=point(24)
        val shoulderMid=if(ls!=null && rs!=null) Offset((ls.x+rs.x)/2,(ls.y+rs.y)/2) else null
        val headRadius=(h*.067f).coerceIn(14.dp.toPx(),34.dp.toPx())
        val headCenter=shoulderMid?.let { Offset(it.x,it.y-headRadius*1.22f) }
        headCenter?.let { center ->
            drawOval(Lavender.copy(alpha=.13f),Offset(center.x-headRadius*1.12f,center.y-headRadius*1.42f),
                Size(headRadius*2.24f,headRadius*2.84f))
        }

        // 2. Filled, simplified articulated body silhouette.
        if(ls!=null && rs!=null && lh!=null && rh!=null) {
            val expand=h*.018f
            val torso=Path().apply {
                moveTo(ls.x-expand,ls.y-expand*.25f)
                lineTo(rs.x+expand,rs.y-expand*.25f)
                lineTo(rh.x+expand*.65f,rh.y+expand*.25f)
                lineTo(lh.x-expand*.65f,lh.y+expand*.25f)
                close()
            }
            drawPath(torso,Lavender.copy(alpha=.18f))
            val core=Path().apply {
                moveTo(ls.x,ls.y); lineTo(rs.x,rs.y); lineTo(rh.x,rh.y); lineTo(lh.x,lh.y); close()
            }
            drawPath(core,Lavender.copy(alpha=.46f))
            drawLine(Lavender.copy(alpha=.48f),ls,rs,(h*.065f).coerceAtLeast(18.dp.toPx()),StrokeCap.Round)
            drawLine(Lavender.copy(alpha=.45f),lh,rh,(h*.075f).coerceAtLeast(20.dp.toPx()),StrokeCap.Round)
        }
        limbLayers.forEach { (edge,width) ->
            val a=point(edge.first); val b=point(edge.second)
            if(a!=null && b!=null) drawLine(Lavender.copy(alpha=.48f),a,b,width,StrokeCap.Round)
        }
        headCenter?.let { center ->
            drawOval(Lavender.copy(alpha=.50f),Offset(center.x-headRadius*.82f,center.y-headRadius),
                Size(headRadius*1.64f,headRadius*2f))
            drawOval(PaleLavender.copy(alpha=.14f),Offset(center.x-headRadius*.55f,center.y-headRadius*.72f),
                Size(headRadius*.97f,headRadius*1.34f))
            shoulderMid?.let { shoulders ->
                drawLine(Lavender.copy(alpha=.48f),Offset(center.x,center.y+headRadius*.82f),
                    Offset(shoulders.x,shoulders.y+h*.018f),(h*.038f).coerceAtLeast(11.dp.toPx()),StrokeCap.Round)
            }
        }

        // 3. Subtle articulated reference inside the dominant silhouette.
        val boneWidth=(h*.0085f).coerceIn(2.5.dp.toPx(),5.5.dp.toPx())
        edges.forEach { (a,b) ->
            val start=point(a); val end=point(b)
            if(start!=null && end!=null) {
                drawLine(Lavender.copy(alpha=.14f),start,end,boneWidth*2.0f,StrokeCap.Round)
                drawLine(PaleLavender.copy(alpha=.48f),start,end,boneWidth,StrokeCap.Round)
            }
        }

        // 4. Bright joints and restrained joint glow.
        val jointRadius=(h*.011f).coerceIn(3.5.dp.toPx(),7.dp.toPx())
        joints.keys.forEach { id -> point(id)?.let {
            drawCircle(Lavender.copy(alpha=.14f),jointRadius*1.6f,it)
            drawCircle(PaleLavender.copy(alpha=.62f),jointRadius,it)
        } }
        for(ankle in listOf(27,28)) point(ankle)?.let { foot ->
            drawLine(Lavender.copy(alpha=.48f),Offset(foot.x-h*.026f,foot.y),Offset(foot.x+h*.036f,foot.y),
                (h*.028f).coerceAtLeast(9.dp.toPx()),StrokeCap.Round)
        }
    }
}

@Composable
private fun TrainerLabels(layout: TrainerLayout,viewport: IntSize) {
    val density=LocalDensity.current
    val width=104.dp
    val labelWidthPx=with(density) { width.toPx() }
    val y=(layout.targetFloorY+with(density) { 7.dp.toPx() })
        .coerceAtMost(viewport.height.toDouble()-with(density) { 34.dp.toPx() }.toDouble())
    fun labelModifier(center: Double)=Modifier
        .offset { IntOffset((center-labelWidthPx/2).roundToInt(),y.roundToInt()) }
        .width(width)
        .background(AppBackground.copy(alpha=.82f),RoundedCornerShape(50))
        .padding(horizontal=7.dp,vertical=4.dp)
    Text("YOU",labelModifier(layout.userCenterX),color=PrimaryText,textAlign=TextAlign.Center,
        style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
    Text("VIRTUAL TRAINER",labelModifier(layout.targetCenterX),color=Lavender,textAlign=TextAlign.Center,
        style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
}

@Composable
private fun LegacyReferenceSkeleton(joints: Map<Int,Point>,edges: List<Pair<Int,Int>>,mirror: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        fun offset(id: Int): Offset? = joints[id]?.let {
            val point=GhostPoseGeometry.display(it,size.width.toDouble(),size.height.toDouble(),mirror)
            Offset(point.x.toFloat(),point.y.toFloat())
        }
        edges.forEach { (a,b)->
            val start=offset(a); val end=offset(b)
            if(start!=null && end!=null) drawLine(Lavender.copy(alpha=.58f),start,end,5.5.dp.toPx(),StrokeCap.Round)
        }
        joints.keys.forEach { id->offset(id)?.let { drawCircle(Lavender.copy(alpha=.62f),6.5.dp.toPx(),it) } }
    }
}
