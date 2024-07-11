package org.radarbase.passive.phone.audio.input.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.widget.TextView
import org.radarbase.passive.phone.audio.input.utils.AudioTypeFormatUtil.toLogFriendlyType
import org.slf4j.Logger
import org.slf4j.LoggerFactory


object AudioDeviceUtils {

    fun getConnectedMicrophones(context: Context, textView: TextView): List<AudioDeviceInfo> {
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

            textView.text = microphones.joinToString(", ") { it.productName.toString() + " (" + it.type.toLogFriendlyType() +").\n" }
            microphones.forEach {
                logger.info("Name: ${it.productName}, type: ${it.type.  toLogFriendlyType()}, encodings: ${it.encodings.joinToString(", ") { encoding -> AudioTypeFormatUtil.toLogFriendlyEncoding(encoding) }}, SampleRates: ${it.sampleRates.joinToString(", ") { srs-> srs.toString() }} channels: ${it.channelCounts.joinToString (", "){ channels -> channels.toString() }} Id: ${it.id}")
            }
        }
        return microphones
    }

    private val logger: Logger = LoggerFactory.getLogger(AudioDeviceUtils::class.java)

}