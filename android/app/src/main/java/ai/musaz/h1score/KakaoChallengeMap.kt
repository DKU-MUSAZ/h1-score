package ai.musaz.h1score

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                    .setStyles(LabelStyle.from(challengeMarker(spot.name, spot.demoPeers, deadlineLabel)).setAnchorPoint(.5f, .5f))
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
    val bitmap = Bitmap.createBitmap(344, 344, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = 172f
    val ink = Color.rgb(21, 45, 76)
    val mint = Color.rgb(137, 238, 194)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    paint.color = Color.argb(46, 21, 45, 76)
    canvas.drawCircle(center, center + 4f, 169f, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(center, center, 162f, paint)
    paint.color = mint
    canvas.drawCircle(center, center, 153f, paint)
    paint.color = ink
    canvas.drawCircle(center, center, 145f, paint)

    paint.color = Color.WHITE
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = if (name.length > 12) 22f else 28f
    canvas.drawText(name, center, 106f, paint)
    paint.textSize = 88f
    canvas.drawText("${participantCount}명", center, 191f, paint)
    paint.textSize = 29f
    canvas.drawText("참여 중 · 시연", center, 229f, paint)

    paint.color = mint
    canvas.drawRoundRect(96f, 245f, 248f, 292f, 24f, 24f, paint)
    paint.color = ink
    paint.textSize = 31f
    canvas.drawText(deadlineLabel, center, 279f, paint)
    return bitmap
}
