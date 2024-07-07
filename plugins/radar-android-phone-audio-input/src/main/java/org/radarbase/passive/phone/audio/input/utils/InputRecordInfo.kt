package org.radarbase.passive.phone.audio.input.utils

data class InputRecordInfo(
    var recorderCreated: Boolean = false,
    var recordingPathSet: Boolean = false,
    var fileHeadersWritten: Boolean = false,
    var bufferCreated: Boolean = false,
    var isRecording: Boolean = false
)