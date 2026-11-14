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

internal data class PlaceSearchResult(val place: SavedPlace, val address: String)

@Composable
internal fun DestinationSearchDialog(
    vm: ChallengeViewModel,
    keyword: String,
    openUnresolvedInNavigation: Boolean = false,
    onDismiss: () -> Unit,
) {
    var results by remember(keyword) { mutableStateOf<List<PlaceSearchResult>?>(null) }
    var selected by remember(keyword) { mutableStateOf<SavedPlace?>(null) }
    LaunchedEffect(keyword) {
        val found = findPlaces(keyword)
        if (found.isEmpty() && openUnresolvedInNavigation) {
            onDismiss()
            vm.searchDestination(keyword)
            return@LaunchedEffect
        }
        selected = found.singleOrNull { placeKey(it.place.name).equals(placeKey(keyword), ignoreCase = true) }?.place
            ?: found.singleOrNull()?.place
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
        else -> ResolvedDestinationDialog(vm, place, onDismiss)
    }
}

internal suspend fun findPlaces(keyword: String): List<PlaceSearchResult> {
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

    val normalized = placeKey(keyword)
    if (normalized.isBlank()) return emptyList()
    return (registeredChallengePlaces + prototypePlaces).filter {
        placeKey(it.place.name).contains(normalized, ignoreCase = true)
    }.take(5)
}

private fun placeKey(name: String): String = name.filter(Char::isLetterOrDigit)

private val registeredChallengePlaces: List<PlaceSearchResult>
    get() = challengeSpots.mapNotNull { spot ->
        val name = if (spot == hangangChallenge) "여의도 한강공원" else spot.name
        SavedPlace.from(name, spot.point)?.let { PlaceSearchResult(it, spot.area) }
    }

internal fun registeredChallengePlace(keyword: String): SavedPlace? =
    registeredChallengePlaces.singleOrNull {
        placeKey(it.place.name).equals(placeKey(keyword), ignoreCase = true)
    }?.place

private val prototypePlaces = listOfNotNull(
    SavedPlace.from("더현대 서울", GeoPoint(37.5259, 126.9284))?.let { PlaceSearchResult(it, "서울 영등포구 여의대로 108") },
    // Public directions coordinates from Seoul's Hangang park directory.
    SavedPlace.from("뚝섬 한강공원", GeoPoint(37.52936457, 127.07397816))?.let { PlaceSearchResult(it, "서울 광진구") },
    SavedPlace.from("반포 한강공원", GeoPoint(37.50769397, 126.99273098))?.let { PlaceSearchResult(it, "서울 서초구") },
    SavedPlace.from("망원 한강공원", GeoPoint(37.55234296, 126.89988716))?.let { PlaceSearchResult(it, "서울 마포구") },
    SavedPlace.from("서울역", GeoPoint(37.5547, 126.9707))?.let { PlaceSearchResult(it, "서울 중구 한강대로 405") },
)

@Composable
internal fun ResolvedDestinationDialog(
    vm: ChallengeViewModel,
    place: SavedPlace,
    onDismiss: () -> Unit,
) {
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
