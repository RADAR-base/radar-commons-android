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

package org.radarcns.android.device;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.radarcns.key.MeasurementKey;
import org.radarcns.topic.AvroTopic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class DeviceTopics {
    private final Map<String, AvroTopic<MeasurementKey, ? extends SpecificRecord>> topicMap;

    protected DeviceTopics() {
        topicMap = new HashMap<>();
    }

    protected <W extends SpecificRecord> AvroTopic<MeasurementKey, W> createTopic(String name,
                                                        Schema valueSchema, Class<W> valueClass) {
        AvroTopic<MeasurementKey, W> topic = new AvroTopic<>(
                name, MeasurementKey.getClassSchema(), valueSchema,
                MeasurementKey.class, valueClass);
        topicMap.put(name, topic);
        return topic;
    }

    public AvroTopic<MeasurementKey, ? extends SpecificRecord> getTopic(String name) {
        return Objects.requireNonNull(topicMap.get(name), "Topic " + name + " unknown");
    }
}
