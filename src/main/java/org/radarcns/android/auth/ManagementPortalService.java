package org.radarcns.android.auth;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.SparseArray;
import org.json.JSONException;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Objects;

import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.auth.ManagementPortalClient.SOURCES_PROPERTY;
import static org.radarcns.android.device.DeviceServiceProvider.SOURCE_KEY;

/**
 * Handles intents to register sources to the ManagementPortal.
 */
public class ManagementPortalService extends IntentService {
    public static final String RESULT_RECEIVER_PROPERTY = ManagementPortalService.class.getName()
            + ".resultReceiver";
    public static final int MANAGEMENT_PORTAL_REGISTRATION = 1;

    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalService.class);
    private ManagementPortalClient client;
    private SparseArray<AppSource> sources;

    public ManagementPortalService() {
        super(ManagementPortalService.class.getName());
        sources = new SparseArray<>();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        AppSource source = intent.getParcelableExtra(SOURCE_KEY);

        AppSource resultSource = sources.get((int)source.getDeviceTypeId());
        AppAuthState authState = AppAuthState.Builder.from(intent.getExtras()).build();

        if (resultSource == null) {
            ensureClient(intent);
            try {
                resultSource = client.registerSource(authState, source);
                sources.put((int) resultSource.getDeviceTypeId(), resultSource);
                @SuppressWarnings("unchecked")
                List<AppSource> existingSources = (List<AppSource>) authState.getProperties().get(SOURCES_PROPERTY);
                if (existingSources != null) {
                    boolean containsSource = false;
                    for (AppSource existingSource : existingSources) {
                        if (Objects.equals(existingSource.getSourceId(), resultSource.getSourceId())) {
                            containsSource = true;
                            break;
                        }
                    }
                    if (!containsSource) {
                        authState.invalidate(getApplicationContext());
                    }
                }
            } catch (AuthenticationException ex) {
                authState.invalidate(getApplicationContext());
            } catch (IOException | JSONException ex) {
                logger.error("Failed to register source {} of type {} {}",
                        source.getSourceName(), source.getDeviceProducer(),
                        source.getDeviceModel(), ex);
            }
        }

        ResultReceiver receiver = intent.getParcelableExtra(RESULT_RECEIVER_PROPERTY);
        Bundle result = new Bundle();
        result.putParcelable(SOURCE_KEY, resultSource);
        authState.addToBundle(result);
        receiver.send(MANAGEMENT_PORTAL_REGISTRATION, result);
    }

    private void ensureClient(Intent intent) {
        if (client == null) {
            String url = intent.getStringExtra(MANAGEMENT_PORTAL_URL_KEY);
            try {
                ServerConfig portalConfig = new ServerConfig(url);
                client = new ManagementPortalClient(portalConfig);
            } catch (MalformedURLException ex) {
                logger.error("Management portal URL {} is invalid", url, ex);
            }
        }
    }

    /** Build an intent to create a request for the management portal. */
    public static Intent createRequest(Context context, ServerConfig managementPortalUrl,
            AppSource source, AppAuthState state, ResultReceiver receiver) {
        Intent intent = new Intent(context, ManagementPortalService.class);
        Bundle extras = new Bundle();
        extras.putString(MANAGEMENT_PORTAL_URL_KEY, managementPortalUrl.getUrlString());
        extras.putParcelable(SOURCE_KEY, source);
        state.addToBundle(extras);
        extras.putParcelable(RESULT_RECEIVER_PROPERTY, receiver);
        intent.putExtras(extras);
        return intent;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.close();
        }
    }
}
