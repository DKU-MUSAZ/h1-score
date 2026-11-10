package ai.musaz.h1score

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private var pendingResult=false
    private val model get() = (application as H1Application).model
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = buildList {
            add("android.car.permission.CAR_SPEED")
            add("android.car.permission.CAR_POWERTRAIN")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(),7)
        setContent { FirstRunHost(model, getSharedPreferences("h1-demo-v1",0), onExit=::finish, onComplete=::consumeResultRequest) }
        handleNotification(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotification(intent)
    }
    private fun handleNotification(intent: Intent?) {
        if (intent?.action == RecordingService.OPEN_RESULT) {
            pendingResult=true
            if(getSharedPreferences("h1-demo-v1",0).getBoolean("first-run-complete-v1",false)) consumeResultRequest()
        }
    }
    private fun consumeResultRequest() {
        if(pendingResult) {
            pendingResult=false
            model.openCompletedResult()
            RecordingService.clearResult(this)
        }
    }
    override fun onStart() { super.onStart(); model.enterForeground() }
    override fun onStop() { model.leaveForeground(); super.onStop() }
}
