package org.radarbase.passive.google.healthconnect

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object HealthConnectPreferencesSerializer : Serializer<HealthConnectPreferences> {
    override val defaultValue: HealthConnectPreferences = HealthConnectPreferences.getDefaultInstance()
    override suspend fun readFrom(input: InputStream): HealthConnectPreferences {
        try {
            return HealthConnectPreferences.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: HealthConnectPreferences, output: OutputStream) = t.writeTo(output)
}
