package ai.musaz.h1score

import ai.musaz.h1score.core.DrivingEvent
import ai.musaz.h1score.core.DrivingEventType
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/** Accents scoped to saved-drive details, including their full-view dialogs. */
internal object SavedDriveDetailStyle {
    val Good = Color(0xFF2E6C50)
    val Improve = Color(0xFFB44941)
}

internal fun DrivingEventType.detailColor(): Color = when (this) {
    DrivingEventType.OVERSPEED -> Color(0xFFAA5927)
    DrivingEventType.RAPID_ACCELERATION -> Color(0xFF386BA5)
    DrivingEventType.RAPID_DECELERATION -> Color(0xFF7858A0)
}

internal fun DrivingEventType.detailLabel(): String = when (this) {
    DrivingEventType.OVERSPEED -> "제한속도 초과"
    DrivingEventType.RAPID_ACCELERATION -> "급가속"
    DrivingEventType.RAPID_DECELERATION -> "급감속"
}

internal fun DrivingEvent.speedDetail(): String = when (type) {
    DrivingEventType.OVERSPEED -> "${toKph.roundToInt()} km/h · 제한 $limitKph km/h"
    else -> "${fromKph.roundToInt()} → ${toKph.roundToInt()} km/h"
}
