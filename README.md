# RADAR-Commons-Android

[![Build Status](https://travis-ci.org/RADAR-base/radar-commons-android.svg?branch=master)](https://travis-ci.org/RADAR-base/radar-commons-android)

Base module for the RADAR passive remote monitoring app. Plugins for that app should implement the API from this base library. Also user interfaces should use this as a base. Currently, the main user interface is [RADAR-AndroidApplication](https://github.com/RADAR-base/radar-prmt-android.git).

Plugins that are implemented as part of RADAR-base are included in the `plugins` directory. Please refer to the README in the respective plugin for information on how to use the plugin.

## Configuration

This library takes the following firebase parameters:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `kafka_rest_proxy_url` | URL | `<empty>` | URL of a Kafka REST Proxy or RADAR-Gateway to send data to. |
| `schema_registry_url` | URL | `<empty>` | URL of a Kafka Schema Registry to sync schemas with. |
| `management_portal_url` | URL | `<empty>` | URL of the RADAR Management Portal. If empty, the Management Portal will not be used. |
| `unsafe_kafka_connection` | boolean | `false` | Whether to accept unsafe HTTPS certificates. Only meant to be set to `true` in development environments. |
| `plugins` | string | `<empty>` | A space-separated list of source providers to connect. |
| `kafka_records_send_limit` | int | 1000 | Number of records to send in a single request. |
| `kafka_records_size_limit` | int (bytes) | 5000000 (= 5 MB)| Maximum size to read for a single request. |
| `kafka_upload_rate` | int (s) | 50 | Rate after which to send data. In addition, after every `kafka_upload_rate` divided by 5 seconds, if more than `kafka_records_send_limit` are in the buffer, these are sent immediately. |
| `database_commit_rate` | int (ms) | 10000 (= 10 seconds) | Rate of committing new data to disk. If the application crashes, at most this interval of data will be lost. |
| `sender_connection_timeout` | int (s) | 120 | HTTP timeout setting for data uploading. |
| `kafka_upload_minimum_battery_level` | int (s) | 0.1 (= 10%) | Battery level percentage below which to stop sending data. Data will still be collected. |
| `max_cache_size_bytes` | long (byte) | 450000000 | Maximum number of bytes per topic to store. |
| `send_only_with_wifi` | boolean | `true` | Whether to send only when WiFi is connected. If false, for example LTE would also be used. |
| `send_over_data_high_priority_only` | boolean | `true` | Only the data of high priority topics will be sent over LTE. Only used if `send_only_with_wifi` is set to `true`. High priority topics are determined by the `topics_high_priority` property. |
| `topics_high_priority` | string | `<empty>` | A comma separated list of topics that should be considered high priority. |
| `send_with_compression` | boolean | `true` | Send data with GZIP compression. This requires RADAR-Gateway to be installed in front of the Kafka REST Proxy. |
| `firebase_fetch_timeout_ms` | long (ms) | 43200000 (= 12 hours) | Interval for fetching new Firebase configuration if the app is not active. |
| `send_binary_data` | boolean | `true` | Send data using a binary protocol. If the server does not support it, the app will fall back to regular JSON protocol. |

## Usage

Include this repository by adding the following snippet to your Android `build.gradle` file:

```gradle
repositories {
    mavenCentral()
}
dependencies {
    api 'org.radarbase:radar-commons-android:1.1.0'
}
```

Include additional plugins by adding:

```gradle
dependencies {
    implementation 'org.radarbase:<plugin name>:1.1.0'
}
```

### Creating a plugin

To add source types to the passive remote monitoring Android app, create a plugin using the following steps:

First, create an Android Library project. Include RADAR Commons Android as a module in `build.gradle`.

1. Add the schemas of the data you intend to send to the [RADAR-base Schemas repository](https://github.com/RADAR-base/RADAR-Schemas). Your record keys should be `org.radarcns.kafka.ObservationKey`. Be sure to set the `namespace` property to `org.radarcns.mydevicetype` so that generated classes will be put in the right package. All values should have `time` and `timeReceived` fields, with type `double`. These represent the time in seconds since the Unix Epoch (1 January 1970, 00:00:00 UTC). Subsecond precision is possible by using floating point decimals. Until those schemas are published, generate them using Avro tools. Find `avro-tools-1.8.2.jar` by going to <http://www.apache.org/dyn/closer.cgi/avro/>, choosing a mirror, and then downloading `avro-1.8.2/java/avro-tools-1.8.2.jar`. You can now generate source code for a schema `myschema.avsc` with the following command:
    ```shell
    java -jar avro-tools-1.8.2.jar compile -string schema path/to/myschema.avsc path/to/plugin/src/main/java
    ```
    Once the schemas are published as part of the central schemas repository, you can remove the generated files again. Do not publish a non-alpha version of your plugin without the central schemas being published, otherwise there may be class conflicts later on.
2. Create a new package `org.radarbase.passive.mydevicetype`. In that package, create classes that:
    - implement `org.radarbase.android.source.SourceManager` to connect to a device and collect its data, register all Kafka topics in its constructor.
    - if needed, implement a custom `org.radarbase.android.source.SourceState` to keep the current state of the device, or simply use `org.radarbase.android.source.BaseSourceState`.
    - subclass `org.radarbase.android.source.SourceService` to run the device manager in.
    - subclass a `org.radarbase.android.source.SourceProvider` that exposes the new service.
3. Add a new service element to `AndroidManifest.xml`, referencing the newly created device service. Also add all the required permissions there. Set all features as optional.
4. Add the `SourceProvider` you just created to the `plugins` property in `app/src/main/res/xml/remote_config_defaults.xml`.

Publish the resulting data on a maven repository. The plugin can also be created in the `plugins` directory. In that case, open a pull request so we can review the plugin to be added to the set of commons plugins.

### Creating an application

To create an Android App, follow the following steps:

1. Include this module in the Gradle dependencies, and also all plugins that you would like to use
2. Update your code with
     - A main activity that extends `org.radarbase.android.MainActivity`.
     - A main activity view that extends `org.radarbase.android.MainActivityView`. This should reference a layout and update its values based on the services that are connected to the main activity.
     - An application class that implements `org.radarbase.android.RadarApplication` or extends `org.radarbase.android.AbstractRadarApplication`.
     - A service class that extends `org.radarbase.android.RadarService`. Add all plugins that should be enabled in the app to the `plugins` value.
     - A service class that extends `org.radarbase.android.auth.AuthService`. Implement any `org.radarbase.android.auth.LoginManager` classes to be able to log in.
     - A login activity that extends `org.radarbase.android.auth.LoginActivity`.
     - An activity that extends `org.radarbase.android.splash.SplashActivity` and initializes the app. 
     - If wanted, create a `BroadcastListener` that listens to the `ACTION_BOOT_COMPLETED` event and starts your `SplashActivity` subclass. Configure it with the `MainActivity.configureRunAtBoot(Class)` method.
3. In `AndroidManifest.xml`, add your application and service. If wanted add your boot-listener to listen to `ACTION_BOOT_COMPLETED` events and set `enabled` to `false`.
4. Copy `src/main/res/xml/remote_config_defaults_template.xml` to `app/src/main/res/xml/remote_config_defaults.xml` and insert all needed values there.

## Contributing

For latest code use `dev` branch. Code should be formatted using the [Google Java Code Style Guide](https://google.github.io/styleguide/javaguide.html), except using 4 spaces as indentation.
To only build plugins that are of interest to you, add a `gradle.skip` file in all plugin directories that should not be built.

If you want to contribute a feature or fix browse our [issues](https://github.com/RADAR-base/radar-commons-android/issues), and please make a pull request.

## Licensing

Code made in the RADAR-base platform is licensed under the Apache License 2.0, as listed in see the LICENSE file in this directory. Plugins that have additional licensing requirements list them in their README.
