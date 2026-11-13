package ai.musaz.h1score

import ai.musaz.h1score.core.GeoPoint
import ai.musaz.h1score.core.SavedPlace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class PlaceSearchResult(val place: SavedPlace, val address: String)

@Composable
internal fun DestinationSearchDialog(vm: ChallengeViewModel, keyword: String, onDismiss: () -> Unit) {
    var results by remember(keyword) { mutableStateOf<List<PlaceSearchResult>?>(null) }
    var selected by remember(keyword) { mutableStateOf<SavedPlace?>(null) }
    LaunchedEffect(keyword) {
        val found = findPlaces(keyword)
        selected = found.singleOrNull()?.place
        results = found
    }

    val found = results
    val place = selected
    when {
        found == null -> Modal("장소 검색 중", keyword, onDismiss) {
            Text("목적지 좌표를 확인하고 있어요.")
        }
        found.isEmpty() -> NavigationSearchDialog(keyword, onDismiss, onSearch = {
            onDismiss()
            vm.searchDestination(keyword)
        })
        place == null -> Modal("목적지를 선택해 주세요", keyword, onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (result in found) {
                    OutlinedButton(onClick = { selected = result.place }, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(result.place.name)
                            if (result.address.isNotBlank()) Text(result.address, color = H1Style.Muted)
                        }
                    }
                }
            }
        }
        else -> {
            val venue = vm.venueFor(place)
            if (venue != null) {
                ChallengeEntryDialog(vm, venue, place, onDismiss)
            } else {
                NavigationSearchDialog(place.name, onDismiss, onSearch = {
                    onDismiss()
                    vm.navigateToPlace(place)
                }, placeResolved = true)
            }
        }
    }
}

private suspend fun findPlaces(keyword: String): List<PlaceSearchResult> {
    if (BuildConfig.KAKAO_LOCAL_REST_API_KEY.isNotBlank()) {
        val results = withContext(Dispatchers.IO) {
            try {
                queryKakaoLocal(keyword)
            } catch (_: IOException) {
                emptyList()
            } catch (_: org.json.JSONException) {
                emptyList()
            }
        }
        if (results.isNotEmpty()) return results
    }

    // Coordinates previously received from Pleos for the two prototype search examples.
    val place = when (keyword.filter(Char::isLetterOrDigit)) {
        "더현대서울" -> SavedPlace.from("더현대 서울", GeoPoint(37.5259, 126.9284))
        "서울역" -> SavedPlace.from("서울역", GeoPoint(37.5547, 126.9707))
        else -> null
    }
    return listOfNotNull(place?.let { PlaceSearchResult(it, "") })
}

private fun queryKakaoLocal(keyword: String): List<PlaceSearchResult> {
    val encoded = URLEncoder.encode(keyword, "UTF-8")
    val connection = URL("https://dapi.kakao.com/v2/local/search/keyword.json?size=5&query=$encoded")
        .openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "KakaoAK ${BuildConfig.KAKAO_LOCAL_REST_API_KEY}")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
        val rows = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getJSONArray("documents") }
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val place = SavedPlace.from(row.optString("place_name"), GeoPoint(row.optDouble("y"), row.optDouble("x")))
                ?: return@mapNotNull null
            PlaceSearchResult(place, row.optString("road_address_name").ifBlank { row.optString("address_name") })
        }.distinctBy { it.place }
    } finally {
        connection.disconnect()
    }
}
