package ai.musaz.h1score

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener

internal class ChallengeVoice(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false

    fun start() {
        if (engine != null) return
        runCatching {
            val service = TextToSpeech(context, Mode.ON_DEVICE)
            engine = service
            service.initialize(object : OnInitializeListener {
                override fun onSuccess() { main.post { if (engine === service) ready = true } }
                override fun onFailure(errorType: ErrorType) {
                    android.util.Log.w("H1Voice", "Pleos TTS: ${errorType.name}")
                }
            })
        }.onFailure { android.util.Log.w("H1Voice", "Pleos TTS unavailable", it) }
    }

    fun say(message: String) {
        if (!ready) return
        runCatching { engine?.speak(message, isFinal = true) }
            .onFailure { android.util.Log.w("H1Voice", "Announcement unavailable", it) }
    }

    fun stop() {
        ready = false
        val service = engine
        engine = null
        runCatching { service?.release() }
    }
}
