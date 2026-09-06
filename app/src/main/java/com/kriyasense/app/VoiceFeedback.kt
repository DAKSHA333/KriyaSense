package com.kriyasense.app

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import com.kriyasense.assessment.CoachingFeedback
import com.kriyasense.assessment.SpeechThrottle
import java.util.Locale

class VoiceFeedback(context: Context) : AutoCloseable {
    private var ready=false
    private val throttle=SpeechThrottle()
    private var tts: TextToSpeech?=null
    init {
        tts=TextToSpeech(context) { status ->
            if(status==TextToSpeech.SUCCESS) {
                // Select only an installed offline voice; never request network synthesis.
                val voice=tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language==Locale.ENGLISH.language }
                if(voice!=null) { tts?.voice=voice; ready=true }
            }
        }
    }
    fun say(feedback: CoachingFeedback) {
        val now=SystemClock.uptimeMillis()
        if(!ready || !feedback.speakable || !throttle.shouldSpeak(feedback,now)) return
        tts?.speak(feedback.message,TextToSpeech.QUEUE_FLUSH,null,feedback.code)
    }
    fun stop() { tts?.stop() }
    override fun close() { ready=false; tts?.stop(); tts?.shutdown(); tts=null }
}
