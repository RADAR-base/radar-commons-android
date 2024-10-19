package org.radarbase.android.kafka

import android.os.SystemClock

sealed class TopicSendResult(
    val topic: String,
    val time: Long = SystemClock.elapsedRealtime(),
)

class TopicSendReceipt(
    topic: String,
    val numberOfRecords: Long,
) : TopicSendResult(topic)

class TopicFailedReceipt(topic: String) : TopicSendResult(topic)
