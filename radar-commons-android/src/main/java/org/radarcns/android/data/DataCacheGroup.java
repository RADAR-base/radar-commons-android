package org.radarcns.android.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class DataCacheGroup<K, V> implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(DataCacheGroup.class);

    private final String topicName;
    private final DataCache<K, V> activeDataCache;
    private final List<ReadableDataCache> deprecatedCaches;

    public DataCacheGroup(DataCache<K, V> activeDataCache, List<ReadableDataCache> deprecatedCaches) {
        this.topicName = activeDataCache.getTopic().getName();
        this.activeDataCache = activeDataCache;
        this.deprecatedCaches = deprecatedCaches;
    }

    public DataCache<K, V> getActiveDataCache() {
        return activeDataCache;
    }

    public List<ReadableDataCache> getDeprecatedCaches() {
        return deprecatedCaches;
    }

    public void deleteEmptyCaches() throws IOException {
        Iterator<ReadableDataCache> cacheIterator = deprecatedCaches.iterator();
        while (cacheIterator.hasNext()) {
            ReadableDataCache storedCache = cacheIterator.next();
            if (storedCache.numberOfRecords().first > 0) {
                continue;
            }
            cacheIterator.remove();
            storedCache.close();
            File tapeFile = storedCache.getFile();
            if (!tapeFile.delete()) {
                logger.warn("Cannot remove old DataCache file " + tapeFile + " for topic " + storedCache.getReadTopic().getName());
            }
            String name = tapeFile.getAbsolutePath();
            String base = name.substring(0, name.length() - CacheStore.TAPE_EXTENSION.length());
            File keySchemaFile = new File(base + CacheStore.KEY_SCHEMA_EXTENSION);
            if (!keySchemaFile.delete()) {
                logger.warn("Cannot remove old key schema file " + keySchemaFile + " for topic " + storedCache.getReadTopic().getName());
            }
            File valueSchemaFile = new File(base + CacheStore.VALUE_SCHEMA_EXTENSION);
            if (!valueSchemaFile.delete()) {
                logger.warn("Cannot remove old value schema file " + valueSchemaFile + " for topic " + storedCache.getReadTopic().getName());
            }
        }
    }

    public String getTopicName() {
        return topicName;
    }

    @Override
    public void close() throws IOException {
        activeDataCache.close();
        for (ReadableDataCache cache : deprecatedCaches) {
            cache.close();
        }
    }
}
