package ai.musaz.h1score

import android.app.*
import android.content.Intent
import android.os.IBinder

class H1Application : Application() {
    var kakaoMapEnabled = false
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            kakaoMapEnabled = runCatching {
                com.kakao.vectormap.KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
            }.isSuccess
        }
    }

    val model by lazy { ChallengeViewModel(this) }
}

/** Keeps the user-started session alive while navigation occupies the foreground. */
class RecordingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"recording")
            .setSmallIcon(R.drawable.app_icon).setContentTitle("H1-score 주행 기록")
            .setContentText("내비게이션을 사용해도 기록은 계속됩니다. 주차 후 종료할 수 있어요.")
            .setContentIntent(open).setOngoing(true).build()
        val locationGranted=checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(android.os.Build.VERSION.SDK_INT>=34) startForeground(10,notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if(locationGranted) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0))
        else if(android.os.Build.VERSION.SDK_INT>=29 && locationGranted) startForeground(10,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(10,notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { (application as H1Application).model.serviceEnded(); super.onDestroy() }
}
