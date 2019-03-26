# Telephony metadata for RADAR-pRMT

Plugin that collects telephony metadata, specifically of SMS and call logs. Phone numbers are irreversibly hashed before transmission.

This plugin requires the Android `READ_CALL_LOG` and `READ_SMS` permissions. Per [policy of the Google App Store](https://play.google.com/about/privacy-security-deception/permissions/), including this plugin in an app will prevent the app to be accepted on the Google Play Store. Self-distributing an APK is still possible, or using alternative app stores.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    runtimeOnly "org.radarbase:radar-android-phone-telephony:$radarCommonsAndroidVersion"
}
```

## Configuration

To enable this plugin, add the provider `.passive.phone.telephony.PhoneLogProvider` to `device_services_to_connect` property of the configuration.

The following Firebase parameters are available:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `call_sms_log_interval_seconds` | int (s) | 86400 (= 1 day) | Interval for gathering Android call/sms logs. |

This produces data to the following Kafka topics (all types are prefixed with the `org.radarcns.passive.phone` package).

| Topic | Type |
| ----- | ---- |
| `android_phone_call` | `PhoneCall` |
| `android_phone_sms` | `PhoneSms` |
| `android_phone_sms_unread` | `PhoneSmsUnread` |
