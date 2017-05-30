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

package org.radarcns.android.util;

import android.os.Environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * A class-bounded file-based storage to load or store properties.
 * @deprecated Use SharedPreferences instead to avoid needing external files permissions.
 */
@Deprecated
public class PersistentStorage {
    private static Logger logger = LoggerFactory.getLogger(PersistentStorage.class);

    private final File classFile;
    private Properties loadedProps;

    public PersistentStorage(Class<?> forClass) {
        File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

        if (!path.exists() && !path.mkdirs()) {
            logger.error("'{}' could not be created", path.getAbsolutePath());
        }

        classFile = new File(path, forClass.getName() + ".properties");
        loadedProps = null;
    }

    /**
     * Use a class-bounded file-based storage to load or store properties.
     *
     * If a class-bound properties file exists, the default properties are updated with the contents
     * of that file. If any defaults were not stored yet, the combined loaded properties and default
     * properties are stored again. If no values were stored, the given defaults are stored
     * and returned.
     * @param defaults default properties, null if no defaults are known
     * @throws IOException if the properties cannot be retrieved or stored.
     * @return modified defaults, overwritten any loaded properties. If defaults was none, a new
     *         Properties object is returned.
     */
    public Properties loadOrStore(Properties defaults)
            throws IOException {
        Properties loadedProps = load();

        // Nothing needs to be stored
        if (defaults == null) {
            Properties ret = new Properties();
            ret.putAll(loadedProps);
            return ret;
        }

        if (!defaults.isEmpty()) {
            // Find out if the defaults had more values than the properties file. If not, do not
            // store the values again.
            Set<Object> originalKeySet = new HashSet<>(defaults.keySet());
            originalKeySet.removeAll(loadedProps.keySet());
            if (!originalKeySet.isEmpty()) {
                for (Object key : originalKeySet) {
                    loadedProps.put(key, defaults.get(key));
                }
                store(loadedProps);
            }
        }

        defaults.putAll(loadedProps);
        return defaults;
    }

    /**
     * Load properties from class file, if it exists.
     * @throws IOException if the file is not a valid properties file
     * @return loaded properties or an empty Properties object.
     */
    private synchronized Properties load() throws IOException {
        if (loadedProps != null) {
            return loadedProps;
        }

        loadedProps = new Properties();

        if (classFile.exists()) {
            // Read source id
            try (FileInputStream fin = new FileInputStream(classFile);
                 Reader fr = new InputStreamReader(fin, "UTF-8");
                 Reader in = new BufferedReader(fr)) {
                loadedProps.load(in);
                logger.debug("Loaded persistent properties from {}", classFile);
            }
        }

        return loadedProps;
    }

    /** Store properties to given file. */
    public synchronized void store(Properties props) throws IOException {
        try (FileOutputStream fout = new FileOutputStream(classFile);
             OutputStreamWriter fwr = new OutputStreamWriter(fout, "UTF-8");
             Writer out = new BufferedWriter(fwr)) {
            props.store(out, null);
            logger.debug("Stored persistent properties to {}", classFile);
            if (loadedProps != props) {
                loadedProps = new Properties();
                loadedProps.putAll(props);
            }
        }
    }

    /**
     * Load or store a single persistent UUID value.
     * @param key key to store the UUID with.
     * @return The generated UUID value
     */
    public String loadOrStoreUUID(String key) {
        try {
            Properties props = load();
            String uuid = props.getProperty(key);
            if (uuid != null) {
                return uuid;
            }
            uuid = UUID.randomUUID().toString();
            props.setProperty(key, uuid);
            store(props);
            return uuid;
        } catch (IOException ex) {
            logger.error("Failed to load or store UUID, generating new unpersisted one", ex);
            // Use newly generated UUID
            return UUID.randomUUID().toString();
        }
    }

    /**
     * Get a String value from persistent storage.
     * @param key key of the value
     * @return value or null if not present
     */
    public String get(String key) throws IOException {
        return load().getProperty(key);
    }

    /**
     * Get a String value from persistent storage and store a default value otherwise.
     * @param key key of the value
     * @return value or null if not present
     */
    public String getOrSet(String key, String defaultValue) throws IOException {
        Properties props = load();
        String ret = props.getProperty(key);
        if (ret == null && defaultValue != null) {
            props.setProperty(key, defaultValue);
            store(props);
            return defaultValue;
        } else {
            return ret;
        }
    }

    /**
     * Put a String in storage replacing a previous value, if any.
     * @param key key of the value
     * @param value value to store
     */
    public void put(String key, String value) throws IOException {
        Properties props = load();
        props.setProperty(key, value);
        store(props);
    }

    /** Clear the storage. */
    public void clear() throws IOException {
        store(new Properties());
    }

    /** Number of properties stored. */
    public int size() throws IOException {
        return load().size();
    }

    /** Remove element with given key. */
    public void remove(String key) throws IOException {
        Properties props = load();
        props.remove(key);
        store(props);
    }
}
