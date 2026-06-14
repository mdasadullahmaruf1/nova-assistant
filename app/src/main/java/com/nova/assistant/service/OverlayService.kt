package com.nova.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nova.assistant.ai.GeminiClient
import com.nova.assistant.ai.NovaResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object { const val CHANNEL_ID = "nova_overlay" }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
        setupSpeech()
    }

    private fun setupOverlay() {
        overlayView = TextView(this).apply {
            text = "✦"; textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            background = resources.getDrawable(
                resources.getIdentifier("overlay_btn", "drawable", packageName), null)
            setPadding(20, 20, 20, 20)
        }

        val params = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 24; y = 180 }

        var startX = 0f; var startY = 0f; var initX = 0; var initY = 0; var moved = false
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; initX = params.x; initY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX; val dy = event.rawY - startY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true; params.x = (initX - dx).toInt(); params.y = (initY + dy).toInt()
                        wm.updateViewLayout(overlayView, params)
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleListening(); true }
                else -> false
            }
        }
        wm.addView(overlayView, params)
    }

    private fun setupSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) = setOrbState("listening")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() = setOrbState("processing")
            override fun onError(err: Int) {
                isListening = false; setOrbState("idle")
                val msg = when (err) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error — try again"
                    else -> "Voice error $err"
                }
                NovaAccessibilityService.handler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                setOrbState("processing")
                processCommand(text)
            }
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(t: Int, p: android.os.Bundle?) {}
        })
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening(); isListening = false; setOrbState("idle")
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer.startListening(intent); isListening = true; setOrbState("listening")
        }
    }

    private fun processCommand(text: String) {
        scope.launch {
            try {
                val response: NovaResponse = GeminiClient.sendMessage(text)
                Toast.makeText(applicationContext, response.text.take(100), Toast.LENGTH_LONG).show()
                val svc = NovaAccessibilityService.instance
                if (svc != null && response.action != null) {
                    svc.executeAction(response.action) { setOrbState("idle") }
                } else setOrbState("idle")
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                setOrbState("idle")
            }
        }
    }

    private fun setOrbState(state: String) {
        NovaAccessibilityService.handler.post {
            when (state) {
                "listening"  -> { overlayView.text = "◉"; overlayView.setTextColor(0xFF34D399.toInt()) }
                "processing" -> { overlayView.text = "◌"; overlayView.setTextColor(0xFFA78BFA.toInt()) }
                else         -> { overlayView.text = "✦"; overlayView.setTextColor(0xFFFFFFFF.toInt()) }
            }
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "NOVA", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NOVA is active")
            .setContentText("Tap ✦ to speak")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) wm.removeView(overlayView)
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
    }
}
