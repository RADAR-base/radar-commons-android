package org.radarbase.passive.phone.audio.input.utils

data class InputRecordInfo(
    val recorderCreated: Boolean,
    val recordingPathSet: Boolean,
    val fileHeadersWritten: Boolean,
    val bufferCreated: Boolean,
    val isRecording: Boolean
)