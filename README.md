# RADAR-Commons-Android

[![Build Status](https://travis-ci.org/RADAR-CNS/RADAR-Commons-Android.svg?branch=master)](https://travis-ci.org/RADAR-CNS/RADAR-Commons-Android)

Base module for the RADAR passive remote monitoring app. Plugins for that app should implement the API from this base library. Also user interfaces should use this as a base. Currently, the main user interface is [RADAR-AndroidApplication](https://github.com/RADAR-CNS/RADAR-AndroidApplication.git).

## Creating a plugin

To add device types to the passive remote monitoring Android app, create a plugin using the following steps (see the [RADAR-Android-Pebble repository](https://github.com/RADAR-CNS/RADAR-Android-Pebble.git) as an example):

1. Add the schemas of the data you intend to send to the [RADAR-CNS Schemas repository](https://github.com/RADAR-CNS/RADAR-Schemas). Your record keys should be `org.radarcns.key.MeasurementKey`. Be sure to set the `namespace` property to `org.radarcns.mydevicetype` so that generated classes will be put in the right package. All values should have `time` and `timeReceived` fields, with type `double`. These represent the time in seconds since the Unix Epoch (1 January 1970, 00:00:00 UTC). Subsecond precision is possible by using floating point decimals. Until those schemas are published, generate them using Avro tools. Find `avro-tools-1.8.1.jar` by going to <http://www.apache.org/dyn/closer.cgi/avro/>, choosing a mirror, and then downloading `avro-1.8.1/java/avro-tools-1.8.1.jar`. You can now generate source code for a schema `myschema.avsc` with the following command:
    ```shell
    java -jar avro-tools-1.8.1.jar compile schema path/to/myschema.avsc path/to/plugin/src/main/java
    ```
    Once the schemas are published as part of the central schemas repository, you can remove the generated files again. Do not publish a non-alpha version of your plugin without the central schemas being published, otherwise there may be class conflicts later on.
2. Create a new package `org.radarcns.mydevicetype`. In that package, create classes that:
  - implement `org.radarcns.android.device.DeviceManager` to connect to a device and collect its data.
  - implement `org.radarcns.android.DeviceState` to keep the current state of the device.
  - subclass `org.radarcns.android.device.DeviceService` to run the device manager in.
  - subclass a singleton `org.radarcns.android.device.DeviceTopics` that contains all Kafka topics that the wearable will generate.
  - subclass a `org.radarcns.android.device.DeviceServiceProvider` that exposes the new service.
3. Add a new service element to `AndroidManifest.xml`, referencing the newly created device service. Also add all the required permissions there
4. Add the `DeviceServiceProvider` you just created to the `device_services_to_connect` property in `app/src/main/res/xml/remote_config_defaults.xml`.

Make a pull request once the code is working.

## Creating an application

To create an Android App, follow the following steps:

1. Include this module in the Gradle dependencies, and also all plugins that you would like to use
2. Update your code with
     - A main activity that extends `org.radarcns.android.MainActivity`.
     - A main activity view that extends `org.radarcns.android.MainActivityView`. This should reference a layout and update its values based on the services that are connected to the main activity.
     - An application class that extends `org.radarcns.android.RadarApplication`.
     - If wanted, create a `BroadcastListener` that listens to the `ACTION_BOOT_COMPLETED` event and starts your `MainActivity` subclass. Configure it with the `MainActivity.configureRunAtBoot(Class)` method.
3. In `AndroidManifest.xml`, add your application and service. If wanted add your boot-listener to listen to `ACTION_BOOT_COMPLETED` events and set `enabled` to `false`.
4. Copy `src/main/res/xml/remote_config_defaults_template.xml` to `app/src/main/res/xml/remote_config_defaults.xml` and insert all needed values there.

## Contributing

For latest code use `dev` branch. Code should be formatted using the [Google Java Code Style Guide](https://google.github.io/styleguide/javaguide.html), except using 4 spaces as indentation.

If you want to contribute a feature or fix browse our [issues](https://github.com/RADAR-CNS/RADAR-Commons-Android/issues), and please make a pull request.
