package ai.musaz.h1score

internal fun decodeHazard(raw: String?): Boolean? = when(raw) {
    "0", "false" -> false
    "1", "true" -> true
    else -> null
}

internal fun acceptTurnReply(requestedAt: Long, now: Long, lastAcceptedAt: Long): Boolean =
    requestedAt > lastAcceptedAt && now-requestedAt in 0..500
