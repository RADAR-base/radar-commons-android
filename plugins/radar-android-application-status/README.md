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

| Name | Type | Default | Description |
| ---- | ---- | ------- | ----------- |
| `ntp_server` | string | `<empty>` | NTP server to synchronize time with. If empty, time is not synchronized and the `application_external_time` topic will not receive data. |
| `application_status_update_rate` | int (seconds) | `300` = 5 minutes | Rate at which to send data for all application topics. |
| `application_send_ip` | boolean | `false` | Whether to send the device IP address with the server status. |
| `application_time_zone_update_rate` | int (seconds) | `86400` = 1 day | How often to send the current time zone. Set to `0` to disable. |

This plugin produces data for the following topics: (types starts with `org.radarcns.monitor.application` prefix)

| Topic | Type | Description |
| ----- | ---- | ----------- |
| `application_external_time` | `ApplicationExternalTime` | External NTP time. Requires `ntp_server` parameter to be set. |
| `application_record_counts` | `ApplicationRecordCounts` | Number of records sent and in queue. |
| `application_uptime` | `ApplicationUptime` | Time since the device booted. |
| `application_server_status` | `ApplicationServerStatus` | Server connection status. |
| `application_time_zone` | `ApplicationTimeZone` | Application time zone. Data is only sent on updates. |
| `application_device_info` | `ApplicationDeviceInfo` | Device information. Data is only sent on updates. |
| `application_topic_records_sent` | `ApplicationTopicRecordsSent` | Number of records sent per topic and their success status. |
| `application_plugin_status` | `ApplicationPluginStatus` | Status of each plugin. |
| `application_error` | `ApplicationError` | Error encountered in the application. |
