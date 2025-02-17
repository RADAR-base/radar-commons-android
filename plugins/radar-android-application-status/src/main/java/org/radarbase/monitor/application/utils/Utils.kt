package org.radarbase.monitor.application.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun dateTimeFromInstant(epochSeconds: Long): String {
    return DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))
}