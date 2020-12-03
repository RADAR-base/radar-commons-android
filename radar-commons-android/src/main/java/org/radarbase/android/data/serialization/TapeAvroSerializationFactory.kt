package org.radarbase.android.data.serialization

import org.apache.avro.generic.GenericData
import org.apache.avro.specific.SpecificData
import org.radarbase.data.Record
import org.radarbase.topic.AvroTopic
import org.radarbase.util.BackedObjectQueue

/**
 * Serialization for binary Avro records to a tape.
 */
class TapeAvroSerializationFactory: SerializationFactory {
    override val fileExtension: String = ".tape"

    // The receiving end may have problems with non-numeric representations of floats, so they are not allowed.
    private val genericData: GenericData = object : GenericData(TapeAvroSerializationFactory::class.java.classLoader) {
        override fun isFloat(datum: Any?): Boolean = datum is Float && datum.isFinite()
        override fun isDouble(datum: Any?): Boolean = datum is Double && datum.isFinite()
    }

    // The receiving end may have problems with non-numeric representations of floats, so they are not allowed.
    private val specificData: SpecificData = object : SpecificData(TapeAvroSerializationFactory::class.java.classLoader) {
        override fun isFloat(datum: Any?): Boolean = datum is Float && datum.isFinite()
        override fun isDouble(datum: Any?): Boolean = datum is Double && datum.isFinite()
    }

    override fun <K: Any, V: Any> createDeserializer(
            topic: AvroTopic<K, V>
    ): BackedObjectQueue.Deserializer<Record<K, V>> = TapeAvroDeserializer(topic, genericData)

    override fun <K : Any, V : Any> createSerializer(
            topic: AvroTopic<K, V>
    ) = TapeAvroSerializer(topic, specificData)

    override fun toString() = "TapeAvroSerialization"
}
