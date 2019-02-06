package org.radarcns.android.auth.portal;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.SparseArray;

import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;

import org.json.JSONException;
import org.radarcns.android.RadarApplication;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AuthStringParser;
import org.radarcns.android.auth.SourceMetadata;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.RadarConfiguration.OAUTH2_CLIENT_ID;
import static org.radarcns.android.RadarConfiguration.OAUTH2_CLIENT_SECRET;
import static org.radarcns.android.RadarConfiguration.RADAR_CONFIGURATION_CHANGED;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

/**
 * Handles intents to register sources to the ManagementPortal.
 *
 * It also keeps a cache of the access token, refresh token and registered sources.
 */
public class ManagementPortalService extends Service {
    public static final int MANAGEMENT_PORTAL_REGISTRATION = 1;
    public static final int MANAGEMENT_PORTAL_REFRESH = 4;
    public static final int MANAGEMENT_PORTAL_REFRESH_FAILED = 5;

    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalService.class);
    public static final String REQUEST_FAILED_REASON = "org.radarcns.android.auth.ManagementPortalService.refreshFailedReason";
    public static final int REQUEST_FAILED_REASON_IO = 1;
    public static final int REQUEST_FAILED_REASON_UNAUTHORIZED = 2;
    public static final int REQUEST_FAILED_REASON_CONFIGURATION = 3;
    public static final int REQUEST_FAILED_REASON_DISCONNECTED = 5;

    private ManagementPortalClient client;
    private SparseArray<SourceMetadata> sources;
    private String clientSecret;
    private String clientId;
    private AppAuthState authState;
    private HandlerThread handlerThread;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("managementPortalService");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        authState = AppAuthState.Builder.from(this).build();
        sources = new SparseArray<>();
        updateSources();
    }

    private void updateSources() {
        for (SourceMetadata source : authState.getSourceMetadata()) {
            if (source.getSourceId() != null) {
                sources.put((int) source.getSourceTypeId(), source);
            }
        }
    }

    private void addSource(SourceMetadata source) {
        sources.put((int) source.getSourceTypeId(), source);
        List<SourceMetadata> existingSources = authState.getSourceMetadata();

        boolean containsSource = false;
        List<SourceMetadata> updatedSources;
        updatedSources = new ArrayList<>(existingSources.size());
        updatedSources.add(source);
        for (SourceMetadata existingSource : existingSources) {
            if (existingSource.getSourceTypeId() != source.getSourceTypeId()) {
                updatedSources.add(existingSource);
            } else if (Objects.equals(source.getSourceId(), existingSource.getSourceId())) {
                containsSource = true;
            }
        }
        authState = authState.newBuilder()
                .sourceMetadata(updatedSources)
                .build();

        if (!containsSource) {
            authState.invalidate(this);
        }
    }

    private void ensureClientConnectivity() throws FirebaseRemoteConfigException, ConnectException {
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connManager != null ? connManager.getActiveNetworkInfo() : null;
        if (networkInfo == null || !networkInfo.isConnected()) {
            throw new ConnectException();
        }
        if (client == null) {
            RadarConfiguration config = ((RadarApplication)getApplication()).getConfiguration();
            String url = config.getString(MANAGEMENT_PORTAL_URL_KEY);
            boolean unsafe = config.getBoolean(UNSAFE_KAFKA_CONNECTION, false);
            try {
                ServerConfig portalConfig = new ServerConfig(url);
                portalConfig.setUnsafe(unsafe);
                client = new ManagementPortalClient(portalConfig);
            } catch (MalformedURLException ex) {
                throw new FirebaseRemoteConfigException();
            }
            clientId = config.getString(OAUTH2_CLIENT_ID);
            clientSecret = config.getString(OAUTH2_CLIENT_SECRET);
        }
    }

    public static boolean isEnabled(@NonNull Context context) {
        return ((RadarApplication)context.getApplicationContext()).getConfiguration()
                .getString(MANAGEMENT_PORTAL_URL_KEY, null) != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        post(() -> authState.addToPreferences(this));
        synchronized (this) {
            handler = null;
            handlerThread.quitSafely();
        }
    }

    private synchronized void post(Runnable runnable) {
        if (handler != null) {
            handler.post(runnable);
        }
    }

    public void registerSource(SourceMetadata source, SourceMetadataCallback success, ExceptionCallback failure) {
        logger.debug("Handling source registration");

        SourceMetadata resultSource = sources.get((int)source.getSourceTypeId());

        if (resultSource == null) {
            try {
                ensureClientConnectivity();
                resultSource = client.registerSource(authState, source);
                addSource(resultSource);
                success.sourceUpdated(authState, resultSource);
            } catch (UnsupportedOperationException ex) {
                logger.warn("ManagementPortal does not support updating the app source.");
                resultSource = source;
                addSource(resultSource);
                success.sourceUpdated(authState, resultSource);
            } catch (ConflictException ex) {
                try {
                    authState = client.getSubject(authState, new GetSubjectParser(authState));
                    updateSources();
                    resultSource = sources.get((int) source.getSourceTypeId());
                    if (resultSource == null) {
                        logger.error("Source was not added to ManagementPortal, even though conflict was reported.");
                    }
                    success.sourceUpdated(authState, resultSource);
                } catch (IOException ioex) {
                    logger.error("Failed to register source {} of type {} {}: already registered",
                            source.getSourceName(), source.getSourceTypeProducer(),
                            source.getSourceTypeModel(), ex);

                    failure.failure(ex);
                }
            } catch (AuthenticationException ex) {
                authState.invalidate(this);
                logger.error("Authentication error; failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);

                failure.failure(ex);
            } catch (IOException | JSONException | FirebaseRemoteConfigException ex) {
                logger.error("Failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                failure.failure(ex);
            }
        }
    }

    @Nullable
    @Override
    public ManagementPortalBinder onBind(Intent intent) {
        return new ManagementPortalBinder();
    }

    public class ManagementPortalBinder extends Binder {
        public void registerSource(SourceMetadata source, SourceMetadataCallback success, ExceptionCallback failure) {
            post(() -> ManagementPortalService.this.registerSource(source, success, failure));
        }

        public void refresh(String refreshToken, boolean updateSubject, AuthenticationCallback success, ExceptionCallback failure) {
            post(() -> ManagementPortalService.this.refresh(refreshToken, updateSubject, success, failure));
        }

        public void initialize(String refreshTokenUrl, AuthenticationCallback success, ExceptionCallback failure) {
            post(() -> ManagementPortalService.this.fetchRefreshToken(refreshTokenUrl, success, failure));
        }
    }

    private void refresh(String refreshToken, boolean updateSubject, AuthenticationCallback success, ExceptionCallback failure) {
        logger.debug("Refreshing JWT");

        try {
            ensureClientConnectivity();

            if (refreshToken != null) {
                authState = authState.newBuilder()
                        .attribute(MP_REFRESH_TOKEN_PROPERTY, refreshToken)
                        .build();
            }
            AuthStringParser parser = updateSubject
                    ? new SubjectTokenParser(client, authState)
                    : new AccessTokenParser(authState);

            authState = client.refreshToken(authState, clientId, clientSecret, parser);
            logger.info("Refreshed JWT");

            if (updateSubject) {
                updateSources();
            }
            success.authenticationUpdated(authState);
        } catch (Exception ex) {
            logger.error("Failed to get access token", ex);
            failure.failure(ex);
        }
    }

    private void fetchRefreshToken(String refreshTokenUrl, AuthenticationCallback success, ExceptionCallback failure) {
        logger.debug("Retrieving refreshToken from url");

        try {
            ensureClientConnectivity();

            // create parser
            AuthStringParser parser = new MetaTokenParser(authState);

            // retrieve token and update authState
            authState = client.getRefreshToken(refreshTokenUrl, parser);
            RadarConfiguration config = ((RadarApplication) getApplication()).getConfiguration();
            // update radarConfig
            if (config.updateWithAuthState(this, authState)) {
                LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(new Intent(RADAR_CONFIGURATION_CHANGED));
                // refresh client
                client = null;
            }
            logger.info("Retrieved refreshToken from url");
            // refresh token
            refresh(null, true, success, failure);
        } catch (Exception ex) {
            logger.error("Failed to get access token", ex);
            failure.failure(ex);
        }
    }



    public interface ExceptionCallback {
        void failure(@Nullable Exception ex);
    }

    public interface SourceMetadataCallback {
        void sourceUpdated(@NonNull AppAuthState authState, @Nullable SourceMetadata source);
    }

    public interface AuthenticationCallback {
        void authenticationUpdated(@NonNull AppAuthState authState);
    }
}
