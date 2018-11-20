package org.radarcns.android.auth.portal;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.SparseArray;

import com.crashlytics.android.Crashlytics;

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
import java.lang.ref.SoftReference;
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
import static org.radarcns.android.device.DeviceServiceProvider.SOURCE_KEY;

/**
 * Handles intents to register sources to the ManagementPortal.
 *
 * It also keeps a cache of the access token, refresh token and registered sources.
 */
public class ManagementPortalService extends IntentService {
    public static final String RESULT_RECEIVER_PROPERTY = ManagementPortalService.class.getName()
            + ".resultReceiver";
    public static final int MANAGEMENT_PORTAL_REGISTRATION = 1;
    public static final int MANAGEMENT_PORTAL_REGISTRATION_FAILED = 2;
    public static final int MANAGEMENT_PORTAL_REFRESH = 4;
    public static final int MANAGEMENT_PORTAL_REFRESH_FAILED = 5;

    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalService.class);
    private static final String REGISTER_SOURCE_ACTION = "org.radarcns.android.auth.ManagementPortalService.registerSourceAction";
    private static final String GET_STATE_ACTION = "org.radarcns.android.auth.ManagementPortalService.getStateAction";
    private static final String REFRESH_TOKEN_ACTION = "org.radarcns.android.auth.ManagementPortalService.refreshTokenAction";
    private static final String GET_REFRESH_TOKEN_ACTION = "org.radarcns.android.auth.ManagementPortalService.getRefreshTokenAction";
    public static final String REFRESH_TOKEN_KEY = "org.radarcns.android.auth.ManagementPortalService.refreshToken";
    private static final String REFRESH_TOKEN_URL_KEY = "org.radarcns.android.auth.ManagementPortalService.refreshTokenUrl";
    private static final String UPDATE_SUBJECT_KEY = "org.radarcns.android.auth.ManagementPortalService.updateSubject";
    public static final String REQUEST_FAILED_REASON = "org.radarcns.android.auth.ManagementPortalService.refreshFailedReason";
    public static final int REQUEST_FAILED_REASON_IO = 1;
    public static final int REQUEST_FAILED_REASON_UNAUTHORIZED = 2;
    public static final int REQUEST_FAILED_REASON_CONFIGURATION = 3;
    public static final int REQUEST_FAILED_REASON_CONFLICT = 4;
    public static final int REQUEST_FAILED_REASON_DISCONNECTED = 5;

    private static SoftReference<SparseArray<SourceMetadata>> staticSources = new SoftReference<>(null);
    private ManagementPortalClient client;
    private SparseArray<SourceMetadata> sources;
    private String clientSecret;
    private String clientId;
    private AppAuthState authState;

    public ManagementPortalService() {
        super(ManagementPortalService.class.getName());
        // try to avoid losing already-registered sources
        sources = staticSources.get();
        if (sources == null) {
            sources = new SparseArray<>();
            staticSources = new SoftReference<>(sources);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        authState = AppAuthState.Builder.from(this).build();
        updateSources();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean isSuccessful;
        try {
            if (intent == null) {
                throw new IllegalArgumentException("No intent given");
            }
            Bundle extras = intent.getExtras();
            if (extras == null) {
                throw new IllegalArgumentException("No intent extras provided to ManagementPortalService");
            }
            extras.setClassLoader(ManagementPortalService.class.getClassLoader());

            String action = intent.getAction();
            if (action == null) {
                throw new IllegalArgumentException("No intent extras provided to ManagementPortalService");
            }
            switch (action) {
                case REGISTER_SOURCE_ACTION:
                    isSuccessful = registerSource(extras);
                    break;
                case REFRESH_TOKEN_ACTION:
                    isSuccessful = refreshToken(extras);
                    break;
                case GET_STATE_ACTION:
                    isSuccessful = getState(extras);
                    break;
                case GET_REFRESH_TOKEN_ACTION:
                    isSuccessful = getRefreshToken(extras);
                    break;
                default:
                    logger.warn("Cannot complete action {}: action unknown", intent.getAction());
                    isSuccessful = false;
                    break;
            }
            if (isSuccessful) {
                authState.addToPreferences(this);
            } else {
                logger.error("Failed to interact with ManagementPortal with {}", authState);
            }
        } catch (IllegalArgumentException ex) {
            Crashlytics.logException(ex);
        }
    }

    /**
     * Gets refreshToken from tokenUrl and refreshes access-token using it.
     * @param extras input bundle data.
     * @return {@code true} if success.
     * @see #refreshToken(Bundle) refreshing access token.
     */
    private boolean getRefreshToken(Bundle extras) {
        logger.debug("Retrieving refreshToken from url");

        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        if (receiver == null) {
            throw new IllegalArgumentException("ResultReceiver not set");
        }

        String refreshTokenUrl = extras.getString(REFRESH_TOKEN_URL_KEY);
        if (refreshTokenUrl == null) {
            throw new IllegalArgumentException("RefreshTokenUrl not set");
        }

        Bundle result = new Bundle();

        try {
            if (!ensureClientConnectivity(receiver, result)) {
                return false;
            }
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
            return refreshToken(extras);
        } catch (IOException e) {
            logger.error("Failed to get access token", e);
            result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_IO);
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, result);
        }

        return true;
    }

    private boolean getState(Bundle extras) {
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        if (receiver == null) {
            throw new IllegalArgumentException("ResultReceiver not set");
        }
        Bundle result = new Bundle();
        authState.addToBundle(result);
        receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
        return true;
    }

    private boolean refreshToken(Bundle extras) {
        logger.debug("Refreshing JWT");
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        if (receiver == null) {
            throw new IllegalArgumentException("ResultReceiver not set");
        }
        Bundle result = new Bundle();

        try {
            if (!ensureClientConnectivity(receiver, result)) {
                return false;
            }

            String refreshToken = extras.getString(REFRESH_TOKEN_KEY);
            if (refreshToken != null) {
                authState = authState.newBuilder()
                        .attribute(MP_REFRESH_TOKEN_PROPERTY, refreshToken)
                        .build();
            }
            AuthStringParser parser;
            if (extras.getBoolean(UPDATE_SUBJECT_KEY, false)) {
                parser = new SubjectTokenParser(client, authState);
            } else {
                parser = new AccessTokenParser(authState);
            }
            authState = client.refreshToken(authState, clientId, clientSecret, parser);
            logger.info("Refreshed JWT");

            if (extras.getBoolean(UPDATE_SUBJECT_KEY, false)) {
                updateSources();
            }
            authState.addToBundle(result);
            receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
            return true;
        } catch (AuthenticationException ex) {
            logger.error("Failed to get access token", ex);
            result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_UNAUTHORIZED);
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, result);
            return false;
        } catch (IOException e) {
            logger.error("Failed to get access token", e);
            result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_IO);
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, result);
            return false;
        } catch (IllegalArgumentException ex) {
            authState.invalidate(this);
            logger.error("ManagementPortal error; Firebase settings incomplete", ex);
            Crashlytics.logException(ex);
            result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_CONFIGURATION);
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, result);
            return false;
        }

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
            authState.invalidate(getApplicationContext());
        }
    }

    private boolean registerSource(Bundle extras) {
        logger.debug("Handling source registration");
        SourceMetadata source;
        try {
            source = new SourceMetadata(extras.getString(SOURCE_KEY));
        } catch (JSONException ex) {
            throw new IllegalArgumentException("Failed to deserialize SourceMetadata", ex);
        }

        SourceMetadata resultSource = sources.get((int)source.getSourceTypeId());
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        if (receiver == null) {
            throw new IllegalArgumentException("ResultReceiver not set");
        }

        Bundle result = new Bundle();

        if (resultSource == null) {
            try {
                if (!ensureClientConnectivity(receiver, result)) {
                    return false;
                }
                resultSource = client.registerSource(authState, source);
                addSource(resultSource);
            } catch (UnsupportedOperationException ex) {
                logger.warn("ManagementPortal does not support updating the app source.");
                resultSource = source;
                addSource(resultSource);
            } catch (IllegalArgumentException ex) {
                authState.invalidate(this);
                logger.error("ManagementPortal error; firebase settings incomplete", ex);
                result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_CONFIGURATION);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, result);
                return false;
            } catch (ConflictException ex) {
                try {
                    authState = client.getSubject(authState, new GetSubjectParser(authState));
                    updateSources();
                    resultSource = sources.get((int) source.getSourceTypeId());
                    if (resultSource == null) {
                        logger.error("Source was not added to ManagementPortal, even though conflict was reported.");
                        return false;
                    }
                } catch (IOException ioex) {
                    logger.error("Failed to register source {} of type {} {}: already registered",
                            source.getSourceName(), source.getSourceTypeProducer(),
                            source.getSourceTypeModel(), ex);
                    result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_CONFLICT);
                    receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, result);
                    return false;
                }
            } catch (AuthenticationException ex) {
                authState.invalidate(this);
                logger.error("Authentication error; failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_UNAUTHORIZED);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, result);
                return false;
            } catch (IOException | JSONException ex) {
                logger.error("Failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_IO);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, result);
                return false;
            }
        }

        authState.addToBundle(result);
        result.putString(SOURCE_KEY, resultSource.toJsonString());
        receiver.send(MANAGEMENT_PORTAL_REGISTRATION, result);
        return true;
    }

    private void ensureClient() {
        if (client == null) {
            RadarConfiguration config = ((RadarApplication)getApplication()).getConfiguration();
            String url = config.getString(MANAGEMENT_PORTAL_URL_KEY);
            boolean unsafe = config.getBoolean(UNSAFE_KAFKA_CONNECTION, false);
            try {
                ServerConfig portalConfig = new ServerConfig(url);
                portalConfig.setUnsafe(unsafe);
                client = new ManagementPortalClient(portalConfig);
            } catch (MalformedURLException ex) {
                throw new IllegalArgumentException("Management portal URL " + url + " is invalid", ex);
            }
            clientId = config.getString(OAUTH2_CLIENT_ID);
            clientSecret = config.getString(OAUTH2_CLIENT_SECRET);
        }
    }

    private Boolean ensureClientConnectivity(ResultReceiver resultReceiver, Bundle result) {
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connManager != null ? connManager.getActiveNetworkInfo() : null;
        if (networkInfo == null || !networkInfo.isConnected()) {
            result.putInt(REQUEST_FAILED_REASON, REQUEST_FAILED_REASON_DISCONNECTED);
            resultReceiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, result);
            return false;
        }
        ensureClient();
        return true;
    }

    /** Build an intent to create a request for the management portal. */
    public static void registerSource(Context context, SourceMetadata source, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(REGISTER_SOURCE_ACTION);
        Bundle extras = new Bundle();
        extras.putString(SOURCE_KEY, source.toJsonString());
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        intent.putExtras(extras);
        context.startService(intent);
    }

    /** Build an intent to create a request for the management portal. */
    public static void requestAccessToken(Context context, String refreshToken, boolean updateSubject, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(REFRESH_TOKEN_ACTION);
        Bundle extras = new Bundle();
        extras.putString(REFRESH_TOKEN_KEY, refreshToken);
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        extras.putBoolean(UPDATE_SUBJECT_KEY, updateSubject);
        intent.putExtras(extras);
        context.startService(intent);
    }

    /** Build an intent to create a request for the management portal. */
    public static void requestRefreshToken(Context context, String refreshTokenUrl, Boolean updateSubject,  ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(GET_REFRESH_TOKEN_ACTION);
        Bundle extras = new Bundle();
        extras.putString(REFRESH_TOKEN_URL_KEY, refreshTokenUrl);
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        extras.putBoolean(UPDATE_SUBJECT_KEY, updateSubject);
        intent.putExtras(extras);
        context.startService(intent);
    }

    public static boolean isEnabled(@NonNull Context context) {
        return ((RadarApplication)context.getApplicationContext()).getConfiguration()
                .getString(MANAGEMENT_PORTAL_URL_KEY, null) != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        authState.addToPreferences(this);
    }
}
