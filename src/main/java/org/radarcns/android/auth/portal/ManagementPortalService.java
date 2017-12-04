package org.radarcns.android.auth.portal;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.SparseArray;
import org.json.JSONException;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.android.auth.AuthStringParser;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.RadarConfiguration.OAUTH2_CLIENT_ID;
import static org.radarcns.android.RadarConfiguration.OAUTH2_CLIENT_SECRET;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;
import static org.radarcns.android.auth.portal.ManagementPortalClient.SOURCES_PROPERTY;
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
    private static final String REFRESH_TOKEN_KEY = "org.radarcns.android.auth.ManagementPortalService.refreshToken";
    private static final String UPDATE_SUBJECT_KEY = "org.radarcns.android.auth.ManagementPortalService.updateSubject";
    private ManagementPortalClient client;
    private SparseArray<AppSource> sources;
    private String clientSecret;
    private String clientId;
    private AppAuthState authState;

    public ManagementPortalService() {
        super(ManagementPortalService.class.getName());
        sources = new SparseArray<>();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        authState = AppAuthState.Builder.from(this).build();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean isSuccessful;
        Bundle extras = intent.getExtras();
        extras.setClassLoader(ManagementPortalService.class.getClassLoader());

        switch (intent.getAction()) {
            case REGISTER_SOURCE_ACTION:
                isSuccessful = registerSource(extras);
                break;
            case REFRESH_TOKEN_ACTION:
                isSuccessful = refreshToken(extras);
                break;
            case GET_STATE_ACTION:
                isSuccessful = getState(extras);
                break;
            default:
                logger.warn("Cannot complete action {}: action unknown", intent.getAction());
                isSuccessful = false;
                break;
        }
        if (isSuccessful) {
            authState.addToPreferences(this);
        }
    }

    private boolean getState(Bundle extras) {
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        Bundle result = new Bundle();
        authState.addToBundle(result);
        receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
        return true;
    }

    private boolean refreshToken(Bundle extras) {
        logger.info("Refreshing JWT");
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);
        try {
            ensureClient();

            String refreshToken = extras.getString(REFRESH_TOKEN_KEY);
            if (refreshToken != null) {
                authState = authState.newBuilder()
                        .property(MP_REFRESH_TOKEN_PROPERTY, refreshToken)
                        .build();
            }
            AuthStringParser parser;
            if (extras.getBoolean(UPDATE_SUBJECT_KEY, false)) {
                parser = new SubjectTokenParser(client, authState);
            } else {
                parser = new AccessTokenParser(authState);
            }
            authState = client.refreshToken(authState, clientId, clientSecret, parser);

            Bundle result = new Bundle();
            authState.addToBundle(result);
            receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
            return true;
        } catch (JSONException | IOException e) {
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, null);
            return false;
        } catch (IllegalArgumentException ex) {
            authState.invalidate(this);
            logger.error("ManagementPortal error; firebase settings incomplete", ex);
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, null);
            return false;
        }

    }

    private boolean registerSource(Bundle extras) {
        logger.info("Handling source registration");
        AppSource source = extras.getParcelable(SOURCE_KEY);

        AppSource resultSource = sources.get((int)source.getSourceTypeId());
        ResultReceiver receiver = extras.getParcelable(RESULT_RECEIVER_PROPERTY);

        if (resultSource == null) {
            try {
                ensureClient();

                resultSource = client.registerSource(authState, source);
                sources.put((int) resultSource.getSourceTypeId(), resultSource);
                @SuppressWarnings("unchecked")
                List<AppSource> existingSources = (List<AppSource>) authState.getProperties().get(SOURCES_PROPERTY);

                boolean containsSource = false;
                ArrayList<AppSource> updatedSources;
                if (existingSources != null) {
                    updatedSources = new ArrayList<>(existingSources.size());
                    updatedSources.add(resultSource);
                    for (AppSource existingSource : existingSources) {
                        if (existingSource.getSourceTypeId() != resultSource.getSourceTypeId()) {
                            updatedSources.add(existingSource);
                        } else if (Objects.equals(resultSource.getSourceId(), existingSource.getSourceId())) {
                            containsSource = true;
                        }
                    }
                } else {
                    updatedSources = new ArrayList<>(1);
                    updatedSources.add(resultSource);
                }
                authState = authState.newBuilder()
                        .property(SOURCES_PROPERTY, updatedSources)
                        .build();

                if (!containsSource) {
                    authState.invalidate(getApplicationContext());
                }
            } catch (IllegalArgumentException ex) {
                authState.invalidate(this);
                logger.error("ManagementPortal error; firebase settings incomplete", ex);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, null);
                return false;
            } catch (AuthenticationException ex) {
                authState.invalidate(this);
                logger.error("Authentication error; failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, null);
                return false;
            } catch (IOException | JSONException ex) {
                logger.error("Failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, null);
                return false;
            }
        }

        Bundle result = new Bundle();
        authState.addToBundle(result);
        result.putParcelable(SOURCE_KEY, resultSource);
        receiver.send(MANAGEMENT_PORTAL_REGISTRATION, result);
        return true;
    }

    private void ensureClient() {
        if (client == null) {
            RadarConfiguration config = RadarConfiguration.getInstance();
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

    /** Build an intent to create a request for the management portal. */
    public static void registerSource(Context context, AppSource source, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(REGISTER_SOURCE_ACTION);
        Bundle extras = new Bundle();
        extras.putParcelable(SOURCE_KEY, source);
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

    public static boolean isEnabled() {
        return RadarConfiguration.getInstance().getString(MANAGEMENT_PORTAL_URL_KEY, null) != null;
    }

    @Override
    public void onDestroy() {
        authState.addToPreferences(this);
        if (client != null) {
            client.close();
        }
    }
}
