package uz.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground("Jarvis ishga tushmoqda...")
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val loc = Locale.forLanguageTag(Prefs.get(this, Prefs.LANG, Prefs.DEFAULT_LANG))
                val r = tts?.setLanguage(loc)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { main.post { finishCycle() } }
                    override fun onDone(id: String?) { main.post { finishCycle() } }
                })
            }
        }
        startWakeWord()
    }

    private fun startAsForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateNotification(text: String) {
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, n)
    }

    private fun startWakeWord() {
        val key = Prefs.get(this, Prefs.PICO_KEY)
        if (key.isBlank()) {
            updateNotification("Picovoice AccessKey kiritilmagan")
            stopSelf()
            return
        }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext, PorcupineManagerCallback { onWakeWord() })
            porcupine?.start()
            updateNotification("\"Jarvis\" deb chaqiring")
        } catch (e: Exception) {
            updateNotification("Xato: " + (e.message ?: "Picovoice ishga tushmadi"))
            stopSelf()
        }
    }

    private fun onWakeWord() {
        main.post {
            if (busy) return@post
            busy = true
            try { porcupine?.stop() } catch (_: Exception) {}
            try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
            main.postDelayed({ listen() }, 350)
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Bu telefonda ovoz tanish xizmati yo'q.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) { updateNotification("Eshityapman...") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: android.os.Bundle?) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
                override fun onError(error: Int) { finishCycle() }
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) finishCycle() else handle(text)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.get(this, Prefs.LANG, Prefs.DEFAULT_LANG))
            sr.startListening(i)
        }
    }

    private fun handle(text: String) {
        updateNotification("O'ylayapman...")
        if (tryLocalCommand(text)) return
        val key = Prefs.get(this, Prefs.GROQ_KEY)
        val model = Prefs.get(this, Prefs.MODEL, Prefs.DEFAULT_MODEL).ifBlank { Prefs.DEFAULT_MODEL }
        if (key.isBlank()) { speak("Groq kaliti kiritilmagan."); return }
        thread {
            val reply = Groq.ask(key, model, text)
            main.post { speak(reply) }
        }
    }

    private fun tryLocalCommand(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        if (!t.contains("och")) return false
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, 0)
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            if (label.length >= 3 && t.contains(label.lowercase(Locale.ROOT))) {
                val i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName) ?: continue
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(i)
                    speak("$label ochilyapti.")
                } catch (e: Exception) {
                    speak("Ilovani ocha olmadim. Sozlamalarda boshqa ilovalar ustidan ko'rsatishga ruxsat bering.")
                }
                return true
            }
        }
        return false
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        } else {
            finishCycle()
        }
    }

    private fun finishCycle() {
        busy = false
        recognizer?.destroy()
        recognizer = null
        main.postDelayed({
            try { porcupine?.start() } catch (_: Exception) {}
            updateNotification("\"Jarvis\" deb chaqiring")
        }, 300)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
