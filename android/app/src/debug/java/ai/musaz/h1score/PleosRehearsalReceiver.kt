package ai.musaz.h1score

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PleosRehearsalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "ai.musaz.h1score.REHEARSE_NAVIGATION") return
        resultData = if (Build.HARDWARE == "ranchu" && Build.DEVICE.startsWith("crp_avd")) {
            val app = context.applicationContext as H1Application
            app.model.rehearseNavigation(intent.getStringExtra("event").orEmpty())
        } else {
            "Pleos emulator required"
        }
    }
}
