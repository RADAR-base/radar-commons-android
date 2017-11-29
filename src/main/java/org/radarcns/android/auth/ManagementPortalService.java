package org.radarcns.android.auth;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.SparseArray;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.json.JSONException;
import org.radarcns.android.RadarConfiguration;
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
import static org.radarcns.android.auth.ManagementPortalClient.SOURCES_PROPERTY;
import static org.radarcns.android.auth.ManagementPortalClient.parseAccessToken;
import static org.radarcns.android.device.DeviceServiceProvider.SOURCE_KEY;

/**
 * Handles intents to register sources to the ManagementPortal.
 */
public class ManagementPortalService extends IntentService {
    public static final String RESULT_RECEIVER_PROPERTY = ManagementPortalService.class.getName()
            + ".resultReceiver";
    public static final int MANAGEMENT_PORTAL_REGISTRATION = 1;
    public static final int MANAGEMENT_PORTAL_REGISTRATION_FAILED = 2;
    public static final int MANAGEMENT_PORTAL_REFRESH = 3;
    public static final int MANAGEMENT_PORTAL_REFRESH_FAILED = 4;

    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalService.class);
    private static final String REGISTER_SOURCE_ACTION = "registerAction";
    private static final String REFREST_TOKEN_ACTION = "refreshTokenAction";
    private ManagementPortalClient client;
    private SparseArray<AppSource> sources;
    private String clientSecret;
    private String clientId;

    public ManagementPortalService() {
        super(ManagementPortalService.class.getName());
        sources = new SparseArray<>();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        ensureClient();

        switch (intent.getAction()) {
            case REGISTER_SOURCE_ACTION:
                registerSource(intent);
                break;
            case REFREST_TOKEN_ACTION:
                refreshToken(intent);
                break;
            default:
                // do nothing
                break;
        }
    }

    private void refreshToken(Intent intent) {
        logger.info("Refreshing JWT");
        final ResultReceiver receiver = intent.getParcelableExtra(RESULT_RECEIVER_PROPERTY);
        try {
            final AppAuthState state = AppAuthState.Builder.from(intent.getExtras()).build();
            client.refreshToken(state, clientId, clientSecret, new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    logger.info("Failed to refresh JWT", e);
                    receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, null);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        AppAuthState updatedState = parseAccessToken(state, response);
                        Bundle result = new Bundle();
                        updatedState.addToBundle(result);
                        logger.info("Refreshed JWT");
                        receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
                    } catch (IOException ex) {
                        onFailure(call, ex);
                    }
                }
            });

            Bundle result = new Bundle();
            state.addToBundle(result);
            receiver.send(MANAGEMENT_PORTAL_REFRESH, result);
        } catch (JSONException | IOException e) {
            receiver.send(MANAGEMENT_PORTAL_REFRESH_FAILED, null);
        }
    }

    private void registerSource(Intent intent) {
        logger.info("Handling source registration");
        AppSource source = intent.getParcelableExtra(SOURCE_KEY);

        AppSource resultSource = sources.get((int)source.getSourceTypeId());
        AppAuthState authState = AppAuthState.Builder.from(intent.getExtras()).build();
        ResultReceiver receiver = intent.getParcelableExtra(RESULT_RECEIVER_PROPERTY);

        if (resultSource == null) {
            try {
                resultSource = client.registerSource(authState, source);
                sources.put((int) resultSource.getSourceTypeId(), resultSource);
                @SuppressWarnings("unchecked")
                List<AppSource> existingSources = (List<AppSource>) authState.getProperties().get(SOURCES_PROPERTY);

                if (existingSources != null) {
                    ArrayList<AppSource> updatedSources = new ArrayList<>(existingSources.size());
                    updatedSources.add(resultSource);
                    boolean containsSource = false;
                    for (AppSource existingSource : existingSources) {
                        if (existingSource.getSourceTypeId() != resultSource.getSourceTypeId()) {
                            updatedSources.add(existingSource);
                        } else if (Objects.equals(resultSource.getSourceId(), existingSource.getSourceId())) {
                            containsSource = true;
                        }
                    }
                    authState = authState.newBuilder()
                            .property(SOURCES_PROPERTY, updatedSources)
                            .build();
                    if (!containsSource) {
                        authState.invalidate(getApplicationContext());
                    }
                }
            } catch (AuthenticationException ex) {
                authState.invalidate(getApplicationContext());
            } catch (IOException | JSONException ex) {
                logger.error("Failed to register source {} of type {} {}",
                        source.getSourceName(), source.getSourceTypeProducer(),
                        source.getSourceTypeModel(), ex);
                if (receiver != null) {
                    receiver.send(MANAGEMENT_PORTAL_REGISTRATION_FAILED, null);
                }
                return;
            }
        }

        Bundle result = new Bundle();
        result.putParcelable(SOURCE_KEY, resultSource);
        authState.addToBundle(result);
        receiver.send(MANAGEMENT_PORTAL_REGISTRATION, result);
    }

    private void ensureClient() {
        if (client == null) {
            RadarConfiguration config = RadarConfiguration.getInstance();
            String url = config.getString(MANAGEMENT_PORTAL_URL_KEY, null);
            if (url == null) {
                throw new IllegalStateException("Management Portal URL is not given");
            }
            boolean unsafe = config.getBoolean(UNSAFE_KAFKA_CONNECTION, false);
            try {
                ServerConfig portalConfig = new ServerConfig(url);
                portalConfig.setUnsafe(unsafe);
                client = new ManagementPortalClient(portalConfig);
            } catch (MalformedURLException ex) {
                logger.error("Management portal URL {} is invalid", url, ex);
            }
            clientId = config.getString(OAUTH2_CLIENT_ID);
            clientSecret = config.getString(OAUTH2_CLIENT_SECRET);
        }
    }

    /** Build an intent to create a request for the management portal. */
    public static Intent createRequest(Context context,
            AppSource source, AppAuthState state, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(REGISTER_SOURCE_ACTION);
        Bundle extras = new Bundle();
        extras.putParcelable(SOURCE_KEY, source);
        state.addToBundle(extras);
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        intent.putExtras(extras);
        return intent;
    }


    /** Build an intent to create a request for the management portal. */
    public static Intent createRequest(Context context, AppAuthState state, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        intent.setAction(REFREST_TOKEN_ACTION);
        Bundle extras = new Bundle();
        state.addToBundle(extras);
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        intent.putExtras(extras);
        return intent;
    }

    public static boolean isEnabled() {
        return RadarConfiguration.getInstance().getString(MANAGEMENT_PORTAL_URL_KEY, null) != null;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.close();
        }
    }
}
