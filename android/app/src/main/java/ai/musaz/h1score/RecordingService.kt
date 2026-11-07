package ai.musaz.h1score

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
    companion object {
        private const val resultId = 11

        fun notifyResult(context: Context, confirmedParked: Boolean) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = if (confirmedParked) {
                NotificationChannel("parked_results", "주차 후 주행 결과", NotificationManager.IMPORTANCE_HIGH)
            } else {
                NotificationChannel("results", "주행 결과", NotificationManager.IMPORTANCE_DEFAULT)
            }
            manager.createNotificationChannel(channel)
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(context, resultId, intent, PendingIntent.FLAG_IMMUTABLE)
            manager.notify(resultId, Notification.Builder(context, channel.id)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("H1-score 주행 결과가 준비됐어요")
                .setContentText("결과 보기를 눌러 이번 주행을 확인하세요.")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "결과 보기", open).build())
                .setAutoCancel(true)
                .build())
        }

        fun clearResult(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(resultId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "주행 기록", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(10, Notification.Builder(this,"recording")
            .setSmallIcon(R.drawable.app_icon).setContentTitle("H1-score 주행 기록")
            .setContentText("내비게이션을 사용해도 기록은 계속됩니다. 주차 후 종료할 수 있어요.")
            .setContentIntent(open).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { (application as H1Application).model.serviceEnded(); super.onDestroy() }
}
