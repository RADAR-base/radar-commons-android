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

package org.radarcns.android.device;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;

import com.crashlytics.android.Crashlytics;

import org.radarcns.android.MainActivity;
import org.radarcns.android.RadarApplication;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.RadarService;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

import static android.Manifest.permission.BLUETOOTH;
import static android.Manifest.permission.BLUETOOTH_ADMIN;
import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.RadarConfiguration.PROJECT_ID_KEY;
import static org.radarcns.android.RadarConfiguration.RADAR_PREFIX;
import static org.radarcns.android.RadarConfiguration.USER_ID_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_CLEAN_RATE_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_RECORDS_SEND_LIMIT_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_REST_PROXY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_UPLOAD_RATE_KEY;
import static org.radarcns.android.RadarConfiguration.MAX_CACHE_SIZE;
import static org.radarcns.android.RadarConfiguration.SCHEMA_REGISTRY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.SENDER_CONNECTION_TIMEOUT_KEY;
import static org.radarcns.android.RadarConfiguration.SEND_ONLY_WITH_WIFI;
import static org.radarcns.android.RadarConfiguration.SEND_WITH_COMPRESSION;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;

/**
 * RADAR service provider, to bind and configure to a service. It is not thread-safe.
 * @param <T> state that the Service will provide.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public abstract class DeviceServiceProvider<T extends BaseDeviceState> {
    public static final String NEEDS_BLUETOOTH_KEY = DeviceServiceProvider.class.getName() + ".needsBluetooth";
    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceProvider.class);
    public static final String SOURCE_KEY = DeviceServiceProvider.class.getName() + ".source";

    private RadarService radarService;
    private RadarConfiguration config;
    private DeviceServiceConnection<T> connection;
    private boolean bound;
    private AppSource source;

    /**
     * Class of the service.
     * @return non-null DeviceService
     */
    public abstract Class<?> getServiceClass();

    /**
     * Creator for a device state.
     * @return non-null state creator.
     * @deprecated state creators are no longer used
     */
    @Deprecated
    public Parcelable.Creator<T> getStateCreator() { return null; }

    /** Display name of the service. */
    public abstract String getDisplayName();

    /**
     * Image to display when onboarding for this service.
     * @return resource number or -1 if none is available.
     */
    public int getDescriptionImage() {
        return -1;
    }

    /**
     * Description of the service. This should tell what the service does and why certain
     * permissions are needed.
     * @return description or {@code null} if no description is needed.
     */
    public String getDescription() {
        return null;
    }

    /**
     * Whether the service has a UI detail view that can be invoked. If not,
     * {@link #showDetailView()} will throw an UnsupportedOperationException.
     */
    public boolean hasDetailView() {
        return false;
    }

    /**
     * Show a detail view from the MainActivity.
     * @throws UnsupportedOperationException if {@link #hasDetailView()} is false.
     */
    public void showDetailView() {
        throw new UnsupportedOperationException();
    }

    public DeviceServiceProvider() {
        bound = false;
    }

    /**
     * Get or create a DeviceServiceConnection. Once created, it will be a single fixed connection
     * object.
     * @throws IllegalStateException if {@link #setRadarService(RadarService)} has not been called.
     * @throws UnsupportedOperationException if {@link #getServiceClass()} returns null.
     */
    public DeviceServiceConnection<T> getConnection() {
        if (connection == null) {
            if (radarService == null) {
                throw new IllegalStateException("#setRadarService(RadarService) needs to be set before #getConnection() is called.");
            }
            Class<?> serviceClass = getServiceClass();
            if (serviceClass == null) {
                throw new UnsupportedOperationException("RadarServiceProvider " + getClass().getSimpleName() + " does not provide service class");
            }
            connection = new DeviceServiceConnection<>(radarService, serviceClass.getName());
        }
        return connection;
    }

    /**
     * Bind the service to the MainActivity. Call this when the {@link MainActivity#onStart()} is
     * called.
     * @throws IllegalStateException if {@link #setRadarService(RadarService)} and
     *                               {@link #setConfig(RadarConfiguration)} have not been called or
     *                               if the service is already bound.
     */
    public void bind() {
        if (radarService == null) {
            throw new IllegalStateException(
                    "#setRadarService(RadarService) needs to be set before #bind() is called.");
        }
        if (config == null) {
            throw new IllegalStateException(
                    "#setConfig(RadarConfiguration) needs to be set before #bind() is called.");
        }
        if (bound) {
            throw new IllegalStateException("Service is already bound");
        }
        logger.info("Binding {}", this);
        Intent intent = new Intent(radarService, getServiceClass());
        Bundle extras = new Bundle();
        configure(extras);
        intent.putExtras(extras);

        radarService.startService(intent);
        radarService.bindService(intent, getConnection(), Context.BIND_ABOVE_CLIENT);

        bound = true;
    }

    /**
     * Unbind the service from the MainActivity. Call this when the {@link MainActivity#onStop()} is
     * called.
     */
    public void unbind() {
        if (radarService == null) {
            throw new IllegalStateException("#setRadarService(RadarService) needs to be set before #unbind() is called.");
        }
        if (!bound) {
            throw new IllegalStateException("Service is not bound");
        }
        logger.info("Unbinding {}", this);
        bound = false;
        radarService.unbindService(connection);
        connection.onServiceDisconnected(null);
    }

    /**
     * Update the configuration of the service based on the given RadarConfiguration.
     * @throws IllegalStateException if {@link #getConnection()} has not been called
     *                               yet.
     */
    public void updateConfiguration() {
        if (config == null) {
            throw new IllegalStateException("#setConfig(RadarConfiguration) needs to be set before #bind() is called.");
        }
        if (connection == null) {
            throw new IllegalStateException("#getConnection() has not yet been called.");
        }
        if (connection.hasService()) {
            Bundle bundle = new Bundle();
            configure(bundle);
            connection.updateConfiguration(bundle);
        }
    }

    /**
     * Configure the service from the set RadarConfiguration.
     */
    @CallSuper
    protected void configure(Bundle bundle) {
        // Add the default configuration parameters given to the service intents
        config.putExtras(bundle,
                KAFKA_REST_PROXY_URL_KEY, SCHEMA_REGISTRY_URL_KEY, PROJECT_ID_KEY, USER_ID_KEY,
                KAFKA_UPLOAD_RATE_KEY, KAFKA_CLEAN_RATE_KEY, KAFKA_RECORDS_SEND_LIMIT_KEY,
                SENDER_CONNECTION_TIMEOUT_KEY, MAX_CACHE_SIZE, SEND_ONLY_WITH_WIFI,
                SEND_WITH_COMPRESSION, UNSAFE_KAFKA_CONNECTION);
        String mpUrl = config.getString(MANAGEMENT_PORTAL_URL_KEY, null);
        if (mpUrl != null && !mpUrl.isEmpty()) {
            bundle.putString(RADAR_PREFIX + MANAGEMENT_PORTAL_URL_KEY, mpUrl);
        }
        ((RadarApplication)radarService.getApplicationContext()).configureProvider(config, bundle);
        List<String> permissions = needsPermissions();
        bundle.putBoolean(NEEDS_BLUETOOTH_KEY, permissions.contains(BLUETOOTH) ||
                permissions.contains(BLUETOOTH_ADMIN));
        AppAuthState.Builder.from(radarService).build().addToBundle(bundle);
        bundle.putParcelable(SOURCE_KEY, source);
    }

    /**
     * Loads the service providers specified in the
     * {@link RadarConfiguration#DEVICE_SERVICES_TO_CONNECT}. This function will call
     * {@link #setRadarService(RadarService)} and {@link #setConfig(RadarConfiguration)} on each of the
     * loaded service providers.
     */
    public static List<DeviceServiceProvider> loadProviders(@NonNull RadarService context,
                                                            @NonNull RadarConfiguration config) {
        List<DeviceServiceProvider> providers = loadProviders(config.getString(RadarConfiguration.DEVICE_SERVICES_TO_CONNECT));
        for (DeviceServiceProvider provider : providers) {
            provider.setRadarService(context);
            provider.setConfig(config);
        }
        return providers;
    }

    /**
     * Loads the service providers specified in given whitespace-delimited String.
     */
    public static List<DeviceServiceProvider> loadProviders(@NonNull String deviceServicesToConnect) {
        List<DeviceServiceProvider> providers = new ArrayList<>();
        Scanner scanner = new Scanner(deviceServicesToConnect);
        while (scanner.hasNext()) {
            String className = scanner.next();
            if (className.charAt(0) == '.') {
                className = "org.radarcns" + className;
            }
            try {
                Class<?> providerClass = Class.forName(className);
                DeviceServiceProvider serviceProvider = (DeviceServiceProvider) providerClass.newInstance();
                providers.add(serviceProvider);
            } catch (ClassNotFoundException | ClassCastException | InstantiationException
                    | IllegalAccessException ex) {
                logger.warn("Provider {} is not a legal DeviceServiceProvider", className, ex);
                Crashlytics.logException(ex);
            }
        }
        return providers;
    }

    /** Get the MainActivity associated to the current connection. */
    public RadarService getRadarService() {
        return this.radarService;
    }

    /**
     * Associate a MainActivity with a new connection.
     * @throws NullPointerException if given context is null
     * @throws IllegalStateException if the connection has already been started.
     */
    public void setRadarService(@NonNull RadarService radarService) {
        if (this.connection != null) {
            throw new IllegalStateException(
                    "Cannot change the RadarService after a connection has been started.");
        }
        Objects.requireNonNull(radarService);
        this.radarService = radarService;
    }

    /** Get the RadarConfiguration currently set for the service provider. */
    public RadarConfiguration getConfig() {
        return config;
    }

    /** Whether {@link #getConnection()} has already been called. */
    public boolean hasConnection() {
        return connection != null;
    }

    /** Set the config. To update a running service with given config, call
     * {@link #updateConfiguration()}.
     * @throws NullPointerException if the configuration is null.
     */
    public void setConfig(@NonNull RadarConfiguration config) {
        Objects.requireNonNull(config);
        this.config = config;
    }

    /**
     * Whether {@link #bind()} has been called and {@link #unbind()} has not been called since then.
     * @return true if bound, false otherwise
     */
    public boolean isBound() {
        return bound;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "<" + getServiceClass().getSimpleName()  + ">";
    }

    /** Whether the current service can meaningfully be displayed. */
    public boolean isDisplayable() {
        return true;
    }

    /**
     * Whether the device name should be checked with given filters before a connection is allowed
     */
    public boolean isFilterable() { return false; }

    /**
     * Android permissions that the underlying service needs to function correctly.
     */
    public abstract List<String> needsPermissions();

    /**
     * Match device type.
     *
     * @param source stored source
     * @param checkVersion whether to do a strict version check
     */
    public boolean matches(AppSource source, boolean checkVersion) {
        return source.getSourceTypeProducer().equalsIgnoreCase(getDeviceProducer())
                && source.getSourceTypeModel().equalsIgnoreCase(getDeviceModel())
                && !checkVersion
                || source.getSourceTypeCatalogVersion() == null
                || source.getSourceTypeCatalogVersion().equalsIgnoreCase(getVersion());
    }

    public void setSource(AppSource source) {
        this.source = source;
    }

    @NonNull
    public abstract String getDeviceProducer();

    @NonNull
    public abstract String getDeviceModel();

    @NonNull
    public abstract String getVersion();

    public AppSource getSource() {
        return source;
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass() == getClass();
    }
}
