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

package org.radarbase.android.data

import android.content.Context
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.BuildConfig
import org.radarbase.android.data.serialization.SerializationFactory
import org.radarbase.android.data.serialization.TapeAvroSerializationFactory
import org.radarbase.android.util.SafeHandler
import org.radarbase.topic.AvroTopic
import org.radarbase.util.SynchronizedReference
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.lang.Exception
import java.nio.charset.StandardCharsets
import java.util.*

class CacheStore(
        private val serializationFactories: List<SerializationFactory> = listOf(TapeAvroSerializationFactory())
) {
    private val tables: MutableMap<String, SynchronizedReference<DataCacheGroup<*, *>>> = HashMap()
    private val handler = SafeHandler.getInstance("DataCache", THREAD_PRIORITY_BACKGROUND)

    init {
        require(serializationFactories.isNotEmpty()) { "Need to specify at least one serialization method" }
        if (BuildConfig.DEBUG) {
            check(serializationFactories.none { s1 ->
                serializationFactories.any { s2 -> s1.fileExtension.endsWith(s2.fileExtension, ignoreCase = true) }
            }) { "Serialization factories cannot have overlapping extensions, to avoid the wrong deserialization method being chosen."}
        }
        handler.start()
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    @Throws(IOException::class)
    fun <K: ObservationKey, V: SpecificRecord> getOrCreateCaches(
            context: Context,
            topic: AvroTopic<K, V>,
            config: CacheConfiguration,
            handler: SafeHandler? = null,
    ): DataCacheGroup<K, V> {
        val useHandler = if (handler != null) {
            require(handler.isStarted) { "Cannot load a cache from a stopped handler" }
            handler
        } else this.handler
        val ref = tables[topic.name] as SynchronizedReference<DataCacheGroup<K, V>>?
                ?: SynchronizedReference {
                    loadCache(
                        context.cacheDir.absolutePath + "/" + topic.name,
                        topic,
                        config,
                        useHandler,
                    )
                }.also { tables[topic.name] = it as SynchronizedReference<DataCacheGroup<*, *>> }

        return ref.get()
    }

    @Throws(IOException::class)
    private fun <K: Any, V: Any> loadCache(
            base: String,
            topic: AvroTopic<K, V>,
            config: CacheConfiguration,
            handler: SafeHandler,
    ): DataCacheGroup<K, V> {
        val fileBases = getFileBases(base)
        logger.debug("Files for topic {}: {}", topic.name, fileBases)

        var activeDataCache: DataCache<K, V>? = null
        val deprecatedDataCaches = ArrayList<ReadableDataCache>()

        for ((fileBase, serialization) in fileBases) {
            val tapeFile = File(fileBase + serialization.fileExtension)
            val (keySchema, valueSchema) = loadSchemas(topic, fileBase)
                    ?: continue  // no use in reading without valid schemas

            val outputTopic = AvroTopic(topic.name,
                    keySchema, valueSchema,
                    Any::class.java, Any::class.java)

            if (keySchema == topic.keySchema
                    && valueSchema == topic.valueSchema
                    && serialization == serializationFactories.first()) {
                if (activeDataCache != null) {
                    logger.error("Cannot have more than one active cache")
                }

                logger.info("Loading matching data store with schemas {}", tapeFile)
                activeDataCache = TapeCache(
                        tapeFile, topic, outputTopic, handler, serialization, config)
            } else {
                logger.debug("Loading deprecated data store {}", tapeFile)
                deprecatedDataCaches.add(TapeCache(
                        tapeFile, outputTopic, outputTopic, handler, serialization, config))
            }
        }

        if (activeDataCache == null) {
            val baseDir = File(base)
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw IOException("Cannot make data cache directory")
            }
            val serialization = serializationFactories.first()
            activeDataCache = IntRange(0, 99)
                    .map { "$base/cache-$it" }
                    .find { fileBase -> fileBases.none { it.first == fileBase } }
                    ?.let { fileBase ->
                        storeSchema(topic.keySchema, File(fileBase + KEY_SCHEMA_EXTENSION))
                        storeSchema(topic.valueSchema, File(fileBase + VALUE_SCHEMA_EXTENSION))

                        val outputTopic = AvroTopic(topic.name,
                                topic.keySchema, topic.valueSchema,
                                Any::class.java, Any::class.java)

                        val tapeFile = File(fileBase + serialization.fileExtension)
                        logger.info("Creating new data store {}", tapeFile)
                        TapeCache(
                                tapeFile, topic, outputTopic, handler, serialization, config)
                    } ?: throw IOException("No empty slot to store active data cache in.")
        }

        return DataCacheGroup(activeDataCache, deprecatedDataCaches)
    }

    private fun loadSchemas(topic: AvroTopic<*, *>, base: String): Pair<Schema, Schema>? {
        val parser = Schema.Parser()

        val keySchemaFile = File(base + KEY_SCHEMA_EXTENSION)
        val valueSchemaFile = File(base + VALUE_SCHEMA_EXTENSION)
        val keySchema = loadSchema(parser, keySchemaFile)
        val valueSchema = loadSchema(parser, valueSchemaFile)

        return when {
            keySchema != null && valueSchema != null -> Pair(keySchema, valueSchema)
            keySchema == null && valueSchema == null -> Pair(topic.keySchema, topic.valueSchema)
            keySchema == null && valueSchema == topic.valueSchema -> Pair(topic.keySchema, topic.valueSchema)
            keySchema == topic.keySchema && valueSchema == null -> Pair(topic.keySchema, topic.valueSchema)
            else -> {
                logger.error("Cannot load partially specified schema")
                null
            }
        }
    }

    private fun getFileBases(base: String): List<Pair<String, SerializationFactory>> {
        val regularFiles = serializationFactories
                .filter { sf -> File(base + sf.fileExtension).isFile }
                .map { sf -> Pair(base + sf.fileExtension, sf) }

        val dirFiles = File(base)
                .takeIf { it.isDirectory }
                ?.listFiles { _, fileName -> serializationFactories.any { sf -> fileName.endsWith(sf.fileExtension) } }
                ?.map { f ->
                    val fileName = f.name
                    val sf = serializationFactories.first { fileName.endsWith(it.fileExtension) }
                    Pair(base + "/" + fileName.substring(0, fileName.length - sf.fileExtension.length), sf)
                }

        return if (dirFiles != null) regularFiles + dirFiles else regularFiles
    }

    private fun loadSchema(parser: Schema.Parser, file: File): Schema? {
        return try {
            if (file.isFile) parser.parse(file) else null
        } catch (ex: Exception) {
            logger.error("Failed to load schema", ex)
            null
        }
    }

    private fun storeSchema(schema: Schema, file: File) {
        try {
            FileOutputStream(file).use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use {
                    it.write(schema.toString(false))
                }
            }
        } catch (ex: IOException) {
            logger.error("Cannot write schema", ex)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CacheStore::class.java)

        internal const val KEY_SCHEMA_EXTENSION = ".key.avsc"
        internal const val VALUE_SCHEMA_EXTENSION = ".value.avsc"
    }
}
