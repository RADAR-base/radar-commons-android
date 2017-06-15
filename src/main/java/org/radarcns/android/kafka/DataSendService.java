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

package org.radarcns.android.kafka;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PersistableBundle;

import org.radarcns.config.AvroTopicConfig;
import org.radarcns.topic.AvroTopic;

import java.lang.reflect.InvocationTargetException;

/**
 * Created by joris on 14/06/2017.
 */

public class DataSendService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        PersistableBundle extras = params.getExtras();
        AvroTopicConfig config = new AvroTopicConfig();
        config.setTopic(extras.getString("topic"));
        config.setKeySchema(extras.getString("key_schema"));
        config.setValueSchema(extras.getString("value_schema"));
        AvroTopic<?, ?> topic;
        try {
            topic = config.parseAvroTopic();
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException ex) {
            throw new IllegalArgumentException("Cannot instantiate topic from configured topic "
                    + extras.getString("topic") + " with key schema class "
                    + extras.getString("key_schema") + " and value schema class "
                    + extras.getString("value_schema"), ex);
        }



        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
