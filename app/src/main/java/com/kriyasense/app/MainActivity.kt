package com.kriyasense.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kriyasense.assessment.*
import kotlinx.coroutines.delay
import android.os.SystemClock
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private val Mint=Color(0xFF9AEACB)
private val Ink=Color(0xFF101E25)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Mint,background=Ink,surface=Color(0xFF1D3039))) { KriyaSense() } }
    }
}
private fun Double?.display(unit: String="") = this?.let { "%.1f%s".format(it,unit) } ?: "—"
private fun Long.dateTime() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(this))
private data class ExerciseTutorial(val setup: String,val cues: List<String>,val checks: List<String>,val mediaFileName: String)
private fun tutorialFor(type: ExerciseType)=when(type) {
    ExerciseType.SQUAT -> ExerciseTutorial("Stand side-on with your whole body in view.",listOf("Keep feet planted","Lower with control","Return to standing"),listOf("Knee angle","Movement depth and ROM","Configured form rules"),"squat_demo")
    ExerciseType.LUNGE -> ExerciseTutorial("Stand where hips, knees and ankles are visible.",listOf("Step and lower steadily","Reach depth","Return to start"),listOf("Knee angle","Movement range"),"lunge_demo")
    ExerciseType.PUSH_UP -> ExerciseTutorial("Keep shoulders, elbows, wrists and hips visible.",listOf("Lower with control","Use your arm range","Return to top"),listOf("Elbow angle","Movement range"),"push_up_demo")
    ExerciseType.BICEP_CURL -> ExerciseTutorial("Keep both arms visible from shoulder to wrist.",listOf("Start extended","Curl higher","Return to extension"),listOf("Elbow angle","Curl range"),"bicep_curl_demo")
    ExerciseType.SHOULDER_PRESS -> ExerciseTutorial("Keep shoulders, elbows and wrists in frame.",listOf("Start lowered","Press overhead","Return with control"),listOf("Elbow extension","Wrists above shoulders"),"shoulder_press_demo")
    ExerciseType.JUMPING_JACK -> ExerciseTutorial("Leave room for arms and feet to open.",listOf("Start closed","Raise arms","Open stance"),listOf("Arm position","Stance width"),"jumping_jack_demo")
    ExerciseType.PLANK -> ExerciseTutorial("Use a clear side view of shoulders, hips and ankles.",listOf("Make a straight line","Hold steadily","Stay visible"),listOf("Shoulder–hip–ankle alignment","Hold duration"),"plank_demo")
    ExerciseType.CALF_RAISE -> ExerciseTutorial("Keep ankles and heels clearly visible.",listOf("Start standing","Raise heels","Return slowly"),listOf("Heel lift"),"calf_raise_demo")
}

