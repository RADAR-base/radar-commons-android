package org.radarcns.android.device;

import android.support.annotation.NonNull;

import com.crashlytics.android.Crashlytics;

import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.RadarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

public class ProviderLoader {
    private static final Logger logger = LoggerFactory.getLogger(ProviderLoader.class);

    private String previousDeviceServices;
    private List<DeviceServiceProvider<?>> previousProviders;

    public ProviderLoader() {
        previousDeviceServices = null;
        previousProviders = null;
    }

    /**
     * Loads the service providers specified in the
     * {@link RadarConfiguration#DEVICE_SERVICES_TO_CONNECT}. This function will call
     * {@link DeviceServiceProvider#setRadarService(RadarService)} and {@link DeviceServiceProvider#setConfig(RadarConfiguration)} on each of the
     * loaded service providers.
     */
    public synchronized List<DeviceServiceProvider<?>> loadProviders(@NonNull RadarService context,
            @NonNull RadarConfiguration config) {
        String deviceServices = config.getString(RadarConfiguration.DEVICE_SERVICES_TO_CONNECT);
        if (Objects.equals(previousDeviceServices, deviceServices)) {
            return previousProviders;
        }
        List<DeviceServiceProvider<?>> providers = loadProviders(deviceServices);
        for (DeviceServiceProvider provider : providers) {
            provider.setRadarService(context);
            provider.setConfig(config);
        }
        previousDeviceServices = deviceServices;
        previousProviders = providers;

        return providers;
    }

    /**
     * Loads the service providers specified in given whitespace-delimited String.
     */
    private List<DeviceServiceProvider<?>> loadProviders(@NonNull String deviceServices) {
        List<DeviceServiceProvider<?>> providers = new ArrayList<>();
        Scanner scanner = new Scanner(deviceServices);
        while (scanner.hasNext()) {
            String className = scanner.next();
            if (className.charAt(0) == '.') {
                className = "org.radarcns" + className;
            }
            try {
                Class<?> providerClass = Class.forName(className);
                DeviceServiceProvider<?> serviceProvider = (DeviceServiceProvider<?>) providerClass.newInstance();
                providers.add(serviceProvider);
            } catch (ClassNotFoundException | ClassCastException | InstantiationException
                    | IllegalAccessException ex) {
                logger.warn("Provider {} is not a legal DeviceServiceProvider: {}", className,
                        ex.toString());
                Crashlytics.logException(ex);
            }
        }
        return providers;
    }
}
