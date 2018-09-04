package org.radarcns.android.data;

import org.radarcns.data.AvroRecordData;
import org.radarcns.data.RecordData;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.topic.AvroTopic;

import java.util.List;
import java.util.Objects;

public class TopicKey<V> {
    public final AvroTopic<ObservationKey, V> topic;
    public final ObservationKey key;

    public TopicKey(AvroTopic<ObservationKey, V> topic, ObservationKey key) {
        this.topic = topic;
        this.key = key;
    }

    public RecordData<ObservationKey, V> getRecordData(List<V> values) {
        return new AvroRecordData<>(topic, key, values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TopicKey<?> topicKey = (TopicKey<?>) o;
        return Objects.equals(topic, topicKey.topic) &&
                Objects.equals(key, topicKey.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topic, key);
    }
}
