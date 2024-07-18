/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.passive.phone.audio.input.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.android.material.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.radarbase.passive.phone.audio.input.utils.AudioTypeFormatUtil.toLogFriendlyType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale


object AudioDeviceUtils {

    fun getConnectedMicrophones(context: Context): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val microphones: MutableList<AudioDeviceInfo> = ArrayList()

        for (device in devices) {
            if ( device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ) {

                microphones.add(device)
                logger.info("Devices found: $device")
            }
            microphones.forEach {
                logger.info("Name: ${it.productName}, type: ${it.type.  toLogFriendlyType()}, encodings: ${it.encodings.joinToString(", ") { encoding -> AudioTypeFormatUtil.toLogFriendlyEncoding(encoding) }}, SampleRates: ${it.sampleRates.joinToString(", ") { srs-> srs.toString() }} channels: ${it.channelCounts.joinToString (", "){ channels -> channels.toString() }} Id: ${it.id}")
            }
        }
        return microphones
    }

    fun showAlertDialog(context: Context, configure: MaterialAlertDialogBuilder.() -> Unit) {
        try {
            MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Material3_Dialog_Alert).
            setIcon(org.radarbase.passive.phone.audio.input.R.drawable.icon_play_alert_dialogue)
                .apply(configure)
                .show()
        } catch (ex: IllegalStateException) {
            logger.warn("Cannot show alert dialogue for closing activity")
        }
    }

    fun setWavHeaders(header: ByteArray, numChannels: Short, sampleRate: Int, bitsPerSample: Short) {
        // RIFF header
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 0 // Final file size not known yet, write 0
        header[5] = 0
        header[6] = 0
        header[7] = 0
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1
        header[21] = 0
        header[22] = numChannels.toByte()
        header[23] = 0
        header[24] = (sampleRate.toLong() and 0xffL).toByte()
        header[25] = ((sampleRate.toLong() shr 8) and 0xffL).toByte()
        header[26] = ((sampleRate.toLong() shr 16) and 0xffL).toByte()
        header[27] = ((sampleRate.toLong() shr 24) and 0xffL).toByte()
        val byteRate = sampleRate * bitsPerSample * numChannels / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        val blockAlign = numChannels * bitsPerSample / 8
        header[32] = blockAlign.toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (0 and 0xff).toByte()
        header[41] = ((0 shr 8) and 0xff).toByte()
        header[42] = ((0 shr 16) and 0xff).toByte()
        header[43] = ((0 shr 24) and 0xff).toByte()
    }

    fun formatMsToReadableTime(elapsedTime: Long): String {
        val seconds = (elapsedTime / 1000).toInt() % 60
        val minutes = ((elapsedTime / (1000 * 60)) % 60).toInt()
        val hours = ((elapsedTime / (1000 * 60 * 60)) % 24).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }


    private val logger: Logger = LoggerFactory.getLogger(AudioDeviceUtils::class.java)
}