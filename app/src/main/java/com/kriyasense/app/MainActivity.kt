package com.kriyasense.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import com.kriyasense.app.ui.theme.*
import com.kriyasense.app.auth.*
import com.kriyasense.app.ui.mirrorcoach.*
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import android.os.SystemClock
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KriyaTheme { KriyaSense() } }
    }
}
private fun Double?.display(unit: String="") = this?.let { "%.1f%s".format(it,unit) } ?: "—"
private fun Long.dateTime() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(this))
private data class ExerciseTutorial(val setup: String,val cues: List<String>,val checks: List<String>,val mediaFileName: String)
private fun tutorialFor(type: ExerciseType)=when(type) {
    ExerciseType.SQUAT -> ExerciseTutorial("Stand facing the camera with your whole body in view.",listOf("Keep feet planted","Lower with control","Return to standing"),listOf("Knee angle","Movement depth and ROM","Configured form rules"),"squat_demo")
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
    val auth=remember { LocalAuth(context) }
    var loggedIn by remember { mutableStateOf(auth.loggedIn) }
    var brandEntry by remember { mutableStateOf(!auth.loggedIn) }
    LaunchedEffect(Unit) { delay(600); brandEntry=false }
    if(brandEntry) { BrandEntry(); return }
    if(!loggedIn) { AuthEntry(auth) { loggedIn=true }; return }
    val owner=LocalLifecycleOwner.current
    val navigation = remember { ScreenBackStack("landing") }
    val screen = navigation.screen
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
    fun leaveCamera() {
        if (screen == "camera") {
            if (running) live=engine.pause()
            running=false
            voice.stop()
            pose=null
        }
    }
    fun navigateTo(destination: String) {
        if (destination == screen) return
        leaveCamera()
        navigation.navigateTo(destination)
    }
    fun goBack() {
        leaveCamera()
        navigation.goBack()
    }
    BackHandler(enabled=navigation.canGoBack) { goBack() }
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
    Surface(Modifier.fillMaxSize(),color=AppBackground) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("KriyaSense",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if(screen!="history" && screen!="historyDetail") TextButton(onClick={navigateTo("history")}) { Text("History") }
                }
            }
            when(screen) {
                "landing" -> {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                        Text(auth.name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }?.let { "Welcome back, $it" } ?: "Welcome back",color=SecondaryText,style=MaterialTheme.typography.titleMedium)
                        Card(colors=CardDefaults.cardColors(containerColor=PurpleSurface),modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                Spacer(Modifier.height(20.dp))
                                Text("MOVEMENT INTELLIGENCE",color=Lavender,style=MaterialTheme.typography.labelMedium)
                                Text("Move with\nintention.",style=MaterialTheme.typography.displaySmall)
                                Text("Understand your movement. Build better form, one session at a time.",color=PaleLavender)
                                Button(onClick={navigateTo("profile")},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Start Assessment") }
                                Text("Includes your optional profile setup",color=SecondaryText,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        val overview=WorkoutHistory.overview(history.sessions())
                        Text("Your movement so far",style=MaterialTheme.typography.titleLarge)
                        MetricRow("Workouts",overview.totalWorkouts.toString(),"Completed reps",overview.totalCompletedReps.toString())
                        if(overview.totalWorkouts>0) CompletionRing(overview.averageCompletionPercentage,"Average completion")
                        else Text("Your first assessment starts your story. Completed workouts will appear here.",color=SecondaryText)
                        OutlinedButton(onClick={navigateTo("history")},modifier=Modifier.fillMaxWidth()) { Text("Explore workout history") }
                        Text("ON-DEVICE ANALYSIS",color=Lavender,style=MaterialTheme.typography.labelMedium)
                        Text("Camera analysis happens on-device. Frames are not stored or uploaded.",style=MaterialTheme.typography.bodySmall,color=SecondaryText)
                    }
                }
                "profile" -> {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Make it yours",style=MaterialTheme.typography.headlineLarge)
                        Text("Optional details personalize your setup only. BMI is informational, not medical advice.",color=SecondaryText)
                        OutlinedTextField(heightInput,{heightInput=it},label={Text("Height (cm)")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(weightInput,{weightInput=it},label={Text("Weight (kg)")},modifier=Modifier.fillMaxWidth())
                        val draft=profile.copy(heightCm=heightInput.toDoubleOrNull(),weightKg=weightInput.toDoubleOrNull())
                        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=PurpleSurface)) {
                            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("BODY MASS INDEX",color=Lavender,style=MaterialTheme.typography.labelMedium)
                                Text(draft.bmi.display(),style=MaterialTheme.typography.displaySmall)
                                Text("Calculated from your height and weight",color=SecondaryText,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Experience",style=MaterialTheme.typography.titleMedium)
                        Column(Modifier.fillMaxWidth()) { ExperienceLevel.entries.forEach { level -> FilterChip(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),selected=profile.experience==level,onClick={profile=profile.copy(experience=level)},label={Text(level.name.lowercase().replaceFirstChar { it.uppercase() })}) } }
                        Text("Primary goal",style=MaterialTheme.typography.titleMedium)
                        Column { FitnessGoal.entries.forEach { goal -> FilterChip(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),selected=profile.goal==goal,onClick={profile=profile.copy(goal=goal)},label={Text(goal.name.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() })}) } }
                        draft.validationError()?.let { Text(it,color=Warning) }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick={ if(draft.validationError()==null) { profileStore.save(draft); profile=draft; navigateTo("select") } },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Continue") }
                        TextButton(onClick={navigateTo("select")},modifier=Modifier.fillMaxWidth()) { Text("Skip for now") }
                        HorizontalDivider(color=PurpleSurface)
                        Text("Account",style=MaterialTheme.typography.titleMedium)
                        Text(auth.email,color=SecondaryText,style=MaterialTheme.typography.bodyMedium)
                        var logoutError by remember { mutableStateOf(false) }
                        TextButton(onClick={ if(auth.logout()) loggedIn=false else logoutError=true }) { Text("Log out",color=SecondaryText) }
                        if(logoutError) Text("Couldn't save logout. Please try again.",color=Warning)

                    }
                }
                "select" -> {
                    LazyVerticalGrid(columns=GridCells.Adaptive(136.dp),modifier=Modifier.weight(1f).fillMaxWidth().testTag("exercise_grid"),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        item(span={ GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.padding(bottom=12.dp)) {
                    Text("Choose your exercise",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                    Text("On-device movement assessment. Select one to see its setup guidance.",color=SecondaryText,style=MaterialTheme.typography.bodySmall)
                            Text(selectedType.displayName,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("selected_exercise_name"))
                            Text(selectedType.id + if(selectedType.experimental) " • EXPERIMENTAL" else "",color=Lavender,style=MaterialTheme.typography.labelMedium,modifier=Modifier.testTag("selected_exercise_id"))
                            Text(if(selectedType==ExerciseType.PLANK) "Use a clear side view and keep your shoulders, hips and ankles visible." else "Keep the required joints clearly visible; movement feedback is assessed on-device.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("selected_exercise_instruction"))
                            }
                        }
                                items(ExerciseType.entries.toList(),key={it.id}) { type ->
                                    val selected=selectedType==type
                                    Button(onClick={selectedType=type; selectedVariant=ExerciseVariants.forExercise(type).first()},modifier=Modifier.fillMaxWidth().heightIn(min=116.dp).testTag("exercise_${type.name}"),shape=RoundedCornerShape(24.dp),contentPadding=PaddingValues(16.dp),colors=ButtonDefaults.buttonColors(containerColor=if(selected) DeepPurple else ElevatedBackground,contentColor=if(selected) PaleLavender else PrimaryText)) {
                                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Text(if(selected) "SELECTED" else "ASSESSMENT",color=if(selected) Lavender else MutedText,style=MaterialTheme.typography.labelSmall)
                                            Text(type.displayName,style=MaterialTheme.typography.titleMedium)
                                            if(type.experimental) Text("Experimental",color=Warning,style=MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                    }
                    Button(onClick={navigateTo(if(ExerciseVariants.forExercise(selectedType).size>1) "variants" else "tutorial")},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("start_camera")) { Text("View tutorial • ${selectedType.displayName}") }
                    Text("Private by design. Camera frames are processed in memory and never saved or uploaded.",style=MaterialTheme.typography.bodySmall,color=SecondaryText)
                }
                "variants" -> {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(selectedType.displayName,color=Lavender,style=MaterialTheme.typography.labelLarge)
                    Text("Choose your version",style=MaterialTheme.typography.headlineLarge)
                    Text("Choose the version that fits your current training setup.",color=SecondaryText)
                    ExerciseVariants.forExercise(selectedType).forEach { variant -> Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(selectedVariant.id==variant.id) PurpleSurface else CardSurface,contentColor=PrimaryText)) { Column(Modifier.padding(14.dp)) {
                        if(selectedVariant.id==variant.id) Text("CURRENT SELECTION",color=Lavender,style=MaterialTheme.typography.labelSmall)
                        Text(variant.displayName,style=MaterialTheme.typography.titleLarge); Text(variant.description,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={selectedVariant=variant; navigateTo("tutorial")},modifier=Modifier.fillMaxWidth()) { Text("Select") }
                    } } }
                    }
                    OutlinedButton(onClick={goBack()},modifier=Modifier.fillMaxWidth()) { Text("Back") }
                }
                "tutorial" -> {
                    val tutorial=tutorialFor(selectedType)
                    val challenge=ChallengeDefinitions.forExercise(selectedType)?.takeIf { selectedVariant.id==ExerciseVariants.standardId(selectedType) }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("PREPARE TO MOVE",color=Lavender,style=MaterialTheme.typography.labelMedium)
                    Text(selectedVariant.displayName,style=MaterialTheme.typography.headlineLarge)
                    Text("Setup",color=Lavender,style=MaterialTheme.typography.labelLarge)
                    Text(tutorial.setup)
                    Card(Modifier.fillMaxWidth().height(120.dp),colors=CardDefaults.cardColors(containerColor=PurpleSurface)) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("Exercise demo coming soon",color=SecondaryText) } }
                    Text("Technique cues",style=MaterialTheme.typography.titleLarge)
                    tutorial.cues.forEach { Text("• $it") }
                    Text("What KriyaSense checks",style=MaterialTheme.typography.titleLarge)
                    tutorial.checks.forEach { Text("• $it") }
                    if(selectedType.experimental) Text("Experimental tracking: heel landmarks can vary by view and lighting.",color=Warning)
                    }
                    Button(onClick={ activeChallenge=null; engine=ExerciseEngines.create(selectedType,selectedVariant.id); live=engine.current(); pose=null; started=false; running=false; cameraError=null; activeSessionId=UUID.randomUUID().toString(); navigateTo("camera"); if(!permission) request.launch(Manifest.permission.CAMERA) },modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Start Assessment") }
                    challenge?.let { c -> OutlinedButton(onClick={ activeChallenge=c; engine=ExerciseEngines.create(selectedType,selectedVariant.id); live=engine.current(); pose=null; started=false; running=false; cameraError=null; activeSessionId=UUID.randomUUID().toString(); navigateTo("camera"); if(!permission) request.launch(Manifest.permission.CAMERA) },modifier=Modifier.fillMaxWidth()) { Text("Try Challenge") } }
                    OutlinedButton(onClick={goBack()},modifier=Modifier.fillMaxWidth()) { Text("Back") }
                }
                "camera" -> {
                    Text("${selectedType.displayName} • ${selectedVariant.displayName}",style=MaterialTheme.typography.titleMedium)
                    if(!permission) {
                        Text("Camera access is needed to measure your movement. Allow access, or enable Camera in app settings if previously denied.")
                        Button(onClick={ request.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                        TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
                        TextButton(onClick={ goBack() }) { Text("Back") }
                    } else {
                        activeChallenge?.let { challenge ->
                            val progress=FormChallenges.evaluate(challenge,engine.finish())
                            Text("${challenge.title.uppercase()}  ${progress.progress} / ${challenge.targetValue}",color=Lavender,style=MaterialTheme.typography.labelLarge)
                        }
                        val mirrorProfile=remember(selectedType) { MirrorCoachProfiles.forExercise(selectedType) }
                        val ghostController=remember(activeSessionId,mirrorProfile.exerciseId) { GhostPoseController(mirrorProfile) }
                        Box(Modifier.fillMaxWidth().weight(1.4f).background(AppBackground,RoundedCornerShape(24.dp))) {
                            CameraView(Modifier.fillMaxSize(),onPose={ frame,front ->
                                lastPoseTime=SystemClock.uptimeMillis(); mirror=front
                                pose=if(running) frame else null
                                if(running) {
                                    live=engine.process(frame)
                                    voice.say(live.coaching)
                                }
                            },onError={ cameraError=it; if(running) { running=false; live=engine.pause(); voice.stop() } })
                            val coachActive=mirrorCoachEnabled && selectedVariant.id==ExerciseVariants.standardId(selectedType)
                            if(coachActive) GhostPoseOverlay(pose,live,running,mirror,activeSessionId,mirrorProfile,ghostController)
                            PoseOverlay(pose,mirror)
                            Text(if(running) live.result.status.name.replace('_',' ') else if(started) "PAUSED" else "READY",
                                Modifier.align(Alignment.TopStart).padding(12.dp).background(AppBackground.copy(alpha=0.85f),RoundedCornerShape(16.dp)).padding(10.dp),color=if(running && live.result.visibility!=Visibility.SUFFICIENT) TrackingError else if(running && live.result.status==Status.VALID) Success else Lavender)
                        }
                        cameraError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(if(selectedType==ExerciseType.PLANK) live.result.holdDurationSeconds.display(" s hold") else "${live.result.completeReps} complete reps",style=MaterialTheme.typography.headlineLarge,color=PrimaryText)
                        if(selectedVariant.id==ExerciseVariants.standardId(selectedType)) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text("Mirror Coach",fontWeight=FontWeight.Bold); Text(mirrorProfile.waitingText,style=MaterialTheme.typography.bodySmall,color=SecondaryText) }
                            Switch(checked=mirrorCoachEnabled,onCheckedChange={mirrorCoachEnabled=it})
                        }
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("COACHING", color=Lavender, style=MaterialTheme.typography.labelSmall)
                            Text(if(started) live.coaching.message else "Stand upright, then start your session.",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=if(!started) PrimaryText else when(live.coaching.priority) { CoachingPriority.VISIBILITY -> TrackingError; CoachingPriority.FORM, CoachingPriority.RANGE_OF_MOTION, CoachingPriority.DRIFT -> Warning; CoachingPriority.SUCCESS -> Success; else -> PrimaryText })
                        } }
                        if(live.result.driftDetected) Text("MOVEMENT TREND  Drifting ↓",color=Warning,style=MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            MetricRow(live.primaryMetricLabel ?: "Primary metric",live.primaryMetricValue.display(live.primaryMetricUnit),"Current ROM",live.currentRomPercentage.display("%"))
                            MetricRow("Complete",live.result.completeReps.toString(),"Incomplete",live.result.incompleteReps.toString())
                            MetricRow("Movement state",live.movementState,"Pose",live.result.visibility.name)
                            MetricRow("Avg rep",live.result.averageRepDurationSeconds.display(" s"),"Tension",live.result.timeUnderTensionSeconds.display(" s"))
                            MetricRow("Form factor",live.result.formFactor.display(),"Confidence",(live.result.confidence*100).display("%"))
                        }
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
                                result=completed; running=false; voice.stop(); pose=null; navigateTo("results")
                            },modifier=Modifier.weight(1f)) { Text("Finish") }
                        }
                    }
                }
                "results" -> {
                    val summary=remember(result) { SessionSummaries.from(result) }
                    val signature=remember(result,historyRevision) { MovementSignatures.build(history.sessions(),result.exerciseId) }
                    val comparison=remember(result,historyRevision) { currentStoredSession?.let { MovementSignatures.compare(it,history.sessions().filter { session -> session.id!=it.id }) } }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Your performance",style=MaterialTheme.typography.headlineLarge)
                        activeChallenge?.let { challenge ->
                            val challengeResult=FormChallenges.evaluate(challenge,result)
                            Text(if(challengeResult.status==ChallengeStatus.COMPLETED) "CHALLENGE COMPLETE" else "CHALLENGE NOT COMPLETED",color=if(challengeResult.status==ChallengeStatus.COMPLETED) Success else Warning,style=MaterialTheme.typography.titleLarge)
                            Text(challenge.title,fontWeight=FontWeight.Bold)
                            Text(challengeResult.message)
                            challengeResult.qualityValue?.let { Text("Consistency  ${it.display("%")}") }
                        }
                        Text("${result.exerciseName} • ${result.status.name.replace('_',' ')}",color=Lavender)
                        PerformanceHero(result.completeReps,summary.completionPercentage)
                        result.holdDurationSeconds?.let { Text("${it.display(" s")} hold",style=MaterialTheme.typography.headlineLarge,color=Lavender) }
                        MetricRow("Incomplete",result.incompleteReps.toString(),"Total attempts",summary.totalAttempts.toString())
                        MetricRow("Completion",summary.completionPercentage.display("%"),"Average rep",result.averageRepDurationSeconds.display(" s"))
                        MetricRow("Average ROM",result.romPercentage.display("%"),"Tension",result.timeUnderTensionSeconds.display(" s"))
                        MetricRow("Form factor",result.formFactor.display(),"Confidence",(result.confidence*100).display("%"))
                        if(result.driftDetected) Text("Session trend: movement quality decreased during later reps.",color=SecondaryText)
                        Text("Your movement signature",style=MaterialTheme.typography.titleLarge)
                        if(signature==null || signature.sessionsUsed<2) Text("Your personal baseline is being built. Complete more sessions to see your movement trends.",color=SecondaryText)
                        else {
                            signature.averageRomPercentage?.let { MetricRow("ROM",it.display("%"),"Tempo",signature.averageRepDurationSeconds.display(" s")) }
                            MetricRow("ROM consistency",signature.romConsistency.display("%"),"Tempo consistency",signature.tempoConsistency.display("%"))
                            signature.averageHoldDurationSeconds?.let { Text("Average hold  ${it.display(" s")}") }
                            comparison?.let { c -> Text("Compared with your prior baseline: ROM ${c.romChange?.let { "${if(it>=0) "+" else ""}${it.display("%")}" } ?: "—"}; Tempo ${c.tempoChangeSeconds.display(" s")}",style=MaterialTheme.typography.bodySmall,color=SecondaryText) }
                        }
                        summary.mostCommonObservation?.let { Text("Most common observation: ${it.message}",color=SecondaryText) }
                        Text("Form observations",style=MaterialTheme.typography.titleLarge)
                        if(result.formErrors.isEmpty()) Text(if(result.timeline.isEmpty()) "No assessed repetitions." else "No configured form warnings detected.")
                        result.formErrors.forEach { e -> ErrorCard(e) }
                        Text("Rep timeline",style=MaterialTheme.typography.titleLarge)
                        result.timeline.forEach { rep ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Rep ${rep.index} • ${if(rep.complete) "Complete" else "Incomplete"}",fontWeight=FontWeight.Bold,color=if(rep.complete) Success else Warning)
                                Text("${rep.startSeconds.display()}–${rep.endSeconds.display()} s • ${rep.durationSeconds.display(" s")} • ROM ${rep.romPercentage.display("%")}")
                                rep.formErrors.forEach { Text(it.message,style=MaterialTheme.typography.bodySmall) }
                            } }
                        }
                        var showJson by remember { mutableStateOf(false) }
                        TextButton(onClick={showJson=!showJson}) { Text(if(showJson) "Hide SDK JSON" else "View SDK JSON") }
                        if(showJson) Text(result.toJson(),style=MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick={goBack()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("New session") }
                }
                "history" -> {
                    val sessions=remember(historyRevision) { history.sessions() }
                    var confirmClear by remember { mutableStateOf(false) }
                    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("Clear workout history?")},
                        text={Text("This permanently removes all locally stored workout metrics from this device.")},
                        confirmButton={TextButton(onClick={history.clear(); historyRevision++; confirmClear=false}) { Text("Clear") }},
                        dismissButton={TextButton(onClick={confirmClear=false}) { Text("Cancel") }})
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Your progress",style=MaterialTheme.typography.headlineLarge)
                        if(sessions.isEmpty()) {
                            Text("Your completed workouts will appear here after you train.",color=SecondaryText)
                        } else {
                            val overview=WorkoutHistory.overview(sessions)
                            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=PurpleSurface)) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            MetricRow("Workouts",overview.totalWorkouts.toString(),"Completed reps",overview.totalCompletedReps.toString())
                            CompletionRing(overview.averageCompletionPercentage,"Average completion")
                            } }
                            MetricRow("Avg completion",overview.averageCompletionPercentage.display("%"),"Latest change",overview.latestCompletionChange?.let { "${if(it>=0) "+" else ""}${it.display("%")}" } ?: "—")
                            Text("Recent workouts",style=MaterialTheme.typography.titleLarge)
                            sessions.forEach { session -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(session.exerciseName,style=MaterialTheme.typography.titleLarge)
                                Text(session.completedAtEpochMs.dateTime(),style=MaterialTheme.typography.bodySmall,color=SecondaryText)
                                Text("${session.completeReps} complete • ${session.totalAttempts} attempts • ${session.completionPercentage.display("%")}")
                                session.mostCommonObservation?.let { Text(it.message,style=MaterialTheme.typography.bodySmall,color=SecondaryText) }
                                TextButton(onClick={selectedHistorySession=session; navigateTo("historyDetail")}) { Text("View details") }
                            } }
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={goBack()},modifier=Modifier.weight(1f)) { Text("Back") }
                        if(sessions.isNotEmpty()) OutlinedButton(onClick={confirmClear=true},modifier=Modifier.weight(1f),colors=ButtonDefaults.outlinedButtonColors(contentColor=SecondaryText)) { Text("Clear history") }
                    }
                }
                "historyDetail" -> {
                    val session=selectedHistorySession
                    if(session==null) LaunchedEffect(Unit) { goBack() } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Workout details",style=MaterialTheme.typography.headlineLarge)
                        Text("${session.exerciseName} • ${session.completedAtEpochMs.dateTime()}",color=Lavender)
                        PerformanceHero(session.completeReps,session.completionPercentage)
                        MetricRow("Average rep",session.averageRepDurationSeconds.display(" s"),"Hold",session.holdDurationSeconds.display(" s"))
                        MetricRow("Incomplete",session.incompleteReps.toString(),"Total attempts",session.totalAttempts.toString())
                        MetricRow("Completion",session.completionPercentage.display("%"),"Average ROM",session.averageRomPercentage.display("%"))
                        MetricRow("Confidence",session.averageConfidence?.times(100).display("%"),"Storage","Local only")
                        Text("Recorded form observations",style=MaterialTheme.typography.titleLarge)
                        if(session.observations.isEmpty()) Text("No configured form warnings were recorded.")
                        session.observations.forEach { observation -> Card(Modifier.fillMaxWidth()) { Text(observation.message,Modifier.padding(12.dp)) } }
                        Text("Historical metrics only — pose analysis cannot be replayed.",style=MaterialTheme.typography.bodySmall,color=SecondaryText)
                    }
                    Button(onClick={goBack()},modifier=Modifier.fillMaxWidth()) { Text("Back to history") }
                }
            }
        }
    }
}
@Composable private fun ErrorCard(e: FormError) {
    Card { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(e.message,fontWeight=FontWeight.Bold,color=Warning)
        Text("${e.code} • measured ${e.measuredValue.display()}",style=MaterialTheme.typography.bodySmall)
        Text("${e.expected} • confidence ${(e.confidence*100).display("%")}",style=MaterialTheme.typography.bodySmall)
    } }
}
@Composable private fun MetricRow(a: String,av: String,b: String,bv: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        MetricValue(a,av,Modifier.weight(1f))
        MetricValue(b,bv,Modifier.weight(1f))
    }
}
@Composable private fun MetricValue(label: String,value: String,modifier: Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,color=SecondaryText,style=MaterialTheme.typography.bodySmall)
        Text(value,style=MaterialTheme.typography.titleLarge,color=if(label=="Incomplete") Warning else PaleLavender)
    }
}
@Composable private fun PerformanceHero(reps: Int,completion: Double?) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=PurpleSurface)) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("$reps",style=MaterialTheme.typography.displayLarge)
            Text("Completed repetitions",color=PaleLavender)
            CompletionRing(completion,"Completion")
        }
    }
}
@Composable private fun CompletionRing(percentage: Double?,label: String) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
        Canvas(Modifier.size(80.dp).semantics { contentDescription="$label: ${percentage.display("%")}" }) {
            val stroke=Stroke(width=8.dp.toPx(),cap=StrokeCap.Round)
            val inset=8.dp.toPx()
            val diameter=size.width-inset*2
            drawArc(DeepPurple,-90f,360f,false,Offset(inset,inset),androidx.compose.ui.geometry.Size(diameter,diameter),style=stroke)
            percentage?.takeIf { it.isFinite() }?.let {
                drawArc(VibrantPurple,-90f,(it.coerceIn(0.0,100.0)*3.6).toFloat(),false,Offset(inset,inset),androidx.compose.ui.geometry.Size(diameter,diameter),style=stroke)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(percentage.display("%"),style=MaterialTheme.typography.headlineMedium,color=PaleLavender)
            Text(label,color=SecondaryText,style=MaterialTheme.typography.bodyMedium)
        }
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
@Composable private fun PoseOverlay(frame: PoseFrame?,mirror: Boolean) {
    val edges=listOf(11 to 12,11 to 13,13 to 15,12 to 14,14 to 16,11 to 23,12 to 24,23 to 24,23 to 25,25 to 27,24 to 26,26 to 28,27 to 29,29 to 31,28 to 30,30 to 32)
    Canvas(Modifier.fillMaxSize()) {
        if(frame==null) return@Canvas
        fun point(id: Int): Offset? {
            val l=frame.landmarks[id] ?: return null
            if(l.visibility<0.65 || l.presence<0.65 || l.position.x !in 0.0..1.0 || l.position.y !in 0.0..1.0) return null
            return Offset((if(mirror) 1-l.position.x else l.position.x).toFloat()*size.width,l.position.y.toFloat()*size.height)
        }
        edges.forEach { (a,b) -> val p=point(a); val q=point(b); if(p!=null && q!=null) drawLine(Lavender,p,q,3.dp.toPx()) }
        frame.landmarks.keys.forEach { point(it)?.let { p->drawCircle(PrimaryText,4.dp.toPx(),p) } }
    }
}
