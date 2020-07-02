package org.radarbase.android.data.serialization

import org.radarbase.data.Record
import org.radarbase.topic.AvroTopic
import org.radarbase.util.BackedObjectQueue

/**
 * Factory for serializer and deserializers for the data cache.
 */
interface SerializationFactory {
    /**
     * File extension to use with this serialization type. It should be unique across used
     * serialization factories.
     */
    val fileExtension: String

    /**
     * Creates a deserializer for a given topic.
     */
    fun <K: Any, V: Any> createDeserializer(topic: AvroTopic<K, V>): BackedObjectQueue.Deserializer<Record<K, V>>

    /**
     * Creates a serializer for a given topic.
     */
    fun <K: Any, V: Any> createSerializer(topic: AvroTopic<K, V>): BackedObjectQueue.Serializer<Record<K, V>>
}
