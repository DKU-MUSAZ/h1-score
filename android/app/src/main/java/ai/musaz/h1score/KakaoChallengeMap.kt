package ai.musaz.h1score

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import kotlinx.coroutines.delay

private val challengeLocation = LatLng.from(hangangVenue.latitude, hangangVenue.longitude)

@Composable
internal fun KakaoChallengeMap(
    spots: List<Destination>,
    selectedSpot: Destination?,
    deadlineLabel: String,
    onReady: (KakaoMap) -> Unit,
    onFailure: () -> Unit,
    onMarkerClick: (Destination) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val click by rememberUpdatedState(onMarkerClick)
    val ready by rememberUpdatedState(onReady)
    val fail by rememberUpdatedState(onFailure)
    val handler = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    val labels = remember { mutableMapOf<Label, Destination>() }

    DisposableEffect(view, lifecycle) {
        val mapView = view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.resume()
                Lifecycle.Event.ON_PAUSE -> mapView?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { mapView ->
                view = mapView
                mapView.start(object : MapLifeCycleCallback() {
                    override fun onMapDestroy() = Unit
                    override fun onMapError(error: Exception) {
                        android.util.Log.w("H1Map", "Kakao map unavailable", error)
                        handler.post { if (view === mapView) fail() }
                    }
                }, object : KakaoMapReadyCallback() {
                    override fun getPosition() = challengeLocation
                    override fun getZoomLevel() = 10
                    override fun onMapReady(kakaoMap: KakaoMap) {
                        handler.post {
                            if (view === mapView) {
                                map = kakaoMap
                                ready(kakaoMap)
                            }
                        }
                    }
                })
            }
        },
        onRelease = { mapView ->
            view = null
            mapView.finish()
        },
    )
    LaunchedEffect(map, selectedSpot) {
        val readyMap = map ?: return@LaunchedEffect
        val point = selectedSpot?.point ?: return@LaunchedEffect
        delay(300)
        readyMap.moveCamera(CameraUpdateFactory.newCenterPosition(
            LatLng.from(point.latitude, point.longitude), 15), CameraAnimation.from(450))
    }
    LaunchedEffect(map, spots, deadlineLabel) {
        labels.keys.toList().forEach { it.remove() }
        labels.clear()
        val readyMap = map ?: return@LaunchedEffect
        spots.forEach { spot ->
            val point = spot.point ?: return@forEach
            val marker = readyMap.labelManager?.layer?.addLabel(
                LabelOptions.from(LatLng.from(point.latitude, point.longitude))
                    .setStyles(LabelStyle.from(challengeMarker(spot.name, spot.demoPeers, deadlineLabel)).setAnchorPoint(.5f, .96f))
                    .setClickable(true))
            if (marker != null) labels[marker] = spot
        }
        readyMap.setOnLabelClickListener { _, _, tapped ->
            val spot = labels[tapped]
            if (spot != null) { handler.post { click(spot) }; true } else false
        }
    }
}

private fun challengeMarker(name: String, participantCount: Int, deadlineLabel: String): Bitmap {
    val bitmap = Bitmap.createBitmap(450, 294, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = bitmap.width / 2f
    val cardInset = 12f
    val metricCenterOffset = (center - cardInset) / 2f
    val participantCenter = center - metricCenterOffset
    val deadlineCenter = center + metricCenterOffset
    val ink = H1Style.Ink.toArgb()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    val tip = Path().apply {
        moveTo(center - 25f, 249f)
        lineTo(center, 282f)
        lineTo(center + 25f, 249f)
        close()
    }

    paint.color = Color.argb(40, 25, 44, 78)
    canvas.drawRoundRect(12f, 14f, 438f, 269f, 44f, 44f, paint)
    paint.color = ink
    canvas.drawPath(tip, paint)
    canvas.drawRoundRect(cardInset, 8f, bitmap.width - cardInset, 259f, 42f, 42f, paint)

    paint.color = Color.WHITE
    canvas.drawRoundRect(19f, 15f, 431f, 99f, 35f, 35f, paint)
    paint.color = ink
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = 41f
    val nameWidth = paint.measureText(name)
    if (nameWidth > 364f) paint.textSize *= 364f / nameWidth
    canvas.drawText(name, center, 57f - (paint.ascent() + paint.descent()) / 2f, paint)

    paint.color = H1Style.Mint.copy(alpha = .35f).toArgb()
    paint.strokeWidth = 2f
    canvas.drawLine(center, 123f, center, 228f, paint)

    paint.color = H1Style.Mint.toArgb()
    paint.textSize = 28f
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    canvas.drawText("참여 인원", participantCenter, 150f, paint)
    canvas.drawText("마감 시간", deadlineCenter, 150f, paint)

    paint.color = Color.WHITE
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = 60f
    canvas.drawText("${participantCount}명", participantCenter, 222f, paint)
    canvas.drawText(deadlineLabel.removeSuffix(" 마감"), deadlineCenter, 222f, paint)
    return bitmap
}
