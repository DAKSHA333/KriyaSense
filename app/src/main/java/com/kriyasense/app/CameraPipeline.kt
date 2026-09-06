package com.kriyasense.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.kriyasense.assessment.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraPipeline(private val context: Context, private val owner: LifecycleOwner,
    private val previewView: PreviewView, private val onPose: (PoseFrame, Boolean)->Unit,
    private val onError: (String)->Unit) : AutoCloseable {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=ContextCompat.getMainExecutor(context)
    private val closed=AtomicBoolean(false)
    private val busy=AtomicBoolean(false)
    private var task: PoseLandmarker?=null
    private var provider: ProcessCameraProvider?=null
    private var preview: Preview?=null
    private var analysis: ImageAnalysis?=null
    private var inFlight: MPImage?=null
    private var front=true
    fun start() {
        worker.execute {
            try {
                task=PoseLandmarker.createFromOptions(context,PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setRunningMode(RunningMode.LIVE_STREAM).setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.6f).setMinPosePresenceConfidence(0.6f).setMinTrackingConfidence(0.6f)
                    .setResultListener { result,input ->
                        val landmarks=result.landmarks().firstOrNull()?.mapIndexed { i,p ->
                            i to Landmark(Point(p.x().toDouble(),p.y().toDouble(),p.z().toDouble()),
                                p.visibility().orElse(0f).toDouble(),p.presence().orElse(0f).toDouble())
                        }?.toMap() ?: emptyMap()
                        val frame=PoseFrame(result.timestampMs(),landmarks,input.width,input.height)
                        main.execute { if(!closed.get()) onPose(frame,front) }
                        inFlight?.close(); inFlight=null; busy.set(false)
                    }.setErrorListener { error -> fail("Pose analysis failed: ${error.message}") }.build())
                main.execute { if(!closed.get()) bind() }
            } catch(e: Exception) { fail("Cannot load pose model: ${e.message}") }
        }
    }
    private fun bind() {
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(closed.get()) return@addListener
            try {
                val cameraProvider=future.get(); provider=cameraProvider
                front=cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector=if(front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                preview=Preview.Builder().build().also { it.surfaceProvider=previewView.surfaceProvider }
                analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build().also { useCase ->
                        useCase.setAnalyzer(worker) { proxy -> analyze(proxy) }
                    }
                // Shared viewport gives preview and analysis the same sensor crop. Crop before rotation.
                val viewport=previewView.viewPort ?: error("Camera view is not laid out")
                cameraProvider.bindToLifecycle(owner,selector,UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(preview!!).addUseCase(analysis!!).build())
            } catch(e: Exception) { fail("Cannot start camera: ${e.message}") }
        },main)
    }
    private fun analyze(proxy: ImageProxy) {
        if(closed.get() || !busy.compareAndSet(false,true)) { proxy.close(); return }
        try {
            val bitmap=proxy.toBitmap()
            val crop=proxy.cropRect
            val matrix=Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val upright=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width(),crop.height(),matrix,true)
            val input=BitmapImageBuilder(upright).build(); inFlight=input
            task!!.detectAsync(input,SystemClock.uptimeMillis())
        } catch(e: Exception) { inFlight?.close(); inFlight=null; busy.set(false); fail("Camera analysis failed: ${e.message}") }
        finally { proxy.close() }
    }
    private fun fail(message: String) { main.execute { if(!closed.get()) onError(message) } }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        analysis?.clearAnalyzer()
        listOfNotNull(preview,analysis).takeIf { it.isNotEmpty() }?.let { provider?.unbind(*it.toTypedArray()) }
        worker.execute { task?.close(); task=null; inFlight?.close(); inFlight=null }
        worker.shutdown()
    }
}
