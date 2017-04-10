# RADAR-Commons-Android

[![Build Status](https://travis-ci.org/RADAR-CNS/RADAR-Commons-Android.svg?branch=master)](https://travis-ci.org/RADAR-CNS/RADAR-Commons-Android)

Base module for the RADAR passive remote monitoring app. Modules for that app should implement the API from this base library. Also user interfaces should use this as a base.

## Contributing

To add additional device types to this application, make the following steps (see the `org.radarcns.pebble2` package in the [RADAR-AndroidApplication repository](https://github.com/RADAR-CNS/RADAR-AndroidApplication.git) as an example):

- Add the schemas of the data you intend to send to the [RADAR-CNS Schemas repository](https://github.com/RADAR-CNS/RADAR-Schemas). Your record keys should be `org.radarcns.key.MeasurementKey`. Be sure to set the `namespace` property to `org.radarcns.mydevicetype` so that generated classes will be put in the right package. All values should have `time` and `timeReceived` fields, with type `double`. These represent the time in seconds since the Unix Epoch (1 January 1970, 00:00:00 UTC). Subsecond precision is possible by using floating point decimals.
- Create a new package `org.radarcns.mydevicetype`. In that package, create classes that:
  - implement `org.radarcns.android.device.DeviceManager` to connect to a device and collect its data.
  - implement `org.radarcns.android.DeviceState` to keep the current state of the device.
  - subclass `org.radarcns.android.device.DeviceService` to run the device manager in.
  - subclass a singleton `org.radarcns.android.device.DeviceTopics` that contains all Kafka topics that the wearable will generate.
  - subclass a `org.radarcns.android.device.DeviceServiceProvider` that exposes the new service.
- Add a new service element to `AndroidManifest.xml`, referencing the newly created device service.
- Add the `DeviceServiceProvider` you just created to the `device_services_to_connect` property in `app/src/main/res/xml/remote_config_defaults.xml`.

Make a pull request once the code is working.
