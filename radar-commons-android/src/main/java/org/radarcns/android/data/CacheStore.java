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

package org.radarcns.android.data;

import android.content.Context;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.util.AndroidThreadFactory;
import org.radarcns.android.util.SharedSingleThreadExecutorFactory;
import org.radarcns.android.util.SingleThreadExecutorFactory;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.SynchronizedReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

public class CacheStore {
    private static final Logger logger = LoggerFactory.getLogger(CacheStore.class);

    static final String TAPE_EXTENSION = ".tape";
    static final String KEY_SCHEMA_EXTENSION = ".key.avsc";
    static final String VALUE_SCHEMA_EXTENSION = ".value.avsc";
    private static CacheStore store = new CacheStore();
    private final GenericData genericData;
    private final Map<String, SynchronizedReference<DataCacheGroup>> tables;

    public static CacheStore get() {
        return store;
    }

    private final SpecificData specificData;
    private SingleThreadExecutorFactory cacheExecutorFactory;

    private CacheStore() {
        cacheExecutorFactory = null;
        specificData = new SpecificData(CacheStore.class.getClassLoader()) {
            @Override
            protected boolean isFloat(Object object) {
                return object instanceof Float
                        && !((Float) object).isNaN()
                        && !((Float) object).isInfinite();
            }
            @Override
            protected boolean isDouble(Object object) {
                return object instanceof Double
                        && !((Double) object).isNaN()
                        && !((Double) object).isInfinite();
            }
        };

        genericData = new GenericData(CacheStore.class.getClassLoader()) {
            @Override
            protected boolean isFloat(Object object) {
                return object instanceof Float
                        && !((Float) object).isNaN()
                        && !((Float) object).isInfinite();
            }
            @Override
            protected boolean isDouble(Object object) {
                return object instanceof Double
                        && !((Double) object).isNaN()
                        && !((Double) object).isInfinite();
            }
        };
        tables = new HashMap<>();
    }

    private synchronized SingleThreadExecutorFactory getCacheExecutorFactory() {
        if (cacheExecutorFactory == null) {
            cacheExecutorFactory = new SharedSingleThreadExecutorFactory(
                    new AndroidThreadFactory("DataCache", THREAD_PRIORITY_BACKGROUND));
        }
        return cacheExecutorFactory;
    }


    @SuppressWarnings("unchecked")
    public synchronized <K extends SpecificRecord, V extends SpecificRecord> DataCacheGroup<K, V>
            getOrCreateCaches(Context context, AvroTopic<K, V> topic) throws IOException {
        SynchronizedReference<DataCacheGroup> ref = tables.get(topic.getName());
        if (ref == null) {
            final String base = context.getCacheDir().getAbsolutePath() + "/" + topic.getName();

            ref = new SynchronizedReference<>(() -> loadCache(base, topic));

            tables.put(topic.getName(), ref);
        }
        return ref.get();
    }

