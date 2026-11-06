package ai.musaz.h1score

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model get() = (application as H1Application).model
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = listOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if(permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(),7)
        setContent { H1App(model) }
    }
    override fun onStart() { super.onStart(); model.enterForeground() }
    override fun onStop() { model.leaveForeground(); super.onStop() }
}
