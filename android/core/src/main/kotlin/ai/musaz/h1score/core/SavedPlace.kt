package ai.musaz.h1score.core

@ConsistentCopyVisibility
data class SavedPlace private constructor(val name: String, val point: GeoPoint) {
    companion object {
        fun from(name: String, point: GeoPoint?): SavedPlace? {
            if (point == null || !point.latitude.isFinite() || !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0 ||
                name.isBlank() || name.any(Char::isISOControl)) return null
            return SavedPlace(name.trim(), point)
        }
    }
}
fun shortcutLabel(value: String): String? = value.trim().takeIf {
    it.length in 1..12 && !value.any(Char::isISOControl)
}
