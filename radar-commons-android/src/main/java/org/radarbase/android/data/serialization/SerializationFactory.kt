package org.radarbase.android.data.serialization

import org.radarbase.data.Record
import org.radarbase.topic.AvroTopic
import org.radarbase.util.BackedObjectQueue

interface SerializationFactory {
    val fileExtension: String
    fun <K: Any, V: Any> createDeserializer(topic: AvroTopic<K, V>): BackedObjectQueue.Deserializer<Record<K, V>>
    fun <K: Any, V: Any> createSerializer(topic: AvroTopic<K, V>): BackedObjectQueue.Serializer<Record<K, V>>
}