@Composable private fun KriyaSense() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var screen by remember { mutableStateOf("landing") }
    var engine by remember { mutableStateOf<AssessmentEngine>(SquatEngine()) }
    var selectedType by remember { mutableStateOf(ExerciseType.SQUAT) }
    var selectedVariant by remember { mutableStateOf(ExerciseVariants.squatStandard) }
    var live by remember { mutableStateOf(engine.current()) }
    var result by remember { mutableStateOf(SessionResult()) }
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var lastPoseTime by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var pose by remember { mutableStateOf<PoseFrame?>(null) }
    var mirror by remember { mutableStateOf(true) }
    var mirrorCoachEnabled by remember { mutableStateOf(false) }
    val history=remember { SharedPreferencesWorkoutHistory(context) }
    val profileStore=remember { SharedPreferencesUserProfile(context) }
    var profile by remember { mutableStateOf(profileStore.load()) }
    var heightInput by remember { mutableStateOf(profile.heightCm?.toString() ?: "") }
    var weightInput by remember { mutableStateOf(profile.weightKg?.toString() ?: "") }
    var historyRevision by remember { mutableIntStateOf(0) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var currentStoredSession by remember { mutableStateOf<WorkoutSession?>(null) }
    var activeChallenge by remember { mutableStateOf<FormChallenge?>(null) }
    var selectedHistorySession by remember { mutableStateOf<WorkoutSession?>(null) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission=it }
    val voice=remember { VoiceFeedback(context) }
    DisposableEffect(Unit) { onDispose { voice.close() } }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_STOP && running) { running=false; live=engine.pause(); voice.stop(); pose=null }
            if(event==Lifecycle.Event.ON_RESUME) permission=ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(running,screen) {
        while(running && screen=="camera") {
            delay(350)
            if(SystemClock.uptimeMillis()-lastPoseTime>750) {
                pose=null
                live=engine.process(PoseFrame(SystemClock.uptimeMillis(),emptyMap(),1,1))
            }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("KriyaSense",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if(screen!="history" && screen!="historyDetail") TextButton(onClick={screen="history"}) { Text("History") }
                    Text("ON DEVICE",color=Mint,style=MaterialTheme.typography.labelSmall)
                }
            }
            when(screen) {
                "landing" -> {
                    Spacer(Modifier.weight(1f))
                    Text("KriyaSense",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold,color=Mint)
                    Text("Move Better. Every Day.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("Real-time, on-device movement assessment and coaching.",color=Color.LightGray)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick={screen="profile"},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Get Started") }
                    OutlinedButton(onClick={screen="history"},modifier=Modifier.fillMaxWidth()) { Text("Workout History") }
                    Text("Camera analysis happens on-device. Frames are not stored or uploaded.",style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
                    Spacer(Modifier.weight(1f))
                }
                "profile" -> {
                    Text("Your setup",style=MaterialTheme.typography.headlineLarge)
                    Text("Optional details personalize your setup only. BMI is informational, not medical advice.",color=Color.LightGray)
                    OutlinedTextField(heightInput,{heightInput=it},label={Text("Height (cm)")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(weightInput,{weightInput=it},label={Text("Weight (kg)")},modifier=Modifier.fillMaxWidth())
                    val draft=profile.copy(heightCm=heightInput.toDoubleOrNull(),weightKg=weightInput.toDoubleOrNull())
                    Text("BMI: ${draft.bmi.display()}")
                    Text("Experience",style=MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { ExperienceLevel.entries.forEach { level -> FilterChip(selected=profile.experience==level,onClick={profile=profile.copy(experience=level)},label={Text(level.name.lowercase().replaceFirstChar { it.uppercase() })}) } }
                    Text("Primary goal",style=MaterialTheme.typography.titleMedium)
                    Column { FitnessGoal.entries.forEach { goal -> FilterChip(selected=profile.goal==goal,onClick={profile=profile.copy(goal=goal)},label={Text(goal.name.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() })}) } }
                    draft.validationError()?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick={ if(draft.validationError()==null) { profileStore.save(draft); profile=draft; screen="select" } },modifier=Modifier.fillMaxWidth()) { Text("Continue") }
                    TextButton(onClick={screen="select"},modifier=Modifier.fillMaxWidth()) { Text("Skip for now") }
                }
                "select" -> {
                    Text("Choose an exercise",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("On-device movement assessment. Select one to see its setup guidance.",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(selectedType.displayName,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("selected_exercise_name"))
                            Text(selectedType.id + if(selectedType.experimental) " • EXPERIMENTAL" else "",color=Mint,style=MaterialTheme.typography.labelMedium,modifier=Modifier.testTag("selected_exercise_id"))
                            Text(if(selectedType==ExerciseType.PLANK) "Use a clear side view and keep your shoulders, hips and ankles visible." else "Keep the required joints clearly visible; movement feedback is assessed on-device.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("selected_exercise_instruction"))
                            LazyVerticalGrid(columns=GridCells.Fixed(2),modifier=Modifier.weight(1f).fillMaxWidth().testTag("exercise_grid"),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(ExerciseType.entries.toList(),key={it.id}) { type ->
                                    val selected=selectedType==type
                                    Button(onClick={selectedType=type; selectedVariant=ExerciseVariants.forExercise(type).first()},modifier=Modifier.fillMaxWidth().testTag("exercise_${type.name}"),colors=ButtonDefaults.buttonColors(containerColor=if(selected) Mint else Color(0xFF29434E),contentColor=if(selected) Ink else Color.White)) {
                                        Text(type.displayName + if(type.experimental) "\nExperimental" else "",style=MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick={screen=if(ExerciseVariants.forExercise(selectedType).size>1) "variants" else "tutorial"},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("start_camera")) { Text("View tutorial • ${selectedType.displayName}") }
                    Text("Private by design. Camera frames are processed in memory and never saved or uploaded.",style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
                }
                "variants" -> {
                    Text("Choose your version",style=MaterialTheme.typography.headlineLarge)
                    Text("Choose the version that fits your current training setup.",color=Color.LightGray)
                    ExerciseVariants.forExercise(selectedType).forEach { variant -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Text(variant.displayName,fontWeight=FontWeight.Bold); Text(variant.description,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={selectedVariant=variant; screen="tutorial"},modifier=Modifier.fillMaxWidth()) { Text("Select") }
                    } } }
                    Spacer(Modifier.weight(1f)); OutlinedButton(onClick={screen="select"},modifier=Modifier.fillMaxWidth()) { Text("Back") }
                }
                "tutorial" -> {
                    val tutorial=tutorialFor(selectedType)
                    val challenge=ChallengeDefinitions.forExercise(selectedType)?.takeIf { selectedVariant.id==ExerciseVariants.standardId(selectedType) }
                    Text(selectedVariant.displayName,style=MaterialTheme.typography.headlineLarge)
                    Text("Setup",color=Mint,style=MaterialTheme.typography.labelLarge)
                    Text(tutorial.setup)
                    Card(Modifier.fillMaxWidth().height(150.dp)) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("Exercise demo coming soon",color=Color.LightGray) } }
                    Text("Technique cues",style=MaterialTheme.typography.titleLarge)
                    tutorial.cues.forEach { Text("• $it") }
                    Text("What KriyaSense checks",style=MaterialTheme.typography.titleLarge)
                    tutorial.checks.forEach { Text("• $it") }
                    if(selectedType.experimental) Text("Experimental tracking: heel landmarks can vary by view and lighting.",color=MaterialTheme.colorScheme.error)
                    Spacer(Modifier.weight(1f))
                    Button(onClick={ activeChallenge=null; engine=ExerciseEngines.create(selectedType,selectedVariant.id); live=engine.current(); pose=null; started=false; running=false; cameraError=null; activeSessionId=UUID.randomUUID().toString(); screen="camera"; if(!permission) request.launch(Manifest.permission.CAMERA) },modifier=Modifier.fillMaxWidth()) { Text("Start Assessment") }
                    challenge?.let { c -> OutlinedButton(onClick={ activeChallenge=c; engine=ExerciseEngines.create(selectedType,selectedVariant.id); live=engine.current(); pose=null; started=false; running=false; cameraError=null; activeSessionId=UUID.randomUUID().toString(); screen="camera"; if(!permission) request.launch(Manifest.permission.CAMERA) },modifier=Modifier.fillMaxWidth()) { Text("Try Challenge") } }
                    OutlinedButton(onClick={screen="select"},modifier=Modifier.fillMaxWidth()) { Text("Back to exercises") }
                }
                "camera" -> {
                    Text("${selectedType.displayName} • ${selectedVariant.displayName}",style=MaterialTheme.typography.titleLarge)
                    if(!permission) {
                        Text("Camera access is needed to measure your movement. Allow access, or enable Camera in app settings if previously denied.")
                        Button(onClick={ request.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                        TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
                        TextButton(onClick={ screen="select" }) { Text("Back") }
                    } else {
                        activeChallenge?.let { challenge ->
                            val progress=FormChallenges.evaluate(challenge,engine.finish())
                            Text("${challenge.title.uppercase()}  ${progress.progress} / ${challenge.targetValue}",color=Mint,style=MaterialTheme.typography.labelLarge)
                        }
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black,RoundedCornerShape(20.dp))) {
                            CameraView(Modifier.fillMaxSize(),onPose={ frame,front ->
                                lastPoseTime=SystemClock.uptimeMillis(); mirror=front
                                pose=if(running) frame else null
                                if(running) {
                                    live=engine.process(frame)
                                    voice.say(live.coaching)
                                }
                            },onError={ cameraError=it; if(running) { running=false; live=engine.pause(); voice.stop() } })
                            PoseOverlay(pose,mirror,if(mirrorCoachEnabled && selectedVariant.id=="SQUAT_STANDARD" && live.result.visibility==Visibility.SUFFICIENT) live.state else null)
                            Text(if(running) live.result.status.name.replace('_',' ') else if(started) "PAUSED" else "READY",
                                Modifier.align(Alignment.TopStart).padding(12.dp).background(Ink.copy(alpha=0.85f),RoundedCornerShape(8.dp)).padding(8.dp),color=Mint)
                        }
                        cameraError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        if(selectedVariant.id=="SQUAT_STANDARD") Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Column { Text("Mirror Coach",fontWeight=FontWeight.Bold); Text("Reference pose guide",style=MaterialTheme.typography.bodySmall,color=Color.LightGray) }
                            Switch(checked=mirrorCoachEnabled,onCheckedChange={mirrorCoachEnabled=it})
                        }
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text("COACHING", color=Mint, style=MaterialTheme.typography.labelSmall)
                            Text(if(started) live.coaching.message else "Stand upright, then start your session.",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        } }
                        if(live.result.driftDetected) Text("MOVEMENT TREND  Drifting ↓",color=Mint,style=MaterialTheme.typography.labelMedium)
                        Column(Modifier.heightIn(max=170.dp).verticalScroll(rememberScrollState())) {
                            MetricRow(live.primaryMetricLabel ?: "Primary metric",live.primaryMetricValue.display(live.primaryMetricUnit),"Current ROM",live.currentRomPercentage.display("%"))
                            MetricRow("Complete",live.result.completeReps.toString(),"Incomplete",live.result.incompleteReps.toString())
                            MetricRow("Movement state",live.movementState,"Pose",live.result.visibility.name)
                            MetricRow("Avg rep",live.result.averageRepDurationSeconds.display(" s"),"Tension",live.result.timeUnderTensionSeconds.display(" s"))
                            MetricRow("Form factor",live.result.formFactor.display(),"Confidence",(live.result.confidence*100).display("%"))
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(enabled=cameraError==null,onClick={
                                if(running) { running=false; live=engine.pause(); voice.stop(); pose=null }
                                else { engine.resume(); started=true; running=true; lastPoseTime=SystemClock.uptimeMillis() }
                            },modifier=Modifier.weight(1f)) { Text(if(running) "Pause" else if(started) "Resume" else "Start Session") }
                            OutlinedButton(onClick={
                                val completed=engine.finish()
                                activeSessionId?.let { id ->
                                    val stored=WorkoutHistory.fromResult(completed,id,System.currentTimeMillis())
                                    if(history.save(stored)) { currentStoredSession=stored; historyRevision++ }
                                    activeSessionId=null
                                }
                                result=completed; running=false; voice.stop(); pose=null; screen="results"
                            },modifier=Modifier.weight(1f)) { Text("Finish") }
                        }
                    }
                }
                "results" -> {
                    val summary=remember(result) { SessionSummaries.from(result) }
                    val signature=remember(result,historyRevision) { MovementSignatures.build(history.sessions(),result.exerciseId) }
                    val comparison=remember(result,historyRevision) { currentStoredSession?.let { MovementSignatures.compare(it,history.sessions().filter { session -> session.id!=it.id }) } }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Session complete",style=MaterialTheme.typography.headlineLarge)
                        activeChallenge?.let { challenge ->
                            val challengeResult=FormChallenges.evaluate(challenge,result)
                            Text(if(challengeResult.status==ChallengeStatus.COMPLETED) "CHALLENGE COMPLETE" else "CHALLENGE NOT COMPLETED",color=Mint,style=MaterialTheme.typography.titleLarge)
                            Text(challenge.title,fontWeight=FontWeight.Bold)
                            Text(challengeResult.message)
                            challengeResult.qualityValue?.let { Text("Consistency  ${it.display("%")}") }
                        }
                        Text("${result.exerciseName} • ${result.status.name.replace('_',' ')}",color=Mint)
                        Text("${result.completeReps}",style=MaterialTheme.typography.displayLarge)
                        Text("complete repetitions",color=Color.LightGray)
                        MetricRow("Incomplete",result.incompleteReps.toString(),"Total attempts",summary.totalAttempts.toString())
                        MetricRow("Completion",summary.completionPercentage.display("%"),"Average rep",result.averageRepDurationSeconds.display(" s"))
                        MetricRow("Average ROM",result.romPercentage.display("%"),"Tension",result.timeUnderTensionSeconds.display(" s"))
                        MetricRow("Form factor",result.formFactor.display(),"Confidence",(result.confidence*100).display("%"))
                        if(result.driftDetected) Text("Session trend: movement quality decreased during later reps.",color=Color.LightGray)
                        Text("Your movement signature",style=MaterialTheme.typography.titleLarge)
                        if(signature==null || signature.sessionsUsed<2) Text("Your personal baseline is being built. Complete more sessions to see your movement trends.",color=Color.LightGray)
                        else {
                            signature.averageRomPercentage?.let { MetricRow("ROM",it.display("%"),"Tempo",signature.averageRepDurationSeconds.display(" s")) }
                            MetricRow("ROM consistency",signature.romConsistency.display("%"),"Tempo consistency",signature.tempoConsistency.display("%"))
                            signature.averageHoldDurationSeconds?.let { Text("Average hold  ${it.display(" s")}") }
                            comparison?.let { c -> Text("Compared with your prior baseline: ROM ${c.romChange?.let { "${if(it>=0) "+" else ""}${it.display("%")}" } ?: "—"}; Tempo ${c.tempoChangeSeconds.display(" s")}",style=MaterialTheme.typography.bodySmall,color=Color.LightGray) }
                        }
                        summary.mostCommonObservation?.let { Text("Most common observation: ${it.message}",color=Color.LightGray) }
                        Text("Form observations",style=MaterialTheme.typography.titleLarge)
                        if(result.formErrors.isEmpty()) Text(if(result.timeline.isEmpty()) "No assessed repetitions." else "No configured form warnings detected.")
                        result.formErrors.forEach { e -> ErrorCard(e) }
                        Text("Rep timeline",style=MaterialTheme.typography.titleLarge)
                        result.timeline.forEach { rep ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text("Rep ${rep.index} • ${if(rep.complete) "Complete" else "Incomplete"}",fontWeight=FontWeight.Bold)
                                Text("${rep.startSeconds.display()}–${rep.endSeconds.display()} s • ${rep.durationSeconds.display(" s")} • ROM ${rep.romPercentage.display("%")}")
                                rep.formErrors.forEach { Text(it.message,style=MaterialTheme.typography.bodySmall) }
                            } }
                        }
                        var showJson by remember { mutableStateOf(false) }
                        TextButton(onClick={showJson=!showJson}) { Text(if(showJson) "Hide SDK JSON" else "View SDK JSON") }
                        if(showJson) Text(result.toJson(),style=MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick={screen="select"},modifier=Modifier.fillMaxWidth()) { Text("New session") }
                }
                "history" -> {
                    val sessions=remember(historyRevision) { history.sessions() }
                    var confirmClear by remember { mutableStateOf(false) }
                    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("Clear workout history?")},
                        text={Text("This permanently removes all locally stored workout metrics from this device.")},
                        confirmButton={TextButton(onClick={history.clear(); historyRevision++; confirmClear=false}) { Text("Clear") }},
                        dismissButton={TextButton(onClick={confirmClear=false}) { Text("Cancel") }})
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Workout history",style=MaterialTheme.typography.headlineLarge)
                        if(sessions.isEmpty()) {
                            Text("Your completed workouts will appear here after you train.",color=Color.LightGray)
                        } else {
                            val overview=WorkoutHistory.overview(sessions)
                            MetricRow("Workouts",overview.totalWorkouts.toString(),"Completed reps",overview.totalCompletedReps.toString())
                            MetricRow("Avg completion",overview.averageCompletionPercentage.display("%"),"Latest change",overview.latestCompletionChange?.let { "${if(it>=0) "+" else ""}${it.display("%")}" } ?: "—")
                            Text("Recent workouts",style=MaterialTheme.typography.titleLarge)
                            sessions.forEach { session -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(session.exerciseName,fontWeight=FontWeight.Bold)
                                Text(session.completedAtEpochMs.dateTime(),style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
                                Text("${session.completeReps} complete • ${session.totalAttempts} attempts • ${session.completionPercentage.display("%")}")
                                session.mostCommonObservation?.let { Text(it.message,style=MaterialTheme.typography.bodySmall,color=Color.LightGray) }
                                TextButton(onClick={selectedHistorySession=session; screen="historyDetail"}) { Text("View details") }
                            } }
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={screen="select"},modifier=Modifier.weight(1f)) { Text("Back") }
                        if(sessions.isNotEmpty()) OutlinedButton(onClick={confirmClear=true},modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Clear history") }
                    }
                }
                "historyDetail" -> {
                    val session=selectedHistorySession
                    if(session==null) screen="history" else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Workout details",style=MaterialTheme.typography.headlineLarge)
                        Text("${session.exerciseName} • ${session.completedAtEpochMs.dateTime()}",color=Mint)
                        Text("${session.completeReps}",style=MaterialTheme.typography.displayLarge)
                        Text("complete repetitions",color=Color.LightGray)
                        MetricRow("Incomplete",session.incompleteReps.toString(),"Total attempts",session.totalAttempts.toString())
                        MetricRow("Completion",session.completionPercentage.display("%"),"Average ROM",session.averageRomPercentage.display("%"))
                        MetricRow("Confidence",session.averageConfidence?.times(100).display("%"),"Storage","Local only")
                        Text("Recorded form observations",style=MaterialTheme.typography.titleLarge)
                        if(session.observations.isEmpty()) Text("No configured form warnings were recorded.")
                        session.observations.forEach { observation -> Card(Modifier.fillMaxWidth()) { Text(observation.message,Modifier.padding(12.dp)) } }
                        Text("Historical metrics only — pose analysis cannot be replayed.",style=MaterialTheme.typography.bodySmall,color=Color.LightGray)
                    }
                    Button(onClick={screen="history"},modifier=Modifier.fillMaxWidth()) { Text("Back to history") }
                }
            }
        }
    }
}
@Composable private fun ErrorCard(e: FormError) {
    Card { Column(Modifier.padding(12.dp)) {
        Text(e.message,fontWeight=FontWeight.Bold)
        Text("${e.code} • measured ${e.measuredValue.display()}",style=MaterialTheme.typography.bodySmall)
        Text("${e.expected} • confidence ${(e.confidence*100).display("%")}",style=MaterialTheme.typography.bodySmall)
    } }
}
@Composable private fun MetricRow(a: String,av: String,b: String,bv: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("$a  $av",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text("$b  $bv",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun CameraView(modifier: Modifier,onPose: (PoseFrame,Boolean)->Unit,onError: (String)->Unit) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val latestPose by rememberUpdatedState(onPose); val latestError by rememberUpdatedState(onError)
    val preview=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FILL_CENTER; implementationMode=PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory={preview},modifier=modifier)
    DisposableEffect(preview,owner) {
        val pipeline=CameraPipeline(context,owner,preview,{f,m->latestPose(f,m)},{latestError(it)})
        val start=Runnable { pipeline.start() }
        preview.post(start)
        onDispose { preview.removeCallbacks(start); pipeline.close() }
    }
}
@Composable private fun PoseOverlay(frame: PoseFrame?,mirror: Boolean,guidePhase: SquatState?=null) {
    val edges=listOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,29 to 31,28 to 30,30 to 32)
    Canvas(Modifier.fillMaxSize()) {
        if(frame==null) return@Canvas
        fun point(id: Int): Offset? {
            val l=frame.landmarks[id] ?: return null
            if(l.visibility<0.65 || l.presence<0.65 || l.position.x !in 0.0..1.0 || l.position.y !in 0.0..1.0) return null
            return Offset((if(mirror) 1-l.position.x else l.position.x).toFloat()*size.width,l.position.y.toFloat()*size.height)
        }
        edges.forEach { (a,b) -> val p=point(a); val q=point(b); if(p!=null && q!=null) drawLine(Mint,p,q,3.dp.toPx()) }
        frame.landmarks.keys.forEach { point(it)?.let { p->drawCircle(Color.White,4.dp.toPx(),p) } }
        val guide=guidePhase?.let { SquatMirrorCoach.aligned(frame,it) } ?: return@Canvas
        fun guidePoint(id: Int): Offset? { val p=guide[id] ?: return null; return Offset((if(mirror) 1-p.x else p.x).toFloat()*size.width,p.y.toFloat()*size.height) }
        edges.forEach { (a,b) -> val p=guidePoint(a); val q=guidePoint(b); if(p!=null && q!=null) drawLine(Color(0xFF9AEACB).copy(alpha=.35f),p,q,2.dp.toPx()) }
        listOf(23,24,25,26).forEach { guidePoint(it)?.let { p -> drawCircle(Color(0xFF9AEACB).copy(alpha=.45f),5.dp.toPx(),p) } }
    }
}
