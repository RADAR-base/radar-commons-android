# Garmin plugin for RADAR-pRMT

Application to be run on an Android 5.0 (or later) device with Bluetooth Low Energy (Bluetooth 4.0 or later), to interact with the Garmin.

## Installation

Garmin plugin for Radar platform is developed using Companion SDK (CSDK) from Garmin.  Overview of the SDK is available on Garmin site here - https://developer.garmin.com/health-sdk/overview/

To create Garmin plugin below steps should be followed: 

1. Pull RADAR commons repo.
2. Now download the Companion SDK (CSDK) folder provided by Garmin. You will have to contact Garmin and request for Companion SDK (CSDK). CSDK can be requested from Garmin using - https://www.garmin.com/en-US/forms/wellnesspartner/
4. The CSDK folder will have sample app in it.
5. Copy the below mentioned folders from sample app and paste it under  path Radar-base-commons/plugins/radar-android-garmin/src/main/java/org/radarbase/garmin:
            a. adapters
            b. devices
            c. pairing
            d. ui
            e. util
            f. utils
6. Copy MainActivity and move it in ui folder.
7. Now, uncomment the commented line from Garmin plugins manifest file.

```
<activity android:name=".ui.MainActivity" />
<activity android:name=".ui.DataDisplayActivity" />
```

8. uncomment the overridden action method from GarminProvider class.

```
override val actions: List<Action>
get() = listOf(Action(radarService.getString(R.string.pair)) {
startActivity(Intent(this, MainActivity::class.java))
})
```
9. Uncomment the below statement from start method of GarminManager class.

```
RealTimeDataHandler.getInstance().registerListenerForHealthData(this)
```
10. Modify RealTimeDataHandler class

```
public class RealTimeDataHandler implements RealTimeDataListener {

private static final String TAG = RealTimeDataHandler.class.getSimpleName();

private static RealTimeDataHandler sInstance;

private static Device device;


private final HashMap<String, HashMap<RealTimeDataType, RealTimeResult>> mLatestData;


public synchronized static RealTimeDataHandler getInstance() {
if (sInstance == null) {
sInstance = new RealTimeDataHandler();
}
return sInstance;
}

private static OnHealthDataReceieved healthDataRecieved;

private RealTimeDataHandler() {
mLatestData = new HashMap<>();
}

public HashMap<RealTimeDataType, RealTimeResult> getLatestData(String deviceAddress) {
return mLatestData.get(deviceAddress);
}

public void registerListenerForHealthData(OnHealthDataReceieved healthDataRecieved) {
RealTimeDataHandler.healthDataRecieved = healthDataRecieved;
}

@SuppressLint("DefaultLocale")
public static void logRealTimeData(String tag, String macAddress, RealTimeDataType dataType, RealTimeResult result) {
//Log out the main value for each data type
if(healthDataRecieved!=null)
{
switch (dataType) {
case STEPS:
healthDataRecieved.stepsReceived(result.getSteps());
break;
case HEART_RATE_VARIABILITY:
healthDataRecieved.heartRateVariabilityReceived(result.getHeartRateVariability());
break;
case CALORIES:
healthDataRecieved.calorieRecieved(result.getCalories());
break;
case ASCENT:
healthDataRecieved.ascentDataReceived(result.getAscent());
break;
case INTENSITY_MINUTES:
healthDataRecieved.intensityMinutesReceived(result.getIntensityMinutes());
break;
case HEART_RATE:
healthDataRecieved.heartRateReceived(result.getHeartRate());
break;
case STRESS:
healthDataRecieved.stressReceived(result.getStress());
break;
case ACCELEROMETER:
healthDataRecieved.accelerometerReceived(result.getAccelerometer());
break;
case SPO2:
healthDataRecieved.spo2Received(result.getSpo2());
break;
case RESPIRATION:
healthDataRecieved.respirationReceived(result.getRespiration());
break;
}
}

}

public void setDevice(Device device) {
RealTimeDataHandler.device = device;
if(healthDataRecieved!=null)
healthDataRecieved.deviceInfoDetailsReceived(device);
}

@Override
public void onDataUpdate(@NonNull String macAddress, @NonNull RealTimeDataType dataType, @NonNull RealTimeResult result) {
logRealTimeData(TAG, macAddress, dataType, result);

//Cache last received data of each type
//Used to display values if device loses connection
HashMap<RealTimeDataType, RealTimeResult> latestData = mLatestData.get(macAddress);
if (latestData == null) {
latestData = new HashMap<>();
mLatestData.put(macAddress, latestData);
}
latestData.put(dataType, result);
}
}

```



