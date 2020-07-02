package org.radarbase.android.data.serialization

import org.apache.avro.generic.GenericData
import org.apache.avro.specific.SpecificData
import org.radarbase.data.Record
import org.radarbase.topic.AvroTopic
import org.radarbase.util.BackedObjectQueue

class TapeAvroSerializationFactory: SerializationFactory {
    override val fileExtension: String = ".tape"

    private val genericData: GenericData = object : GenericData(TapeAvroSerializationFactory::class.java.classLoader) {
        override fun isFloat(`object`: Any?): Boolean {
            return (`object` is Float
                    && !`object`.isNaN()
                    && !`object`.isInfinite())
        }

        override fun isDouble(`object`: Any?): Boolean {
            return (`object` is Double
                    && !`object`.isNaN()
                    && !`object`.isInfinite())
        }
    }

    private val specificData: SpecificData = object : SpecificData(TapeAvroSerializationFactory::class.java.classLoader) {
        override fun isFloat(`object`: Any?): Boolean {
            return (`object` is Float
                    && !`object`.isNaN()
                    && !`object`.isInfinite())
        }

        override fun isDouble(`object`: Any?): Boolean {
            return (`object` is Double
                    && !`object`.isNaN()
                    && !`object`.isInfinite())
        }
    }

    override fun <K: Any, V: Any> createDeserializer(
            topic: AvroTopic<K, V>
    ): BackedObjectQueue.Deserializer<Record<K, V>> = TapeAvroDeserializer(topic, genericData)

    override fun <K : Any, V : Any> createSerializer(
            topic: AvroTopic<K, V>
    ) = TapeAvroSerializer(topic, specificData)
}
