# RADAR-Commons-Android

[![Build Status](https://travis-ci.org/RADAR-CNS/radar-commons-android.svg?branch=master)](https://travis-ci.org/RADAR-CNS/radar-commons-android)

Base module for the RADAR passive remote monitoring app. Plugins for that app should implement the API from this base library. Also user interfaces should use this as a base. Currently, the main user interface is [RADAR-AndroidApplication](https://github.com/RADAR-CNS/RADAR-AndroidApplication.git).

## Configuration

This library takes the following firebase parameters:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `kafka_rest_proxy_url` | URL | `<empty>` | URL of a Kafka REST Proxy or RADAR-Gateway to send data to. |
| `schema_registry_url` | URL | `<empty>` | URL of a Kafka Schema Registry to sync schemas with. |
| `management_portal_url` | URL | `<empty>` | URL of the RADAR Management Portal. If empty, the Management Portal will not be used. |
| `unsafe_kafka_connection` | boolean | `false` | Whether to accept unsafe HTTPS certificates. Only meant to be set to `true` in development environments. |
| `device_services_to_connect` | string | `<empty>` | A space-separated list of device service providers to connect. The `org.radarcns` prefix may be excluded. |
| `kafka_records_send_limit` | int | 1000 | Number of records to send in a single request. |
| `kafka_upload_rate` | int (s) | 50 | Rate after which to send data. In addition, after every `kafka_upload_rate` divided by 5 seconds, if more than `kafka_records_send_limit` are in the buffer, these are sent immediately. |
| `database_commit_rate` | int (ms) | 10000 (= 10 seconds) | Rate of committing new data to disk. If the application crashes, at most this interval of data will be lost. |
| `sender_connection_timeout` | int (s) | 120 | HTTP timeout setting for data uploading. |
| `kafka_upload_minimum_battery_level` | int (s) | 0.1 (= 10%) | Battery level percentage below which to stop sending data. Data will still be collected. |
| `max_cache_size_bytes` | long (byte) | 450000000 | Maximum number of bytes per topic to store. |
| `send_only_with_wifi` | boolean | `true` | Whether to send only when WiFi is connected. If false, for example LTE would also be used. |
| `send_with_compression` | boolean | `true` | Send data with GZIP compression. This requires RADAR-Gateway to be installed in front of the Kafka REST Proxy. |
| `firebase_fetch_timeout_ms` | long (ms) | 43200000 (= 12 hours) | Interval for fetching new Firebase configuration if the app is not active. |
| `send_over_data_high_priority_only` | boolean | `true` | the data of high priority topics will be sent only if LTE is used. |
| `firebase_fetch_timeout_ms` | string | <empty> |  A comma separated lists the topics that should be considered high priority. |

## Usage

Include this repository by adding the following snippet to your Android `build.gradle` file:
```gradle
repositories {
    jcenter()
}
dependencies {
    api 'org.radarcns:radar-commons-android:0.6.2'
}
```

### Creating a plugin

To add device types to the passive remote monitoring Android app, create a plugin using the following steps (see the [RADAR-Android-Pebble repository](https://github.com/RADAR-CNS/RADAR-Android-Pebble.git) as an example):

First, create an Android Library project. Include RADAR Commons Android as a module in `build.gradle`.

1. Add the schemas of the data you intend to send to the [RADAR-CNS Schemas repository](https://github.com/RADAR-CNS/RADAR-Schemas). Your record keys should be `org.radarcns.kafka.ObservationKey`. Be sure to set the `namespace` property to `org.radarcns.mydevicetype` so that generated classes will be put in the right package. All values should have `time` and `timeReceived` fields, with type `double`. These represent the time in seconds since the Unix Epoch (1 January 1970, 00:00:00 UTC). Subsecond precision is possible by using floating point decimals. Until those schemas are published, generate them using Avro tools. Find `avro-tools-1.8.2.jar` by going to <http://www.apache.org/dyn/closer.cgi/avro/>, choosing a mirror, and then downloading `avro-1.8.2/java/avro-tools-1.8.2.jar`. You can now generate source code for a schema `myschema.avsc` with the following command:
    ```shell
    java -jar avro-tools-1.8.2.jar compile -string schema path/to/myschema.avsc path/to/plugin/src/main/java
    ```
    Once the schemas are published as part of the central schemas repository, you can remove the generated files again. Do not publish a non-alpha version of your plugin without the central schemas being published, otherwise there may be class conflicts later on.
2. Create a new package `org.radarcns.mydevicetype`. In that package, create classes that:
    - implement `org.radarcns.android.device.DeviceManager` to connect to a device and collect its data, register all Kafka topics in its constructor.
    - implement `org.radarcns.android.DeviceState` to keep the current state of the device or simply use `org.radarcns.android.BaseDeviceState`.
    - subclass `org.radarcns.android.device.DeviceService` to run the device manager in.
    - subclass a `org.radarcns.android.device.DeviceServiceProvider` that exposes the new service.
3. Add a new service element to `AndroidManifest.xml`, referencing the newly created device service. Also add all the required permissions there.
4. Add the `DeviceServiceProvider` you just created to the `device_services_to_connect` property in `app/src/main/res/xml/remote_config_defaults.xml`.

Make a pull request once the code is working.

### Creating an application

To create an Android App, follow the following steps:


1. Include this module in the Gradle dependencies, and also all plugins that you would like to use
2. Update your code with
     - A main activity that extends `org.radarcns.android.MainActivity`.
     - A main activity view that extends `org.radarcns.android.MainActivityView`. This should reference a layout and update its values based on the services that are connected to the main activity.
     - An application class that extends `org.radarcns.android.RadarApplication`.
     - A login activity that extends `org.radarcns.android.auth.LoginActivity`. Implement any `org.radarncs.android.auth.LoginManager` classes to be able to log in.
     - If wanted, create a `BroadcastListener` that listens to the `ACTION_BOOT_COMPLETED` event and starts your `MainActivity` subclass. Configure it with the `MainActivity.configureRunAtBoot(Class)` method.
3. In `AndroidManifest.xml`, add your application and service. If wanted add your boot-listener to listen to `ACTION_BOOT_COMPLETED` events and set `enabled` to `false`.
4. Copy `src/main/res/xml/remote_config_defaults_template.xml` to `app/src/main/res/xml/remote_config_defaults.xml` and insert all needed values there.

## Contributing

For latest code use `dev` branch. Code should be formatted using the [Google Java Code Style Guide](https://google.github.io/styleguide/javaguide.html), except using 4 spaces as indentation.

If you want to contribute a feature or fix browse our [issues](https://github.com/RADAR-CNS/RADAR-Commons-Android/issues), and please make a pull request.
