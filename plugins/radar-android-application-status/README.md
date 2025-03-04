# Application status plugin for RADAR-pRMT

[![Build Status](https://travis-ci.org/RADAR-base/radar-android-application-status.svg?branch=master)](https://travis-ci.org/RADAR-base/radar-android-application-status)

Plugin that sends application statuses about the RADAR pRMT app.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-application-status:$radarCommonsAndroidVersion"
}
```

Add `org.radarbase.monitor.application.ApplicationStatusProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To activate this plugin, add the provider `application_status` to the Firebase Remote Config `plugins` variable.

This plugin takes the following Firebase configuration parameters:

| Name                                           | Type          | Default                 | Description                                                                                                                                              |
|------------------------------------------------|---------------|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ntp_server`                                   | string        | `<empty>`               | NTP server to synchronize time with. If empty, time is not synchronized and the `application_external_time` topic will not receive data.                 |
| `application_status_update_rate`               | int (seconds) | `300` = 5 minutes       | Rate at which to send data for all application topics.                                                                                                   |
| `application_send_ip`                          | boolean       | `false`                 | Whether to send the device IP address with the server status.                                                                                            |
| `application_time_zone_update_rate`            | int (seconds) | `86400` = 1 day         | How often to send the current time zone. Set to `0` to disable.                                                                                          |
| `application_metrics_verification_update_rate` | int (seconds) | `300` = 5 minutes       | Defines the interval for verifying that records older than application_metrics_retention_time are not present. Set to 0 to disable.                      |
| `application_metrics_buffer_size`              | int (count)   | `100`                   | Specifies the number of records to keep in the buffer before adding them to the database. Storing records in batches significantly improves performance. |
| `application_metrics_retention_time`           | int (seconds) | `7*86400` = 7 day       | Determines how long application metrics are retained in the database. Records older than this value are automatically deleted.                           |
| `application_metrics_data_retention_count`     | int (seconds) | `10000` = 10000 records | Specifies the maximum number of messages to retain for application metrics in the database. When this limit is exceeded, older messages are deleted.     |

This plugin produces data for the following topics: (types starts with `org.radarcns.monitor.application` prefix)

| Topic                            | Type                          | Description                                                                                                                                                                        |
|----------------------------------|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `application_external_time`      | `ApplicationExternalTime`     | External NTP time. Requires `ntp_server` parameter to be set.                                                                                                                      |
| `application_record_counts`      | `ApplicationRecordCounts`     | Number of records sent and in queue.                                                                                                                                               |
| `application_uptime`             | `ApplicationUptime`           | Time since the device booted.                                                                                                                                                      |
| `application_server_status`      | `ApplicationServerStatus`     | Server connection status.                                                                                                                                                          |
| `application_time_zone`          | `ApplicationTimeZone`         | Application time zone. Data is only sent on updates.                                                                                                                               |
| `application_device_info`        | `ApplicationDeviceInfo`       | Device information. Data is only sent on updates.                                                                                                                                  |
| `application_network_status`     | `ApplicationNetworkStatus`    | Represents the network connectivity status of an application, indicating whether it is connected to the internet and, if so, whether the connection is via Wi-Fi or cellular data. |
| `application_plugin_status`      | `ApplicationPluginStatus`     | Represents the status of a plugin, indicating its current connection state.                                                                                                        |
| `application_topic_records_sent` | `ApplicationTopicRecordsSent` | Number of records sent for the topic since the last upload                                                                                                                         |