    private <K, V> DataCacheGroup<K, V> loadCache(String base, AvroTopic<K, V> topic) throws IOException {
        List<String> fileBases = getFileBases(base);

        DataCache<K, V> activeDataCache = null;
        List<ReadableDataCache> deprecatedDataCaches = new ArrayList<>();

        for (String fileBase : fileBases) {
            Schema.Parser parser = new Schema.Parser();
            File keySchemaFile = new File(fileBase + KEY_SCHEMA_EXTENSION);
            Schema keySchema = keySchemaFile.isFile() ? parser.parse(keySchemaFile) : null;
            File valueSchemaFile = new File(fileBase + VALUE_SCHEMA_EXTENSION);
            Schema valueSchema = valueSchemaFile.isFile() ? parser.parse(valueSchemaFile) : null;

            File tapeFile = new File(fileBase + TAPE_EXTENSION);

            if (keySchema == null || valueSchema == null) {
                if ((keySchema != null && !keySchema.equals(topic.getKeySchema()))
                        || (valueSchema != null && !valueSchema.equals(topic.getValueSchema()))) {
                    logger.error("Cannot load partially specified schema");
                } else if (activeDataCache != null) {
                    logger.error("Cannot have more than one active cache");
                } else {
                    AvroTopic<Object, Object> outputTopic = new AvroTopic<>(topic.getName(),
                            topic.getKeySchema(), topic.getValueSchema(),
                            Object.class, Object.class);

                    if (keySchema == null) {
                        try (FileOutputStream out = new FileOutputStream(keySchemaFile);
                             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                            writer.write(topic.getKeySchema().toString(false));
                        } catch (IOException ex) {
                            logger.error("Cannot write key schema", ex);
                        }
                    }
                    if (valueSchema == null) {
                        try (FileOutputStream out = new FileOutputStream(valueSchemaFile);
                             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                            writer.write(topic.getValueSchema().toString(false));
                        } catch (IOException ex) {
                            logger.error("Cannot write value schema", ex);
                        }
                    }

                    activeDataCache = new TapeCache<>(
                            tapeFile, topic, outputTopic, getCacheExecutorFactory(),
                            specificData, genericData);
                }
            } else {
                AvroTopic<Object, Object> outputTopic = new AvroTopic<>(topic.getName(),
                        keySchema, valueSchema,
                        Object.class, Object.class);

                if (keySchema.equals(topic.getKeySchema()) && valueSchema.equals(topic.getValueSchema())) {
                    if (activeDataCache != null) {
                        logger.error("Cannot have more than one active cache");
                    }

                    activeDataCache = new TapeCache<>(
                            tapeFile, topic, outputTopic, getCacheExecutorFactory(),
                            specificData, genericData);
                } else {
                    deprecatedDataCaches.add(new TapeCache<>(tapeFile, outputTopic,
                            outputTopic, getCacheExecutorFactory(), specificData, genericData));
                }
            }
        }

        if (activeDataCache == null) {
            File baseDir = new File(base);
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw new IOException("Cannot make data cache directory");
            }
            for (int i = 0; i < 100; i++) {
                String fileBase = base + "/cache-" + i;
                if (!fileBases.contains(fileBase)) {
                    File tapeFile = new File(fileBase + TAPE_EXTENSION);
                    File keySchemaFile = new File(fileBase + KEY_SCHEMA_EXTENSION);
                    File valueSchemaFile = new File(fileBase + KEY_SCHEMA_EXTENSION);

                    AvroTopic<Object, Object> outputTopic = new AvroTopic<>(topic.getName(),
                            topic.getKeySchema(), topic.getValueSchema(),
                            Object.class, Object.class);

                    try (FileOutputStream out = new FileOutputStream(keySchemaFile);
                         OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                        writer.write(topic.getKeySchema().toString(false));
                    } catch (IOException ex) {
                        logger.error("Cannot write key schema", ex);
                    }
                    try (FileOutputStream out = new FileOutputStream(valueSchemaFile);
                         OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                        writer.write(topic.getValueSchema().toString(false));
                    } catch (IOException ex) {
                        logger.error("Cannot write value schema", ex);
                    }

                    activeDataCache = new TapeCache<>(
                            tapeFile, topic, outputTopic, getCacheExecutorFactory(),
                            specificData, genericData);
                    break;
                }
            }

            if (activeDataCache == null) {
                throw new IOException("No empty slot to store active data cache in.");
            }
        }

        return new DataCacheGroup<>(activeDataCache, deprecatedDataCaches);
    }

    private List<String> getFileBases(String base) {
        List<String> files = new ArrayList<>(2);
        if (new File(base + TAPE_EXTENSION).isFile()) {
            files.add(base);
        }
        File baseDir = new File(base);
        if (baseDir.isDirectory()) {
            File[] tapeFiles = baseDir.listFiles((dir, name) -> name.toLowerCase().endsWith(TAPE_EXTENSION));
            for (File tapeFile : tapeFiles) {
                String name = tapeFile.getName();
                files.add(base + "/" + name.substring(0, name.length() - TAPE_EXTENSION.length()));
            }
        }
        return files;
    }

    public SpecificData getSpecificData() {
        return specificData;
    }

    public GenericData getGenericData() {
        return genericData;
    }
}
